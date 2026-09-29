# 消息跟踪 + 「定位建议」设计（看板 #8）

> **状态**：📝 设计稿（未实施）
> **建立日期**：2026-09-30
> **对标**：华为云 IoTDA「消息跟踪」（设备详情页签，单用户同时跟踪设备上限 10，失败点给「定位建议」）
> **依据**：`docs/PLATFORM-GAP-REPORT-2026-09-28.md:199`、`docs/IOT-UX-PROPOSAL.md:187-188`
> **实现前置**：本文评审通过后，按 §8 的分批计划实施

---

## 0. 结论先行（TL;DR）

1. **本能力要解决的真实问题不是「缺一个日志页」，而是「用户报障时说不清消息走到哪断了」。** 现网已有零散的链路痕迹（下发实例、采集回执、运行期事件），但**没有一个以「一条消息」为主线**视图把它们串起来。
2. **不新建「大而全的日志平台」**（那会与看板 #10 指标大盘、以及被刻意排除的日志采集重叠）。本设计**只做设备维度的消息时序 + 失败定位建议**，数据全部来自**已在写入的既有链路**，不引入新采集通道。
3. **分两期**：一期「**只读聚合**」（不改任何写入路径，风险最低、可独立交付）；二期「**结构化补齐**」（给上行链路补 receipt→落库的关联字段）。**一期即可交付用户价值**，二期是把"定位建议"从"能用"做到"好用"。
4. ⚠️ **不确定的地方直说**：本设计的**写入侧覆盖面**取决于各链路当前是否真的留痕。§3 逐条标注了「已确认存在」与「**未核实/需实测放行**」——**未核实项不得在实施时当作既成事实**。

---

## 1. 现状盘点（一手代码，非推测）

### 1.1 已有的链路痕迹（**已确认存在**）

| 来源 | 表/类 | 覆盖链路环节 | 关键字段 |
|---|---|---|---|
| 下行命令 | `IotCommandInstance`（`iot_command_instance`） | **命令下发全生命周期** | `deviceId`、`identifier`、`kind`、`requestId`、`topic`、`payload`、`replyPayload`、`statusCode`、`errorCode`、`errorMsg`、`timeoutMs`、`retryCount`、`emqxMessageId`、`source`、`operatorUserId`、`sentAt`、`finishedAt` |
| 上行（**仅 EMQX MQTT 入站**） | `IotMqttIngestReceipt`（`iot_mqtt_ingest_receipt`） | **仅覆盖 EMQX→MQTT 入站这一条路径**（见下方 ⚠️） | `deviceId`、`requestId`、`itemCount`、`create_time` |
| 运行期事件 | `IotEventLog`（`iot_event_log`） | 设备上报事件 / 平台侧事件 | `deviceId`、`eventCode`、`eventName`、`level`、`params`、`eventTs`、`idempotentKey` |
| 在线状态/断档 | `OutageEvent`、`DeviceLiveness` | 在线性与断档 | — |

> **重要澄清 ①**：`IotEvent`（`iot_event`）是 **TSL 物模型里的事件定义**（`serviceId`/`identifier`/`dataType`/`enumList`…），**不是**运行时消息记录。设计时**不要**把它当消息表用（名字极易误判）。已被独立复核**核实正确**，方向无误。

> ⚠️ **重要澄清 ②（复核发现，原稿范围夸大）**：`iot_mqtt_ingest_receipt` 的回执**只由 EMQX→MQTT 入站路径写入**（`MqttReadingIngestServiceImpl#apply`）。而 **access 节点的 HTTP 出口 `POST /internal/readings` 直接调 `AvailabilityService.ingest`、不写任何回执**（`InternalReadingController.java:36-51`）。⇒ **经 HTTP 通道上报的设备（access/Modbus/OPC-UA/TCP）在该表中恒无记录**。这不是"覆盖率待实测"，而是**按通道分流**的确定事实 ⇒ §3.3 的 `UP_*` 阶段**只对 MQTT 通道成立**，前端必须对"该设备是否走 MQTT"给出可解释的空态，**不得显示成"没有上行"** ✗。

> 🔴 **重要澄清 ③（复核发现，原稿完全漏掉的死列）**：`iot_device.online_status` / `last_seen_at` 在**生产代码中没有任何写入方**（全仓唯一生产引用是 `IotDeviceServiceImpl.java:199` 的**读取**；唯一写入在单测 `IotDeviceServiceImplTest.java:104`）⇒ 该列**恒为建表默认值 `'unknown'`**。
> ⇒ **本设计一律不使用 `online_status`/`last_seen_at`**；在线性只取**有写入方**的 `DeviceLiveness.lastObservedAt` 与 `OutageEvent`。原稿 §4.1 曾建议用户"先看本页最后在线"——**该建议会把排障引向一个永不更新的字段**，正是 §4.2 自己禁止的"错误建议"，已删除。（同类"死列"教训仓内已登记：`IOT-UX-PROPOSAL.md:33` 影子 reported 永不写入。）

### 1.2 缺口（**这才是 #8 要补的**）

| 缺口 | 后果 |
|---|---|
| **无统一时间线** | 用户看到的是三张互不关联的表：命令实例、采集回执、运行期事件。要回答"这台设备 10:03 那次下发到底怎么了"，得同时在三个地方翻 |
| **无跨环节关联** | 上行的 `requestId` 与下行的 `requestId` 语义不同、无统一 trace id ⇒ **无法把"下发→设备回执→数据上报"串成一条链** |
| **失败无人话解释** | `errorCode`/`errorMsg` 是给人看的**原文**，但没有"**这意味着什么、下一步查什么**"的引导（华为的「定位建议」正是这一层） |
| **无「跟踪某台设备」的实时视图** | 现网只有"回看历史"，没有"我在盯这台设备，让它把消息实时打出来" |

### 1.3 已核实结论 与 未核实项

#### 1.3.1 已核实（独立复核 + 本设计一手核对，**结论确定**）

- ❌ **被丢弃的上行在库中零痕迹**：`MqttReadingIngestServiceImpl.java:301-306` 在整批未通过点位映射时 **刻意不写回执** ⇒ §3.3.1。**这是"不可实现"，不是"未核实"**。
- ⚠️ **`item_count` 语义受限**（`007-iot-data.sql:459`：重投时不重算）⇒ §3.3.2。
- ⚠️ **三张表都没有保留期机制**：`RetentionProperties`（`RetentionProperties.java:37-40`）**只覆盖 `outage_event` 与 `maintenance_window`**；`iot_command_instance` / `iot_event_log` / `iot_mqtt_ingest_receipt` / `outage_event` 之外的**前三张只增不减**。其中 `iot_event_log` 是设备高频上报事件 ⇒ **无上限增长只是时间问题**（见 §6 风险 R4）。
- ⚠️ **`iot_mqtt_ingest_receipt` 无时间列索引**：只有唯一键 `uk_...(tenant_id, device_id, request_id)`（`007-iot-data.sql:467`），**按 `create_time` 做时间窗查询会退化为 device 范围内扫描**（见 §6 风险 R3）。
- ✅ **索引齐备的**：`iot_command_instance`(`tenant_id, device_id, create_time`)、`iot_event_log`(`tenant_id, device_id, event_ts`)、`outage_event`(`tenant_id, device_id, start_ts`) 均支持"设备+时间"。
- 🔴 **既有门禁覆盖缺口（本设计复核时发现，与本能力无关）**：`iot_command_instance` 与 `iot_mqtt_ingest_receipt` 实体均 `extends TenantBaseEntity`，但**均未登记进** `IotTenantIsolationGateTest#M1_TENANT_TABLES`（该清单当前止于 `iot_device_import_row`）⇒ 清单式门禁**不覆盖这两张租户表**（`007-iot-data.sql:475-476` 的 DDL 注释早已点名要求登记）。**建议作为独立小修立即补 2 行**，不必等本设计评审。

#### 1.3.2 未核实项（**实施前必须实测放行**）

- **各链路的实际写入覆盖面**：设备上报的**每条**消息是否都产生回执，还是只在批量场景产生，**未核实**。
- **实际量级与增长速率**：三张表的日增行数与查询耗时，**未实测** ⇒ §7.2 的性能判据需以实测为准（**不预设数字**）。
- **EMQX 侧是否可查消息级轨迹**（`emqx ctl` / Trace 功能）：**未核实**，本设计**不依赖**它。

---

## 2. 目标与非目标

### 2.1 目标

1. **一条时间线**：设备详情 →「消息跟踪」页签，按时间倒序展示该设备的**上行 / 下行 / 回执 / 事件 / 断档**，可筛选。
2. **定位建议**：每条失败/异常消息，给出一句"**为什么 + 下一步查什么**"（不是只回显错误码）。
3. **复用既有资产**：前端复用「在线调试」页签（`detail-debug.vue`）的表格/轮询/详情呈现约定；后端**只读聚合**既有表，一期不改写入路径。
4. **可解释的取舍**：明确"不做什么"（§2.2），避免被当成日志平台验收。

### 2.2 非目标（**刻意不做**，防误报）

| 不做 | 理由 |
|---|---|
| 全量日志采集/存储/检索（按 TraceId 跨设备全局检索） | 属"独立课题"（`IOT-UX-PROPOSAL.md:1045`）；会与 #10 大盘和"刻意不做"的日志平台重叠 |
| 消息体全文长期留存 | 隐私与存储成本；`payload` 只在既有表已存的范围内展示，**不新增留存** |
| 实时"跟踪模式"推送（WebSocket/SSE） | 一期用**手动刷新/轮询**即可满足排障；引入长连接会带来新的运维面（网关/多副本）⇒ 列入二期可选项，非默认 |
| 跨租户/全局消息检索 | **安全红线**：与既有租户隔离门禁冲突 |
| 自动修复/自愈 | 本能力只**定位**、不**代做**（与 TLS 巡检同一原则：只告警不自愈） |

---

## 3. 数据设计

### 3.1 设计原则

**一期不建新表**。理由：三条链路的数据**已在库里**，一期只需**读侧聚合**；新建消息表意味着**双写**，任何一处漏写都会让"时间线缺一段"——那是最难排查的缺陷（用户不会知道少了哪条）。

### 3.2 一期的"逻辑时间线"（读侧聚合）

统一视图条目 `TraceItem`：

| 字段 | 说明 |
|---|---|
| `ts` | 发生时刻（各来源取各自的时间字段） |
| `direction` | `UP` / `DOWN` / `INTERNAL`（回执、事件、断档等平台侧） |
| `stage` | 链路阶段枚举（见 §3.3） |
| `outcome` | `OK` / `FAILED` / `TIMEOUT` / `UNKNOWN` |
| `title` | 人话标题（如「命令下发：设置温度阈值」） |
| `detailRef` | 指向原始记录（表名 + 主键），供展开看原始 payload |
| `advice` | **定位建议**（见 §4），失败/异常时必有 |

### 3.3 链路阶段 → 唯一推导表达式（**已逐条核对代码**）

> ⚠️ **本节经独立复核后重写**。原稿把 8 个阶段并列枚举，掩盖了两个会产出"错误产品语义"的问题：**① 被丢弃的上行在库里零痕迹（物理上不可实现）；② 三个 DOWN 阶段不是三条记录，而是同一行的三个时刻。** 下表改为「阶段 → 唯一推导表达式 → 可实现性」，实施者按此写，不再有解释空间。

| 阶段 | 推导表达式（**唯一来源**） | 可实现性 |
|---|---|---|
| `DOWN_ENQUEUED` | 同一行的 `create_time`（`CommandInstanceServiceImpl:204-219` 先 insert 且 `statusCode=PENDING`） | ✅ |
| `DOWN_PUBLISHED` | 同一行的 `sent_at` 非空 ⟺ 已投递 EMQX（`:476-483`；`emqx_message_id` 为佐证） | ✅ |
| `DOWN_ACK` | 同一行的 `finished_at` 非空 / `statusCode` 进终态（回执路径回写 `reply_payload`） | ✅ |
| `UP_RECEIVED` | `iot_mqtt_ingest_receipt` 行的 `create_time`（首次受理即写回执） | ✅ **仅 MQTT 通道**（§1.1 澄清②） |
| `UP_PERSISTED` | —（不存在独立的"已落库"记录） | ❌ **一期结构性不可得**，见 §3.3.2 |
| `UP_DISCARDED` | —（被丢弃的批在库中零痕迹） | ❌ **一期不可能实现**，见 §3.3.1 |
| `EVENT_REPORTED` | `iot_event_log` 行（`device_id` + `event_ts`，索引 `idx_iot_event_log_device_ts`） | ✅ |
| `DEVICE_OFFLINE` | `outage_event` 行（`device_id` + `start_ts`/`end_ts`，索引 `idx_outage_event_device_start`） | ✅ |

#### 3.3.1 ❌ 为什么 `UP_DISCARDED` 一期不可能实现（**已一手核实**）

`MqttReadingIngestServiceImpl#apply`（`MqttReadingIngestServiceImpl.java:301-306`）在整批读数全部未通过点位映射校验时：

> `reject(MqttIngestRejectReason.NO_ACCEPTED_ITEM, ...)` —— 注释原文：**「刻意**不写回执**」**

原因也写在注释里：修好映射后设备用同一 `requestId` 重投仍应被受理。

⇒ **被整批丢弃的上行在库里零痕迹**（无回执行、无其它持久记录），**`iot_mqtt_ingest_receipt` 物理上回答不了"这条上报有没有被丢弃"**。部分丢弃（`:317-324`）同样**只落 `log.warn` + 由既有 Micrometer 指标计数**，不落库。

**因此一期必须显式表达为"该断点不可见"**：前端在时间线上**不渲染**任何"丢弃"条目，改为在页签内注明"整批被拒的上报不会留痕，需查应用日志与 `iot.ingest.propertyid.unmapped/orphan` 指标" —— **不许造一个假状态** ✗。若要真正呈现，须走二批（§3.4 补 `discardReason`），且按 §6 评估对采集链路的影响。

#### 3.3.2 ❌ 为什么 `UP_PERSISTED` 一期**结构性不可得**（复核结论，原稿低估为"弱推导"）

关键在**语义不可区分**：`iot_mqtt_ingest_receipt` 的行**只在受理成功后写入**（`MqttReadingIngestServiceImpl.java:307-315` 位于 `accepted <= 0` 的拒绝分支**之后**）。因此：

- **"有回执行" ⟺ "已受理" ⟺ "已落库"**——三者是同一件事，**无法区分**，也就**无法**做"收到 vs 落库"之间的断点定位；
- 真正的落库出口是 **IoTDB 与 Redis**（`writeDerivedAfterCommit`），**都不写 MySQL 记录**；
- `item_count` 只是"首次受理时通过校验的条数"，且表注释明写**「重投时原样读回，不重算」**（`007-iot-data.sql:459`）⇒ 若两次投递之间点位映射被改过，它是**漂移的旧值**。

⇒ **一期不呈现 `UP_PERSISTED`**，只呈现 `UP_RECEIVED`（"已受理 N 条"，N 取 `item_count`）。要让"落库"成为可观测的断点，须走**二批**（§3.4 补 `persistedAt`/`persistedRows`）——**这是新增写入路径，不是可选优化**。

#### 3.3.2b ⚠️ `DOWN_ACK` 的判据必须用 `reply_payload`，不能只看 `status_code = failed`

（复核指出的高频误判点。）`status_code = failed` 有**两个完全不同的来源**：

| 来源 | 含义 | `reply_payload` | `error_code` |
|---|---|---|---|
| **publish 阶段失败** | **根本没送到设备** | `null` | `NO_SUBSCRIBER` / `EMQX_ERROR` |
| **设备回执失败** | **设备收到了并拒绝** | **非 null** | `DEVICE_REJECTED` |

⇒ **判据必须是 `reply_payload != null`（或 `error_code = DEVICE_REJECTED`）**。只看 `failed` 会把"没发出去"误报成"设备回执失败"，把用户引向错误方向 ✗。

#### 3.3.2c ⚠️ `DOWN_PUBLISHED` 的语义是"投递**成功**"，且**重发会覆写时间戳**

- `sent_at` 的赋值条件是 `target == SENT`（`CommandInstanceServiceImpl.java:476`），即**仅当 EMQX 报告至少有一个订阅者**时才有值；`NO_SUBSCRIBER`/异常时 `sent_at = null`。⇒ 它表达"**投递成功**"，**不能**用来判断"是否尝试过投递"。
- **重发（resend）用同一 `requestId`、就地覆写同一行**的 `sent_at`/`finished_at`/`error_code`/`reply_payload`（`:486-495`）⇒ **历史尝试的时间戳被覆盖丢失**，时间线上"同一 `requestId` 的多次投递"**不可还原**，只能靠 `retry_count` 知道"重发过几次"。
- ⇒ §3.3.3 的"一行 = 一条目"因此还带一个**必须如实告知用户的限制**：条目的时刻会随重发**向前跳**。前端在 `retry_count > 0` 时**必须显示"已重发 N 次（历史时刻已被覆盖）"**，不得让用户以为"10:03 那次"还是原来的那次 ✗。

#### 3.3.3 ⚠️ 时间线条目模型：**一行 = 一条目**（消除验收争议）

三个 `DOWN_*` 阶段是**同一行记录的三个时刻**，不是三条记录。一期**固定采用「一行命令实例 = 时间线中一条目」**：

- 条目时刻取**该行最新的已知时刻**（`finished_at` → `sent_at` → `create_time` 依次回落）；
- 该条目在 `detailRef` 中携带**三个时刻**，展开时可看到"受理 → 投递 → 回执"的完整推进；
- 阶段筛选匹配该行**已达的最远阶段**。

> 这样时间线不会因一行膨胀成三条而失真，阶段筛选也仍有明确语义。**二期若要做"每条消息一个刻度"的真实多条目视图，需先有独立的消息级主键**（当前没有）⇒ 列入二期，且**必须连带修订本表**。

### 3.4 二期（可选）结构化补齐

若一期实测确认上行链路存在"断点不可见"，二期给 `IotMqttIngestReceipt` 补 `persistedAt` / `persistedRows` / `discardReason` 等字段，把 §3.3 的未核实阶段变成可呈现。**二期才动写入路径**，并按 §6 评估对采集链路的影响（**采集是生产生命线，绝不能被拖慢**）。

---

## 4. 「定位建议」规则（本能力的灵魂）

### 4.1 原则

1. **只从**已存在的结构化字段**推导**（`errorCode`/`errorMsg`/`statusCode`/`timeoutMs`/`retryCount`…），**不做日志文本挖掘**（脆弱且不可测）。
2. **建议必须是可执行动作**，不是复述错误。反例：「命令超时」✗；正例：「命令在 5000ms 内未收到设备回执。**建议**：① 看本页断档记录（`OutageEvent`）判断当时是否离线；② 用『在线调试』重发一次对照；③ 仍超时则核对设备订阅的 topic 是否为 `{productKey}/{deviceCode}/cmd/down`」✓
   - ⚠️ **不得引用 `online_status`/`last_seen_at`**：该两列在生产代码中**无写入方**（§1.1 澄清③），引用它们等于给用户一个永不更新的字段。在线性只取 `DeviceLiveness.lastObservedAt` 与 `OutageEvent`。
3. **规则表可枚举、可单测**：每条规则是一个纯函数 `(trace item) → advice | null`，**无 IO**，因此可以在 jsdom 之外直接跑用例（与 `import-state.ts` 同一约定）。

### 4.2 一期规则清单（草案，实施时逐条补用例）

> **规则直接挂在既有的枚举列上**（已核实）：`error_code` 的取值就是
> `DEVICE_OFFLINE | NO_SUBSCRIBER | EMQX_ERROR | DEVICE_REJECTED | TIMEOUT`（`007-iot-data.sql:494`），
> `status_code` 为 `pending | sent | succeeded | failed | timeout | cancelled`（`:493`）。
> ⇒ **规则不必靠文本猜测，一一对应即可**（这也是"只从结构化字段推导"原则的落地证据）。

| 规则 id | 触发条件（既有枚举） | 建议（要点） |
|---|---|---|
| `CMD_DEVICE_OFFLINE` | `error_code = DEVICE_OFFLINE` | **根因是设备不在线**（不是平台问题）：先看本页"最后在线/断档"；确认设备供电与网络后重发 |
| `CMD_NO_SUBSCRIBER` | `error_code = NO_SUBSCRIBER` | 设备在线但**未订阅**下行 topic：核对设备订阅的主题是否为 `{productKey}/{deviceCode}/cmd/down`（下发靠**主题定向**，官方无按 clientid 定向的端点） |
| `CMD_EMQX_ERROR` | `error_code = EMQX_ERROR` | **平台侧投递失败**（非设备问题）：检查 EMQX 可达性与连接数，看平台侧指标 |
| `CMD_DEVICE_REJECTED` | `error_code = DEVICE_REJECTED` | 设备**明确拒绝**：检查命令参数是否符合物模型（类型/范围/枚举）—— 看 `reply_payload` 里设备返回的原因 |
| `CMD_TIMEOUT` | `error_code = TIMEOUT` 或 `status_code = timeout` | 在 `timeout_ms` 内未收到回执：① 设备是否在线 ② 是否订阅正确 topic ③ 用「在线调试」重发一次对照；仍失败则核对设备侧处理耗时 |
| `CMD_FAILED_GENERIC` | `status_code = failed` 但 `error_code` 为空 | 失败但平台未归因：**这本身是要暴露的问题**（归因缺失），建议查应用日志该 `request_id` |
| `UP_PARTIAL_DROPPED` | `UP_RECEIVED` 且存在丢弃（**仅弱判据**，见 §3.3.2） | 提示性文案：本批"受理 N 条"，若设备上报条数更多，说明有点位未映射/孤儿映射 ⇒ 引导查看 `iot.ingest.propertyid.unmapped/orphan` 指标与点位映射配置。**不作为断言** |
| `DEVICE_OFFLINE_WINDOW` | 时间窗内存在 `outage_event` | 该时段设备曾断档：先排除"断档"这一根因，再看窗口内其它失败 |
| `NO_TRACE` | 时间窗内完全没有记录 | 空态引导，**必须区分三种成因**（复核指出原稿引用了不存在的"对账周期"配置 —— 全仓无此配置项）：<br>① **该设备本就走 HTTP 通道**（access/Modbus/OPC-UA/TCP）⇒ 回执表**恒为空**，这是**预期**而非故障（§1.1 澄清②）；<br>② 设备离线 ⇒ 用 `DeviceLiveness.lastObservedAt`/`OutageEvent` 说明（**不是** `online_status`，那是死列）；<br>③ 窗口内确实没通信 ⇒ 建议先在「在线调试」下发一条命令验证链路。<br>并**明示"整批被拒的上行不留痕"**（§3.3.1），指向应用日志与 `iot.mqtt.ingest.rejected` 指标 |

> **未知情况必须显式表达**：规则不匹配时返回 `null`，前端显示「**暂无定位建议**」+ **原始错误码**，而不是硬凑一句可能是错的建议 ✗（错误建议比没有建议更糟：会把排障引向错误方向）。

---

## 5. 接口设计

> 🔴 **本节经独立复核后重写（原稿有权限降级缺陷）**。原稿把两个端点都挂在 `iot:device:list` —— 复核指出：该码是**设备列表页的菜单级权限**（凡能看到设备列表的角色皆有），而**命令 payload/回执的既有查询端点用的是 `iot:debug:get`**（`IotCommandController.java:76`；注册于 `migration/2026-10-02-iot-command-instance.sql:46`）。仓内并有**明文门禁**断言「**查询与下发绝不能同码**」（`IotCommandControllerGateTest.java:44-51`，含具体理由"只读角色不应能给设备发命令"）。⇒ 用 `iot:device:list` 去返回下行报文与回执，是**权限降级**，不是"沿用" ✗。

### 5.1 按条目类型分级的授权矩阵（**实施按此表**）

| 端点 | 返回内容 | 权限码 | 理由 |
|---|---|---|---|
| `GET /devices/{deviceId}/messages` | 时间线**摘要**（时刻/方向/阶段/结果/标题，**不含 payload**） | `iot:debug:get` | 端点整体含命令类条目 ⇒ 取**较严**的码（保守做法，避免字段级授权复杂度） |
| `GET /devices/{deviceId}/messages/{refTable}/{refId}` | 单条明细（**默认摘要**；`?raw=true` 才返回原始 payload/replyPayload） | `iot:debug:get` | 明细可能含**用户自由文本** payload，必须与既有命令查询同码 |

> **取舍说明**：更"精细"的做法是按条目类型分别授权（命令类 `iot:debug:get`、事件类 `iot:device:list`），但那需要**字段级/条目级授权**，实现与测试复杂度显著上升，且一旦漏判就是数据暴露。**本设计选择"整端点取较严的既有码"**：不新造权限码、不与既有安全模型冲突、行为可预测。若后续确有"只看事件、不看命令"的角色，再做条目级授权（列入二期可选项）。

### 5.2 路径口径（**必须与仓内约定一致**）

- **客户端**调用 `/iot/devices/{id}/messages`（网关 `Path=/iot/**` + `StripPrefix=1`，见 `deploy/nacos/ypbin-gateway.yaml`）；
- **Controller 映射**写 `@RequestMapping("/devices")` + `@GetMapping("/{deviceId}/messages")` ——**不带 `/iot` 前缀**（与 `IotCommandController.java:46`、`IotDeviceEventController` 同口径）。
- 类注释中两者都要写明（原稿 §5 与 §7.1 一处写 `/iot/...`、一处写 `/devices/...`，**自相矛盾**，已统一）。

**其他约定**：
- 租户隔离沿用既有机制（`TenantBaseEntity` + 门禁登记的租户表清单）——**新查询不得绕过**；并**顺带修 §1.3.1 的门禁缺口**（把两张表补进清单，使本能力所依赖的表确实受门禁覆盖）。
- ⚠️ **跨源分页（复核指出的最大实现陷阱，见 §6 R5）**：三表时间字段语义不同（命令 `create_time`、回执 `create_time`、事件 `eventTs` 由上报方给出**可能乱序**）⇒ **跨源 UNION 后 `ORDER BY`+`LIMIT` 用不上任何单表索引，且"各表各取 N 条再合并"会在第 2 页漏数据** ✗。**一期采用"按时间窗一次取该窗内全部条目（带上限）+ 服务层合并排序"**，并**明示上限**（超限时**显式告知有截断**，不静默）；**不承诺**数据库级全局分页。
- 分页参数与既有列表一致（`page`/`pageSize`），`long` 字段按仓内口径走 `toBackendNumber`。
- **不提供**"按 trace id 全局检索"端点（非目标）。

---

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **R1 聚合查询拖慢库** | 多表查询，设备多、时间窗大时慢 | ① 强制时间窗（默认 1 小时）；② **走已核实存在的索引**（§1.3.1）；③ **实测**后再决定优化 |
| **R2 `iot_mqtt_ingest_receipt` 无时间索引** | 该表按 `create_time` 筛会退化为 device 范围扫描 | 一期**限制该源的时间窗**（取 `[from, to]` 且窗口设上限）；**实测**耗时后再决定是否补 `(tenant_id, device_id, create_time)` 索引（加索引须走迁移脚本 + SQL 等价门禁） |
| **R3 无保留期 ⇒ 表无限增长** | `iot_command_instance`/`iot_event_log`/`iot_mqtt_ingest_receipt` 只增不减（`RetentionProperties` 未覆盖） | **本设计不新增表，但也不能假装此债不存在**：① 时间窗查询 + 结果上限；② 在看板**登记该债**（建议独立立项：把三表纳入保留策略）；③ **本设计不擅自扩大范围去改保留策略** |
| **R5 跨源合并排序 + 分页在 DB 层不成立**（复核指出的**最大实现陷阱**） | 三表时间字段语义不同（命令/回执用 `create_time`，事件用上报方给出的 `event_ts` 且**可能乱序**）⇒ 跨表 `UNION` 后 `ORDER BY`+`LIMIT` **用不上任何单表索引**；且"各表各取 N 条再合并"会在**第 2 页漏数据** | **一期不做 DB 级全局分页**：按时间窗**一次取该窗内全部条目（带上限）**+ 服务层合并排序；**超限显式告知已截断**（不静默）；默认窗收窄（如 1 小时）。**实测**耗时后再评估优化 |
| **R6 payload 无内容过滤 + 无保留期（组合风险）** | `payload` 是**用户自由输入**的参数序列化（`CommandSendReq.params` 为 `Object`，**服务端不过滤内容**），`TEXT` 列可达 64KB；若用户在命令参数里写了口令/密钥（`service_call` 下发设备密码很常见），它会**原样长期存库**（§1.3.1：无保留期）**且**被本能力全文展示 | ① 明细端点用较严的 `iot:debug:get`（§5.1）；② **默认只返回摘要**，`?raw=true` 才给原文；③ **登记该组合风险**（建议独立立项评估保留期与内容过滤），**本设计不擅自扩大范围去改写入侧** |
| **R4 明细端点扩大 payload 暴露面** | `iot:device:list` 是**菜单级宽权限**，新增明细端点等于让"能看到设备列表的人"都能读明文 `payload`/`reply_payload` | **两个端点均取较严的既有码 `iot:debug:get`**（与既有命令查询端点同码；**不新造码**、不与 `IotCommandControllerGateTest` 的"查询与下发不同码"断言冲突）；且**默认只返回摘要**，原始 payload 需显式 `?raw=true`。详见 §5.1 授权矩阵 |
| **"定位建议"给错** | 把用户引向错误方向（比没有更糟） | 规则**只基于结构化字段**；不匹配时显式"暂无建议"；每条规则配用例 + **变异验证**（改坏判据必须转红） |
| **误当日志平台验收** | 期望管理失败 | §2.2 非目标写清；看板登记时如实标注"不是全量日志检索" |
| **一期呈现不了上行断点** | 能力不完整 | §1.3 已标未核实；**实测后再决定**是一期带出还是留二期，**不造状态** |
| **payload 含敏感信息** | 隐私 | 沿用既有表的展示边界；明细接口按 `iot:device:list` 鉴权；**不新增留存** |

---

## 7. 验收判据（可测）

### 7.1 功能

1. 给定一台有历史下发记录的设备，`GET /devices/{id}/messages` 返回**按时间倒序**的时间线。**每条 `iot_command_instance` 最多产生 1 个条目**（§3.3.3），条目时刻取 `finished_at→sent_at→create_time` 依次回落；条目携带全部三个时刻。
   - **反例守卫（必须有用例）**：同一行**不得**展开成 3 个条目（否则时间线失真、阶段筛选语义不清）。
2. 失败的 `DOWN_ACK` 条目**必带** `advice`（非空）；成功的条目**可以不带**。
3. 规则不匹配时，条目 `advice` 为 `null`，前端显示"暂无定位建议"**且同时显示原始 errorCode**（不得只显示"暂无建议"）。
4. 时间窗/方向/阶段/结果**筛选各自生效**（四组筛选组合有对应用例）。
5. **租户隔离**：A 租户查不到 B 租户设备的任何消息。**复用仓内既有模式、不要自创**：`ypbin-service/ypbin-iot/src/test/java/cn/ypbin/admin/iot/it/IotTenantIsolationIT.java:67-70` 定义 `TENANT_A=900001`/`TENANT_B=900002`，`:119-131` 的 `crossTenantListMustNotLeak` 用 `insertProductRaw(TENANT_B, ...)` 造"**存在但属别租户**"的行，并用 `executeIgnore` **反证该行确实存在**（防"空跑假绿"）。
   - ⚠️ 该用例需要真实 DB ⇒ 属 **`-Pit` 集成测试面**（`IotTenantIsolationGateTest` 类注释已说明它**不替代**端到端越权用例）⇒ **不能在纯单测里做一个假绿版本** ✗。

### 7.2 边界与性能

6. 时间窗缺失时回落默认值；超上限时**显式拒绝**（不静默截断——静默截断会让用户以为"就这些"）。
7. `pageSize` 超上限时按上限收敛（与既有分页同口径）。
8. **空结果返回空集合，不是 null**（仓内红线）。
9. 聚合查询在**实测**数据量下的耗时记录进看板（**不预设数字**）。

### 7.3 门禁

10. 新增端点登记进权限码门禁；新增实体（若二期建表）登记进 `IotTenantIsolationGateTest`；循环内查库须在 `SourceConventionTest` 登记理由。
11. 前端 `*.test.ts` 覆盖规则纯函数（**每条规则至少一正一反**）+ 空态/失败态分离。

---

## 8. 分批实施计划

| 批次 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| **一批（可独立交付）** | 后端只读聚合端点 + 定位建议规则（含用例）+ 前端「消息跟踪」页签 | 无 | 低（只读，不改写入路径） |
| **二批（可选）** | 上行链路结构化补齐（§3.4）；实时跟踪模式（§2.2 可选项） | 一批 + §1.3 实测结论 | 中（触及采集链路写入 ⇒ 需按生产发布纪律，先备份、可回滚） |

> **实施纪律**：二批**触及采集链路** ⇒ 按 `docs/DEPLOY-BACKEND.md` 的先合并再部署、备份留回滚、验收窗口冻结其它容器动作执行；**并需一次外委独立复核**（生产发布类）。

---

## 9. 外部参考（一手来源）

| 内容 | 来源 | 类型 | 访问日期 |
|---|---|---|---|
| 华为云 IoTDA 消息跟踪（设备详情页签、单用户同时跟踪上限 10、失败点「定位建议」） | `support.huaweicloud.com iot_01_0030_0`（见 `PLATFORM-GAP-REPORT-2026-09-28.md:199` 引用） | 厂商官方文档 | 2026-09-28（**本设计未重新联网复核**） |
| 阿里云云端运行日志（可查 7 天）+ 设备本地日志 + 日志转储 | 见 `IOT-UX-PROPOSAL.md:1045` 引用 | 厂商官方文档 | 2026-09-28（同上） |

> ⚠️ **本设计的对标描述沿用仓内既有登记**（`PLATFORM-GAP-REPORT` / `IOT-UX-PROPOSAL`），**本次未重新联网核实**这些厂商页面的当前表述 ⇒ 引用强度为"**沿用仓内登记**"，不宣称本次一手复核 ✗。

---

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-30 | 建立本设计稿。结论先行、现状为一手代码盘点、非目标与"不做"清单明确（§2.2）、定位建议规则可枚举可单测（§4）、验收判据可测（§7）、分批计划含二批生产纪律（§8）。**本稿未实施任何代码改动**。 |
| 2026-09-30 | **第二轮独立复核后修订（第 3 稿）**。第二位复核者以更细粒度逐条核对（同样附 `文件:行号`），又发现 **1 处高危权限错误 + 1 处范围夸大 + 1 处死列遗漏**，均已按证据修订：<br>• **§5 权限降级（高危，已重写为 §5.1 授权矩阵）**：原稿两个端点都挂 `iot:device:list`，但**命令 payload/回执的既有查询端点是 `iot:debug:get`**（`IotCommandController.java:76`），且仓内有**明文门禁**「查询与下发绝不能同码」（`IotCommandControllerGateTest.java:44-51`）。`iot:device:list` 是**设备列表页菜单级权限**（范围宽得多）⇒ 用它返回下行报文是**权限降级** ✗。现改为**两个端点均取较严的既有 `iot:debug:get`**（不新造码）。<br>• **§1.1 澄清② 覆盖面夸大**：`iot_mqtt_ingest_receipt` **只覆盖 EMQX MQTT 入站**；access 的 HTTP 出口 `POST /internal/readings`（`InternalReadingController.java:36-51`）**不写任何回执** ⇒ 非 MQTT 通道**恒无记录**，且 `UP_*` 阶段只对 MQTT 成立。<br>• **§1.1 澄清③ 死列（原稿完全漏掉）**：`iot_device.online_status`/`last_seen_at` **生产代码中无写入方**（唯一生产引用是 `IotDeviceServiceImpl.java:199` 的**读**；唯一写在单测）⇒ **恒为 `'unknown'`**。原稿 §4.1 曾建议"先看本页最后在线" ⇒ **会把排障引向永不更新的字段**（正是 §4.2 自己禁止的错误建议），已删除并改为只用 `DeviceLiveness`/`OutageEvent`。<br>• **§3.3.2 升级为"结构性不可得"**：原稿把 `UP_PERSISTED` 写成"弱推导"，复核证明**语义不可区分**（回执行存在 ⟺ 已受理 ⟺ 已落库，三者同一件事；真正落库出口是 IoTDB/Redis，不写 MySQL）⇒ 一期**不呈现**，改期到二批。<br>• **新增 §3.3.2b/2c**：`DOWN_ACK` **必须用 `reply_payload != null`** 判定（`status_code=failed` 有 publish 失败/设备拒绝**两个来源**，只看它会把"没发出去"误报成"设备拒绝"）；`DOWN_PUBLISHED` 的 `sent_at` 语义是"投递**成功**"，且**重发就地覆写时间戳** ⇒ 历史尝试不可还原，前端须显示"已重发 N 次（历史时刻已被覆盖）"。<br>• **新增 §6 R5/R6**：**跨源合并排序与 DB 级分页不成立**（三表时间字段语义不同、事件 `event_ts` 可能乱序 ⇒ 跨表 `ORDER BY`+`LIMIT` 用不上索引，"各表各取 N 条"会在第 2 页**漏数据**）⇒ 一期改为"时间窗内一次取全量（带上限）+ 服务层合并"，超限**显式告知截断**；**payload 无内容过滤 + 无保留期**的组合风险（`CommandSendReq.params` 为自由 `Object`，服务端不过滤，64KB `TEXT`），明细默认只回摘要、`?raw=true` 才给原文。<br>• **§5.2 路径口径统一**：原稿 §5 写 `/iot/...`、§7.1 写 `/devices/...`（自相矛盾）；现明确"客户端 `/iot/devices/{id}/messages`，Controller 映射 `/devices/{deviceId}/messages`（不带 `/iot`，网关 `StripPrefix=1`）"。<br>• **§4.2 `NO_TRACE` 重写**：原稿引用**不存在的"对账周期"配置**（全仓无此项）⇒ 改为区分三种成因（HTTP 通道恒空／设备离线／确实无通信）。<br>• **§7.1 判据 5 指向既有先例**：改用 `IotTenantIsolationIT` 的 `TENANT_A/B` + `executeIgnore` 反证模式（防假绿），并说明属 `-Pit` 面。<br>**复核边界**：两轮均为**静态读码判定**，未运行任何测试（含 `-Pit`）、未实测数据量、未联网复核厂商表述。 |
| 2026-09-30 | **独立复核后修订（第 2 稿）**。复核（独立上下文、附 `文件:行号` 证据）判定"**修改后可用**"，并指出初稿**三处会产出错误产品语义**的缺陷，均已按证据修订：<br>• **§3.3 重写**：初稿把 8 个阶段并列枚举，掩盖两个硬问题 —— ① **`UP_DISCARDED` 一期物理上不可实现**：`MqttReadingIngestServiceImpl.java:301-306` 整批被拒时**刻意不写回执**（已被丢弃的批在库中零痕迹），初稿仅标"未核实"属**定性不足**；② **三个 `DOWN_*` 是同一行的三个时刻**（`CommandInstanceServiceImpl:204-219` insert→publish→回执），初稿的验收判据 #1 因此**自相矛盾**（"字段与表一致"与"三个阶段"不可兼得）。现改为「阶段 → 唯一推导表达式」+ §3.3.3 明确"**一行 = 一条目**"并配反例守卫。<br>• **§1.3 拆为"已核实 / 未核实"**：新增三项**确定结论** —— ① 被丢弃的上行零痕迹（**不可实现**，非"未核实"）；② 三张表**均无保留期**机制（`RetentionProperties` 只覆盖 `outage_event`/`maintenance_window`，而 `iot_event_log` 是高频上报 ⇒ 无上限增长）；③ `iot_mqtt_ingest_receipt` **无时间列索引**（按 `create_time` 查会退化）—— 后两项初稿完全漏掉。<br>• **§5/§6 安全修订（R4）**：初稿把明细端点也挂在 `iot:device:list`。该码是**菜单级宽权限**，用它返回明文 `payload`/`reply_payload` 等于**扩大既有暴露面** ⇒ 改为**新造更窄的 `iot:device:trace`**（先例：#7 的 `iot:device:import`），并默认只返回摘要、`?raw=true` 才给原文。<br>• **§4.2 重写**：改为**直接绑定既有枚举**（`error_code` = `DEVICE_OFFLINE|NO_SUBSCRIBER|EMQX_ERROR|DEVICE_REJECTED|TIMEOUT`，`status_code` 六态；均已在 `007-iot-data.sql:493-494` 核实），不再依赖文本猜测。<br>• **复核的另一独立发现（已回传，与本能力无关）**：`iot_command_instance` 与 `iot_mqtt_ingest_receipt` 均为租户实体却**均未登记**进 `IotTenantIsolationGateTest#M1_TENANT_TABLES` ⇒ **真实门禁覆盖缺口**（`007-iot-data.sql:475-476` 的 DDL 注释早已点名要求）。建议**立即独立小修补 2 行**，不必等本设计评审 ⇒ 已登记 §1.3.1 与 §11。<br>**复核边界（如实声明）**：复核为**读码判定**、未实跑 `mvn`（本机 2 核）⇒ "门禁缺口"的逻辑依据是测试自身的 `containsAll` 语义，**未经运行时验证**；厂商对标表述**未重新联网核实**（沿用仓内登记）。 |

---

## 11. 由本设计派生出的独立小修（**建议立即做，不等本设计评审**）

| # | 事项 | 依据 | 规模 |
|---|---|---|---|
| 1 | 把 `iot_command_instance`、`iot_mqtt_ingest_receipt` **补进** `IotTenantIsolationGateTest#M1_TENANT_TABLES` | 两者均 `extends TenantBaseEntity` 却未登记；`007-iot-data.sql:475-476` DDL 注释早已点名要求 | **2 行** |
| 2 | 把 `iot_command_instance`/`iot_event_log`/`iot_mqtt_ingest_receipt` 纳入**保留策略**评估 | `RetentionProperties` 只覆盖 `outage_event`/`maintenance_window`；`iot_event_log` 为高频上报且只增不减 | 需独立立项（含量级实测） |
| 3 | `iot_mqtt_ingest_receipt` 补 `(tenant_id, device_id, create_time)` **索引** | 现只有唯一键，时间窗查询会退化 | 1 个迁移脚本（须过 SQL 等价门禁）——**待实测确认确有需要再做**（避免过早优化） |
