# 租户节点归属与租约（增量 2）

> 解决的问题：IoT 平台里「**哪个 access 节点采哪个租户**」必须是服务端的权威事实——
> 否则节点扩容/重启/挂掉时会出现两个节点采同一租户（双主），或者租户静默离线没人发现。

## 一、对外契约（内部端点）

路径前缀 `/internal/lease`，**仅供 access 单元调用**，由 `InternalTokenGuardInterceptor`（+ `InternalTokenGuardWebConfig`）保护
（凭证未配置一律拒绝，常量时间比较，失败转 HTTP 200 + `R.code=401`）。

> ⚠️ **网关侧的限制（已知缺口，跨仓待办）**：本仓的网关路由是 `Path=/iot/**` + `StripPrefix=1`，
> 因此 `POST /iot/internal/lease/release` 会被转发到服务内的 `/internal/lease/release`；
> 而网关既没有「拒绝 `/internal/**`」的规则，也没有把 `X-Internal-Token` 放进剥离名单
> （`HeaderSanitizeGlobalFilter` 只清身份头）。**这是全平台既有形态**（`/system/internal/**` 同样如此），
> 不是本增量新引入，但本增量新增了 `/internal` 端点，所以必须记下来：修复要在 **admin 仓**做
> （加拒绝规则 + 把该头加入剥离名单），本仓按 `SYNC.md` 通过同步获得。

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | `/register` | 注册节点（`accessNode` + 可选 `maxTenants`，**不填 = 不限**）。幂等覆盖 |
| POST | `/acquire` | 领取/续期：返回本节点当前应采集的租户清单（含到期时间与 epoch） |
| POST | `/renew` | 周期续约：`renewedLeases`（成功）+ `revokedTenantIds`（不再属于本节点）+ `nodeFenced`（节点级失效） |
| POST | `/release` | 节点主动下线时释放租户（状态置为 `released`，可被重新分配） |
| POST | `/assignment` | 查询单个租户的归属（从未分配返回 `data=null`） |
| GET | `/epochs` | 批量对账：一次拉取所有租户的 epoch（**判据只用 epoch**，不用设备数） |

状态机：`ACTIVE --到期/释放--> PENDING_TAKEOVER | RELEASED`，`PENDING_TAKEOVER | RELEASED --接管/重新分配--> ACTIVE`。

## 二、并发正确性（为什么不会双主）

**归属裁决不使用进程内锁**，全部由数据库裁决（单条原子 UPDATE / `INSERT IGNORE`），因此多副本部署也只有一个赢家；
**容量计数额外用「每节点进程内锁」**（仅同一 JVM 有效，见 §5 的 1b）——两者作用不同，别混为一谈：

| 操作 | 手段 |
|---|---|
| 领取时续期自己持有的 | 单条 `UPDATE ... WHERE access_node=? AND state='active'` |
| 接管（待接管/已释放/已过期，**含本节点自己的**） | 单条 `UPDATE ... SET epoch = epoch + 1, access_node=? ... WHERE (state IN ('pending_takeover','released') OR (state='active' AND lease_expire_at<=now)) LIMIT <容量剩余>`（**不能**排除本节点：否则节点领不回自己被判失效/已释放的租户） |
| 首次分配 | 单条 `INSERT IGNORE ... VALUES (...),(...)`：唯一键（`tenant_id`）冲突者被跳过，其余写入 |
| 续约 | 一次批量 `UPDATE` + 一次批量查询定位「实际续到的」；`请求 − 续到` = 需回收 |
| 释放 | 单条 `UPDATE ... WHERE access_node=? AND state='active' AND tenant_id IN (...)` |
| 失效扫描 | 单条 `UPDATE ... SET state='pending_takeover' WHERE state='active' AND lease_expire_at<=now` |

**没有循环内的 DB 调用**（架构门禁 `loopsMustNotCallDbOrRpc` 会拦 N+1）。

## 三、epoch（台账版本号）语义

- **归属每次「转移」都推进**：首次分配 = `1`；接管（含释放后重新分配）= 当前值 + 1（在 SQL 里自增，避免读-改-写丢更新）。
  `release` 本身只改状态与到期时间、**不涨 epoch**（它不是归属转移，行仍属原节点名下直到被接管）。
  这样旧快照的 epoch 一定更小，无法被采纳——**这条解决了旧独立栈 ADR-0001 里「释放→再分配不涨 epoch」的悬空项**。
- 判据是纯函数（`LeaseEpochRules`）：`shouldAdoptSnapshot` / `shouldApplyEvent` / `nextEpoch` / `shouldFence` /
  `needsSelfFence` / `shouldReplayAfterSnapshot` / `isLeaseExpired`，实现只能引用它们，不许另写一套。

## 四、续约语义（本增量定死的取舍）

请求里带的**本地 epoch 不再用于拒绝续约**：只要归属仍在本节点名下就续期，并把**服务端 epoch** 放进回执，
节点据此自更新。旧设计把「本地 epoch 落后」当吊销——一次视图滞后就换来整租户断链，抖动大且没必要。
真正需要停采的是「归属已不在本节点名下」，那一条仍然进 `revokedTenantIds`。

## 五、已知取舍与后续（诚实清单）

1. **节点注册表在进程内**：本服务重启后节点需重新注册，期间它们的续约会收到 `nodeFenced=true`。
   这是设计好的恢复路径（access 收到节点级失效 → 整体停采 → 重新注册 → 重新领取），
   但「容量与节点存活跨重启」应落库，属 **M0b**。
1b. **容量的临界区只在同一 JVM 内**：领取用「每节点锁 + 事务模板（提交先于解锁）」保证同进程并发不超额；
   多个副本用同一个 nodeId 属误配置（nodeId 是归属键，必须唯一）。数据库级原子容量（节点行 + `SELECT ... FOR UPDATE`）属 **M0b**。
1c. **跨副本时钟偏移未防护**：到期时间用**应用时钟**写、也用应用时钟比较（`markExpired`/接管条件）。
   若某副本时钟快于其它副本，可能出现「续约成功的同时被判定过期」的窗口（≤ 一个续约周期）。
   要求多副本 NTP 对齐；彻底解法是把比较改成数据库时间。属 **M0b**。
2. **可分配租户来自配置**（`ypbin.lease.assignable-tenant-ids`，空 = 不分配任何）：
   M0b 起应来自设备/租户台账表，避免「配置漏了就什么都不分配」这种静默形态（当前是**显式**的）。
3. **真实并发未在本机验证**：单测覆盖的是「发出的 SQL 守卫条件」与「affectedRows 各取值下的分支」；
   多副本抢同一租户的真库并发测试属 **M0b/P5**（旧栈曾用 200 轮并发用例抓出过双主）。
   ⚠️ 本机虽有既存 MySQL 容器（部署栈，非本项目所有），**未获授权不得使用**，因此没有真库验证。
3b. **`INSERT IGNORE` 是 MySQL 语义**：除唯一键外，它也会忽略其它「可忽略错误」（如数据截断）。
   `tenant_id/epoch/state/lease_expire_at` 由服务端生成，但 **`access_node` 来自请求**
   （`AccessNodeRegisterReq` 等）——现已按列宽加 `@Size(max = 128)` 校验；即便如此，换数据库时仍必须同步改这条 SQL。
3c. **接管 SQL 的 `LIMIT` 没有 `ORDER BY`**：选取哪几个候选不确定（正确性无碍，但行为不确定、不便复现）。
3d. **单表大事务风险**：容量不限时 `LIMIT 2147483647`，且首次分配是一次插入全部可分配租户。
3e. **容量只约束「新增」，不回收已持有**：把注册请求里的 `maxTenants` 调小（或后续改成节点表字段）不会让节点主动释放已持有的租户。
3f. **扫描周期与 ttl 无自检关系**：接管最坏延迟 ≈ `ttl + scan-interval`（默认 45s），
   靠「active 且已过期」的兜底分支缩短；这条关系未做成启动自检。
3g. **`batchEpoch` 是全表读**（无分页/上限）：自用规模无碍，租户数上来要加上限或分页。
4. **access 侧的 Feign 客户端**（`ILeaseClient`）随**增量 3** 一起落地——先有服务端契约，再有调用方。
5. 失效扫描与续约的**指标**已埋（`iot.lease.takeover|expired|revoked`），但**告警阈值**未定（M1 观测面）。

## 六、配置参考

见 `deploy/nacos/ypbin-iot.yaml`：

```yaml
ypbin:
  tenant:
    ignore-tables: [tenant_node_assignment]   # 平台表：不受租户插件约束
  lease:
    enabled: true
    ttl: 30s                  # 必须 > expected-renew-interval + 4s（一次内部调用最坏耗时），启动自检拒绝不合法组合
    scan-interval-ms: 15000
    expected-renew-interval: 10s
    assignable-tenant-ids: []  # 空 = 不分配任何租户
```
