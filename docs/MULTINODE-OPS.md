# 多节点生产化：后台任务分布式互斥与租约（#220）

> 解决的问题：本仓一批 `@Scheduled` 后台任务按「单实例」语义编写（用进程内原语防重入）。
> 多副本部署后，这些原语只看得到自己那个 JVM ⇒ 同一批规则被 N 个副本同时评估、同一轮清理被跑 N 遍。
> 本批把「必须单实例执行」的任务升级为 **Redis 租约锁**，并如实登记「哪些任务本来就能并行 / 已幂等」。

## 一、结论（先看这里）

- **6 个任务**已加分布式锁：告警评估、断档→告警映射、通知投递、告警保留清理、平台自告警、断档/维护窗口保留清理。
- **4 个任务不需要锁**：它们或是一条原子 UPDATE（多副本天然安全）、或只读、或每节点必须各跑一份。
- 锁具备：**租约 TTL + 看门狗自动续期 + 异常必释放 + 获取失败=跳过（不报错）**；存储不可用时默认 fail-open（记 ERROR + 指标）。
- **未做**：真实 MySQL + Nacos 下的「2 节点 × ≥4 租户」端到端联调（本机没有运行中的 MySQL/Nacos，未获授权启动部署栈）。
  已用「真实 Redis + 2 个独立 JVM 进程」做了跨进程互斥实测，详见第六节。

## 二、定时任务清单与多副本语义分类

### 2.1 必须单实例执行（本批已加锁）

| 任务 | 触发点 | 默认周期 | 多副本重复执行的后果 | 锁名（`ypbin:lease-lock:` + 名称） |
|---|---|---|---|---|
| 告警评估 | `AlertEvaluator#evaluate` | 15s | 同一租户被并发评估：重复推进状态、重复入队通知 | `alert-evaluate` |
| 断档→告警映射 | `AlertOutageScanner#scan` | 15s | 重复建/恢复断档告警 | `alert-outage-scan` |
| 告警通知投递 | `AlertNotifyDispatcher#dispatch` | 10s | 重复扫描到期行（单条有 claim CAS 兜底，但连接与限流浪费） | `alert-notify-dispatch` |
| 平台自告警判定 | `PlatformAlertEvaluator#evaluate` | 30s（默认关） | 重复开单/续期平台告警 | `platform-alert-evaluate` |
| 断档/维护窗口保留清理 | `RetentionCleanupServiceImpl#cleanupOnce` | 24h | 重复 DELETE、计数翻倍（删除本身按截止时刻幂等） | `retention-cleanup` |
| 告警保留清理 | `AlertRetentionCleanupServiceImpl#cleanupOnce` | 24h | 重复 DELETE、计数翻倍 | `alert-retention-cleanup` |

### 2.2 可并行 / 已幂等（**刻意不加锁**，附判据）

| 任务 | 判据（仓内一手事实） |
|---|---|
| `LeaseExpiryScanner#scan` | 一条原子 `UPDATE ... WHERE state='active' AND lease_expire_at<=now`，多副本同时跑不会互相覆盖（见该类 javadoc） |
| `OutageScanner#scan` | 开断档走 `livenessMapper.markOpenOutage` 的 CAS；抢输的副本会 `deleteById` 回滚自己插入的事件（见 `AvailabilityServiceImpl#openOutage`） |
| `CommandTimeoutScanner#scan` | `markTimeout` 是「只改仍是 pending/sent 的行」的单条批量 UPDATE，天然防重入（见 `IotCommandInstanceMapper`） |
| `IotDbRowCountProbe#probeOnce` | 纯只读 `SELECT COUNT(*)`，重复执行只是重复观测（写 gauge，多副本各自一份指标） |

### 2.3 本仓其它部署单元的定时任务（#220 未改动）

| 任务 | 判据 |
|---|---|
| `LeaseRenewScheduler#renew`（access） | 每个接入节点续自己的租约，**必须每节点各跑一份**，不能单实例 |
| `AccessMetricsReporter#report`（access） | 每节点上报自己的指标快照，**必须每节点各跑一份** |
| `HttpAccessReadingSink#flush`（access） | 每节点冲刷各自的内存读数队列，**必须每节点各跑一份** |

> access 是独立部署单元、且不与 iot 共用本批的锁实现；上述三项语义上就是「每节点并行」，不需要互斥。

## 三、锁的实现：**已上收到 ypbin-starter**（2026-10-15 task-18）

> **实现位置变了，语义没变**：IoT 原先自建的 `cn.ypbin.admin.iot.lock`（14 类约 1094 行）已**删除**，
> 改为消费 `cn.ypbin.starter.tools.lock` 的通用租约锁。业务仓只保留「哪些任务互斥 + 锁名」这一层业务语义。
> 上收理由：租约锁是**跨宿主通用能力**（access / ai / system 同样需要），焊在 iot 业务仓会让每个宿主各写一份。

| 类（starter） | 职责 |
|---|---|
| `LeaseLockService` / `LockLease` | 端口：`tryAcquire`（拿不到返回 null）与租约（`renew/release/isHeld`） |
| `LeaseLockServiceImpl` | 应用层租约：值 = `token@expireAtMillis`，token = 实例 ID + 每次获取随机串 |
| `RedisLeaseLockStore` | 三条 Lua 脚本保证「抢/续/删」各自原子（绝不先 GET 再 SET） |
| `InMemoryLeaseLockStore` | 无 Redis 时的单实例回退（打 WARN；语义与 #227 之前一致） |
| `LeaseLockGuard` | 守卫：抢到才执行、看门狗续期、**finally 必释放**、fail-open/closed |
| `LeaseLockAutoConfiguration` | 装配 + 启动自检（`renew-interval < ttl` 等） |

**宿主侧剩下的东西**：各任务自己的 `LOCK_NAME` 常量（每个锁名恰好一处使用），
以及 `ScheduledTaskLockWiringTest` 钉住的「抢到才调业务、抢不到不调」接线。

**键**：`ypbin:lease-lock:{name}`（前缀可配 `ypbin.lock.key-prefix`）。

> ⚠️⚠️ **滚动升级必读：key-prefix 变了**
> 改造前 IoT 自建默认前缀是 `ypbin:iot:lock:`，starter 默认是 `ypbin:lease-lock:`。
> **键一变 ⇒ 新旧实例在滚动升级窗口内各锁各的**（旧实例锁 A 键、新实例锁 B 键），同一任务可能被两个副本同时执行。
> 处置（三选一）：① 优先「停旧 → 起新」，不做滚动共存；② 接受窗口内一次重复执行（本批后台任务均幂等）；
> ③ 过渡期把 `ypbin.lock.key-prefix` 显式设为旧值 `ypbin:iot:lock:` 让新旧互斥，升级完成后再改回。
> 本仓 `deploy/nacos/ypbin-iot.yaml` 已显式写上 starter 默认值（不依赖代码默认，避免"只改一半"）。

**TTL / 续期**（`ypbin.lock.*`）：

```yaml
ypbin:
  lock:
    key-prefix: "ypbin:lease-lock:"   # ⚠️ 由 ypbin:iot:lock: 变更而来，见上方滚动升级说明
    # enabled / ttl / renew-interval / fail-open 未显式配置 ⇒ 沿用 starter 默认：
    #   enabled=true、ttl=60s、renew-interval=20s、fail-open=true、metric-prefix=ypbin.lock
```

**续期**：`LeaseLockGuard` 在持锁期间注册一条共享守护线程按 `renew-interval`
自动调 `lease.renew()`；任务结束时先取消看门狗再释放租约。续期失败只记 WARN + 指标，不打断正在跑的任务
（任务此时最坏可能被另一实例并发执行——这是「尽力而为」的边界，见第七节）。

**释放**：`release()` 用 token 比对删除——**不是自己的锁绝不删**；释放异常只记 ERROR（残留锁由 TTL 兜底）。
无论任务正常返回还是抛异常，`try/finally` 都保证释放（starter 侧有单测钉住）。

**获取失败 = 跳过**：`tryAcquire` 在锁被占用时返回 `null`，守卫记 INFO + `ypbin.lock.skipped` 计数后直接返回，
**不抛异常、不排队**。保留清理对外的 `skipReason` 为 `lock-held-by-other`。

## 四、观测与失效处置

**指标**（Micrometer，均带 `task` tag）：

> ⚠️ **指标名已变更（2026-10-15 task-18）**：`iot.lock.*` → **`ypbin.lock.*`**（由 starter 的
> `ypbin.lock.metric-prefix` 控制，默认即 `ypbin.lock`）。**监控面板与告警规则必须同步改名**，
> 否则会静默失去对「锁被占/Redis 抖/续期失败」的观测（旧名不再有新数据）。
> 注：这三个指标现由 **starter 注册**（不再是 iot 的指标），其口径以 starter 仓为准；本节只保留
> IoT 侧的「任务名 ↔ 指标 tag」对照与判读建议。本仓 `docs/METRICS.md` 只覆盖 ypbin-access，不含本组。

| 指标 | 含义 | 异常判读 |
|---|---|---|
| `ypbin.lock.skipped` | 因锁被其它实例持有而跳过 | 正常情况下只有少数实例在某个任务上持续 >0；若所有实例都持续为 0，反而要怀疑「锁一直抢不到或任务没在跑」 |
| `ypbin.lock.unavailable` | 锁存储不可用、fail-open 继续执行 | **>0 必须处理**：说明 Redis 抖过，该轮多副本可能重复执行 |
| `ypbin.lock.renew.failed` | 续期失败（锁可能已丢失） | **>0 必须处理**：Redis 不稳或任务耗时逼近 TTL，考虑调大 `ttl` |

**Redis 里直接看**：`KEYS ypbin:lease-lock:*` / `GET ypbin:lease-lock:alert-evaluate`，
值形如 `<instanceId>:<uuid>@<毫秒到期时刻>`，可据此看出「谁持锁、什么时候过期」。

**日志**：`[ypbin] 任务 X 的分布式锁已被其它实例持有，本次跳过`；
`[ypbin] 分布式锁不可用，按 fail-open 继续执行`；
`[ypbin] 任务 X 的锁续期失败`；`[iot] 保留清理未取得分布式锁`。

| 失效场景 | 表现 | 处置 |
|---|---|---|
| 持锁实例崩溃 | 锁在 `ttl` 后过期，其它实例自动接管；期间该任务最多停 `ttl` | 无需人工；想更快接管就调小 `ttl`（保证 `ttl > 单轮最坏耗时` 且 `renew-interval < ttl`） |
| Redis 不可达 | `ypbin.lock.unavailable` 增长，任务按 fail-open 继续（多副本可能重复） | 修 Redis；要严格单实例则设 `ypbin.lock.fail-open=false`（任务会直接失败，由调度器记异常） |
| 无 `StringRedisTemplate` | 启动打 WARN，退化为进程内锁 | **多副本部署必须有 Redis**；单实例可用 |
| 配置 `renew-interval >= ttl` | **启动失败**（自检拒绝） | 调小 `renew-interval` 或调大 `ttl` |
| 任务耗时 > TTL 且续期全失败 | `ypbin.lock.renew.failed` 增长，可能出现并发执行 | 调大 `ttl` 并排查 Redis 延迟 |
| **滚动升级期新旧实例混跑** | 两边锁键不同（`ypbin:iot:lock:` vs `ypbin:lease-lock:`）⇒ 同一任务被两个副本各跑一遍 | 见第三节「滚动升级必读」；本批任务均幂等，最坏一次重复执行 |

## 五、告警通知的重复发送

通知**行**的幂等由数据库保证：`uk_alert_notification_idem(tenant_id, idempotent_key)` +
`INSERT ... ON DUPLICATE KEY UPDATE id = id`，幂等键含 `instance_id:event:channel:target:轮次`。
投递前还有一次 `claim` CAS（把 `next_retry_ts` 推到租约之后），只有 CAS 成功的那一次才真正发送。
本批给投递器再加分布式锁，把「N 个副本同时扫同一批到期行」收敛为「一个副本在投递」，
减少连接浪费并让实例内限流的行为更确定。

**测试钉住**：`ScheduledTaskLockWiringTest`（锁被占时投递器零调用）、
`AlertRetentionCleanupServiceImplTest`、以及既有的 `AlertTenantNotifierTest` / `AlertNotifyPlannerTest`
（幂等键与 claim 语义）。

## 六、实测证据（本机真跑）

### 6.1 单元 / 组件级

> **2026-10-15 task-18 后，锁实现类已迁到 ypbin-starter** ⇒ 原先针对实现的用例
> （`LeaseDistributedLockTest`/`TaskLockGuardTest`/`RedisLockStoreTest`/`IotLockConfigurationTest`/
> `MultiNodeLockContentionTest`）随实现一并移出本仓，改由 starter 仓的同名用例覆盖
> （`LeaseLockServiceTest`/`LeaseLockGuardTest`/`RedisLeaseLockStoreTest`/`LeaseLockConfigurationTest`），
> 覆盖同样的语义：抢到才执行 / 抢不到跳过 / 任务异常必释放 / TTL 到期接管 / 续期 /
> 非持有者续期删除无效 / fail-open 与 fail-closed / 看门狗注册与取消。
> **本仓保留的是宿主接线用例**（这部分不可上收）：

```
# 宿主侧：哪些任务互斥 + 锁名 + 「抢到才调业务」
mvn -B -o -pl ypbin-service/ypbin-iot -am test \
  -Dtest='ScheduledTaskLockWiringTest,RetentionCleanupServiceImplTest,AlertRetentionCleanupServiceImplTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

覆盖：每个 `@Scheduled` 壳「锁被占时零调用、空闲时恰好一轮」、不同任务锁互不影响、
锁名唯一性、保留清理在锁被占时如实返回 `lock-held-by-other`。

### 6.2 真实 Redis + 2 个独立 JVM 进程（跨进程互斥）

> **⚠️ 本节所述探针（`RedisLockTwoProcessDemo`）已随自建锁实现一并删除（2026-10-15 task-18）。**
> 它验证的是「Redis 存储 + 应用层租约」的跨进程互斥——**那正是已上收到 starter 的能力**，
> 现由 starter 仓的 `RedisLeaseLockStoreTest`（Lua 抢/续/删原子性）与其集成测试覆盖。
> 本节保留历史实测记录，用于说明**当时的证据强度**，不代表当前仓内仍可复现该命令。
>
> 当前仓内可复现的跨实例验证是 **SSE 扇出**（同一能力批次的另一半）：
> `mvn -o -pl ypbin-service/ypbin-iot -am test -Dtest='IotRealtimeServiceTest,IotSseWiringTest'`
> —— 覆盖「租户隔离（A 的连接不得收到 B 的事件）」与「结构化载荷可编码（否则跨实例静默退化为只投本实例）」。
>
> 历史实测（2026-10-13，当时自建实现、现已迁出）记录如下：

两个进程抢同一把 `ypbin:iot:lock:alert-evaluate`（**旧前缀，现已变更为 `ypbin:lease-lock:`**）：

```bash
CP="ypbin-service/ypbin-iot/target/classes:ypbin-service/ypbin-iot/target/test-classes:$(cat /tmp/iot-cp.txt)"
export REDIS_HOST=127.0.0.1 REDIS_PORT=16379
java -cp "$CP" cn.ypbin.admin.iot.lock.RedisLockTwoProcessDemo node-A 6000 20000 &
sleep 1.5
java -cp "$CP" cn.ypbin.admin.iot.lock.RedisLockTwoProcessDemo node-B 4000 20000
```

实测输出（节选，毫秒时间戳为原样；**命令在当前版本已不可跑，仅存证**）：

```
[...874388][node-A] ACQUIRED 锁（key=ypbin:iot:lock:alert-evaluate，TTL=30s）
[...874420][node-A] 处理 tenant-1
[...875922][node-A] 处理 tenant-2
[...877424][node-A] 处理 tenant-3
[...878924][node-A] 处理 tenant-4
[...880435][node-A] RELEASED 锁
[...875527][node-B] SKIPPED：锁被其它进程持有，等待重试…
[...880609][node-B] ACQUIRED 锁（key=ypbin:iot:lock:alert-evaluate，TTL=30s）
[...880618][node-B] 处理 tenant-1
...
[...884628][node-B] RELEASED 锁
```

结论：node-A 持锁期间 node-B 的所有尝试均失败；node-A 释放 174ms 后 node-B 才拿到锁，
**两个进程没有任何重叠的持锁区间**，满足「2 节点下同一轮任务只有一个执行者」。

## 七、未核实 / 已知边界（诚实清单）

1. **未做真库端到端**：真实 MySQL + Nacos 下的「2 节点 × ≥4 租户」完整联调未执行（本机没有运行中的
   MySQL/Nacos，未获授权启动部署栈）。第 6.1 节的 2 节点 × 4 租户是共享锁上的组件级模型，第 6.2 节是
   真 Redis + 真双进程，但**没有经过真实告警规则/设备数据的端到端链路**。
2. **时钟偏移**：锁的过期判定用**各副本的应用时钟**（换取可注入时钟的可测性）。副本间有明显时钟偏移时，
   「谁先抢到过期的锁」可能不同；Redis 侧 `PX` 只做键不永久残留的兜底。建议各节点 NTP 同步。
3. **续期失败不是硬中断**：看门狗续期失败后正在跑的任务**不会被强行终止**（Java 无法安全强杀），
   最坏出现一小段并发执行；靠 `ypbin.lock.renew.failed` 暴露，靠调大 `ttl` 规避。
4. **锁不覆盖 access 部署单元的每节点任务**（它们本就该每节点并行，见 2.3）。
5. **待反哺 starter**：`DistributedLock` 是通用能力（Redis 租约锁 + 守卫），长期应沉到
   `ypbin-starter`（如 `ypbin-starter-lock`）供各业务域复用。本批为不改动本仓之外的仓库，
   先在 iot 内落地过渡实现；**本机无法访问 starter 仓的 issue/PR 追踪，未创建反哺 PR 链接**，
   登记于此，待具备条件时以本实现为蓝本反哺并把 iot 侧改为依赖 starter。
6. **`RetentionSkipReason.ALREADY_RUNNING` 保留但不再产生**：`lock-held-by-other` 取代了它表达跨实例
   跳过；旧值仍在枚举里，避免破坏 #227 已建立的对外契约。
