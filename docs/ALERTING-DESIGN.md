# 告警与阈值能力设计（ALERTING-DESIGN）

> 状态：**设计方案，未实现**。本文只出方案，不含任何可执行实现代码。
> 目标：让平台**能设阈值、能触发、能看见**。
> 一句话现状：**本平台目前完全没有告警能力** —— 没有任何阈值配置、没有告警实例、没有告警状态、没有告警页面（`deploy/sql/006-iot-schema.sql` 与 `007-iot-data.sql` 里既无 `alarm` 类表也无相关权限码）。
> 本文的一手调研部分遵守全局 `~/.dsh/AGENTS.md` 第 2 节 R1–R3：只用官方文档，逐条标链接与访问日期，确认不了的写进 §6「未确认项」，不编造字段名。

---

## 0. 结论先行（TL;DR）

| 议题 | 建议 | 理由（一句话） |
|---|---|---|
| 规则存哪 | **MySQL 单表 + 点位行**（`iot_alert_rule` + `iot_alert_rule_point`），作用域字段用「租户 / 产品 / 设备 / 点位」四级，最具体者优先 | 与仓内既有「规则表 + 明细行」形态一致；规则量级是「百~千」，不需要单独存储引擎 |
| 在哪算 | **独立评估器（`@Scheduled` 扫描器）**，读 **Redis 最新值**做实时判定，**IoTDB 只用于窗口类规则与回溯解释** | 复用仓内已有的 5 个扫描器范式；Redis 最新值是现成的、便宜的；IoTDB 每次查询都要建会话，按设备×点位轮询它做秒级判定代价过高 |
| 状态机 | **4 态**：`pending`（已越界未满持续条件）→ `firing`（活动）→ `acked`（已确认，**仍是活动**）→ `resolved`（已恢复）。**静默刻意不做成状态**（它是通知侧判定，与状态机正交） | 「已确认」与「静默」必须分开，否则「我看到了，别重复喊」与「计划停机，别喊」会互相污染（ThingsBoard 4 态 + Prometheus silence 的组合）。**静默若做成状态，会出现「静默结束后该回哪个状态」无法定义** |
| 抖动抑制 | **连续 N 次**为主（默认 N=3），可选 **持续 T 秒**；两者都要求「同一越界方向连续成立」 | 阿里云与 ThingsBoard 都把「条件」与「持续/次数」分开配；本平台点位是**周期上报**，次数比时长更贴合（周期可不固定） |
| 通知渠道 | **站内信（复用 `sys_message`）+ 邮件（已有 `MailController`）**；Webhook **本期不做** | 站内信与邮件是**已有的、已验证的**能力；Webhook 需要新的出站白名单、超时、重试与 SSRF 防护，属独立课题 |
| 重复通知抑制 | 活动期内**按 `repeat_interval` 重发**（默认 30 分钟，可配），状态变化（触发/恢复/确认）**立即通知**一次 | 与 Alertmanager 的 `repeat_interval` 同一取向；避免「一直坏着但只喊过一次」 |
| 静默 | **复用 `maintenance_window`**（计划停机窗口内只记录不通知）+ 规则自带的 `silence_start/end` | 维护窗口已存在且语义正好吻合；不新造一套「静默」概念 |
| 首次落地范围 | **阈值告警（点位数值越界）** 一种，其余（设备离线告警、复合条件、AI 异常检测）不做 | 见 §2.6 |
| 需要用户决策 | **7 项**，见 §5 | 涉及产品形态与代价，不代为拍板 |

**最大的三个设计风险**（都在 §2 展开）：

1. **「没数据」与「读不到」必须分清**：`TimeSeriesQueryService.query` 在时序库不可用时是**抛异常**而非返回空列表（仓内既有纪律，注释原话「空列表会被读成『这段时间没数据』」），评估器若把「查询失败」当成「没有越界」就会**静默漏报**。这是本设计里最容易出错、也最难在事后发现的一点。**恢复只能由「读到合格的未越界值」驱动**（§2.2.4）。
2. **点位的 `value` 是字符串**（`TimeSeriesPoint.value` 是 `String`），阈值比较必须显式数值化；且**非数值 / 坏质量 / 陈旧**这三类「不可判定」**不得**当作 0、**不得**当作越界、**不得**当作恢复，且必须有指标可见（§2.2.3）。
3. **去重的唯一索引极易写反**（🔴 本文档初稿就写反过，已修正）：MySQL 唯一索引对**含 NULL 的行不做约束**，所以**不能**把 `resolved_ts` 放进唯一索引——那恰好把「活动告警」（`resolved_ts IS NULL`）这一批**放走**，去重完全失效。正确做法是用「只在活动期非 NULL」的 `active_dedup_key` 列（§2.1 表 C）。**该结论已在本机用 `mysql:8.4` 实测两个方向确认**（见 §2.1），不是照文档记忆断言。**写反了不会报错，只会重复告警刷屏**，因此必须有 §3.3-U5 那样的变异哨兵用例。

---

## 1. 一手调研：主流做法与共性模型

> 以下四节均为**官方文档**，访问日期统一 **2026-09-27**（实测日期吻合）。逐条结论后括注来源。
> 完整调研原文（含 69 处 URL 与 19 条未确认项）见工作区 `IOT-ALARM-RESEARCH.md`。

### 1.1 ThingsBoard —— Alarm rules / alarms

来源：[Alarm rules](https://thingsboard.io/docs/user-guide/alarm-rules/)、[Alarms](https://thingsboard.io/docs/user-guide/alarms/)、[Create alarm node](https://thingsboard.io/docs/reference/rule-engine/nodes/action/create-alarm/)、[Clear alarm node](https://thingsboard.io/docs/reference/rule-engine/nodes/action/clear-alarm/)、[Notifications](https://thingsboard.io/docs/user-guide/notifications/)、[官方 DDL](https://raw.githubusercontent.com/thingsboard/thingsboard/master/dao/src/main/resources/sql/schema-entities.sql)（访问日期均 2026-09-27）

- 规则由五段构成：**Alarm type / Arguments / Trigger condition（Severity + Condition）/ Schedule / Clear condition**，另有 Propagation。
- **「持续条件」就在规则内部表达，不需要额外状态机**：`Condition type` 三选一 —— `Simple`（立即）、`Duration`（连续保持一段时长）、`Repeating`（连续出现 N 次）。官方示例即 "continuously for 1 minute" 与 "3 times in a row"。
- **去重键 = `originator + type`**：同一组合同时只允许存在一条活动告警。
- **状态机 4 态**，官方代码枚举精确为 `ACTIVE_UNACK, ACTIVE_ACK, CLEARED_UNACK, CLEARED_ACK`（来源：[AlarmStatus.java](https://raw.githubusercontent.com/thingsboard/thingsboard/master/common/data/src/main/java/org/thingsboard/server/common/data/alarm/AlarmStatus.java)）。
- **不配 `Clear condition` 就永不自动恢复**（默认不恢复）；且明确 "Clearing an alarm does not delete it"（清除 ≠ 删除）。
- 通知是**三件套**：Recipients + Templates + Rules；**升级链（escalation chain）**支持多阶段收件人 + 阶段间延迟 + `Stop escalation on the alarm status become`。
- 建议规则建在 **Device/Asset profile**（档案级共享），而不是单个实体。
- 告警实例持久化已确认到 DDL：`alarm`（含 `ack_ts`/`clear_ts`/`start_ts`/`end_ts`/`severity`/`acknowledged`/`cleared`）、`alarm_comment`、`entity_alarm`（传播可见性，主键 `(entity_id, alarm_id)`）、`alarm_types`（唯一键 `(tenant_id, type)`）。
- ⚠️ **未确认**：告警**规则定义**本身存哪张表/哪个列（只知与 device profile 一起保存）。

### 1.2 阿里云 IoT —— 阈值告警 / 事件告警

来源（访问日期均 2026-09-27）：[配置设备告警（生活物联网平台）](https://help.aliyun.com/document_detail/126944.html)、[配置阈值报警规则（云监控）](https://help.aliyun.com/zh/iot/user-guide/configure-alarm-rules)、[视频告警](https://help.aliyun.com/zh/document_detail/151920.html)

> 🔴 **必须先纠偏的一点**：网上/文档里那两篇标着「阈值告警」的页面，读进去会发现它们是**云监控规则**，监控维度是**实例/产品指标**（同时在线设备数、TPS、IOPS、时序存储空间…），**不是「设备上报温度 > 阈值」这种设备属性告警**。把前者当成「阿里云 IoT 的设备阈值告警」去对标 ThingsBoard/EMQX 会**错位**。设备级告警的真实文档在生活物联网平台。

- **云监控侧（实例/产品指标维度）**字段真实存在：`监控指标`、`监控指标维度`（默认全部产品，**每个产品独立判断阈值**）、`统计字段`（计数）、`运算符`（`>=` `>` `<=` `<` `!=`，另有同比昨天/上周、环比上周期百分比）、`阈值`、**`持续周期`（1/3/5/10）**、**`数据聚合周期`（1 或 5 分钟）**、`报警生效时间`、**`报警沉默周期`＝未恢复时重复通知的间隔**、`报警通知对象`（联系人组）、`报警级别`。
- **设备级（生活物联网平台）**：告警规则**只有 `属性` / `事件` / `设备状态` 三类**（原文「支持属性、事件的简单规则」）；等级三档 `提醒通知` / `轻微问题` / `严重告警`；内容宏真实存在（`${var}`、`${value.var}`、`#TSL_REPLACE`、`#DEVICE_NICKNAME`、`#TIME_UTIL` 等）；`权限范围` = 通知用户（消息中心/应用推送/强提醒）+ 通知后台（告警中心）。
  - ⚠️ **未确认**：该页**没有列出属性规则的比较运算符或持续时长字段**。
- **告警级别 → 通知渠道是硬绑定的**：Critical = 电话+短信+邮件+钉钉；Warning = 短信+邮件+钉钉；Info = 邮件+钉钉。
- 视频告警里唯一一处「连续 N 时长」表述：`连续云存异常告警` = 「连续云存持续一小时及以上出现录像丢失则触发告警」；该页还明确提示同时启用设备告警与视频告警**「可能会同时收到两条告警消息」**（跨功能不自动合并）。
- `事件告警` 是云监控的另一个入口（`Event Monitoring > System Event > Event-triggered Alert Rules`），监控的是**限流类事件**（连接/发布/消息/规则引擎请求数达上限），与阈值告警不是同一个东西。
- ⚠️ **未确认**：MNS / 站内消息 / Webhook 作为告警接收端（读到的官方页面里渠道只有电话/短信/邮件/钉钉 + App 消息中心/应用推送）；多篇 `/zh/iot/user-guide/*` 页面标题带「文档停止维护」。

### 1.3 EMQX —— 规则引擎 / 事件 / 系统告警

来源（访问日期均 2026-09-27）：[Alarms](https://docs.emqx.com/en/emqx/latest/observability/alarms.html)、[Rule SQL syntax](https://docs.emqx.com/en/emqx/latest/develop/data-integration/rule-sql-syntax.html)、[Rule SQL events and fields](https://docs.emqx.com/en/emqx/latest/develop/data-integration/rule-sql-events-and-fields.html)、[Webhook](https://docs.emqx.com/en/emqx/latest/develop/data-integration/webhook.html)

- 系统告警清单（含级别与阈值配置项）：`high_system_memory_usage`、`high_process_memory_usage`、`high_cpu_usage`、`too_many_processes`、`license_quota`、`license_expiry`、`License_tps`、`partition`、`resource`、`conn_congestion`；级别 `Error/Warning/Critical`。
- **阈值机制是高/低水位线滞回**：`sysmon.os.cpu_high_watermark=80%` / `cpu_low_watermark=60%`、`sysmem_high_watermark=70%`、`procmem_high_watermark=5%`；检查间隔 `sysmon.os.cpu_check_interval=60s` 等。
- **状态机只有 2 态** `activated` / `deactivated`，**无 ACK、无指派、无升级**；去重是**告警项级天然去重**（激活期间不会产生第二条）；**自动恢复**+可手动取消。
- 存储/保留：`alarm.actions=["log","publish"]`、`alarm.size_limit=1000`、`alarm.validity_period=24h`。
- 出口：`$SYS/brokers/<Node>/alarms/activate|deactivate`；规则引擎事件 **`$events/sys/alarm_activated` / `$events/sys/alarm_deactivated`**；**自 5.8.5 起 Dashboard 可配告警 Webhook**，触发器已预选这两个事件。
- 规则引擎形态 = **Data Source(FROM/WHERE) → Data Transformation(SELECT) → Actions（Republish / Console / Sink）**，**只有 `SELECT` 与 `FOREACH` 两种语句**。
- 🔴 **规则 SQL 里没有 `GROUP BY` / `HAVING` / 窗口 / 聚合**（在 syntax 页与内置函数页全文检索 `GROUP BY`/`HAVING`/`window`/`aggregat` 均无匹配）⇒ **自定义遥测没有内置的「连续 N 次越界 / 持续 N 秒」**；只有 sysmon 的滞回 + `check_interval` 是等效物。
  - ⚠️ **未确认**：「必须外接组件补」是基于语法缺失的**推断**，官方没有明文表述，也没有官方计数示例。
- ⚠️ **未确认**：重试/退避/批量参数名；告警是否落关系库；`activate_at` vs `activated_at` 的文档不一致；企业版与开源版的能力边界（所引页面标题为「EMQX 企业版文档」）。
- 与本平台的关系：**本平台已部署 EMQX 作为接入 broker**（见 `docs/EMQX-DEPLOY.md`）。因此 **`$events/sys/alarm_activated` 是一条可用的「broker 自身健康」告警来源**，但它解决的是「EMQX 自己好不好」，**不是**「设备上报的温度超了没」——后者必须由平台侧评估器承担。

### 1.4 Prometheus + Alertmanager —— 通用告警模型（职责切分最干净）

来源（访问日期均 2026-09-27）：[Alerting rules](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)、[Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/)、[Configuration](https://prometheus.io/docs/alerting/latest/configuration/)、[Alerts API](https://prometheus.io/docs/alerting/latest/alerts_api/)、[Overview](https://prometheus.io/docs/alerting/latest/overview/)

- alerting rules 字段：`expr`（PromQL，向量元素存在即 active）、`for`（等待时长，期间须保持 active，未满为 **pending**；无 `for` 则首次求值即 active）、`keep_firing_for`（条件最后一次满足后再保持 firing 一段；官方明确用于防 flapping 与「缺数据导致的假恢复」）、`labels`、`annotations`；合成序列 `ALERTS{alertname=..., alertstate="pending|firing"}`。
- Alertmanager 的职责：**去重、分组、路由 + 静默（Silences）、抑制（Inhibition）**。
  - 路由字段：`receiver` / `group_by`（`'...'` = 按所有标签分组即禁用聚合）/ `continue` / `matchers` / `labels` / `mute_time_intervals` / `active_time_intervals` / `group_wait`（默认 30s）/ `group_interval`（默认 5m）/ `repeat_interval`（默认 4h，且**向上取整到 `group_interval` 的整数倍**）。
  - Inhibitions 用 `source_matchers` / `target_matchers` / `equal`；**Silences 在 Web 界面配置**（基于 matchers）。
- 去重/恢复是**机制级**明确的：**「Labels are used to deduplicate identical instances of the same alert」**；`endsAt` 省略 = 当前时间 + `resolve_timeout`；**`endsAt` 已过去即视为 resolved**；因为 **Alertmanager 无状态**，客户端必须持续重发 firing 告警、并在恢复后**继续重发已恢复告警最多 5 分钟**。
- ⚠️ **未确认**：`inactive` / `resolved` 不是官方文档里显式写出的状态名（官方只写 `pending`/`firing` + 恢复判定）；Silence 的 API 端点未逐字核对（`management_api` 页 2026-09-27 只有 `health`/`ready`/`reload` 三个端点）；Alertmanager **官方没有 escalation 这个词**，把它映射到「路由树 + `continue` + 时序参数」是归纳而非官方术语。

### 1.5 共性模型提炼（跨四家都能映射的最小集合）

#### 1.5.1 四家共同具备的要素（可当普适做法）

1. **「条件」与「持续/次数」分离表达** —— 四家都把「越界判定」与「要持续多久/多少次才算数」当成**两个独立配置项**（Prometheus 的 `expr` 与 `for`；ThingsBoard 的 `Condition` 与 `Condition type`；阿里云的 `运算符`+`阈值` 与 `持续周期`×`数据聚合周期`）。**EMQX 是唯一例外**（自定义遥测没有这一层）。
2. **去抖/抑制抖动是标配，但机制不同且**不等价**：`for`（时长）、`Duration`/`Repeating`（时长/次数）、`持续周期`（次数）、高/低水位线**滞回**。「持续 N 秒」≠「连续 N 次」≠「滞回」，设计时必须明确选哪一种。
3. **「恢复」普遍与「触发」不对称，且必须显式处理**：ThingsBoard **默认不自动恢复**（不配 `Clear condition` 就永不恢复）；Prometheus 的 `for` 只作用于触发侧、恢复侧另有 `keep_firing_for` 与 `endsAt`；EMQX 自动恢复；阿里云靠「沉默周期 + 告警历史」表达「还在坏」。
4. **通知渠道与「谁收到」是独立配置**（ThingsBoard 的 Recipients/Templates/Rules；Alertmanager 的 receivers + 路由树；阿里云的 `报警通知对象` + 级别绑渠道）。
5. **告警必须落库/可查历史，且「清除 ≠ 删除」**。
6. **都提供程序化入口**（REST / 主题 / OpenAPI）。

#### 1.5.2 各家的不同选择（**设计分歧点，不要当成普适契约**）

| 维度 | ThingsBoard | 阿里云 IoT | EMQX | Prometheus + Alertmanager |
|---|---|---|---|---|
| 状态机几个状态 | **4 态**（Active/ACK × Cleared/UnACK） | 官方**未给出设备告警状态机** | **2 态**（activated/deactivated，无 ACK） | 官方只明确 `pending`/`firing`（恢复判定在 Alertmanager） |
| 去重键 | `originator + type` | 未确认（文档反而提示可能重复收到两条） | 告警项自身 | **labels** |
| 静默（按时间屏蔽） | 文档未见用户可配静默窗口 | `报警沉默周期`＝**重发间隔**，不是屏蔽窗口；`报警生效时间`＝允许检查的时间窗 | 未见 | **Silences**（显式按 matcher 屏蔽一段时间） |
| 抑制/依赖 | 未见（用 propagation 解决「向上可见」） | 未见 | 未见 | **Inhibition** |
| 升级 | **两层**：severity 升级既有告警 + 通知升级链（多阶段+延迟+状态终止条件） | 级别**静态三档**且与渠道硬绑定 | 三级但只是给告警定级 | 路由树 + `continue`（官方无 escalation 一词） |
| 持续时长粒度 | `Duration` 用时长；`Repeating` 用次数 | `持续周期`＝**被检查次数**（1/3/5/10）× 聚合周期（1/5 分钟） | **无**通用持续时长 | `for`/`keep_firing_for` 用时长 |
| 规则放哪一层 | 建议放 **Device/Asset profile** | 产品维度（每个产品独立判断阈值） | 规则全局（可按 namespace 隔离） | rules files 按 group 组织 |
| 持久化 | 关系库 4 张表 + 评论 | 云监控侧「告警历史」 | 未确认落库表 | **无状态**（靠客户端重发） |

#### 1.5.3 对本平台的直接启示

1. **必须分清三件事**：阈值判定（本平台自己算）、去重/静默/通知节流（可借用 Alertmanager 思路但自己实现，因为本平台没有 Alertmanager）、通知投递（复用 `sys_message` + 邮件）。
2. **「持续/次数」与「恢复条件」都要显式配置**，不能默认「自动恢复」了事 —— 默认不恢复（ThingsBoard）比默认恢复**更安全**：漏报的代价通常高于误报。
3. **静默 ≠ 重发间隔**（阿里云这两个概念都叫「沉默」，容易混）：本设计把**静默（silence，按时间屏蔽通知）**与**重复通知抑制（repeat_interval）**拆成两个独立配置，并在命名上明确区分。
4. **「清除 ≠ 删除」**：告警恢复后记录必须保留，否则无法回答「上周那台设备到底报警了没有」。

---

## 2. 本平台落地方案

### 2.0 设计约束（来自本仓既有事实，逐条可核对）

| 约束 | 事实来源 | 对本设计的影响 |
|---|---|---|
| 后端 Long 全局序列化成字符串 | `ypbin-starter-json` 的 `JacksonAutoConfiguration`（`Long.TYPE`→`ToStringSerializer`，开关默认 true） | 所有 ID / 时间戳 / 计数在前后端契约里是**字符串**，消费处必须显式转数 |
| 统一 HTTP 200 + `R.code` 区分成败 | 仓内铁律 | 告警接口失败也走 `R.code`；前端三态必须靠 `code` 判定 |
| 枚举必须存 `code` 不是 `ordinal` | 仓内铁律 | 告警状态、级别、比较符、渠道一律 `VARCHAR` 存 code |
| 多租户：含 `tenant_id` 的表必须登记 | `IotTenantIsolationGateTest.M1_TENANT_TABLES`；平台表进 `PLATFORM_TABLES` + nacos `ignore-tables` | 新增告警表必须补进对应清单，否则租户隔离门禁不覆盖它 |
| DDL 双写 + 等价性校验 | `deploy/sql/006-iot-schema.sql` 与 `deploy/sql/migration/<日期>-*.sql` 并存，`tools/check-iot-sql-equivalence.sh` 校验 | 新增告警表要走同一套迁移流程 |
| 已有 5 个 `@Scheduled` 扫描器 | `OutageScanner`(15s) / `CommandTimeoutScanner` / `IotDbRowCountProbe`(约 10min) / `RetentionCleanupServiceImpl` / `LeaseExpiryScanner` | 「独立评估器」不是新范式，是**沿用既有范式** |
| `outage_event` 已是一个「事件状态机」 | `start_ts` / `end_ts`(NULL=进行中) / `duration_sec` / `reason` code；`device_liveness.open_outage_id` 指进行中事件 | 告警实例表**直接照这个形态设计**，风格一致、可复用既有查询与前端展示习惯 |
| `maintenance_window` 已存在 | `device_id` NULL=整租户、`start_ts`/`end_ts`、`source`(MANUAL/LEASE_HANDOVER) | **静默期直接复用它**，不新造概念 |
| `iot_event_log` 有幂等键 | `uk_iot_event_log_idem(tenant_id, device_id, idempotent_key)` | 告警实例的「同键去重」照此办理 |
| 时序库读失败**抛异常**而非返回空 | `TimeSeriesQueryService.query`：`if (!store.available()) throw new BusinessException("历史时序查询未启用…")` | **评估器绝不能把「查询异常」当成「不越界」**（§2.2.4） |
| 点位值在时序库里是**字符串** | `TimeSeriesPoint(tenantId, deviceId, propertyId, String value, String quality, long ts)` | 阈值比较必须显式数值化；非数值/坏质量要按「不可判定」处理 |
| Redis 最新值现成可用 | `iot:latest:{tenantId}:{deviceId}` 哈希，field=点位，value=紧凑 JSON；写入器 `RedisLatestValueWriter` | 实时判定读它，成本极低（§2.2.2） |
| 站内信与邮件已有 | `sys_message` + `SysMessageService`；`MailController` + `NoticePublishServiceImpl` | 通知渠道复用，不重造 |
| **没有 webhook 能力** | 全仓检索 `webhook` 在 Java/配置里**无匹配**（只有 EMQX 文档提到） | Webhook 渠道本期不做（§2.4） |
| 前端已有三态纪律与错误边界 | `panel-error-boundary.vue`；`detail.vue` 的 `latestError`/`eventsError` 等 | 告警区必须同样区分「加载中/空/失败」，禁把失败画成「没有告警」 |

### 2.1 ① 规则存哪：表设计

**原则**：作用域四级（租户 / 产品 / 设备 / 点位）+ **最具体者优先**；规则与「点位条件」分两张表（一条规则可含多个点位条件，为将来的复合条件留位置，但本期只启用单点位条件）。

#### 表 A：`iot_alert_rule`（规则主表）

| 字段 | 类型 | 语义与约束 |
|---|---|---|
| `id` | BIGINT | 主键 |
| `tenant_id` | BIGINT | 租户（必填） |
| `rule_name` | VARCHAR(128) | 规则名（展示用；同名不禁止，靠 id 区分） |
| `scope_type` | VARCHAR(16) | 作用域**码**：`TENANT` / `PRODUCT` / `DEVICE` / `POINT` |
| `scope_product_id` | BIGINT NULL | `scope_type=PRODUCT`/`POINT` 时必填 |
| `scope_device_id` | BIGINT NULL | `scope_type=DEVICE`/`POINT` 时必填 |
| `severity` | VARCHAR(16) | 级别**码**：`INFO` / `WARNING` / `CRITICAL`（不照搬 ThingsBoard 的 5 级，够用即可） |
| `enabled` | TINYINT | 启用开关（**停用不删除**，保留历史与解释能力） |
| `trigger_mode` | VARCHAR(16) | 抖动抑制模式**码**：`CONSECUTIVE_COUNT`（连续 N 次）/ `DURATION`（持续 T 秒）/ `IMMEDIATE` |
| `trigger_threshold` | INT | `CONSECUTIVE_COUNT` 的 N（默认 3）；`DURATION` 的秒数；`IMMEDIATE` 时忽略 |
| `pending_ttl_sec` | INT | `pending` 态的**最大挂起时长**：超过则放弃本次候选（防止「一年前越界一次 + 今天越界一次」被拼成「连续 2 次」） |
| `repeat_interval_sec` | INT | **重复通知抑制**：活动期内未恢复时的重发间隔（默认 1800） |
| `silence_start` / `silence_end` | DATETIME NULL | 规则自带的静默窗口（与 `maintenance_window` 取并集，见 §2.3） |
| `notify_channels` | VARCHAR(64) | 渠道码集合，逗号分隔：`INBOX`（站内信）/ `EMAIL` / `WEBHOOK`（本期仅前两个可用） |
| `notify_targets` | VARCHAR(512) NULL | 收件人（用户 id 列表 / 邮箱列表）；NULL = 规则创建者 |
| `description` | VARCHAR(512) NULL | 说明（人会读的那一句） |
| 审计与逻辑删除 | — | `create_user/create_time/update_user/update_time/status/is_deleted`（与仓内所有表一致） |

**索引建议**：`(tenant_id, enabled, scope_type)`；`(tenant_id, scope_device_id)`；`(tenant_id, scope_product_id)`。

#### 表 B：`iot_alert_rule_point`（规则的点位条件行）

| 字段 | 类型 | 语义与约束 |
|---|---|---|
| `id` | BIGINT | 主键 |
| `tenant_id` | BIGINT | 租户 |
| `rule_id` | BIGINT | 指向 `iot_alert_rule.id` |
| `property_id` | VARCHAR(64) | 点位（属性标识/历史主键字符串形态，**与既有 `PointMappingIndex` 解析口径一致**——本平台点位存在过渡期双形态，见 `TimeSeriesQueryService.resolveCoordinateForms`） |
| `operator` | VARCHAR(8) | 比较符**码**：`GT` / `GTE` / `LT` / `LTE` / `EQ` / `NE` |
| `threshold` | DECIMAL(24,6) | 阈值（用 DECIMAL 而非 DOUBLE：阈值是**配置**，不能有二进制浮点误差） |
| `value_type` | VARCHAR(16) | `NUMERIC` / `BOOLEAN`（布尔点位的「等于 1」语义单列，避免把 true 当 1 的隐式转换散落各处） |
| `deadband` | DECIMAL(24,6) NULL | 回差（滞回）：恢复门槛比触发门槛**往回退**这么多，防止在阈值附近抖动（**借鉴 EMQX 的 high/low watermark 滞回**） |

**作用域优先级**（同一设备同时命中多条规则时）：
`POINT`（具体到点位）> `DEVICE` > `PRODUCT` > `TENANT`。**同优先级的多条规则全部生效**（不做「只取一条」的隐藏覆盖），因为它们可能配的是不同点位或不同级别。

> **为什么要 `scope_type` 而不是「设备/产品/点位三张关联表 + 优先级」**：本平台设备量与规则量都不大（见 §2.2.5 的量级估算），单表 + 作用域字段更容易做门禁与租户过滤，也更容易在页面上一次查全。这是**取舍**，代价是作用域字段存在「组合非法」的可能（如 `scope_type=DEVICE` 却填了 `scope_product_id`）——这类必须由**服务层校验 + 用例**兜住，而不是靠类型系统。

#### 表 C：`iot_alert_instance`（告警实例 / 状态机的载体）

> **直接照 `outage_event` 的形态设计**，保持仓内一致：`end_ts IS NULL` = 仍在进行中。

| 字段 | 类型 | 语义与约束 |
|---|---|---|
| `id` | BIGINT | 主键 |
| `tenant_id` | BIGINT | 租户 |
| `rule_id` | BIGINT | 触发它的规则（规则改名/改阈值**不影响**已产生的实例） |
| `device_id` | BIGINT | 设备 |
| `property_id` | VARCHAR(64) NULL | 点位（设备级/产品级规则触发的实例此处为空） |
| `dedup_key` | VARCHAR(191) | **去重键**：`rule_id + device_id + property_id` 规范化后的字符串（历史留痕用，**不**进唯一索引） |
| `active_dedup_key` | VARCHAR(191) NULL | **只在活动期间非 NULL**（= `dedup_key` 的值）；恢复时置 NULL。它与 `tenant_id` 组成 `uk_alert_active`，实现「同键至多一条活动告警」——**注意不能把 `resolved_ts` 放进唯一索引**，理由见本表下方说明 |
| `severity` | VARCHAR(16) | **冗余存下触发时的级别**（规则后来改了级别，历史实例的级别不能被改写） |
| `state` | VARCHAR(16) | 状态**码**：`PENDING` / `FIRING` / `ACKED` / `RESOLVED`（**4 态**；`SUPPRESSED` 不是状态，见 §2.3） |
| `trigger_value` | VARCHAR(64) NULL | 触发时读到的**原始值**（字符串，原样存；用于「当时到底是多少」） |
| `threshold_snapshot` | VARCHAR(64) NULL | **触发时的阈值快照**（规则改了阈值后，仍能解释当时为何报警） |
| `start_ts` | DATETIME | 首次越界时刻（= `pending` 开始时刻） |
| `firing_ts` | DATETIME NULL | 满足持续条件、正式 `FIRING` 的时刻 |
| `resolved_ts` | DATETIME NULL | 恢复时刻（NULL = 仍活动） |
| `acked_ts` / `acked_by` | DATETIME NULL / BIGINT NULL | 确认时刻与确认人 |
| `last_notified_ts` | DATETIME NULL | 最近一次通知时刻（**重复通知抑制**的唯一依据） |
| `notify_count` | INT | 已通知次数（可观测 + 排查「为什么没再通知」） |
| `reason` | VARCHAR(64) NULL | 恢复/关闭原因码（如 `RECOVERED` / `RULE_DISABLED` / `MANUAL_CLOSE`） |
| 审计与逻辑删除 | — | 同上 |

**去重的库级实现（🔴 这里有一个容易写反的地方，必须写清楚）**

MySQL 的唯一索引对**含 NULL 的行不做约束**——只要索引涉及的列里有任意一个 NULL，该行就被视为「与任何行都不同」。所以：

- ❌ **错误写法**：`uk(tenant_id, dedup_key, resolved_ts)`。活动告警的 `resolved_ts` 正是 `NULL` ⇒ 活动行**不受唯一约束** ⇒ 去重**完全失效**（恰好把要约束的那批行放走了）。
- ✅ **正确写法**：引入一个「**活动标记**」列，让它**只在活动期间非 NULL**：
  - `active_dedup_key` VARCHAR(191) NULL：活动期间 = 规范化后的去重键（与 `dedup_key` 同值）；**一旦恢复（`RESOLVED`）就置 NULL**；
  - `uk_alert_active(tenant_id, active_dedup_key)`：
    - 活动行（非 NULL）**受唯一约束** ⇒ 同租户同键**至多一条活动告警**；
    - 已恢复行（NULL）**不受约束** ⇒ 同一键可以有**多条历史实例**（这正是「清除 ≠ 删除」需要的）。

  这与 ThingsBoard 的 `originator + type` 去重等价，也是本平台**在库级**保证不重复的手段（应用层「先查后插」有竞态窗口，见 `iot_event_log` 的同类批注：并发重复上报只有唯一键兜得住）。

> 替代写法：用生成列 `GENERATED ALWAYS AS (IF(resolved_ts IS NULL, <规范化去重键>, NULL))` 直接参与唯一索引，可以不新增显式列；但**显式列更容易读懂、也更容易写用例**，本设计选显式列。
>
> ⚠️ 该约束的**正确性必须由用例钉住**（含并发插入、以及「恢复后再次越界 ⇒ 允许新建」两个方向）。它依赖数据库对 NULL 的处理，属**隐式契约**：写反了不会有任何编译/类型错误，只会静默失去去重能力——**而失去去重能力的表现是「重复告警刷屏」，不是报错**。

**已在本机实测确认（不是照文档记忆断言）**：用与本项目 compose 同一个镜像 `mysql:8.4`（实测 `mysqld 8.4.11`）在一次性容器里跑了一组最小实验，两个方向都验到了：

| 实验 | 结果 | 结论 |
|---|---|---|
| 错误写法：`uk(tenant_id, dedup_key, resolved_ts)`，连插两条同键、`resolved_ts` 均为 `NULL` | **两条都插入成功**（活动行 = 2） | ❌ 证实该写法**完全不放约束**，去重失效 |
| 正确写法：`uk(tenant_id, active_dedup_key)`，`active_dedup_key='k'` 连插两条 | 第 2 条报 **`ERROR 1062 (23000): Duplicate entry '1-k' for key 'inst.uk_alert_active'`** | ✅ 活动期内**同键至多一条** |
| 把某条 `state` 置 `RESOLVED` 且 `active_dedup_key=NULL`，再插入同键活动行 | **允许**（total=2、active=1） | ✅ 恢复后可重开，历史行不受约束（满足「清除 ≠ 删除」） |
| 恢复后已有一条活动行，再插同键活动行 | 报 `ERROR 1062` | ✅ 重开后仍然只允许一条活动告警 |

> 命令形态（可复现）：`docker run --rm -v <sql>:/t.sql:ro mysql:8.4 sh -c 'mysqld --initialize-insecure --datadir=/d; mysqld --datadir=/d --socket=/d/m.sock --skip-networking & …; mysql --socket=/d/m.sock -uroot < /t.sql'`
> 注意 socket 要放在可写目录（放 `/` 会因 `Could not create unix socket lock file /s.lock` 启动失败）。

**索引建议**：`(tenant_id, device_id, start_ts)`；`(tenant_id, state, start_ts)`；`(tenant_id, severity, state)`。

#### 表 D：`iot_alert_notification`（通知投递记录）

| 字段 | 类型 | 语义与约束 |
|---|---|---|
| `id` | BIGINT | 主键 |
| `tenant_id` | BIGINT | 租户 |
| `instance_id` | BIGINT | 告警实例 |
| `channel` | VARCHAR(16) | `INBOX` / `EMAIL` / `WEBHOOK` |
| `target` | VARCHAR(191) | 收件人标识（用户 id / 邮箱） |
| `event` | VARCHAR(16) | 通知事件码：`FIRING` / `RESOLVED` / `REPEAT` / `ACKED` |
| `status` | VARCHAR(16) | `PENDING` / `SENT` / `FAILED` / `GIVEN_UP` |
| `attempt` | INT | 已尝试次数 |
| `next_retry_ts` | DATETIME NULL | 下次重试时刻（退避） |
| `last_error` | VARCHAR(512) NULL | 最近一次错误（**原样记录**，不吞） |
| `idempotent_key` | VARCHAR(191) | 幂等键（`instance_id + event + channel + target + 轮次`）→ 唯一索引，防重发 |

**为什么通知要单独一张表**：通知是**唯一会出网、会失败、会重试**的环节。把「判定结果」与「投递结果」分开存，才能在通知全挂时仍然看得到告警（**告警本身不能因为邮件发不出去而丢**）。

### 2.2 ② 在哪算：三条路线的取舍

#### 2.2.1 候选路线

| 路线 | 做法 | 代价 |
|---|---|---|
| **A. 复用现有指标** | 用 `iot.timeseries.db.rows` / `iot.ingest.*` / `iot.access.*` 这类**进程内计数器**做阈值告警 | ❌ **不可行**：这些是指标（counter/gauge），不是**设备点位数据**。它们能回答「平台有没有在收数据」，回答不了「3 号设备的温度是多少」。用它们做设备阈值告警是**范畴错误** |
| **B. 直接查 IoTDB** | 评估器每轮对每个 `(设备, 点位)` 调 `TimeSeriesQueryService.query` 取最近点 | ⚠️ **部分可行但代价高**：① 每次查询要建会话/走存储层，按「设备×点位」轮询会放大成 N×M 次查询；② `query` 的语义是「查历史区间」，用它做**秒级最新值判定**是杀鸡用牛刀；③ 它**在库不可用时会抛异常**，评估器必须逐条兜住 |
| **C. 独立评估器 + Redis 最新值** ✅ | 新增 `@Scheduled` 评估器，每轮从 **Redis 最新值哈希**读点位当前值做判定；**IoTDB 只用于**：窗口类规则（持续 T 秒的精确回溯）、以及**事后解释**（「当时那几分钟的曲线长什么样」） | ✅ **推荐**：Redis 最新值是**已经写好、已经在用**的数据（`iot:latest:{tenantId}:{deviceId}`），读取成本极低；评估器与既有 5 个扫描器同范式 |
| **D. 独立评估器 + 独立存储** | 另起一个评估进程/引擎（如 Flink/独立服务） | ❌ **本期不做**：本平台是模块化单体（`ypbin-service/ypbin-iot`），为告警再引入一个运行时是过重的架构动作；且当前数据量不需要 |

#### 2.2.2 推荐：**路线 C** —— 独立评估器，Redis 判定 + IoTDB 回溯

**为什么这是「复用已有能力」而不是「新造一套」**：
- 「周期性扫描 + 幂等处理 + 指标计数」这套范式在本服务里已有 5 处（`OutageScanner` 的 `@Scheduled(fixedDelayString=...)`、`CommandTimeoutScanner`、`IotDbRowCountProbe`、`RetentionCleanupServiceImpl`、`LeaseExpiryScanner`）。评估器是**第 6 个同类**，不引入新机制。
- 判定输入用 Redis 最新值：写入侧 `RedisLatestValueWriter` 已在维护 `iot:latest:{tenantId}:{deviceId}`（field=点位，value=紧凑 JSON，含 `ts`）。评估器只需读它，**不需要任何新的写入链路**。
- IoTDB 只在两种情况用：① `trigger_mode=DURATION` 需要确认「这 T 秒内每个点都越界」；② 页面/通知里要展示「触发前后的曲线」。这两种都是**低频**调用。

**轮询节奏（建议）**：
- 评估器周期**默认 15s**（与 `OutageScanner` 同量级），可配 `ypbin.alert.evaluate-interval-ms`；
- 每轮**只处理「有启用规则覆盖」的设备**（用 `iot_alert_rule` 的作用域反查设备集合），不是全量设备 —— 这是控制代价的关键；
- 单轮设备数上限可配（超出则**跨轮滚动**并计数，不静默丢弃）。

**为什么不做「事件驱动（收到上报就算）」**：
- 事件驱动实时性更好，但会**把评估逻辑塞进上报事务路径**。本仓对上报路径的纪律是「绝不让上报事务回滚」（见 `TimeSeriesWriter` 类注释：Redis/IoTDB 都不参与数据库事务），在热路径里加告警判定会引入新的失败面（例如查规则失败会不会影响入库？）。
- 折中：**评估器读 Redis**（已经被写入侧更新），既能拿到很新的值，又与上报路径解耦。**代价是延迟 = 评估周期（默认 15s）**，对「设备温度超限」这类场景完全够用；对「毫秒级保护动作」不够用 —— 那类需求应走**设备侧/边缘侧**，不是平台侧告警（见 §2.6）。

#### 2.2.3 点位值的比较规则（**最容易出错处**）

`TimeSeriesPoint.value` 是 `String` ⇒ 判定前必须：

1. **显式数值化**：解析失败（非数值、空串）⇒ 本次**不可判定**，**不得**当作 0、**不得**当作越界。计入指标 `iot.alert.evaluate.skipped_non_numeric`。
2. **质量位不是 GOOD 时不可判定**：`quality` 非 good ⇒ 不参与判定（否则一次坏质量会被算成「恢复正常」而错误地 resolve 掉告警）。计入 `iot.alert.evaluate.skipped_bad_quality`。
3. **时间戳陈旧不可判定**：点位 `ts` 距当前超过 `staleness_ttl`（建议默认 = 3× 该点位采集周期，取不到周期则用全局默认）⇒ 视为**陈旧**，不参与判定。**并且**：陈旧**不等于**恢复 —— 「设备不报数了」应当由**设备离线/断档告警**（另一类规则，本期不做）表达，而不是让「温度告警」自动恢复。计入 `iot.alert.evaluate.skipped_stale`。
4. **布尔点位的 `EQ`**：按 `value_type=BOOLEAN` 走显式布尔解析（`true/false/1/0`），与数值路径分开。

> 这四条的共同原则：**「不可判定」是一个独立结果，必须与「未越界」区分开**，并且**必须可观测**（有指标）。若把它并入「未越界」，就会得到「平台静默漏报且无人知道」——这正是本仓在时序查询上已经坚持的纪律（`query` 在库不可用时抛异常而不是返回空列表，注释原话：*「空列表会被读成『这段时间没数据』，与『时序库没启用』是两回事」*）。

#### 2.2.4 「读不到」绝不能被当成「不越界」

评估器对每一类失败的处理必须**显式**：

| 失败 | 处理 | 指标 |
|---|---|---|
| Redis 读失败 / 连接不可用 | 本轮**跳过判定**（不产生 resolve、不产生触发），记录 ERROR 全堆栈，置「评估器不健康」 | `iot.alert.evaluate.redis_failed` |
| 规则查询失败 | 本轮**整体放弃**（宁可不判，也不按空规则集判成「全部恢复」） | `iot.alert.evaluate.rule_load_failed` |
| IoTDB 查询失败（窗口类规则） | 该规则本轮的窗口判定**不可求值** ⇒ **不改状态、不发通知**，计数 | `iot.alert.evaluate.timeseries_failed` |
| 判定成功但值不可用 | 见 §2.2.3 | `...skipped_*` |

**明确的「不做什么」**：本轮失败时**不得**把 `FIRING` 改成 `RESOLVED`。换句话说：**告警的恢复只能由「读到合格的、未越界的值」驱动**，不能由「读不到」驱动。这一条必须在用例里双向钉住（读到未越界 ⇒ resolve；读失败 ⇒ 保持 firing）。

**并且这个不健康状态必须能被人看见**：评估器自身的心跳/滞后也要有指标（`iot.alert.evaluate.last_success_ts`、`iot.alert.evaluate.round.duration`、`iot.alert.evaluate.lag`），接入既有的 L1/L2 链路健康判据体系（`deploy/PROD-OPS-NOTES.md` 的 L1/L2/L3 + `deploy/access-tcp-simulator.md`）。**否则「评估器悄悄死了」就是又一次「静默零写入」**——那个坑本仓已经踩过一次（见 `docs/PROD-OPS-NOTES.md` 第 6 条「采集链路活性」）。

#### 2.2.5 代价评估（量级）

| 项 | 估算 | 依据/假设 |
|---|---|---|
| 规则数 | 百 ~ 千级 | 单租户几十条；租户数十 |
| 每轮需扫描的设备 | 只含「被启用规则覆盖」的设备 | 若无规则则**零成本** |
| Redis 读取 | 每设备 1 次 HGETALL | 已是既有 key，无新写入 |
| IoTDB 查询 | 仅窗口类规则 + 页面回溯 | 低频 |
| DB 写入 | **仅状态变化时**写 `iot_alert_instance`；通知按事件写 | 稳态下几乎为零 |
| 周期 15s 的代价 | 与 `OutageScanner` 同量级（本机低配已验证既有扫描器可用） | — |

> 上述为**量级估算**，不是实测；实施时应用真实租户数据校准（见 §3.5）。

### 2.3 ③ 状态机

```
                    ┌──────────── 越界（首次）─────────────┐
                    ▼                                      │
              ┌───────────┐  连续 N 次 / 持续 T 秒满足   ┌───┴──────┐
   未越界 ──► │  PENDING  │ ───────────────────────────► │ FIRING   │
              └─────┬─────┘                              └───┬──────┘
                    │ 未满条件即回落（抖动被吸收）             │ 人工确认
                    │ 或 pending_ttl_sec 超时                ▼
                    ▼                                    ┌──────────┐
                 （不产生实例）                            │  ACKED   │ 仍是活动告警
                                                          └───┬──────┘
                                                              │
                    ┌────────── 读到「合格的未越界值」 ────────┘
                    ▼
              ┌────────────┐
              │  RESOLVED  │  终态（保留记录，不删除）
              └────────────┘
```

**状态说明**

| 状态 | 含义 | 能否通知 | 说明 |
|---|---|---|---|
| `PENDING` | 已越界，但**尚未满足**持续条件（连续 N 次 / 持续 T 秒） | **否** | 抖动抑制就发生在这里：PENDING 期间回落 ⇒ 直接丢弃，**用户完全无感**，只计数 |
| `FIRING` | 满足持续条件，**活动告警** | 是（首次立即 + 之后按 `repeat_interval_sec`） | 去重键在此状态生效 |
| `ACKED` | 人工**已确认**，**但仍是活动告警**（未恢复） | 是，但**降级**：不再按 `repeat_interval` 重复打扰，只在**恢复**时通知一次 | 「已确认」表达的是「我看到了，正在处理」，不是「问题没了」——所以**不能**改 state 到 RESOLVED |
| `RESOLVED` | 已恢复 | 是（恢复通知一次） | 终态；记录保留（**清除 ≠ 删除**） |
| ~~`SUPPRESSED`~~ | **不是状态** | — | 静默是**通知侧**的判定（见 §2.4 的渠道/投递模型与 §3.4 的静默判据），与判定/状态机**正交**。把静默做成状态会让「静默中的告警」在页面上显得像「不存在」，且静默结束后状态该回哪个值无法定义 |

**抖动抑制（连续 N 次越界才触发）的具体定义**（必须写成可测判据）：

- 维护一个**内存中的候选计数** `consecutive_count`（键：`dedup_key`），每轮判定后：
  - 读到「合格的越界值」⇒ `consecutive_count += 1`；若 `>= N` ⇒ 建/转 `FIRING`；否则若此前无实例则先建 `PENDING` 实例（或纯内存候选，见下）。
  - 读到「合格的未越界值」⇒ `consecutive_count = 0`；若有 `PENDING` 实例 ⇒ 丢弃（记为「抖动被吸收」，计数 `iot.alert.evaluate.flapped`）。
  - 读到「不可判定」（§2.2.3）⇒ **计数保持不变**（既不增也不清零），且**不改变状态**。⚠️ 这一点必须明确：把「不可判定」当成「未越界」会清零计数（永远凑不满 N）；当成「越界」会虚增计数（误报）。
- **`PENDING` 是否落库**：建议**落库**（便于排查「为什么没触发」与展示「正在越界」），但要靠 `pending_ttl_sec` 兜住「陈旧候选」——超过 TTL 未满足条件即删除/置为放弃。**不落库的纯内存方案**会在评估器重启后丢失计数，导致「每次重启都要重新凑 N 次」；本平台容器会重启（部署流程就是 `docker restart`），故**建议落库**。
- 「连续」的严格语义（缺失一次算不算断）必须以**用例**定义清楚，不能只在文档里含糊（调研中四家官方文档都只给行为描述、不给算法级定义）。

**回差（deadband）**：恢复判定用 `threshold ∓ deadband`（见 §2.1 表 B）。**若不配 deadband**，则恢复条件是「不满足触发条件」；配了则要求「反向越过回差」。这是**防抖的另一半**——只防触发侧抖动、不防恢复侧抖动的话，会在阈值附近产生「触发/恢复/触发」的通知风暴。

**手动关闭**（是否需要）：建议**本期提供**「人工关闭活动告警（`MANUAL_CLOSE`）」，但**要留痕**（写 `reason` + `acked_by`）。理由：误报一定会发生（例如设备临时检修），没有关闭入口时用户唯一的办法是把规则停用，那是**更危险**的操作（会让该规则此后全部失效）。

### 2.4 ④ 通知渠道

#### 渠道清单（本期范围）

| 渠道 | 本期 | 复用/新建 | 说明 |
|---|---|---|---|
| **站内信** | ✅ 做 | **复用** `sys_message` + `SysMessageService` | 已有的、已验证的链路；用户在平台内能看到 |
| **邮件** | ✅ 做 | **复用** `MailController` / `NoticePublishServiceImpl` | 已有 JavaMail 能力 |
| **Webhook** | ❌ 不做 | — | 需要一整套出站治理：**URL 白名单/防 SSRF**（平台侧主动出站到用户给的地址，是本设计里**唯一**的 SSRF 面）、超时、重试退避、签名、限流。且本仓**完全没有** webhook 能力（全仓检索无匹配），属独立课题。**代价与理由写在 §2.6** |

#### 投递模型

1. **写 `iot_alert_notification` 记录（`PENDING`）** —— 与判定在同一事务还是分开？
   **建议：分开（先提交告警状态，再投递）**。理由：告警状态是**事实**，通知是**尽力而为**。若绑在同一事务里，邮件服务慢/挂会让整个评估轮次被拖住甚至回滚 —— 那是「通知影响了告警」。分开后即使通知全挂，告警仍然可见（页面上照样能看到 `FIRING`）。
2. **重试与退避**：每渠道独立重试；建议**最多 3 次**，退避 `30s → 2min → 10min`；超过则置 `GIVEN_UP` 并**记录 `last_error`**（不吞异常、不静默）。
   - 所有远程调用必须**显式配 `connectTimeout` 与 `readTimeout`**（仓内铁律），邮件与将来的 webhook 都不例外。
3. **限流**：两个维度——
   - **单实例**：`repeat_interval_sec` 已经限制了单条告警的通知频率；
   - **全局**：防止「一次大面积故障触发上千条告警把邮件网关打挂」。建议**每渠道每分钟令牌桶上限**（可配，默认如 60/min），超出的通知**置为 `PENDING` 延后投递**（不是丢弃），并计数 `iot.alert.notify.throttled`。
   - **合并（grouping）**：本期**做最小版**——同一设备同一轮触发的多条告警合并成一封邮件/一条站内信。完整的分组路由树（Alertmanager 那套）**本期不做**（§2.6）。
4. **幂等**：`iot_alert_notification.idempotent_key` 唯一索引，防重试导致重复投递（邮件重复发送对用户是明显的体验事故）。

#### 通知内容（人读的那一句）

必须包含：**谁**（设备名 + 点位）、**什么**（`>` 阈值，读到多少）、**什么时候**（首次越界时刻、正式触发时刻）、**级别**、以及**一个能点进去的入口**（设备详情告警区）。同时遵循本仓既有的三态纪律：**通知里的值原样展示原始值**（不美化、不四舍五入到看不出差别）。

### 2.5 ⑤ 与现有页面怎么呈现

#### 2.5.1 设备详情抽屉：新增「告警」页签（第 6 个）

现有抽屉已有 5 个页签（概览 / 属性与点位 / 历史曲线 / 事件与断档 / 在线调试），**新增第 6 个「告警」页签**，落在 `apps/web-antd/src/views/iot/devices/modules/` 下：

- 内容分两块：
  1. **活动告警区**（`FIRING`/`ACKED`）：级别 + 点位 + 当前值 + 阈值 + 持续时间 + 「确认」/「关闭」按钮；
  2. **历史告警区**（`RESOLVED`，分页）：时间段 + 级别筛选 + 恢复时间。
- **必须遵守既有纪律**：
  - **三态**：加载中 / 空态（「该设备暂无告警记录」）/ **失败态原样展示后端 message** —— 禁把失败画成「没有告警」（这正是本轮前端刚收口的问题，见下）；
  - **权限门禁用 `computed + v-if`，不用 `v-access` 指令**：已核实上游 `packages/effects/access/src/directive.ts` 的 `v-access` **只有 `mounted` 没有 `updated`**，且无权限时 `el.remove()` **不可逆** ⇒ 权限码迟到时区块永远回不来。**新页面必须沿用本项目已确立的 `computed + v-if` 写法**；
  - **关键区块套 `panel-error-boundary.vue`**（已有组件），渲染期异常必须**可见**（Alert 展示错误），不得空白；
  - i18n 文案里**不得出现裸 `{`/`}`**（JSON 示例、正则片段都是高发处）——这条现在有 CI 门禁（`scripts/check-i18n-message-compile.mjs`，见 §2.5.3）。
- 概览页签加一行摘要（如「活动告警 2 条」），并在设备台账列表加一列/一个标记。

#### 2.5.2 全局告警列表页

新增一个路由页（`views/iot/alerts/index.vue`），**沿用既有的 vxe 列表页范式**（`devices/index.vue` / `products/index.vue` 的写法）：

- 列：级别 / 设备 / 点位 / 规则 / 当前值 / 阈值 / 状态 / 首次越界 / 持续时长 / 操作（确认、关闭、跳设备详情）；
- 筛选：状态、级别、时间范围、设备/产品；
- **必须区分失败态与空态**（与刚修好的台账页同一口径：`#empty` 插槽里，有失败原因就展示后端 message，没有才给空态引导）；
- **分页 `total/page/pageSize` 是字符串**（后端 Long 全局序列化）⇒ 消费处必须显式转数（用 `#/utils/backend-number.ts` 的 `toBackendNumber`）。**这是本轮刚统一的口径，新页面必须遵守**；
- 权限码建议：`iot:alert:list`（查）、`iot:alert:ack`（确认）、`iot:alert:close`（关闭）、`iot:alert:rule:list|update`（规则管理）；规则管理页可作为独立页或抽屉（**待决策**，见 §5）。

#### 2.5.3 与之配套的既有前端风险（本轮已收口，新页面照办）

本轮（同一时期的前端改动）已把「渲染期才暴露」的同类风险收口，**新增告警页面必须沿用这些做法**，否则会重新引入同类事故：

| 风险 | 已建立的防线 |
|---|---|
| i18n 文案里的裸 `{`/`}` 在**渲染期**抛 `SyntaxError` → 整块白屏 | CI 门禁 `scripts/check-i18n-message-compile.mjs`（全库、zh/en、真实 vue-i18n、逐叶子键编译、空跑自检、变异验证过会指名该键） |
| 后端 Long 序列化成字符串 → 分页 prop 类型告警 / `"0"` 被当真值 | `#/utils/backend-number.ts` 的 `toBackendNumber`（唯一转换点）+ 消费处显式转数 |
| `v-access` 无 `updated` → 权限码迟到时区块永不出现 | 关键区块用 `computed + v-if`（不用指令） |
| 列表加载失败被画成空态 | `#empty` 插槽区分失败态（展示后端 message）与空态 |
| 渲染期异常 → 空白 | `panel-error-boundary.vue` |

### 2.6 ⑥ 不做的部分与理由

| 不做 | 理由 |
|---|---|
| **AI / 机器学习异常检测** | ① 需要历史基线训练与持续回填，本平台的时间序列数据量与历史长度都还没到能做可靠基线的程度；② 结果**不可解释**，而告警的第一诉求是「用户看得懂为什么报警」——平台现在连**确定性阈值告警**都还没有，先补确定性的那一层；③ 误报成本高（告警疲劳比没有告警更糟）。**但**：状态机与实例表的设计**为它留了位置**（`severity` + `reason` + `trigger_value`/`threshold_snapshot`，将来加一个 `rule_type=ANOMALY` 即可，不需要改表结构） |
| **Webhook 通知渠道** | 见 §2.4：这是本设计**唯一**的 SSRF 面（平台主动出站到用户提供的 URL），需要 URL 白名单、DNS 重绑定防护、超时、重试退避、签名、限流一整套；本仓当前无任何 webhook 能力，属**独立课题**，不应塞进首个告警版本 |
| **复合条件（多点位 AND/OR 表达式）** | 规则表达式语言一旦引入就有「解析器 + 校验 + 注入面 + 版本兼容」的成本。本期用「一条规则一个点位条件」的形态；表结构（`iot_alert_rule_point` 是独立行表）已为复合条件留位 |
| **设备离线 / 断档类告警** | 本平台**已经有** `outage_event` + `device_liveness` + `OutageScanner`（15s）在做断档判定，前端「事件与断档」页签已经在展示。**再做一个「离线告警」会造成两套口径**。正确做法是**把既有断档事件接入告警的通知链路**，而不是新造判定——这是**后续增量**，不是本设计的一部分（见 §5 决策 4） |
| **告警通知的完整分组路由树 / 升级链（escalation）** | Alertmanager 那套路由树 + `continue` + 抑制（inhibition）+ Silence（按 matcher）是一个**完整子系统**。本期只做「规则自带的渠道 + 收件人 + 重复抑制 + 最小合并」。升级链（无人确认就升级到上级）**留待**有真实运维流程时再做——没有运维流程的升级链只是噪音 |
| **抑制（inhibition，如「断档」抑制「温度告警」）** | 有价值，但需要先有「哪类告警抑制哪类」的**领域共识**。先做基础的，抑制规则等有两类以上告警后再谈 |
| **毫秒级/设备侧联动保护** | 平台侧评估周期是 15s 量级，**不适用于**要求毫秒级动作的保护场景。那类需求属于设备侧/边缘侧或规则引擎（EMQX 规则 + Sink），平台告警只做「人要看」的那一层。**必须写清楚**，避免用户误以为平台告警能当联锁用 |
| **告警规则的版本化 / 审批流** | 规则量级小、使用者少，本期用「谁改谁负责 + 审计字段」即可 |

---

## 3. 验收口径（可测判据）

> 全部判据都要求**给定输入 ⇒ 确定输出**，且**用变异验证证明用例真能咬人**（把实现改回错误行为 ⇒ 用例必须转红）。这是本仓既有纪律（见全局 AGENTS 教训二十七、教训三十一）。

### 3.1 触发 / 不触发 / 恢复

| # | 给定 | 期望 | 备注 |
|---|---|---|---|
| T1 | 规则 `GT 30`，`trigger_mode=IMMEDIATE`，读到 `35` | 立即建实例并 `FIRING` | 基线 |
| T2 | 规则 `GT 30`，读到 `30` | **不触发**（`GT` 是严格大于） | 边界值必须钉住 |
| T3 | 规则 `GTE 30`，读到 `30` | 触发 | 边界值必须钉住 |
| T4 | 规则 `GT 30`，读到 `abc` | **不可判定**：不触发、不恢复、计数 `skipped_non_numeric` | §2.2.3 |
| T5 | 规则 `GT 30`，读到 `35` 且 `quality != good` | 不可判定，计数 `skipped_bad_quality` | §2.2.3 |
| T6 | 规则 `GT 30`，读到 `35` 但 `ts` 超过陈旧阈值 | 不可判定，计数 `skipped_stale`，**且不得把已有 FIRING 改成 RESOLVED** | §2.2.3/2.2.4 |
| T7 | `FIRING` 中，读到 `25`（未越界、合格） | 转 `RESOLVED`，`resolved_ts` 落库，发恢复通知一次 | 恢复只能由合格值驱动 |
| T8 | `FIRING` 中，Redis 读失败 | **保持 `FIRING`**，不发通知，计数 `redis_failed` | §2.2.4 关键判据 |
| T9 | `FIRING` 中，IoTDB 查询失败（窗口类规则） | **保持 `FIRING`**，计数 `timeseries_failed` | §2.2.4 |
| T10 | 规则查询失败 | 本轮**整体不判定**：不产生任何触发、**不产生任何恢复** | §2.2.4 |

### 3.2 抖动抑制

| # | 给定 | 期望 |
|---|---|---|
| D1 | `trigger_mode=CONSECUTIVE_COUNT`, `N=3`；序列 `35, 35` | 停在 `PENDING`，**未**触发，未通知 |
| D2 | 上接 D1，再来一个 `35`（连续第 3 次） | 转 `FIRING`，通知一次 |
| D3 | 序列 `35, 25, 35, 35`（中间回落一次） | 计数被清零；第 4 次时计数为 2 ⇒ 仍 `PENDING`，**未触发** |
| D4 | 序列 `35, 35, abc, 35` | 计数在 `abc` 那轮**保持不变**（仍为 2）⇒ 第 4 轮变成 3 ⇒ **触发** | 变异点：若把 `abc` 当「未越界」，计数归零 ⇒ 用例必须转红 |
| D5 | `N=3`，首轮 `35` 后**停发数据**（点位陈旧） | 超 `pending_ttl_sec` 后候选被放弃；**永不触发**，且不得残留「半个候选」 |
| D6 | `trigger_mode=DURATION`, `T=60s`，`35` 持续 45s 后回落 | 未触发（未满 60s），且候选被丢弃 |
| D7 | `trigger_mode=DURATION`, `T=60s`，`35` 持续 75s | 触发；触发时刻取**满足 60s 那一刻**（±1 个评估周期），不是发现时刻 |
| D8 | 抖动被吸收（`PENDING` 期间回落） | **不产生用户可见通知**；计数 `iot.alert.evaluate.flapped` 增加 |

### 3.3 去重

| # | 给定 | 期望 |
|---|---|---|
| U1 | 同规则同设备同点位已 `FIRING`，再次判定越界 | **不新建实例**（`active_dedup_key` 命中），只更新当前值并（按 `repeat_interval`）决定是否重发 |
| U2 | 并发两轮同时判定同一去重键越界 | **只允许一条活动实例**（`uk_alert_active` 兜住）→ 必须有用例覆盖并发插入 |
| U3 | 已 `RESOLVED` 后再次越界 | **允许**新建一条实例（恢复时 `active_dedup_key` 已置 NULL ⇒ 不再占用唯一键）→ 用例必须同时验证「恢复后可重开」与「活动期内不可重复」**两个方向** |
| U4 | 规则后来改了阈值 | 不影响已有实例（实例上存了 `threshold_snapshot`）；只影响此后新建的实例 |
| U5 | 🔴 **变异验证（去重写反的哨兵）**：把唯一索引写成含 `resolved_ts` 的形态（即那个错误写法） | 用例必须**转红**——否则说明去重没有任何用例真正守住。**失去去重的表现是「重复告警刷屏」，不会报错**，只有用例能拦住 |

### 3.4 静默与重复通知抑制

| # | 给定 | 期望 |
|---|---|---|
| S1 | 规则静默窗口内触发 | 实例照常建、状态照常 `FIRING`（**判定不受静默影响**），但**不发任何通知**；计数 `iot.alert.notify.silenced` |
| S2 | `maintenance_window` 覆盖该设备且窗口进行中触发 | 同上（维护窗口 = 静默） |
| S3 | 静默窗口结束，告警仍活动 | **不补发**历史通知；但若距 `last_notified_ts` 已超 `repeat_interval_sec`，则本轮可以通知 | 避免静默结束瞬间的通知风暴 |
| S4 | `FIRING` 持续 2 小时，`repeat_interval_sec=1800` | 通知次数 = 1（首次）+ 3（重发）± 1（取决于边界对齐）；`notify_count` 与之一致 |
| S5 | `ACKED` 之后仍 `FIRING` | **不再按 `repeat_interval` 重复通知**；仅恢复时通知一次 |
| S6 | 通知渠道失败（如邮件网关 500） | 记录 `FAILED` + `last_error`；按退避重试；3 次后 `GIVEN_UP`；**告警状态不受影响** |
| S7 | 全局限流触发 | 超出部分通知置 `PENDING` **延后**（不丢弃），计数 `throttled` |

### 3.5 非功能判据

| # | 判据 |
|---|---|
| N1 | **评估器活性可观测**：`iot.alert.evaluate.last_success_ts` / `round.duration` / `lag` 三个指标存在且在 actuator 可读（接入既有 L1/L2 判据体系） |
| N2 | **「不可判定」可见**：`skipped_non_numeric` / `skipped_bad_quality` / `skipped_stale` / `redis_failed` / `rule_load_failed` / `timeseries_failed` 六类计数都在 actuator 可读 |
| N3 | **规则作用域组合非法时明确报错**（不静默按「最宽」处理）：如 `scope_type=DEVICE` 却填了 `scope_product_id` ⇒ 明确失败 |
| N4 | **租户隔离**：跨租户读取/写入告警规则与实例必须被拒绝；新表已补进 `IotTenantIsolationGateTest` 的清单（否则清单式门禁不覆盖它） |
| N5 | **多租户下的作用域解析**：`TENANT` 作用域规则只能命中本租户设备（用例必须含两个租户的对照） |
| N6 | **无 N+1**：一轮评估内的 DB 查询次数不随设备数线性增长（批量取规则与实例） |
| N7 | **量级校准**：用真实租户数据实测一轮评估的耗时与 DB 压力，据此定周期与单轮上限（**当前只是估算**，未实测） |

### 3.6 迁移与回滚

- **迁移**：
  1. 新增 4 张表 + 权限码（`sys_menu` 按钮级）；
  2. DDL 必须**双写**：`deploy/sql/006-iot-schema.sql` 追加 + `deploy/sql/migration/<日期>-iot-alert-schema.sql`（文件名日期必须满足既有排序约束，见 `007-iot-data.sql` 里同类批注），并用 `tools/check-iot-sql-equivalence.sh` 校验等价；
  3. 菜单/权限码进 `007` 风格的 data 迁移 + 配套 rollback 脚本（照 `deploy/sql/rollback/` 既有形态）；
  4. 新表补进 `IotTenantIsolationGateTest` 的 `M1_TENANT_TABLES`（若含 `tenant_id`）；**不得**进 nacos `ignore-tables`（含 `tenant_id` 的表进 ignore-tables 会被反向门禁 `NacosTenantIgnoreConfigTest` 拦下）。
- **回滚**：
  1. **DDL 回滚脚本**（建表可回滚；**但告警数据一旦产生就有价值**，回滚脚本应**默认只回滚表结构、不删数据**，或明确要求先导出）；
  2. **功能级开关**：`ypbin.alert.enabled=false` ⇒ 评估器不启动、接口返回明确「未启用」（**返回失败而不是空列表**，与 `TimeSeriesQueryService` 的既有口径一致：空列表会被读成「没有告警」，那是最危险的一种假阴性）；
  3. **停用规则** ≠ 删除规则：**默认动作是 `enabled=0`**，保留实例与历史；
  4. **回滚不改动既有能力**：本设计**不修改** `outage_event` / `device_liveness` / `maintenance_window` 的现有语义（只**读**维护窗口做静默），因此回滚面是**新增物**，不影响既有链路。

---

## 4. 与既有能力的复用关系（一览）

| 需要的东西 | 复用谁 | 是否新建 |
|---|---|---|
| 周期性扫描范式 | `OutageScanner` / `CommandTimeoutScanner` / `IotDbRowCountProbe` / `RetentionCleanupServiceImpl` / `LeaseExpiryScanner` | 沿用（第 6 个同类） |
| 「进行中事件」状态机形态 | `outage_event`（`end_ts IS NULL` = 进行中）+ `device_liveness.open_outage_id` | 照此设计 |
| 幂等/去重 | `iot_event_log.uk_iot_event_log_idem` 的形态 | 照此设计 |
| 静默窗口 | `maintenance_window`（含 `device_id IS NULL` = 整租户） | **直接复用（只读）** |
| 点位当前值 | Redis `iot:latest:{tenantId}:{deviceId}`（`RedisLatestValueWriter`） | **直接复用** |
| 历史回溯 | `TimeSeriesQueryService.query` / `IotDbTimeSeriesStore` | **直接复用** |
| 站内信 | `sys_message` + `SysMessageService` | **直接复用** |
| 邮件 | `MailController` / `NoticePublishServiceImpl` | **直接复用** |
| 前端列表页范式 | `devices/index.vue` / `products/index.vue`（vxe + 失败态区分 + total 转数） | 照此实现 |
| 前端错误边界 | `panel-error-boundary.vue` | **直接复用** |
| 前端权限门禁 | `computed + v-if`（**不用** `v-access`，因上游只有 `mounted`） | 照此实现 |
| 前端 i18n 门禁 | `scripts/check-i18n-message-compile.mjs` + `check-iot-i18n-keys.mjs` | **直接复用** |

---

## 5. 需要用户决策的点（**请勿由实现者代为拍板**）

> 以下 7 项都涉及产品形态、代价或对外承诺，实现者不应自行决定。每项给出**选项 + 代价**，供用户裁定。

**决策 1：告警的触发到通知，可接受的延迟是多少？**
- 选项 A：**15s 量级**（推荐）——沿用 `OutageScanner` 同量级的周期扫描，实现简单、代价低。
- 选项 B：**1~2s 量级**——需要事件驱动（在 Redis 最新值写入侧挂钩子）或缩短周期；代价：评估逻辑靠近上报热路径，失败面变大，DB 压力上升。
- 选项 C：**设备侧动作**——不属平台告警范畴。
- 影响：直接决定架构（扫描器 vs 事件驱动），是**其余设计的前提**。

**决策 2：首次落地覆盖哪些规则类型？**
- 选项 A：**仅点位数值阈值**（推荐）——最小可用，语义最清楚。
- 选项 B：阈值 + **把既有断档事件接入通知**（`outage_event` 已在判定，只缺通知）——增量小、价值高（用户最关心的可能就是「设备掉线了告诉我」）。
- 选项 C：再加复合条件 / 布尔点位 / 越界时长聚合。
- 影响：范围与工期。

**决策 3：通知渠道本期做到哪一步？**
- 选项 A：**站内信 + 邮件**（推荐）——两者都是已有能力。
- 选项 B：再加 **Webhook**——需要额外做 URL 白名单/防 SSRF/超时/重试/签名/限流（本设计唯一出站安全面）。
- 影响：安全评审范围与工作量。

**决策 4：设备离线/断档要不要并入告警？**（与决策 2-B 相关但独立）
- 选项 A：**并入**——把 `outage_event` 的新建/恢复接入通知链路（**不新造判定**，避免两套口径）。
- 选项 B：**不并**——断档继续只在「事件与断档」页签展示。
- 影响：用户观感差异很大（「掉线没人告诉我」是常见抱怨），但会牵动既有链路。

**决策 5：是否需要「人工确认（ACK）」与「人工关闭」？**
- 选项 A：**都要**（推荐）——ACK 抑制重复打扰；关闭用于处理误报（否则用户会去停用规则，那更危险）。
- 选项 B：只要 ACK。
- 选项 C：都不要（保持最简，但误报无出口）。
- 影响：状态机复杂度与页面操作项。

**决策 6：规则的作用域粒度与「谁能配」？**
- 选项 A：**租户/产品/设备/点位四级，平台管理员与租户管理员都可配本租户规则**（推荐）。
- 选项 B：仅平台管理员可配（租户只读）——治理简单，但租户无法自助。
- 选项 C：只做设备/点位两级（不引入产品级继承）。
- 影响：权限码设计与页面形态。

**决策 7：告警数据的保留期限？**
- 选项 A：**与既有保留策略对齐**（本仓已有 `RetentionCleanupServiceImpl` 做时序数据保留，建议告警实例保留更久，如 180 天，因为告警是**审计证据**而时序是**观测数据**）。
- 选项 B：长期保留（不清理）——存储增长。
- 选项 C：固定 N 天后删除。
- 影响：清理任务与合规口径。**注**：`iot_alert_notification` 的 `last_error` / 投递日志增长最快，可能需要更短的保留期（**待定**）。

**另有一项需要用户知晓但不必决策的事实**（R8）：
- 现有 `docs/IOT-ROADMAP.md` 的 S5/S7/N-2/G1 等条目都在处理**接入链路的可靠性**，与本设计**没有冲突**；但「评估器自身健康」必须有独立指标并接入既有 L1/L2 判据体系，否则会重演「采集链路静默零写入」那类**无人知晓的静默失效**（`docs/PROD-OPS-NOTES.md` 第 6 条）。

---

## 6. 未确认项（不编造，逐条列出）

**关于本平台（一手核对范围内已确认，以下是**未**核对的）**

1. **未实测量级**：一轮评估在真实租户数据下的耗时、DB 压力、Redis 负载，本文 §2.2.5 全部是**估算**，不是实测。
2. **未确认** `sys_message` 是否支持「按级别/来源分组」的站内信展示；若不支持，告警站内信可能需要在消息体里自带级别前缀。
3. **未确认**邮件模块的**发件频率上限**与是否已有全局节流；若没有，§2.4 的令牌桶可能要与邮件模块一起改（那会扩大改动面）。
4. **未确认** `PointMappingIndex` 在「同一属性存在多个历史存储形态」时对**告警点位标识**的规范化口径是否需要额外处理（`TimeSeriesQueryService` 有过渡期双形态兼容逻辑，判定侧要不要同样兼容**待定**）。
5. **未确认** IoTDB 是否适合承担 `trigger_mode=DURATION` 的「这 T 秒内每个点都越界」查询代价（可能需要按点位拉取区间再本地判定，代价与点位采集频率成正比）。
6. **未确认** 评估器是否需要在多实例部署下加**分布式锁 / 单例执行**（本平台目前 `ypbin-iot` 是单实例；若将来多副本，重复评估会导致重复通知——`iot_alert_notification` 的幂等键能兜住重复投递，但状态迁移仍需要锁）。

**关于调研（四家的官方文档层面，原文见 §1 与 `IOT-ALARM-RESEARCH.md`）**

7. **ThingsBoard**：告警**规则定义**存哪张表/哪个列**未确认**（只确认了实例落 `alarm` 等 4 张表）；文档版本与产品版本的对应关系未确认（所引为不带版本号的最新文档）。
8. **阿里云 IoT**：**设备属性级**阈值告警的**比较运算符与持续时长字段未确认**（官方页只写「支持属性、事件的简单规则」）；MNS / 站内消息 / Webhook 作为告警接收端**未确认**；多篇 `/zh/iot/user-guide/*` 页面标题带「文档停止维护」。**特别提醒**：常见被当作「阿里云 IoT 阈值告警」引用的那两篇，实际是**云监控的实例/产品指标规则**，维度不同、不可直接对标。
9. **EMQX**：规则 SQL **无** `GROUP BY`/`HAVING`/窗口/聚合（语法页与内置函数页全文检索无匹配），因此**没有**内置的「连续 N 次/持续 N 秒」算子——但**「必须外接组件补」是我的推断，官方无明文**；告警是否落关系库**未确认**；重试/退避参数名**未确认**；`activate_at` vs `activated_at` 文档不一致；所引页面标题为「EMQX **企业版**文档」，社区版能力边界**未逐一确认**。
10. **Prometheus / Alertmanager**：`inactive` / `resolved` **不是**官方显式状态名（官方只写 `pending`/`firing` + 恢复判定），本文使用它们时已标注为归纳；**Silence 的 API 端点未逐字核对**（`management_api` 页 2026-09-27 只有 `health`/`ready`/`reload` 三个端点）；`resolve_timeout` 默认值未逐字确认；**官方没有 escalation 这个词**，本文把它映射到「路由树 + `continue` + 时序参数」是**归纳**。
11. **通用**：「连续 N 次」在**数据稀疏/乱序**时的确切判定规则，四家官方文档都只给行为描述、**不给算法级定义**（例如「中间缺失一次是否清零」）。因此 §2.3 对「不可判定」的处理（**计数保持不变**）是本平台的**自行定义**，必须由用例钉死，不能声称「与某家一致」。

---

## 附：本文证据强度声明

- §1 全部结论来自**官方文档**（访问日期 2026-09-27）或官方仓库源码（ThingsBoard 的 DDL 与枚举），**未使用**非官方博客/自媒体。
- §2 的「本平台既有事实」全部来自**本机一手核对**（源码 / DDL / 配置 / 文档），逐条标注了来源文件或类名，可复现。
- §2.2.5 / §3.5-N7 的量级数字是**估算**，已在正文明确标注，未伪装成实测。
- 凡官方文档未能确认的字段名与行为，一律进 §6，**没有**用「看起来合理」的字段名填充。
