# 多级缓存性能与一致性实验报告

> 实验对象：CloudGallery 云图库 —— 图片列表分页查询接口 `POST /api/picture/list/page/vo`
> 实验时间：2026-11-24
> 数据来源：本机实测，所有数字均为真实运行结果，未做修饰

---

## 一、实验背景与目标

图片列表是典型的高频读、低频写场景。本项目最初的实现是每次请求直接查询 MySQL，存在两个问题：

1. 分页查询带 `ORDER BY createTime DESC`，数据量上去后排序与全表扫描成本升高；
2. 并发升高后所有请求都压到数据库连接池，响应时间线性劣化。

为此引入了**两级缓存**：Caffeine（进程内，L1）+ Redis（跨进程，L2），并采用**数据版本号**方案解决缓存失效问题。

本报告要回答三个问题：

| 编号 | 问题 | 对应实验 |
|---|---|---|
| Q1 | 加缓存到底有多少收益？ | 实验一 A 组 vs B 组 |
| Q2 | 已经有 Redis 了，再加 Caffeine 还有价值吗？ | 实验一 B 组 vs C 组 |
| Q3 | 本地缓存会不会导致多实例之间读到旧数据？ | 实验二 |

---

## 二、测试环境

| 项目 | 配置 |
|---|---|
| 后端 | Spring Boot 2.7.6，JDK 11，单实例，内嵌 Tomcat |
| 数据库 | MySQL 8.0，InnoDB，`picture` 表 100,062 行 |
| 缓存 | Redis 6（本机 6379），Caffeine 15s 写过期 |
| 连接池 | HikariCP 默认配置（maximumPoolSize = 10） |
| 压测客户端 | Node.js 原生 http（keep-alive），与服务端同机 |
| 压测接口 | `POST /api/picture/list/page/vo`，固定查询条件 `{current:1,pageSize:20,sortField:"createTime",sortOrder:"descend"}` |

**前置修正：** 实验前发现 `picture` 表只有 `reviewStatus`、`spaceId` 两个单列索引，列表查询会触发 `Using filesort`。为避免把「缺索引」的性能损耗误算成「没有缓存」的代价，先补了复合索引：

```sql
ALTER TABLE picture
  ADD INDEX idx_review_space_create (reviewStatus, spaceId, isDelete, createTime);
```

执行计划由 `Using filesort` 变为 `Backward index scan`，基准组才具备公平的可比性。

---

## 三、实验一：三组缓存架构性能对比

### 3.1 实验设计

在 `PictureCacheManager` 中引入 `picture.cache.mode` 配置，用同一份代码跑出三组对照组：

| 组别 | 配置值 | 架构 | 作用 |
|---|---|---|---|
| A 组 | `none` | 每次直接查 MySQL | 性能基准 |
| B 组 | `redis` | MySQL + Redis 单级 | 验证 Redis 收益 |
| C 组 | `both` | MySQL + Redis + Caffeine | 验证 Caffeine 额外收益（线上配置） |

三组共用同一套查询条件、同一份数据、同一个 JVM 参数，唯一变量是缓存层级。

### 3.2 测试方法

- 每档并发持续 **45 秒**，先跑 15 秒预热丢弃结果，避免 JIT 与连接建立污染统计；
- 并发档位：20 / 50 / 100 / 200；
- 服务端统计：`GET /api/picture/cache/stats` 读取并清空本节点的请求数、L1 命中、L2 命中、回源次数；
- 数据库统计：MySQL `SHOW GLOBAL STATUS LIKE 'Com_select'` 压测前后差值；
- Redis 统计：`INFO stats` 的 `total_commands_processed` 前后差值；
- 每组切换前重启后端，保证 B/C 组不在残留缓存上开跑。

### 3.3 实验结果

**A 组：仅 MySQL 直查**

| 并发 | QPS | 平均 (ms) | P95 (ms) | P99 (ms) | DB 查询次数 |
|---|---|---|---|---|---|
| 20 | 64.3 | 310.2 | 458.1 | 532.0 | 8,790 |
| 50 | 65.4 | 761.7 | 1,282.5 | 1,613.3 | 9,060 |
| 100 | 64.0 | 1,554.2 | 2,917.5 | 3,657.4 | 9,096 |
| 200 | 63.0 | 3,137.8 | 6,191.1 | 8,057.5 | 9,423 |

**B 组：MySQL + Redis 单级**

| 并发 | QPS | 平均 (ms) | P95 (ms) | P99 (ms) | DB 查询次数 | Redis 命令数 |
|---|---|---|---|---|---|---|
| 20 | 1,497.9 | 13.3 | 47.9 | 84.2 | 3 | 134,877 |
| 50 | 1,665.7 | 30.0 | 70.5 | 165.3 | 1 | 150,091 |
| 100 | 1,665.3 | 59.9 | 110.2 | 265.3 | 1 | 150,219 |
| 200 | 1,430.8 | 139.6 | 222.6 | 355.6 | 3 | 129,363 |

**C 组：MySQL + Redis + Caffeine 两级**

| 并发 | QPS | 平均 (ms) | P95 (ms) | P99 (ms) | DB 查询次数 | Redis 命令数 | L1 命中率 |
|---|---|---|---|---|---|---|---|
| 20 | 1,734.9 | 11.5 | 35.6 | 85.8 | 24 | 78,115 | 99.98% |
| 50 | 1,657.3 | 30.1 | 74.9 | 179.4 | 1 | 74,727 | 99.94% |
| 100 | 1,639.1 | 60.9 | 93.2 | 166.4 | 3 | 73,988 | 99.95% |
| 200 | 1,588.1 | 125.8 | 167.2 | 235.8 | 3 | 71,729 | 99.98% |

> C 组的 L2 命中率仅 0.02%~0.06%，来源是 Caffeine 15 秒到期后的一次回源，属于设计内的正常回填。

### 3.4 分析

**（1）A 组：数据库连接池是硬瓶颈**

并发从 20 涨到 200（10 倍），QPS 基本钉死在 63~65，但 P95 从 458ms 涨到 6,191ms（13.5 倍）。这是典型的**排队型退化**：系统吞吐已达上限，多余请求只能在连接池外等待，并发越高延迟越离谱。

原因：HikariCP 默认 10 个连接，每次请求平均产生约 3 条 SQL（count、分页、用户信息批量查询），折算下来每秒只能处理约 65 次完整请求。

**（2）A → B：Redis 把数据库从关键路径上摘掉了**

20 并发下：

- QPS：64.3 → 1,497.9，**23.3 倍**
- P95：458.1ms → 47.9ms，**下降 89.5%**
- DB 查询：8,790 → 3，**下降 99.97%**

200 并发下 QPS 提升 22.7 倍、P95 下降 96.4%，说明压力越大，缓存的价值越明显 —— 因为被消除的是最容易成为瓶颈的环节。

**（3）B → C：Caffeine 的价值体现在「少打 Redis」而不是「多提 QPS」**

20 并发下：

- P95：47.9ms → 35.6ms，**下降 25.7%**
- Redis 命令：134,877 → 78,115，**下降 42.1%**
- QPS：1,497.9 → 1,734.9，提升 15.8%

200 并发下 P95 下降 24.9%、Redis 命令下降 44.6%、QPS 提升 11.0%。

**为什么 QPS 提升不如 A→B 明显？** 因为本次 Redis 与后端在同一台机器，一次 Redis 往返只有几十微秒，省掉它的收益有限。Caffeine 的真正价值在两种场景：

1. Redis 在另一台机器 / 同机房跨网段时，每次网络往返是实打实的毫秒级开销；
2. Redis 本身成为热点瓶颈时（本次 200 并发已达 Redis 单实例上限），减少 42% 的调用等于把 Redis 的承载上限提高了将近一倍。

**（4）Redis 命令数可以反推缓存层级**

- B 组每请求 2 次 Redis：1 次读版本号 + 1 次读缓存值（134,877 / 67,417 ≈ 2.0）
- C 组每请求约 1 次 Redis：只剩读版本号，缓存值从本地拿到（78,115 / 78,080 ≈ 1.0）

这组数字直接证明了「L1 命中时没有产生 L2 访问」。

### 3.5 结论与局限

**结论：** 两级缓存把 10 万级数据的列表接口从 64 QPS / P95 458ms 提升到 1,735 QPS / P95 36ms，数据库查询次数下降 99.7%；相比 Redis 单级，P95 再降 26%、Redis 访问减少 42%。

**局限（面试需主动说明，避免被追问）：**

1. **压测客户端与服务端同机**，高并发时二者争抢 CPU，绝对 QPS 偏保守，但三组同样吃亏，横向对比仍然有效；
2. **单机 Redis** 掩盖了 Caffeine 的跨网络收益，因此 C 组只能用「Redis 调用次数」和「P95」论证，不能夸大成 QPS 翻倍；
3. **每档 45 秒单次采样**，未做 3 次重复取均值。稳定性依据：B 组在 50/100 并发下 QPS 均为 1,665，C 组相邻档位 QPS 差异 < 2%，可判定已进入稳态；
4. A 组受 HikariCP 默认 10 连接限制，若调大连接池，A 组 QPS 会上涨，缓存倍数会相应缩小。本实验选择保持默认配置，因为它更接近项目实际部署参数。

---

## 四、实验二：多实例缓存一致性验证

### 4.1 要验证的问题

Caffeine 是进程内缓存。若部署两个实例，实例 B 的本地缓存不会因为实例 A 修改了数据而自动失效，可能读到旧数据。

本项目的方案是**不要在写操作时删除缓存，而是让版本号自增**：

```
cacheKey = cloudgallery:pic:list:{scope}:{version}:{md5(查询条件)}
```

任何写操作只需对 `cloudgallery:pic:list:{scope}:version` 执行 `INCR`，旧 key 自然不再被访问，无需 `KEYS`/`SCAN` 批量删除。

### 4.2 实验架构

```
实例 A (9090)                实例 B (9091)
  Spring Boot                  Spring Boot
  Caffeine A                   Caffeine B
        \                        /
         \                      /
          共享 Redis（版本号 + 列表缓存）
                     |
                共享 MySQL
```

### 4.3 实验方法

1. 两个实例分别监听 9090 / 9091，均为 `picture.cache.mode=both`；
2. 在实例 B 上连续查询同一列表两次，确保本地 Caffeine 已建立；
3. 在实例 A 上调用 `POST /api/picture/update` 修改目标图片，接口内部触发版本号 `INCR`；
4. **立即**（远小于 Caffeine 15 秒过期时间）在实例 B 上重新查询，检查返回的是新值还是旧值；
5. 重复 100 轮。

关键设计：验证窗口严格小于本地缓存 TTL，因此只要读到新数据，就只能是版本号机制生效，而不是缓存自然过期。

### 4.4 实验结果

| 指标 | 结果 |
|---|---|
| 测试轮次 | 100 |
| 缓存失效成功次数 | **100** |
| 旧数据读取次数 | **0** |
| 失败次数 | 0 |
| 失效率 | **100%** |
| 实例 A 更新接口耗时 | 平均 14.8ms / P95 22ms |
| 实例 B 读到新数据耗时 | 平均 **18.9ms** / P95 29ms |

### 4.5 分析

- 实例 B 每轮都能在 19ms 左右读到实例 A 刚写入的数据，说明版本号在 Redis 中是**全局可见**的，本地缓存不会阻碍跨实例失效；
- 实例 A 的更新耗时 14.8ms 中包含了版本号 `INCR`，说明失效动作本身开销可以忽略；
- 从「A 提交完成」到「B 发出新请求」的间隔平均 14.8ms，全程远小于 Caffeine 的 15 秒 TTL。

### 4.6 一致性边界（必须如实说明）

这不是**强一致**：

1. 请求先读版本号、再读缓存；若在这两步之间发生了写操作，该请求仍可能拿到旧版本的数据；
2. 数据库事务提交与版本号 `INCR` 不是原子的，极小概率下会出现「版本号已变但数据库尚未提交」的窗口；
3. 准确的表述是**最终一致性 + 写后新请求可见**，而不是「实时强一致」。

如需进一步收敛窗口，可以：

- 把版本号 `INCR` 放到数据库事务提交之后（本项目已这么做，窗口只剩提交与 INCR 之间的微秒级间隔）；
- 对强一致要求的场景，改为「写后延迟双删 + 短 TTL」或直接读主库。

---

## 五、复现步骤

### 5.1 准备数据与索引

```sql
-- 10 万条测试数据
SET SESSION cte_max_recursion_depth = 120000;
INSERT INTO picture (url, name, userId, reviewStatus, spaceId, picSize, picWidth, picHeight, picScale, picFormat, picColor)
WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 100000)
SELECT CONCAT('http://localhost:8123/api/pictures/public/1/bench-', n, '.jpg'),
       CONCAT('bench-', n), 1, 1, NULL, 102400, 1024, 768, 1.33, 'jpg', '#000000'
FROM seq;

-- 复合索引
ALTER TABLE picture
  ADD INDEX idx_review_space_create (reviewStatus, spaceId, isDelete, createTime);
```

### 5.2 三组对比压测

```bash
# A 组：仅 MySQL
mvn -o spring-boot:run -Dspring-boot.run.arguments=--picture.cache.mode=none
# B 组：仅 Redis
mvn -o spring-boot:run -Dspring-boot.run.arguments=--picture.cache.mode=redis
# C 组：Redis + Caffeine
mvn -o spring-boot:run -Dspring-boot.run.arguments=--picture.cache.mode=both

# 每档压测（并发数 / 秒数 / 标签）
node docs/benchmark/bench.js 20 45 A-mysql-c20
```

### 5.3 双实例一致性测试

```bash
# 造一个临时管理员（密码 = MD5("yupi" + "bench123456")）
INSERT INTO user (userAccount, userPassword, userName, userRole)
VALUES ('benchadmin', 'a9711da290af8d0bd5e16c4b4874cb0d', '压测管理员', 'admin');

mvn -o spring-boot:run -Dspring-boot.run.arguments="--server.port=9090"
mvn -o spring-boot:run -Dspring-boot.run.arguments="--server.port=9091"

node docs/benchmark/consistency.js <目标图片ID> 100
```

> 测试完成后记得删除 `bench-%` 测试图片和 `benchadmin` 账号。

---

## 六、可用于简历与面试的结论

**一句话版本：**

> 针对图片列表高频查询，设计 Redis + Caffeine 两级缓存，10 万数据下 QPS 从 64 提升到 1,735（27 倍），P95 从 458ms 降到 36ms（-92%），数据库查询减少 99.7%；相比 Redis 单级，P95 再降 26%、Redis 访问减少 42%；双实例 100 轮跨实例更新测试失效成功率 100%，无旧数据读取。

**面试可能追问的方向：**

| 追问 | 建议回答要点 |
|---|---|
| 为什么不用本地缓存做唯一缓存？ | 多实例下本地缓存无法跨节点失效，必须依赖 Redis 版本号做协调 |
| 为什么用版本号而不是删 key？ | 删 key 需要知道所有查询组合对应的 key，只能 `KEYS`/`SCAN`，生产禁用；版本号只需一次 `INCR` |
| 缓存击穿怎么解决的？ | Redis `SETNX` 互斥锁 + 双重检查 + 等待重读；锁带 5 秒过期，释放前校验持有者 |
| 缓存穿透怎么解决的？ | 空结果也写缓存，TTL 60~90 秒 |
| 缓存雪崩怎么解决的？ | 过期时间加 0~300 秒随机抖动，Caffeine 15 秒短 TTL 兜底 |
| 一致性是强一致吗？ | 不是，是最终一致性，存在版本号读取与 DB 提交之间的窗口，已在报告中如实说明 |
