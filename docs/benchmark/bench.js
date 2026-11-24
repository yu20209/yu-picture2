// 图片列表接口压测脚本（固定时长 + 阶梯并发，等价 JMeter 固定时长场景）
// 用法: node bench.js <并发数> <时长秒> <标签>
const http = require('http');

const CONCURRENCY = parseInt(process.argv[2] || '20', 10);
const DURATION_SEC = parseInt(process.argv[3] || '45', 10);
const LABEL = process.argv[4] || 'bench';

const BODY = JSON.stringify({
  current: 1,
  pageSize: 20,
  sortField: 'createTime',
  sortOrder: 'descend'
});

const agent = new http.Agent({ keepAlive: true, maxSockets: CONCURRENCY });

function listRequest() {
  return new Promise((resolve) => {
    const start = process.hrtime.bigint();
    const req = http.request(
      {
        host: '127.0.0.1',
        port: 8123,
        path: '/api/picture/list/page/vo',
        method: 'POST',
        agent,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(BODY)
        }
      },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          const ms = Number(process.hrtime.bigint() - start) / 1e6;
          resolve({ ms, ok: res.statusCode === 200 && data.includes('"code":0') });
        });
      }
    );
    req.on('error', () => resolve({ ms: Number(process.hrtime.bigint() - start) / 1e6, ok: false }));
    req.end(BODY);
  });
}

function getJson(path) {
  return new Promise((resolve) => {
    http.get({ host: '127.0.0.1', port: 8123, path, agent }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          resolve(null);
        }
      });
    }).on('error', () => resolve(null));
  });
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[idx];
}

(async () => {
  // 清空上一轮统计，并预热避免 JIT 影响
  await getJson('/api/picture/cache/stats');
  for (let i = 0; i < CONCURRENCY; i++) await listRequest();
  await getJson('/api/picture/cache/stats');

  const results = [];
  const deadline = Date.now() + DURATION_SEC * 1000;
  const wallStart = Date.now();

  await Promise.all(
    Array.from({ length: CONCURRENCY }, async () => {
      while (Date.now() < deadline) {
        results.push(await listRequest());
      }
    })
  );

  const wall = (Date.now() - wallStart) / 1000;
  const times = results.map((r) => r.ms).sort((a, b) => a - b);
  const avg = times.reduce((s, v) => s + v, 0) / times.length;
  const okCount = results.filter((r) => r.ok).length;
  const cacheStats = await getJson('/api/picture/cache/stats');

  console.log(JSON.stringify({
    label: LABEL,
    concurrency: CONCURRENCY,
    durationSec: DURATION_SEC,
    total: results.length,
    success: okCount,
    failed: results.length - okCount,
    qps: +(results.length / wall).toFixed(1),
    avgMs: +avg.toFixed(1),
    p50Ms: +percentile(times, 50).toFixed(1),
    p95Ms: +percentile(times, 95).toFixed(1),
    p99Ms: +percentile(times, 99).toFixed(1),
    maxMs: +times[times.length - 1].toFixed(1),
    cache: cacheStats && cacheStats.data ? cacheStats.data : null
  }, null, 2));
  agent.destroy();
})();
