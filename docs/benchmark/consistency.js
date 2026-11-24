// 实验二：双实例缓存一致性测试
// 实例 A(9090) 修改数据触发版本号失效，验证实例 B(9091) 是否能读到新数据
// 用法: node consistency.js <目标图片ID> <轮次>
const http = require('http');

const PORT_A = 9090;
const PORT_B = 9091;
const TARGET_ID = process.argv[2] || '0';
const ROUNDS = parseInt(process.argv[3] || '100', 10);
const LIST_BODY = { current: 1, pageSize: 20, sortField: 'createTime', sortOrder: 'descend' };
const agent = new http.Agent({ keepAlive: true, maxSockets: 10 });

function request(port, method, path, body, cookie) {
  return new Promise((resolve) => {
    const payload = body ? JSON.stringify(body) : '';
    const headers = { 'Content-Type': 'application/json' };
    if (payload) headers['Content-Length'] = Buffer.byteLength(payload);
    if (cookie) headers['Cookie'] = cookie;
    const req = http.request({ host: '127.0.0.1', port, path, method, agent, headers }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(data); } catch (e) { /* ignore */ }
        resolve({ status: res.statusCode, setCookie: res.headers['set-cookie'] || [], json });
      });
    });
    req.on('error', () => resolve({ status: 0, setCookie: [], json: null }));
    req.end(payload);
  });
}

function findTarget(resp) {
  const records = resp && resp.json && resp.json.data && resp.json.data.records;
  if (!records) return null;
  return records.find((r) => String(r.id) === String(TARGET_ID)) || null;
}

(async () => {
  // 实例 A 登录，拿到 Sa-Token（两实例共享 Redis，Token 通用）
  const login = await request(PORT_A, 'POST', '/api/user/login',
    { userAccount: 'benchadmin', userPassword: 'bench123456' });
  const cookie = login.setCookie.map((c) => c.split(';')[0]).join('; ');
  if (!cookie) {
    console.log(JSON.stringify({ error: 'login failed', detail: login.json }, null, 2));
    process.exit(1);
  }

  let success = 0, stale = 0, failed = 0;
  const updateCost = [], readCost = [], versionGap = [];
  let lastIntro = null;

  for (let i = 1; i <= ROUNDS; i++) {
    // 1. 实例 B 连续查询两次，建立并命中本地 Caffeine 缓存
    await request(PORT_B, 'POST', '/api/picture/list/page/vo', LIST_BODY);
    const before = await request(PORT_B, 'POST', '/api/picture/list/page/vo', LIST_BODY);
    const beforeTarget = findTarget(before);
    if (!beforeTarget) { failed++; continue; }
    lastIntro = beforeTarget.introduction;

    // 2. 实例 A 修改图片（内部会 INCR 空间版本号）
    const newIntro = 'round-' + i;
    const t1 = Date.now();
    const upd = await request(PORT_A, 'POST', '/api/picture/update',
      { id: TARGET_ID, introduction: newIntro }, cookie);
    updateCost.push(Date.now() - t1);
    if (!upd.json || upd.json.code !== 0) { failed++; continue; }

    // 3. 立即在实例 B 查询，验证是否读到新值
    const t2 = Date.now();
    const after = await request(PORT_B, 'POST', '/api/picture/list/page/vo', LIST_BODY);
    readCost.push(Date.now() - t2);
    versionGap.push(t2 - t1);

    const afterTarget = findTarget(after);
    if (afterTarget && afterTarget.introduction === newIntro) {
      success++;
    } else if (afterTarget && afterTarget.introduction === lastIntro) {
      stale++;
    } else {
      failed++;
    }
  }

  const avg = (arr) => (arr.length ? +(arr.reduce((s, v) => s + v, 0) / arr.length).toFixed(1) : 0);
  const p95 = (arr) => {
    if (!arr.length) return 0;
    const s = [...arr].sort((a, b) => a - b);
    return s[Math.min(s.length - 1, Math.ceil(0.95 * s.length) - 1)];
  };

  console.log(JSON.stringify({
    rounds: ROUNDS,
    targetId: TARGET_ID,
    invalidationSuccess: success,
    staleReads: stale,
    failures: failed,
    successRate: +((success * 100) / ROUNDS).toFixed(1),
    updateAvgMs: avg(updateCost),
    updateP95Ms: p95(updateCost),
    readAfterUpdateAvgMs: avg(readCost),
    readAfterUpdateP95Ms: p95(readCost),
    versionGapAvgMs: avg(versionGap)
  }, null, 2));
  agent.destroy();
})();
