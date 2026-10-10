# 访问层技术债 R8 系列立项台账（R8-2 / R8-4 ~ R8-9）

> **建立日期**：2026-10-09（初稿同日经独立子代理审计后更正，见 §0.1）
> **依据**：`docs/IOT-ROADMAP.md:405-418`（R8/G8 行）+ `docs/PLATFORM-GAP-REPORT-2026-09-28.md:99,286`（对账/一致性面 + 决策点 6）
> **本文性质**：**只立项、不排期、不实施**。目的是把「扩展前再做」的糊状表述换成**逐条可验收**的条目：
> 每条都有 ①一手证据（文件:行号）②现状判定 ③触发前提 ④方案选项 ⑤可复现验收标准 ⑥依赖/工作量 ⑦风险与回滚。
> **排期口径**：仍遵守用户 2026-09-28 的拍板（`TASK-BOARD.md:19`）——**多租户/多节点扩展前再做，现阶段不做**；
> 本文件只把「做什么」定清楚，**不改变「什么时候做」**。§4 给出扩展前的建议顺序。

---

## 0. 结论先行

| 条目 | 现状判定（2026-10-09 复核） | 触发前提（现实性） | 扩展前优先级 |
|---|---|---|---|
| **R8-2** 对账拖长 tick | **仍存在**：同 tick 内「续约 → 安全网 → 逐租户串行 `loadByTenant`」；启动自检只按**租约**客户端（4s）建模，**未**计入设备规格客户端（6s/租户）；且 access **只有 1 个调度线程**，1s 的 egress 上报与 10s 的续约 tick 互相排队 | 单节点需**持有 ≥4 个租户**且同一 tick ≥3 个租户变更 | P1（后果是抖动而非丢数） |
| **R8-4** `/epochs` 不按节点过滤 | ✅ **已实施（2026-10-09）**：契约改为 `batchEpoch(accessNode)`，过滤下沉 SQL（4 变异转红，见 §2.2；分页方案经架构门禁复核后放弃）。原判：**仍存在**：端点无入参；`batchEpoch()` 全表两查（assignment + ledger），无节点过滤/分页；过滤只在**客户端**做 | **多节点**（单节点等价，无实质代价）；租户数多时单节点也开始放大 | P0（多节点硬前置，改动最小） |
| **R8-5** `fence()` 不清订阅跟踪 | ✅ **已实施（2026-10-09，方案 A）**：`fence()` 补 `planner.forget`（与下架路径对称）+ fence 用例断言（见 §2.3）；框架侧契约测试 LIFE-13 钉住「ADD ⇒ 新实例」。原判：**部分存在**：`fence()` 与 `removeAllDevices()` 不对称、且 fence 用例**零断言**；**但「重领后漏订阅 ⇒ 静默零数据」在框架 0.2.0 下不成立**（§2.3 有源码判定） | 引用残留：任何 fence 都会留下 | P2（残余 = 引用只增不减 + 契约无门禁） |
| **R8-6** 规格变化只重发 ADD | ✅ **方案 A 已落地（2026-10-09）**：框架仓 PR #21 的契约测试 LIFE-13 把「ADD ⇒ 新会话实例」变成有测试的承诺（本仓 B/C 未做，见 §2.4）。原判：**部分存在**：确实只发 ADD；**但框架 `bind()` 先 `detach()` 关旧会话再建新实例 ⇒ 会重新订阅新点位**，静默零数据不成立；跨模块依赖仅以**测试注释里的假设**形式存在 | 仅当框架改为复用会话实例时 | P2（与 R8-5 同源，一并做契约测试） |
| **R8-7** 平台级不变量无门禁 | ✅ **已实施（2026-10-09）**：覆盖面缺口已补齐（扫 migration + `INSERT … SELECT` 可达性 + 显式清单；4 变异转红，见 §2.5）。原判：**部分存在（原表述已过期）**：**反向门禁 2026-09-26 已存在**（`IotMaintenanceAdminGateTest:204`，PR #48）+ 两条正向门禁；**残留 = 覆盖面缺口**：不扫 `migration/*.sql`、只认 `32xx/33xx`、只认 `id IN (...)` 形态——仓内已有一条**门禁看不见**的 `INSERT IGNORE … SELECT …, 3320`（3320 为 `platform_only=1`） | 无需触发；当前该语句命中 0 行，**安全性由数据巧合维持**而非门禁 | P1（纯门禁小改，零运行时代价） |
| **R8-8** bump 与业务写入同事务无守卫 | **仍存在**：无自动守卫；台账写失败会随外层事务**回滚设备/点位写入**（可用性耦合）；另 `bumpConfigEpochOfCurrentTenant()` **自调用**使 `bumpConfigEpoch` 的 `@Transactional` 代理失效 | 台账表被锁/DDL 窗口/连接池耗尽 | P1（需先拍板「一致性 vs 可用性」） |
| **R8-9** 安全网成本与可观测性 | ✅ **可观测性已实施（2026-10-09，A+B）**：新增 `docs/METRICS.md`（33 条逐条登记）+ 3 条耗时计时器（tick/对账/单租户取数）+ 双向门禁（见 §2.7）；剩「安全网成本」属 R8-2 选项。原判：**部分存在**：`ypbin-iot.yaml:264-275`（#53）与 `ypbin-access.yaml:87-105`（#75）**已补**指标暴露（原文"没有暴露配置"已过时）；**剩余** = 新指标未登记进任何指标文档/大盘（全仓无 Prometheus/Grafana）+ 安全网成本 | 成本项：多租户线性放大；登记项：无需触发 | P1（登记属纯文档） |

### 0.1 本次对既有记述的更正（勿再沿用旧表述）

| # | 旧表述（出处） | 复核结论 |
|---|---|---|
| 1 | 「P5 的平台级不变量**无门禁**」/「`IotPermissionCodeGateTest` 只校验权限码存在性」（`IOT-ROADMAP.md:414`） | **已过期**。`IotMaintenanceAdminGateTest` **自 PR #48（2026-09-26）** 起就有反向门禁（`:204 platformOnlyMenusMustNeverEnterTenantTemplate`）与两条正向门禁（`:119`、`:163`）。真实缺口是**覆盖面**（不扫 migration / 只认 `32xx,33xx` / 只认 `id IN` 形态），不是「没有门禁」。 |
| 2 | 「`deploy/nacos/ypbin-iot.yaml` **没有**指标暴露配置」（`IOT-ROADMAP.md:416`） | **已过期**。`:264-275` 已有 `management.endpoints.web.exposure.include: health,metrics,info`（#53 / 2026-09-26），access 侧同款（#75 / 2026-09-27）。 |
| 3 | 「+6s：Feign connect1+read5」与「一次调用最坏耗时」并置（`IOT-ROADMAP.md:409`） | 数字正确但**口径混用**：**设备规格**客户端 = 1s+5s = **6s**（`DeviceSpecFeignConfiguration:34,37`）；**租约**客户端 = 1s+3s = **4s**（`LeaseFeignConfiguration:37,40`，启动自检用的就是这个）。两者不可互换。 |
| 4 | R8-5/R8-6「需真 socket e2e 核实（是否复用同一 `DeviceSession` 实例）」 | **可由框架源码判定**（本次已判）：0.2.0 下**不复用**，每次 ADD 都换新实例 ⇒ 原担心的静默零数据不成立。真 e2e 仍是金标准，但不再是**判定该机制**的必要条件。 |

---

## 1. 共同前提（复核时必须先对齐的口径）

| 量 | 值 | 一手出处 |
|---|---|---|
| 续约（调度 tick）周期 | 10s | `ypbin-service/ypbin-access/.../lease/LeaseRenewScheduler.java:36`（`${ypbin.access.renew-interval-ms:10000}`） |
| tick 内容与顺序 | 自检 → 续约 → 按需刷新归属 → **最后**对账 | `.../lease/AccessLeaseManager.java:199-209` |
| 重领周期 | 15s | `.../config/AccessProperties.java:51` |
| 配置安全网间隔 | 5min（可配，≤0 关闭） | 同上 `:68`（`configRefreshIntervalMs = 300_000L`） |
| 租约 TTL | 30s | `deploy/nacos/ypbin-iot.yaml:141`（`lease.ttl: 30s`）、`LeaseProperties.java:47` |
| 租约客户端最坏耗时 | **4s** = connect 1s + read 3s | `ypbin-service-api/.../lease/config/LeaseFeignConfiguration.java:37,40`；`AccessStartupValidator.java:50-52` |
| 设备规格客户端最坏耗时 | **6s** = connect 1s + read 5s | `ypbin-service-api/.../device/config/DeviceSpecFeignConfiguration.java:34,37` |
| 启动自检安全倍数 | `renewIntervalMs ≥ 4s × 2 = 8s`（默认 10s 通过） | `AccessStartupValidator.java:36,65` |
| 安全网每轮强制上限 | 1 个租户/轮 | `.../lease/ConfigEpochReconciler.java:216` |
| access 调度线程数 | **1（Spring 默认）**——`deploy/nacos/*.yaml` 里 `spring.task.scheduling` 只出现在 `ypbin-iot.yaml:281`；access 无配置 | `grep -rn scheduling deploy/nacos/*.yaml` |
| access 的 @Scheduled | 2 个共用一个线程：续约 tick（10s）、egress 微批上报（1s） | `LeaseRenewScheduler.java:36`、`HttpAccessReadingSink.java:99` |
| 框架版本 | `ypbin-iot.version = 0.2.0` | `ypbin-service/ypbin-access/pom.xml:21` |
| 当前拓扑 | **单节点**、18086 只绑回环 | `deploy/nacos/ypbin-access.yaml:75-78`、`docs/ACCESS-ENABLE.md` |

---

## 2. 逐条立项

### 2.1 R8-2 对账可能拖长调度 tick（P1）

**缺陷**：一次 tick 内串行完成「自检 → 续约 → 归属刷新 → **逐租户对账**」，对账每租户一次**设备规格远端调用**（最坏 6s）。单 tick 超过 TTL（30s）⇒ 下一轮 `selfFenceExpiredLocally` **批量自我 fence 并重领**，形成抖动；线上一旦如此，1s 的 egress 上报也会被同一个调度线程排队拖慢。

**一手证据**
- `AccessLeaseManager.java:199-209`：`selfFenceExpiredLocally(now)` → `renew(now)` → `refreshAssignmentsIfDue(now)` → `reconciler.reconcile(Set.copyOf(holdings.keySet()))`（**同一 tick、串行**）。
- `ConfigEpochReconciler.java:146-151,227-232`：逐条 `reconcileItem` → `linkManager.reconcile(tenantId)`；`IotProtocolTenantLinkManager.java:306-312`：`source.loadByTenant(tenantId)`（Feign 远端，串行）。
- `DeviceSpecFeignConfiguration.java:34,37`：1s + 5s = **6s/租户**（**未**进入启动自检模型）。
- `AccessStartupValidator.java:50-53,65`：`worstCaseMs()` 只用 `LeaseFeignConfiguration`（4s），校验 `renewIntervalMs ≥ 8s` ⇒ **通过**，但对账的 N×6s 完全不在模型内。
- `LeaseRenewScheduler.java:36`（tick 10s）、`deploy/nacos/ypbin-iot.yaml:141`（TTL 30s）。
- 单线程：`deploy/nacos/ypbin-access.yaml` 无 `spring.task.scheduling`（对比 `ypbin-iot.yaml:281` 有 `pool.size: 4`）。

**触发前提**：单节点持有 **≥4 个租户**且同 tick ≥3 个租户发生版本变化（阈值来自 `IOT-ROADMAP.md:409`；**该行的 36s 推导本次未复核**，实施时必须先实测再建模，见验收标准）。

**方案选项**
- A. **tick 级时间预算**：对账前记预算（如 0.5×TTL），超预算把剩余租户延后到下一 tick（计数 + 日志）。
- B. **有界并发**：对账远端调用改为有界并发（替代纯串行），配合每 tick 调用上限。
- C. **收敛安全网触发条件**：仅在「无台账 / `config_epoch` 恒 0 / 距上次成功对账超时」时强制（现状是「距上次**尝试**超时」，健康租户也会被强制）。
- D. 调大 `configRefreshIntervalMs` 或设为 ≤0 关闭安全网（**仅在确定信号链路可靠时**）。
- E. （正交）**给 access 单独配调度线程池**，把 egress 上报与租约 tick 解耦——最小、可独立交付。

**验收标准（可复现）**
- 统一前置：先做 R8-9 B 的 **tick 耗时/对账耗时指标**，用实测数据重建时间预算模型（**不得**沿用未复核的 36s 算式）。
- A/B：用例钉住「N 个变更租户 + 单次调用 t ⇒ 单 tick 总耗时 ≤ 预算」且「超额租户下一 tick 被处理（不丢）」；变异：去掉预算判断 ⇒ 转红。
- C：用例钉住「`config_epoch` 持续变化的租户**不被**安全网强制」。
- E：配置变更 + `PROD-OPS-NOTES.md` 登记；断链场景 egress 上报延迟有实测对比。
- D：仅配置 + 登记「关闭安全网后信号缺失场景的替代兜底」。

**依赖 / 工作量**：依赖 R8-9 B；A/B 1–2 天；C/E/D 各 0.5 天。
**风险 / 回滚**：C/D 是**行为变更**（收敛变慢），必须同时登记代价；E 涉及线程模型（需确认 egress sink 的线程安全性）；回滚 = 还原配置/常量。

---

### 2.2 R8-4 `/internal/lease/epochs` 不按节点过滤（P0，多节点前必修）

> **✅ 已实施（2026-10-09）**：验收标准里的**方案 A（按节点过滤）**已落地：
> ① **契约**：`GET /epochs?accessNode={node}`，`accessNode` **必填**（空/缺失一律拒绝——不按节点过滤就等于把退化行为再打开）；校验放在 api 模块 `LeaseEpochRules#validateAccessNode`，**服务端与客户端共用同一口径**。
> ② **SQL**：新增 `TenantNodeAssignmentMapper#selectEpochItemsByNode`（`WHERE a.access_node = ?` 走 `idx_tenant_node_assignment_node`、`ORDER BY tenant_id` 保证响应稳定、两条 `is_deleted = 0` 手写补上），复杂度从 O(节点数 × 全平台租户) 降到 O(本节点持有租户)。
> ③ **客户端**：`ConfigEpochReconciler` 持有 `nodeId` 并按其做**一次**批量调用；`AccessLeaseConfiguration` 传 `properties.getNodeId()`；`docs/LEASE.md` §一 契约同步。
> **⚠️ 方案 C（分页）为什么被放弃（设计取舍，如实记录）**：初版已实现「节点过滤 + `limit/offset` 翻页」，被 **CI 架构门禁** `SourceConventionTest#loopsMustNotCallDbOrRpc` 拦下（循环体内的 RPC 判定为 N+1）。复核该判定**成立**：分页必然把「一次批量调用」变成「每 `limit` 行一次**串行** RPC」——对单节点持有量（远小于一页）反而更贵，且会吃掉 tick 时间预算（与 R8-2 相悖）。因此改为**只做节点过滤**：返回集已按节点收敛、规模由节点容量（`ypbin.access.capacity`）天然约束，不需要分页兜底；同时避免为一条可避免的循环 RPC 永久开一个门禁豁免。
> **R6 独立复核（外委子代理，对抗式只读）结论**：无阻断项（它在 5611e0c0 上独立复现了架构门禁判红、在收敛后的 963c170f 上独立跑出 `SourceConventionTest` 20/0/0/0）。据其非阻断建议本批已修 5 处：① 缺参/空白参从「兜底 code=500 + ERROR 全栈」改为 `BusinessException(BAD_REQUEST)`（code=400 + WARN），并补 HTTP 层用例 `InternalLeaseControllerTest`（4/0/0/0，钉住缺参/空白/原值透传）；② 客户端 `ConfigEpochReconciler` 构造期调同一 `validateAccessNode`（让「服务端与客户端共用口径」名副其实 + fail-fast）；③ 去掉读侧 `trim()`（与写侧存原值对称，消除「node-id 带空格 ⇒ 查询恒空、信号静默失效」路径）；④ 清理 3 处死导入；⑤ IT 租户区间从与其它 IT 重叠的 9200xx 迁到 **929xxx**，`purge()` 改为「节点名 + 区间」双重清理（容器 reuse ⇒ 只按区间清理会留历史残留，本 IT 初版因此假红过一次）。
> **验证**：新增真库 IT `LeaseEpochNodeFilterIT` **4/0/0/0**（节点过滤+升序 / 无归属节点返回空集 / 软删台账不复活 `config_epoch` / 软删归属不出现）、iot 模块 **883/0/0/0**（含新 MockMvc 4 条）、access 模块 **117/0/0/0**；**变异 4/4 转红**：M1 去掉节点过滤（IT 节点过滤用例红）、M2 去掉台账 `is_deleted=0`（软删台账用例红）、M3 去掉归属 `is_deleted=0`（软删归属用例红）、M4 客户端不传本节点标识（单测 `reconcileMustQueryOnceWithNodeId` 红）。每处先确认落地、跑完 `git checkout --` 还原并复跑确认全绿。
> **未做（本条剩余面）**：生产多节点环境未实测（需 ≥2 节点 + ≥4 租户，登记于 §5）。

**缺陷**：每个 access 节点每 10s 拉**全平台** assignment × ledger；服务端无过滤、无分页，过滤只在客户端做 ⇒ 复杂度 O(节点数 × 全平台租户)。

**一手证据**
- `InternalLeaseController.java:113-116`：`@GetMapping("/epochs") public R<TenantEpochBatchResp> batchEpoch()` —— **无入参**（对照 `:103-106` `/assignment` 是 POST + 请求体）。
- `LeaseServiceImpl.java:408-418`：`mapper.selectList(...)` 全表取 `tenantId, epoch`；再全表取 `tenant_ledger`；**无 `accessNode` 过滤、无分页**。
- 客户端契约同样无参：`ILeaseClient.java:82-83`。
- 过滤在客户端且是「取回后丢弃」：`ConfigEpochReconciler.java:131`（每 tick 无条件 `batchEpoch()`，仅 `holdings` 为空时短路）、`:166-169`（`if (!heldTenants.contains(tenantId)) return false;`）。

**触发前提**：**多节点**（≥2 即成立）；单节点在租户量大时也线性放大（每 10s 全表两查 + 全量响应体）。

**方案选项**
- A. 加 `?accessNode=` 只返回**本节点持有/待接管**的租户（须定义「持有」= `state=ACTIVE and node_id=?` 与 `PENDING_TAKEOVER` 的口径）。
- B. 分页（游标）+ 客户端循环。
- C. A+B（推荐）：节点过滤为主，分页兜住单节点持有过多租户。

**验收标准**
- 真库 IT（仓库已有 `TenantLedgerIT`/`LeaseConcurrencyIT` 先例）：2 节点 × N 租户，断言 `accessNode=X` 只返回 X 的条目、条数 = X 持有数（**不得**用 mock 计数证明）。
- 变异：去掉节点过滤 ⇒ 用例转红。
- 契约同步：`ILeaseClient` 与 controller 签名一致（编译期保证）+ 回写 `docs/LEASE.md` §一。

**依赖 / 工作量**：无前置；约 1 天（含 IT）。
**风险 / 回滚**：过滤口径写错 ⇒ 节点看不到应接管租户（**漏采**）⇒ 必须先钉 `PENDING_TAKEOVER` 语义；回滚 = 保留旧语义（开关或路径版本化）。

---

### 2.3 R8-5 `fence()` 不清理订阅跟踪（P2）

> **✅ 已实施（2026-10-09，方案 A）**：
> ① **本仓**：`IotProtocolTenantLinkManager#fence()` 循环内补 `planner.forget(device.deviceId())`，与 `removeAllDevices()` 逐条对称 ⇒ 停采不再留下只增不减的跟踪条目；用例 `fenceShouldEmitRemoveAndBeIdempotent` 断言 `forgotten` 含被停采设备，并在 `fenceMustClearBackoffSoReacquireRefetchesImmediately` 明确边界「该租户从未成功推送设备（空清单不入 `collected`）⇒ fence 早退、无从清起，不应误清」。
> ② **框架仓**（`ypbin-iot-starter` PR #21）：新增契约测试 `IotLifecycleTest#everyAddMustCloseOldSessionAndRebindNewInstance`（LIFE-13）——规格变化（只重发 ADD）与 `REMOVE → ADD` 两条路径都必须「先关闭旧会话、再产生新实例」；并在 `CONTRACT.md` §四 把它写成**行为承诺**（含宿主依赖点）。变异 2/2 转红（去掉 `detach()` / 把旧实例放回会话表）。
> **验证**：access 模块 **119/0/0/0**；**变异 1/1 转红**（删掉 `fence()` 里的 `planner.forget` ⇒ `fenceShouldEmitRemoveAndBeIdempotent` 红）。
> **未做**：B 方案（`forget` 默认实现改抛异常）未做——方案 A 已消除残留，B 属风格收紧，收益不足；真 socket e2e 仍未做（但本条的**机制**已由源码判定 + 框架契约测试双重固定）。


**缺陷**：断链停采路径 `fence()` 不清 `SubscriptionPlanner` 的订阅跟踪，与「无设备下架」路径不对称；接口 `forget` 的默认实现是空方法且无门禁。

**一手证据**
- `IotProtocolTenantLinkManager.java:463-477`：`fence()` 只做 `collecting/emptyBackoff/collected` 清理 + 逐台 `rebindBackoff.remove` + `emit(REMOVE)`，**无** `planner.forget(...)`。
- 对照 `:374-387`（`removeAllDevices`，`:378` 有 `planner.forget`）、`:326-328`（单设备下架同样有）。
- `SubscriptionPlanner.java:60-62`：`default void forget(String deviceId) { /* 默认不做事 */ }`（注释自己写明「真正跟踪的实现必须覆写」）。
- `AccessSubscriptionPlanner.java:225-235`：覆写实现清 `failureBackoff` / `inFlight` / `subscribedSessions`。
- **测试缺口**：`IotProtocolTenantLinkManagerTest.java` 的 fence 用例（`:379-391`、`:397-410`、`:412-427`、`:431-438`）**一条都没有** `planner.forgotten` 断言；`forgotten` 只出现在对账路径（`:190`、`:205`、`:353`）。

**现状判定（本次把「需 e2e 核实」推进为源码判定）**：框架 0.2.0 下**不会漏订阅**——
- `IotLifecycle.onDeviceChange`（`:337-363`）非 REMOVE 分支 → `bind()`；
- `bind()`（`:216-249`）**无条件**先 `detach(deviceId)`，再 `adapter.bind(...)` 得到新会话并 `sessions.put(...)`；
- `detach()`（`:307-327`）`sessions.remove` + `session.close()`（旧会话关闭）；
- `TcpAdapter.bind`（`:150-156`）`new TcpSession(...)` ⇒ **每次都是新实例**；
- 而订阅跳过判据是会话**实例同一性**：`AccessSubscriptionPlanner.java:134` `if (subscribedSessions.get(deviceId) == session) continue;` ⇒ 新实例必然不相等 ⇒ 重新订阅。
- 版本对齐：`ypbin-service/ypbin-access/pom.xml:21` = `0.2.0`；本地框架仓 `v0.2.0`（`5f34071`）与 `master` 差异仅 pom 版本行；审计方另从 `~/.m2/.../ypbin-iot-spring-boot-starter-0.2.0-sources.jar` 独立解包复核，结论一致。

**因此真实残余**（比原表述小、但确实存在）：
1. 被 fence 设备的 `subscribedSessions` / 退避条目**不会被清理**（下次成功订阅才覆盖；设备不再回来则长期残留）——正是接口注释所说的「只增不减的引用」。
2. 该安全性**依赖框架一条隐式契约**（「ADD ⇒ 先 detach 关旧会话 ⇒ 新会话实例」），本仓**没有任何测试**钉住它（`grep -rln IotLifecycle` 在测试目录 0 命中）⇒ 框架一旦改为复用实例，R8-5 与 R8-6 **同时**退化为静默零数据。

**方案选项**
- A. `fence()` 补 `planner.forget(deviceId)`（与 `removeAllDevices` 对称）+ 补 fence 用例断言（1 行级改动 + 用例）。
- B. A + `forget` 默认实现加门禁（默认抛异常，仅日志桩/测试替身显式覆写为空）。
- C. B + **反哺框架契约测试**（`ypbin-iot-starter`）：断言「ADD 后会话实例与旧的不同、旧会话已关闭」。

**验收标准**
- A：用例造「fence 后再 ADD」⇒ 断言 `subscribedSessions` 无残留、重新发起订阅（复用 `RecordingPlanner.forgotten` 范式）；变异：把 `planner.forget` 去掉 ⇒ **fence 用例转红**（当前不会红，正是缺口）。
- B：断言默认实现不再被「碰巧」用到。
- C：框架仓新增契约测试通过（本次**不实施**，登记为反哺项）。
- 真 socket e2e 仍是金标准（设备变更 → 真实接入侧重建会话并出数），本次未做。

**依赖 / 工作量**：A 0.5 天；C 跨仓 0.5 天。
**风险 / 回滚**：A 风险低；B 改默认实现前须先 `grep` 全部实现类。

---

### 2.4 R8-6 规格变化路径只重发 ADD（P2）

> **✅ 方案 A 已落地（2026-10-09）**：框架仓 PR #21 的 LIFE-13 契约测试 + `CONTRACT.md` §四 第 7 条，把「每次 ADD 都换新 `DeviceSession` 实例、且旧会话先被关闭」从**实现细节**提升为**有测试支撑的行为承诺**，并写明宿主（本仓 `AccessSubscriptionPlanner` 以实例同一性决定是否重新订阅）**依赖**它。
> 由此：本仓「规格变化只重发 ADD」不再依赖一条无人守护的隐式假设；若将来框架改为复用实例，框架仓的契约测试会先红。
> **未做（本仓侧加固，非必需）**：B 方案（本仓主动发 REMOVE+ADD）与 C 方案（本仓按「点位指纹」判据重订阅）——两者都是在契约失效时的**冗余防御**；当前契约已显式且有测试，按变更最小化不默认实施。


**缺陷**：点位/规格变化时不发 REMOVE、只重发 ADD，机制上依赖「框架重新绑定时产生新会话实例，从而重新订阅」。

**一手证据**
- `IotProtocolTenantLinkManager.java:345-353`：`previous != null && !previous.equals(...)` 分支只 `emit(ChangeType.ADD, ...)`（注释写明「框架会先解绑再绑定」）；全仓 `ChangeType.REMOVE` 只出现在 `:326`（单设备下架）、`:376`（全下架）、`:472`（fence）。
- 框架侧与 R8-5 §2.3 **同一组证据**（`IotLifecycle:216-218,249,307-327`、`TcpAdapter:153`、`AccessSubscriptionPlanner:134`）⇒ **「新点位永不订阅」在 0.2.0 下不成立**。
- `emit` 是同步投递（`AccessDeviceRegistry.java:106-116` 直接 `listener.accept(change)`）⇒ 重新订阅执行时新会话已在 `sessions` 中。
- **依赖被写成注释里的假设**：`IotProtocolTenantLinkManagerTest.java:188` 的断言说明写着「框架按『先解绑再绑定』应用，新会话会触发重订阅」，但测试用的是假框架 + `RecordingPlanner`（`:495-513`），**未建模会话实例**。

**触发前提 / 残余风险**：仅当框架改为复用会话实例（或宿主自定义 `SubscriptionPlanner` 用 `deviceId` 而非实例判等）时成立；届时新点位**永不订阅**且只有 DEBUG 日志。

**方案选项**
- A. （推荐，最小）**补框架契约测试**把「ADD ⇒ 新会话实例」钉死；本仓不加运行时改动。
- B. 本仓主动发 REMOVE+ADD（不依赖框架行为）；代价：多一次解绑-绑定往返与瞬时空窗。
- C. 本仓 `AccessSubscriptionPlanner` 增加「点位指纹」判据（地址集合哈希变了就重订阅），彻底不依赖会话实例。

**验收标准**
- A：框架仓契约测试（同 R8-5 C）。
- B：用例断言规格变化路径 REMOVE→ADD 顺序 + `subscribedSessions` 被清。
- C：用例断言「同一会话实例 + 地址集合变化」仍会重新订阅（**当前实现会跳过**，这正是缺口）；变异：去掉指纹判据 ⇒ 转红。
- 三者都需在 `IOT-ROADMAP.md` R8-6 行登记**所依赖的框架契约版本**（当前 = `ypbin-iot 0.2.0`）。

**依赖 / 工作量**：A 0.5 天（跨仓）；B 1 天；C 1–1.5 天。

---

### 2.5 R8-7 平台级不变量的门禁覆盖缺口（P1，安全类）

**原缺陷表述**：`platform_only=1` 与「不进 `sys_template_menu`」只靠人眼。

> **✅ 已实施（2026-10-09）**：验收标准里的方案 A 已落地（仅动门禁测试，不动脚本/代码）：
> ① 扫描面 = 安装脚本 + 全部 `deploy/sql/migration/*.sql`（读不到迁移目录直接炸，不静默退化；菜单属性另读 `002-data.sql` 以解析守卫子项）；
> ② 新增 `PLATFORM_LEVEL_MENUS` **显式清单**（`3206` / `320014` / `320015` / `3320`）：必须 `platform_only=1`、不得被任何脚本的**可达**授权命中，清单陈旧或条目被降级都转红；
> ③ 新增「SELECT 形态补授」的**可达性判据**：子项全为平台级 ⇒ 恒命中 0 行（潜在授权，今天不违规）；任一子项非平台级 ⇒ 守卫可达 ⇒ 目标平台级菜单会被授进租户模板 ⇒ 违规；
> ④ 解析器看不见的目标形态必须显式报出（`UNRESOLVED_GRANT_EXEMPTIONS`，键为完整语句；豁免项在脚本里已不存在也转红）；⑤ 同一 menu id 跨脚本 `platform_only` 取值不一致即红。
> **变异验证 4/4 转红**（每处先确认落地、后还原）：M1 把 `3320` 降级为 `platform_only=0`；M2 把守卫子项 `3004` 降级 ⇒ 3320 守卫变可达；M3 在 **migration** 里把 `3320` 加入 `id IN` 清单；M4 把守卫改成动态目标（解析器看不见）。当前 main 上门禁 **5/0/0/0**，整模块 **878/0/0/0**（基线 877 + 新增 1 条用例）。
> 未做（本条剩余面）：B 方案（等价的 shell 门禁）——Java 门禁已覆盖同等判据，跨语言重复建设收益存疑，故不默认实施。

**现状判定：部分存在，且原表述已过期**——**门禁自 PR #48（2026-09-26, `17df4356`）起就已存在**：

- 反向门禁：`IotMaintenanceAdminGateTest.java:203-234`，`:204` 显示名「★ 反向门禁：`platform_only=1` 的平台级菜单**绝不得**进 `sys_template_menu`」；`:225-227` 对 `id` 为 `32xx/33xx` 且 `platform_only=1` 却出现在模板授权集合中的条目判违规。
- 正向门禁：同文件 `:119-160`（每条菜单必须在授权表里被授权，`platform_only` 决定是否必须进模板）、`:163-200`（K2 防孤儿补授）。
- 因此 `IOT-ROADMAP.md:414`「只靠人眼 / 无门禁」**已过期**（该行 `git blame` 停在 2026-09-22，门禁 4 天后才出现，未回写）。

**真实残留（覆盖面缺口，可复现）**
1. **只扫 `007-iot-data.sql`**，不扫 `deploy/sql/migration/*.sql`（而 migration 里有 14 处 `sys_template_menu` 授权）。
2. **只认 `32xx/33xx`** 前缀（`:141`）。
3. **只认 `INSERT … id IN (...)` 形态**：同脚本里已有一条 **SELECT 形态**、目标为 `platform_only=1` 菜单的模板授权，门禁解析不到：
   - `deploy/sql/007-iot-data.sql:226-228`（= `deploy/sql/migration/2026-09-26-iot-platform-module-menu.sql:52-54`）：
     `INSERT IGNORE INTO sys_template_menu (template_id, menu_id) SELECT DISTINCT tm.template_id, 3320 FROM sys_template_menu tm WHERE tm.menu_id IN (3004, 3009) AND tm.template_id <> 1;`
   - 3320 的 `platform_only=1`：`deploy/sql/007-iot-data.sql:193`（`VALUES (3320, 0, 'PlatformOps', 'catalog', 1, …)`）+ `:184` 注释「3320 运维与监控 = 1」。
   - 当前该语句**命中 0 行** ⇒ 不变量今天成立，但成立原因是「3004/3009 恰好也是 `platform_only=1`」（`:226-228` 的注释自己承认这是把 latent gap 变结构性保障的补授），**不是门禁兜住的**。

**后果**：后续用 SELECT 形态、或写在 migration 脚本里、或用非 `32xx/33xx` 前缀的平台级菜单补授，**现有门禁不会红**，跨租户越权面可能被授给租户管理员。

**方案选项**
- A. 扩展 `IotMaintenanceAdminGateTest`：① 也扫 `deploy/sql/migration/*.sql`；② 识别 `INSERT … SELECT` 形态（至少能把「常量目标菜单 id」的 SELECT 形态纳入检查）；③ 平台级菜单集合改为**显式清单**（不再只靠前缀）。
- B. A + shell 门禁（与 `tools/check-iot-sql-equivalence.sh` 同风格），供非 Java 路径复核。
- C. 若某条语句「有意不参与门禁」，必须加**显式豁免登记**（id + 理由 + 复核人），而不是靠解析器看不见。

**验收标准**
- 门禁在**当前 main** 上通过（先证明不误报），再做变异验证：① 把某平台级菜单改成 `platform_only=0`；② 把某平台级菜单 id 插进 `sys_template_menu`；③ **用 `INSERT … SELECT` 形态**做同样的事 ⇒ 三者**都必须转红**（第 ③ 条是本次新增能力的证明）。
- 平台级清单必须**非空断言**（防空跑假绿）；清单来源写入文档并与 `permission-rollout.md` 口径一致。
- 必须**真跑 JUnit**（本次仅用等价 Python 复刻验证逻辑，未跑 Maven）。

**依赖 / 工作量**：无前置；约 0.5–1 天（含变异验证）。
**风险 / 回滚**：属**扩展既有门禁**（按仓库纪律门禁类改动单独提案）；回滚 = 还原该用例。**本次只立项，未改门禁。**

---

### 2.6 R8-8 版本号 bump 与业务写入同一事务，无自动守卫（P1）

**缺陷**：①「设备/点位变更与 `config_epoch` 推进在**同一事务**」无自动守卫（单测只证明「被调用 3 次」）；② 台账写失败会**回滚业务写入**（可用性耦合）；③ `bumpConfigEpochOfCurrentTenant()` → `bumpConfigEpoch()` 是**自调用**，使后者 `@Transactional` 代理失效。

**一手证据**
- `IotDeviceServiceImpl.java:66-71`（`@Transactional` + `save` + `notifyConfigChanged()`）、`:99-102`（`notifyConfigChanged()` → `bumpConfigEpochOfCurrentTenant()`）；`IotPointMappingServiceImpl.java:110-125`、`DeviceImportServiceImpl.java:151,203` 同构。
- `TenantLedgerService.java:148-161`：`bumpConfigEpoch` 标 `@Transactional`，但 `:153 ledgerMapper.bumpConfigEpoch(tenantId)` **无 try/catch** ⇒ `DataAccessException` 原样上抛 → 外层事务回滚业务写入。Javadoc 只承诺「台账**无该租户**时不阻断」，**未**处理「台账**写失败**」。
- `TenantLedgerMapper.java:66-68`：单条 `@Update` UPDATE。
- **自调用**：`TenantLedgerService.java:171-173` `bumpConfigEpochOfCurrentTenant()` 直接调 `bumpConfigEpoch(...)`（同一 Bean，不走代理）⇒ 一旦将来从**非事务**上下文调用它，`:148` 的 `@Transactional` 不生效，会出现「业务写成功 + 版本号在自动提交下单独执行」的隐蔽分裂。
- **守卫缺失**：`IotDeviceServiceImplTest.java:126,135` 与 `IotPointMappingServiceImplTest.java:204` 只有 `verify(...).times(3)`；`TenantLedgerIT.java` 的用例（`:109`、`:132`、`:145`、`:161`、`:175`）**没有**「bump 失败 ⇒ 业务写入回滚」或「同一事务」用例。

**方案选项（先定取舍，再落守卫）**
- A. **一致性优先**（保持当前行为）：补**真库 IT** 钉住「bump 失败 ⇒ 业务写入回滚」，并把可用性代价写进运维文档。
- B. **可用性优先**：`bumpConfigEpoch` 失败不回滚业务写入（捕获 + `log.error` 带堆栈 + 计数指标 + 告警），代价 = 版本号未推进 ⇒ 接入侧靠安全网收敛。**注意「禁静默降级」：必须计数且可告警。**
- C. **事务边界门禁**：源码级断言「所有调用 `bumpConfigEpochOfCurrentTenant()` 的写入口方法都带 `@Transactional`」（掩盖自调用风险）。
- D. 修自调用：`bumpConfigEpochOfCurrentTenant()` 自身标注事务语义（或改为显式代理调用），使语义不依赖调用方。

**验收标准**
- 真库 IT（`-Pit` 档）：A 断言回滚；B 断言业务写入提交 + 指标/日志出现（`LogCaptor` 或指标读数）。
- C：门禁在 main 上通过 + 变异（去掉 `@Transactional` ⇒ 转红）。
- D：用例证明「从非事务上下文调用时，版本号推进与业务写在**同一**事务内」（这是 D 的核心断言）。
- 任一方案都必须先回答并写入 `PROD-OPS-NOTES.md`：**台账故障时宁可少采（不一致）还是宁可写不进去（不可用）**。

**依赖 / 工作量**：A 0.5 天；B 1 天（含指标登记，依赖 R8-9）；C 0.5 天；D 0.5 天。
**风险 / 回滚**：B 改变失败语义 ⇒ 需用户/运维确认；回滚 = 还原为 A。

---

### 2.7 R8-9 安全网成本与可观测性（P1；**部分已闭合**）

> **✅ 可观测性已实施（2026-10-09，方案 A+B）**：
> ① **登记（A）**：新增 `docs/METRICS.md`，把 `ypbin-access` 的 **33 条**指标逐条登记（口径「何时变化」/建议关注或阈值/注册文件），并说明暴露面（18086 只绑回环、`exposure.include` 只有 health,metrics,info）与读法；iot 服务的指标指向既有文档，避免两处漂移。
> ② **耗时（B，R8-2 的前置）**：新增 3 条 `Timer` —— `iot.access.lease.tick.duration`（整轮 tick：自检+续约+重领+对账）、`iot.access.config.reconcile.duration`（对账阶段：tick 里唯一串行打远端的部分）、`iot.access.spec.load.duration`（**单租户**规格取数 ⇒ 把 R8-2 里「一次调用最坏 6s」的算术假设变成实测分布）。计时用 `System.nanoTime`（单调时钟），与业务判定的可注入 `Clock` 解耦；空持有集合早退**不产生样本**（避免把 0 次远端调用混进统计）。
> ③ **门禁（双向，防文档烂掉）**：`MetricsRegistryGateTest` —— 清单→代码（每个登记名必须能在其注册文件里按「全名或后缀片段」找到，覆盖 `METRIC_PREFIX + "suffix"` 写法）与代码→清单（源码里的完整 `iot.access.*` 字面量必须都登记，新指标不许不登记），两侧均带「非空」自检。
> **验证**：access 模块 **119/0/0/0**（含新增门禁 2 条用例）；**变异 3/3 转红**：M1 清单里把 `tick.duration` 写成 `tick.durations`（清单→代码红）、M2 源码里把 `spec.empty` 改名（代码→清单红）、M3 删掉 tick 的 `record`（计时器断言红）。
> **未做（本条剩余面）**：**未接 Prometheus/Grafana**（本仓无采集端）——若要真正"上大盘"，需先决定是否引入采集组件，属新增基础设施，须单独提案；**安全网成本**（每租户每周期一次全量对账）仍属 R8-2 的选项 C/D/E。


**原缺陷分两半**：① 安全网成本；② 新指标未登记 + 「iot 服务没有指标暴露配置」。

**一手证据（逐半核实）**
- **②-暴露配置：已闭合（原表述已过时）**：`deploy/nacos/ypbin-iot.yaml:264-275` `management.endpoints.web.exposure.include: health,metrics,info`（#53 / 2026-09-26，且 `:248-262` 写明安全边界：最小集合、绝不用 `*`、18084 只绑回环）；`deploy/nacos/ypbin-access.yaml:87-105` 同款（#75 / 2026-09-27）。**本次复核确认 HEAD 上该段仍在**（`ypbin-iot.yaml` 在此之后的 6 次改动未移除它）。
- **②-指标登记：仍存在**：`grep -rn 'iot.access.config.reconcile' docs/ deploy/` 只命中 `docs/IOT-ROADMAP.md`；注册代码在 `ConfigEpochReconciler.java:110-117`（`changed` / `check.failure` / `reconcile.not_applied` / `reconcile.forced`）。全仓**无 Prometheus 抓取配置、无 Grafana 大盘**（`deploy/docker-compose.yml` 里唯一命中是 `:415` 的一句注释）。
- **网关侧**：`deploy/nacos/ypbin-gateway.yaml:117-135` 的 `exclude-paths` **不含** `/iot/actuator/**`；`/actuator/**` 白名单只在 `ypbin-system.yaml:94`。`ypbin-iot.yaml:259` 注释明示这是**刻意设计**（必须带登录态穿过网关；直连 18084 才绕过）——**不是缺陷**。
- **①-成本：仍存在**（`ConfigEpochReconciler.java:190,216`；`AccessProperties.java:68` 未变）。

**方案选项**
- A. **登记指标**（成本最低）：把 `iot.access.config.reconcile.*` 等与既有 `iot.access.*` 一起写入指标清单（建议 `docs/PROD-OPS-NOTES.md` 新增 §或独立 `docs/METRICS.md`）：每条给 ①名称 ②口径（何时 +1）③建议阈值/关注点 ④注册位置（`文件:行号`）。
- B. **加 tick/对账耗时指标**（R8-2 的前置可观测性）。
- C. **降低成本**：按 R8-2 C/D/E 收敛触发条件、调大间隔或拆线程池。
- D. （可选）**接 Prometheus/Grafana**：本仓当前没有采集端；若要真正"上大盘"，需先决定是否引入采集组件（属新增基础设施，须单独提案）。

**验收标准**
- A：清单每条指标齐备四要素；并加门禁断言「清单里的指标名在代码中确实注册」（防清单漂移）；清单不得含实例 IP/口令等敏感信息。
- B：`/actuator/metrics/<name>` 可读到数值，且**不**扩大 exposure 集合。
- C：与 R8-2 同一组用例。
- D：若做，须给出采集组件选型、资源开销、凭据与网络暴露面评估（不得扩大 `exposure.include`）。

**依赖 / 工作量**：A 0.5 天；B 0.5 天；C 见 R8-2；D 未评估。
**风险 / 回滚**：A/B 纯增量；回滚 = 删除登记条/指标。

---

## 3. 统一验收范式（怎么证明一条技术债真的修好了）

1. **真库/真链路优先**：能用 `-Pit` 真库 IT（`TenantLedgerIT`、`LeaseConcurrencyIT`、`LeaseDbClockIT` 先例）或真 socket e2e 的，**不得**只用 mock 计数证明。
2. **变异验证**：每个新断言都要演示「破坏被保护的行为 ⇒ 用例转红」（本仓既有惯例）。
3. **门禁清单式**：清单类门禁必须断言「清单非空」（既有教训：`IotTenantIsolationGateTest` 的 `containsAll` 漏登记隐形）。
4. **解析器能力边界要显式**：门禁的「看不见」必须登记（本次 R8-7 的 SELECT 形态就是活例）。
5. **跨仓契约显式化**：依赖框架行为的结论必须写明**依赖版本**并推动在框架仓落契约测试。
6. **不夸大强度**：代码判定 ≠ 端到端验证；e2e 未做就写「未做」；**未跑的 JUnit 不得声称已通过**。

---

## 4. 扩展前的建议顺序（仅建议，排期由用户决定）

| 顺序 | 条目 | 理由 |
|---|---|---|
| 1 | **R8-4**（节点过滤） | ✅ **已完成（2026-10-09）**：多节点前的硬前置（见 §2.2）。 |
| 2 | **R8-9 A/B**（指标登记 + tick 耗时） | ✅ **已完成（2026-10-09）**：`docs/METRICS.md` 33 条 + 3 条耗时计时器 + 双向门禁（见 §2.7）。 |
| 3 | **R8-5 + R8-6**（对称清理 + 框架契约测试） | ✅ **已完成（2026-10-09）**：本仓 `fence()` 对称清理 + 框架仓契约测试 LIFE-13（PR #21）；本仓点位指纹/主动 REMOVE 两个冗余防御未做（见 §2.3/§2.4）。 |
| 4 | **R8-2**（时间预算/并发/安全网收敛/拆线程池） | 依赖第 2 步；阈值最低（≥4 租户）。 |
| 5 | **R8-8**（事务取舍 + 守卫 + 修自调用） | 需先拍板「一致性 vs 可用性」。 |
| 6 | **R8-7**（门禁覆盖缺口） | ✅ **已完成（2026-10-09）**：只补覆盖面，零运行时代价（见 §2.5）。 |

> **最小提前集**：**R8-7、R8-4、R8-9 A/B、R8-5+R8-6 已于 2026-10-09 完成**（无需再排）。剩下的 R8-2 现已具备三条耗时指标作判据，R8-8 需先拍板「一致性 vs 可用性」。

---

## 5. 未核实项（不得当既定事实）

| # | 未核实项 | 为什么核不到 | 建议核实途径 |
|---|---|---|---|
| 1 | **运行时实际装载的框架版本**是 `0.2.0` 还是 `~/.m2` 里同时存在的 `0.2.0-SNAPSHOT` | pom 钉的是 `0.2.0`，但 `~/.m2` 下两者都在；本次未取容器内证据 | 在 access 容器内核 jar 版本/md5（如 `unzip -l` 看 `ypbin-iot-spring-boot-starter-*`） |
| 2 | 框架「ADD ⇒ 新会话实例」在 **Modbus/MQTT/OPC UA** 适配器下是否同样成立 | 本次只读了 `TcpAdapter`；`TcpAdapter.java:145-147` 注释明确「1:N 协议（Modbus 网关）**必须自行管理设备视图并覆写本方法**」 | 逐适配器核实 + 框架仓契约测试 |
| 3 | 真 socket 端到端（设备变更 → 接入侧重建会话并出数） | 需能稳定跑容器的机器 | 有环境时做一次 e2e |
| 4 | R8-2 的越界算式（`IOT-ROADMAP.md:409` 的 36s 推导） | 本次只核了常量与机制，未复核其相加口径 | 用 R8-9 B 的耗时指标实测后重建模型 |
| 5 | ~~R8-7 门禁的**实际通过状态**~~ **已核实（2026-10-09）** | 已真跑：`IotMaintenanceAdminGateTest` **5/0/0/0**、iot 模块 **878/0/0/0**（`mvn -o -pl ypbin-service/ypbin-iot test`）；`IotPermissionCodeGateTest` 由整模块全量覆盖 | — |
| 6 | 生产活库 `sys_template_menu` 是否真的没有 `platform_only=1` 行 | 未连库 | `ACCESS-ENABLE.md` 口径下的只读查询 |
| 7 | 生产当前持有租户数与 access 节点数 | 未连生产 | 同上 |
| 8 | `deploy/nacos/ypbin-access.yaml` management 段的逐字内容 | 只确认「有同类配置」 | 逐行读该段 |

---

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-09 | 建立本文件：R8-2/R8-4~R8-9 **逐条立项**（一手证据 + 触发前提 + 方案 + 验收标准 + 排期建议）。**只立项，未实施、未改代码、未新建门禁。** |
| 2026-10-09（同日更正） | 经**独立审计**（外委子代理，只读、不同上下文）后更正 4 处：① **R8-7 判定改「部分存在」**——反向+正向门禁自 PR #48（2026-09-26）已存在，真实缺口是**覆盖面**（不扫 migration / 只认 32xx,33xx / 只认 `INSERT … id IN`，而 `007-iot-data.sql:226-228` 的 `INSERT … SELECT …, 3320` 形态在门禁视野外）；② R8-9 的「无指标暴露配置」更正为已闭合（#53/#75）；③ R8-2 补「access 只有 1 个调度线程、与 1s egress 上报互相排队」与 tick 内顺序证据；④ R8-8 补「`bumpConfigEpochOfCurrentTenant()` 自调用使 `bumpConfigEpoch` 的 `@Transactional` 失效」。上述更正均经本文件作者**逐条独立复核**（门禁显示名、SELECT 语句原文、3320 的 `platform_only`、调度配置缺失、fence 用例零断言、`TenantLedgerIT` 无用例）后才写入。 |
| 2026-10-09（R8-7 实施） | **R8-7 门禁覆盖面已实施**（仅动门禁测试）：扫描面扩到 `deploy/sql/migration/*.sql`（+ `002-data.sql` 供解析守卫子项）；新增 `PLATFORM_LEVEL_MENUS` 显式清单与「SELECT 形态补授」的**可达性判据**；解析器看不见的目标形态改为显式报出（带豁免登记与陈旧豁免检查）。**变异 4/4 转红**（降级平台级菜单 / 降级守卫子项 / migration 注入 / 动态目标）；当前 main 门禁 5/0/0/0、iot 模块 878/0/0/0。 |
| 2026-10-09（R8-4 实施） | **R8-4 按节点过滤已实施**：`/epochs` 契约改为 `batchEpoch(accessNode)`（节点必填，服务端/客户端共用 `LeaseEpochRules#validateAccessNode`）；过滤下沉 SQL（走 `access_node` 索引）+ `ORDER BY tenant_id` + 手写两条 `is_deleted=0`。**分页方案已放弃**——初版实现被 CI 架构门禁判为「循环内 RPC（N+1）」且判定成立（分页会把一次批量调用变成每页一次串行 RPC，反而增加 tick 开销），改为只做节点过滤。**真库 IT 4/0/0/0**、iot **879/0/0/0**、access **117/0/0/0**、**变异 4/4 转红**；`docs/LEASE.md` §一 契约同步。 |
| 2026-10-09（R8-4 复核整改） | **外委独立复核（L2）无阻断项**，非阻断建议已修 5 处：缺参/空白参错误码改 400（新增 HTTP 层用例 `InternalLeaseControllerTest` 4 条）、客户端构造期 fail-fast、去掉读侧 trim（对称性）、清死导入、IT 区间迁到 929xxx 并改双重 purge（消除复用容器下的顺序依赖）。复核独立复现：架构门禁在旧实现上红、在收敛后绿。 |
| 2026-10-09（R8-9 实施） | **R8-9 可观测性（A+B）已实施**：新增 `docs/METRICS.md`（access 全家 33 条指标逐条登记：口径/建议关注/注册文件）+ 3 条耗时计时器（`iot.access.lease.tick.duration` / `iot.access.config.reconcile.duration` / `iot.access.spec.load.duration`）+ 双向门禁 `MetricsRegistryGateTest`（清单↔代码，两侧非空自检）。**验证**：access 119/0/0/0、**变异 3/3 转红**。未接 Prometheus/Grafana（无采集端，属单独提案）。 |
| 2026-10-09（R8-5/R8-6 实施） | **R8-5 对称清理 + R8-6 的框架契约测试已落地**：本仓 `fence()` 补 `planner.forget`（与下架路径对称，access 119/0/0/0，变异 1/1 转红）；框架仓 `ypbin-iot-starter` PR #21 新增 LIFE-13 契约测试（ADD ⇒ 新会话实例 + 旧会话关闭，两条路径）并写入 `CONTRACT.md` §四 行为承诺（框架侧 148/0/0/0，变异 2/2 转红）。本仓 B/C 冗余防御未做（理由见 §2.3/§2.4）。 |
