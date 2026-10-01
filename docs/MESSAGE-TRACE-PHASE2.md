# 消息跟踪二批立项设计：上行结构化补齐（看板 #8）

> **性质**：立项设计稿（第 2 稿，已按 L2 独立复核整改），未实现、无代码改动。实施需另开 PR 并按 §8 生产纪律执行。
> **依据**：`docs/MESSAGE-TRACE-DESIGN.md` §3.4/§8（一批已上线：后端只读聚合 + 前端页签）。
> **结论先行**：给 `iot_mqtt_ingest_receipt` 加 6 列（行性质/计数/落库时刻/丢弃原因），让"被拒整批"与"部分丢弃明细"从零痕迹变成可呈现；
> 受理路径**零新增语句**（采集生命线不受影响）；被拒行**不阻塞重试**（预查只认 accepted）；
> 实时跟踪（SSE/长连接）**本期不做**（维持原设计"可选项、非默认"）。
> dev 现状（2026-10-01 作者 dev 一手实测：直查 `iot_mqtt_ingest_receipt` COUNT + `/actuator/metrics` 计数器）：
> receipt 18 行、入站/丢弃计数自重启后全 0 ⇒ "断点不可见"的痛**尚未在 dev 观测到**，
> 实施时机见 §1（条件触发，非立刻开工）。

---

## 0. 新会话第一句话（可直接粘）

> 继续 #8 二批：读 `docs/MESSAGE-TRACE-PHASE2.md`（本文件）+ `docs/MESSAGE-TRACE-DESIGN.md` §3.4/§8；
> 本文件是设计稿，实施前确认 §1 的触发条件已满足。

---

## 1. 触发条件（先判定，再开工）

| 条件 | 现状（2026-10-01 dev 一手） | 结论 |
|---|---|---|
| 一批已上线可用 | ✅ 后端 `/devices/{id}/messages` + 前端页签（#8 一批已合并部署） | 满足 |
| 上行"断点不可见"造成实际排障痛 | ❌ 未观测到：receipt 18 行全受理；`iot.mqtt.ingest.rejected`/`propertyid.unmapped/orphan` 自重启后全 0（计数随重启清零，窗口短，证据弱） | **不满足 ⇒ 本期只立项、不实施** |
| 产品侧要求"定位建议"覆盖丢弃场景 | 待定（需拍板，见 §9 Q1） | — |

> **实施纪律**：触发条件 2/3 任一满足，另开实施 PR（§8 纪律：备份、可回滚、验收窗口冻结其它容器动作 + 外委独立复核）。
> 在此之前，排障口径维持一期现状（页签内注明"整批被拒不留痕，查日志与 `propertyid.unmapped/orphan` 指标"）。

---

## 2. 加列方案（6 列，全部 NULL-able，存量行无需回填）

> ⚠️ **列名避让（L2 复核 A2）**：表已有 `status TINYINT`（启用/停用，`insertReceipt` 硬编码 1），
> 本期行性质用 **`receipt_status`**，绝不碰既有 `status` 列语义。

| 列 | 类型 | 语义 |
|---|---|---|
| `receipt_status` | VARCHAR(16) NOT NULL DEFAULT `'accepted'` | 行性质：`accepted`（受理）/`rejected`（整批被拒）。存量行默认 accepted（与当前语义一致）。 |
| `received_count` | INT NULL | 本批收到的读数条数（含被丢弃的）。accepted 行 = 旧 `item_count` 语义的上界；rejected 行 = 被拒总数。 |
| `persisted_rows` | INT NULL | 实际落库条数（= 旧 `item_count`）。rejected 行为 0。 |
| `persisted_at` | DATETIME NULL | 落库完成时刻（accepted 行 ≈ 当前 `create_time`；显式列让语义可查询，不依赖审计列）。 |
| `discard_reason` | VARCHAR(32) NULL | 丢弃原因码，**直接复用既有 `MqttIngestRejectReason` 枚举**（14 码：INVALID_JSON/BODY_TOO_LARGE/…/DEVICE_NOT_FOUND/NO_ACCEPTED_ITEM，不新造分类）。accepted 且无部分丢弃时为 NULL；部分丢弃时为触发码。 |
| `discard_detail` | VARCHAR(512) NULL | 丢弃明细：未映射 pointId **前 8 个逗号连接**，超出记 `",...(+N)"` 后缀（如 `"p1,p2,...(+5)"`，N=剩余个数）。超长截断并标记，防 TEXT 膨胀。 |

> **为什么不用独立 trace 表**：rejected 行与 accepted 行是同一 requestId 的两种结局，
> 同表 + `receipt_status` 区分使"重试→受理"的结局流转是一行变迁（见 §3），跨表则需分布式事务或对账。
> ⚠️ **已知缺点（L2 复核 D1，不回避）**：rejected 行在重试受理时被**删除** ⇒ DB 层面的拒绝历史断层
> （仅剩应用日志 + `iot.mqtt.ingest.rejected{reason}` 指标计数）。同键的拒绝历史不可保留——这是"不阻塞重试"
> 的代价；若将来需要拒绝审计，需另建 append-only 表（不在本期范围）。
>
> **为什么 `discard_detail` 只 512**：它是排障线索不是审计证据（审计证据是应用日志）；
> 全量明细（如整批 pointId）随批大小线性膨胀，512 截断 + 指标计数已足够定位到"映射问题"。

---

## 3. 关键设计（4 条，每条都是否决项）

### 3.1 被拒行绝不阻塞重试（最高优先级）

现状 `MqttReadingIngestServiceImpl.apply` 的幂等预查命中**任何**同 (device, request) 行即整批跳过。
若被拒行也落库且预查不变 ⇒ 修好映射后设备用**同一个 requestId 重投仍被拒绝**（把"可恢复"变成"永久拒绝"）✗。

设计（必须同时落地，缺一不可）：

1. 预查只认 `receipt_status = 'accepted'` 的行（`selectByRequestId` 加状态条件）；
2. 命中 `rejected` 行 ⇒ **删除该行后按新批次继续**（单条 DELETE，罕见路径；不用 UPDATE 复用——语义是"新的一次受理"，不是"复活旧行"）；
3. 唯一键 `(tenant_id, device_id, request_id)` 不变（DB 层兜底并发）。

> ⚠️ **并发诚实声明（L2 复核 B1）**：删除与重建之间仍有窗口——双方可同时通过预查、同时调 ingest、
> 同时 insert，回执唯一键只保“回执行唯一”，**不保落库不重复**（仓内既有明文：`EMQX-INTEGRATION.md:34`
> 并发重投仍可能双调 ingest、时序可能双写）。本设计保证的是“回执不永久拒绝重试 + 不放大重试”，
> **不断言 at-most-once 落库**。B-4 判据已按此口径重写。

### 3.2 原因码：直接复用 `MqttIngestRejectReason`（14 码，不新造）

| 码 | 触发（服务端已知） | 前端建议要点 |
|---|---|---|
| `NO_ACCEPTED_ITEM` | 整批未通过点位映射（现状 `reject` 分支） | 修点位映射后用**同一 requestId 重投**（幂等键不变仍可受理） |
| （其余 12 码） | 既有枚举全量（上表两码之外，见 `MqttIngestRejectReason.java`） | 按码面文案执行，不另造建议分类 |
| 触发码 + 明细 | 部分丢弃（`dropped > 0`，accepted 行附带；如某条目 `PROPERTY_ID_INVALID` 则整批记该码） | 看 `discard_detail` 的 pointId 清单 + `propertyid.unmapped/orphan` 指标 |
| `DEVICE_NOT_FOUND` | 设备不存在（`tenantId == null` 即此码） | 核对 topic 设备段/设备是否已注册 |

> **更正（L2 复核 A3）**：初稿称"`tenantId == null` 分支连 reject 都不调（直接 return null）"**是错的**。
> 一手代码是 `reject(DEVICE_NOT_FOUND, …)` **抛异常**（`MqttReadingIngestServiceImpl.java:162-165`）。
> 因此该分支天然有原因码，落 rejected 行无码值障碍；遗留问题是 tenant 未知时行写在哪一租户下——
> 倾向：`tenant_id` 记设备解析租户（未知则记 0 哑租户，仅运维跨租户可见），由实施 PR 定（§9 Q2）。

### 3.3 采集性能（生命线，逐条可验收）

| 路径 | 新增代价 | 判据 |
|---|---|---|
| accepted（高频） | **0 新增语句**（同一次 insert 多 6 列；预查多一个状态条件：唯一键前缀定位 + 非键列过滤，见 L2 复核 C2） | dev 实测不劣化（方法见 §7 B-5） |
| rejected（罕见） | +1 insert（现状是 0 条 + 抛错） | 可接受（映射修好前设备会重试，重试走 §3.1 删除路径，不堆积） |
| trace 查询 | 新索引 `(tenant_id, device_id, create_time)`（§11.3 的待实测项在此落定：要按时间窗查就必须有） | EXPLAIN 走索引 |

### 3.4 前端呈现（只读，与一批同权限 `iot:debug:get`）

- 新阶段：`UP_PERSISTED`（`persisted_at != null`，展示"已落库 N/M 条"）/`UP_DISCARDED`（`receipt_status = rejected` + 原因码）；
- 新规则 3 条（纯函数，挂 §3.2 的码）：`UP_REJECTED_MAP`（引导修映射+同 key 重投）、`UP_PARTIAL`（升级现有弱判据为结构化判据）、`UP_DEVICE_NOT_FOUND`；
- 一批的"断点不可见"注记在实施后移除（由真实阶段替代）。

---

## 4. 保留期（与 §11.2 联动）

receipt 表只增不减（高频入站）。建议：与 `iot_event_log` 同一批纳入保留策略评估，
默认提案 **90 天**（排障窗口外无意义），以量级实测修正（见 §9 Q3）。
保留是**软门槛**（L2 复核 F2：保留本身在 §11.2 亦待立项，若升为硬前置会造成无限期等待）——
实施 PR 并行给出保留方案，不单独立项卡住本期。

---

## 5. 实时跟踪（SSE/长连接）：本期不做

维持原设计"可选项、非默认"：长连接带来网关粘滞/多副本广播/断线重放三个新运维面，
而排障场景用手动刷新/短轮询已满足（一批页签现状）。若将来要做，单独立项（不在本文件范围）。

---

## 6. 不做清单

- 不改 EMQX/设备侧任何行为（只改 iot 入站落库侧）；
- 不给 `persisted` 做跨表事务（IoTDB/Redis 落库出口本来就不在 MySQL 事务里，`persisted_at` 记录的是"ingest 返回时刻"，语义见 §2，不伪装成分布式提交）；
- 不做 ACK/指派/评论（与平台告警同口径，一期不做）；
- 不批量回填存量行（`receipt_status` 默认 accepted 即等价语义）。

---

## 7. 验收判据（实施 PR 用）

| # | 给定 | 期望 |
|---|---|---|
| B-1 | 整批未映射上报 | 落 rejected 行（reason=`NO_ACCEPTED_ITEM`，`received_count`=批大小，`persisted_rows`=0）；时间线出现 `UP_DISCARDED` |
| B-2 | 修好映射后同 requestId 重投 | 受理成功（rejected 行被删除后重建为 accepted）；回执恰一行 accepted（落库侧不做 at-most-once 断言，见 §3.1 并发声明） |
| B-3 | 部分丢弃批次 | accepted 行记触发码 + `discard_detail`（前 8 pointId + `...(+N)` 后缀）；指标 `unmapped` 同步计数 |
| B-4 | 并发同 requestId 双发 | 回执恰一行（accepted 或 rejected 恰其一，无双行）；落库侧允许双写（不断言去重，与 `EMQX-INTEGRATION.md:34` 一致） |
| B-5 | dev 固定批次压测前后 | ingest 不劣化（方法：wall-clock 直测——同设备同批 N=100 循环，记 apply 段 p50/p99；前后各跑 3 轮取中位数；数值如实记录。注：`round.duration` 是告警评估器指标，ingest 侧无此指标，不得引用） |
| B-6 | SQL 等价门禁 + 租户越权反证（`TENANT_A/B` + `executeIgnore` 模式，沿用一批判据 5） | 通过 |

---

## 8. 实施纪律（触及采集链路）

按 `MESSAGE-TRACE-DESIGN.md` §8：先合并再部署、备份留回滚（jar + DB 行备份 + 本 migration 的 rollback）、
验收窗口冻结其它容器动作；**外委独立复核一次**；回滚声明必须含"已落库数据不可撤回"。

---

## 9. 拍板事项（实施前需要）

| # | 问题 | 选项 |
|---|---|---|
| Q1 | 是否实施 / 何时实施 | (a) 条件触发再实施（本稿推荐：§1）；(b) 现在就实施（ completeness 优先） |
| Q2 | tenant 未知（`DEVICE_NOT_FOUND`）的 rejected 行写在哪 | (a) `tenant_id` 记 0 哑租户、仅运维跨租户可见（推荐）；(b) 不落该分支的行（该场景维持零痕迹） |
| Q3 | receipt 保留期天数 | (a) 90 天默认 + 实测修正（推荐）；(b) 与 event_log 同策略另议 |
| Q4 | `discard_detail` 上限 | (a) 512 + 前 8 pointId + `...(+N)` 后缀（推荐）；(b) TEXT 全量 |

---

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-01 | 建立二批立项稿（只设计、未实施）。dev 实测前置：receipt 18 行、入站/丢弃计数 0 ⇒ 触发条件未满足。 |
| 2026-10-01 | **L2 独立复核后修订（第 2 稿）**：复核 19 项（过 8/不过 9）逐条整改 —— status 列改名 `receipt_status`（避让既有 TINYINT）；原因码改复用 `MqttIngestRejectReason` 14 码；更正 tenantId 分支现状（`reject(DEVICE_NOT_FOUND)` 抛异常）；并发语义诚实化（B-4 重写）+ B-5 改 wall-clock 直测；保留期降为软门槛；补审计断层缺点 + `discard_detail` N 定义。 |
