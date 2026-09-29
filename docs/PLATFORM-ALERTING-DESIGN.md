# 平台自告警 + 指标大盘设计（看板 #10 / Q7·A12·C4）

> **状态**：📝 设计稿（未实施）
> **建立日期**：2026-09-30
> **依据**：`docs/PLATFORM-GAP-REPORT-2026-09-28.md:41,147,191,272`、`docs/IOT-ROADMAP.md:476,413,552`、`docs/ALERTING-DESIGN.md:699-711`
> **实现前置**：本文评审通过后按 §7 分批实施

---

## 0. 结论先行（TL;DR）

1. **本能力要解决的不是"缺一个监控页"，而是"平台自己坏了没人知道"。** 现有告警引擎监控的是**设备数据**（温度超限等）；平台自身（评估器卡住、通知投递失败、入站丢点位）**没有任何告警**，只能靠人盯日志。
2. **不复用既有告警引擎的评估与状态机**——这不是偷懒与否的问题，而是它**结构上装不下**：`iot_alert_instance` 以 `ruleId` + `deviceId` + `propertyId` 为键，而平台健康规则**没有设备也没有点位**。硬塞会污染设备告警的语义与查询。
3. **一期只做"平台健康规则 + 复用既有通知通道"**：新增一个**独立的、纯函数可测的判定器**，把判定结果落成**平台侧告警记录**，走**已有的**通知投递链路（`AlertNotifyDispatchService` 那条）。**不新建投递通道、不新建大盘前端组件**（大盘复用既有 `/actuator/metrics`）。
4. ⚠️ **一期不承诺"接 Grafana/Prometheus"**：仓内已有 `/actuator/metrics` 暴露（`deploy/nacos/ypbin-iot.yaml` 的 management 段，含明确的安全边界注释）。**是否引入外部大盘栈属独立决策**，不在本设计范围（§2.2 非目标）。

---

## 1. 现状盘点（一手代码）

### 1.1 已经有的（**可复用**）

| 资产 | 位置 | 说明 |
|---|---|---|
| 指标产出 | `AlertMetrics` 等 | 已产出 `iot.alert.*`（rounds/lag/round.failed/notify.failed/…）、`iot.ingest.*`（propertyid.unmapped/orphan/rejected、latest.failed/regressed）、`iot.timeseries.*`、`iot.access.*` |
| 指标暴露 | `deploy/nacos/ypbin-iot.yaml` management 段 | `include: health,metrics,info`（**只读最小集**，刻意不用 `*`）；`health.show-details=never`；**18084 只绑回环**。安全边界已在配置注释中写明 |
| 通知投递 | `AlertNotifyDispatchService` / `AlertNotifyScheduler` / `SystemAlertNotificationSender` | 已有的"入队 → 投递 → 重试 → 放弃"链路，支持 `INBOX`/`EMAIL` 双通道 |
| 调度范式 | 9 个 `@Scheduled`（`AlertEvaluator` 等） | `fixedDelay` + 具名可配常量（`ypbin.alert.*-interval-ms`）；线程池已配 4 |
| 通知模板/渠道 | `AlertNotificationComposer` + `AlertChannel` | 已有"人话"组装与渠道枚举 |

### 1.2 缺口（**这才是 #10 要补的**）

| 缺口 | 后果 |
|---|---|
| **无平台健康规则** | 平台自己坏了（评估器停摆、投递全失败、入站大量丢点位）**没有任何告警** |
| **无平台侧告警记录** | 即使发现了，也无处留痕、无法看"什么时候开始的、恢复了没有" |
| **指标无趋势视图** | `/actuator/metrics` 是**瞬时快照**，值班看不到"是不是在持续恶化" |

### 1.3 🔴 结构约束（**决定了设计形态**）

`iot_alert_instance` 的键是 `ruleId` + `deviceId` + `propertyId` + `dedupKey`
（见 `IotAlertInstance` 实体与 `iot_alert_instance` DDL）。而平台健康规则的判定对象是
**平台自身**（没有设备、没有点位）⇒ **无法直接复用该表**：
- 塞 `deviceId = null` 会让既有"按设备查告警"的索引与语义失效；
- 复用 `ruleId` 会让"规则"这个概念同时表达两种完全不同的事物。

⇒ **一期新增独立的平台告警表**（§3.1），与设备告警**物理分离**，避免污染既有语义。

### 1.4 ⚠️ 未核实项（实施前须实测放行）

- **`/actuator/metrics` 在生产的真实可用性**：仓内注释称"默认不暴露"已通过配置解决，
  但**未实测**在容器内 `curl 127.0.0.1:18084/actuator/metrics` 能否读到**真实进程值**
  （`ALERTING-DESIGN.md:699-711` 登记过"`/actuator/metrics` 未真实进程读值"）⇒ 必须实测。
- **各指标的实际量级与波动**：健康阈值（如"投递失败率 > X%"）**不能凭感觉定** ⇒ 需先观测一段时间。
- **告警通知的系统账号**："平台自告警"该发给谁（租户管理员？平台运维？）**属产品决策** ⇒ §5 列出选项待定。

---

## 2. 目标与非目标

### 2.1 目标

1. **平台健康规则**（一期 4 条，全部基于**已存在**的指标，不新增埋点）：
   | 规则 id | 判据（既有指标） | 阈值初值 | 严重度 |
   |---|---|---|---|
   | `PLATFORM_EVALUATOR_STALLED` | `iot.alert.evaluate.last_success_ts` 距今超过 N 个周期 | 3× 评估周期 | CRITICAL |
   | `PLATFORM_EVALUATOR_LAG` | `iot.alert.evaluate.lag` 超阈值 | 待实测 | WARNING |
   | `PLATFORM_NOTIFY_FAILING` | `iot.alert.notify.failed` 增速 / 失败率 | 待实测 | CRITICAL |
   | `PLATFORM_INGEST_DROPPING` | `iot.ingest.propertyid.unmapped`+`orphan` 增速 | 待实测 | WARNING |
   > ⚠️ **阈值必须实测后定**（§1.4）：表中"待实测"**不得**在实施时用拍脑袋数字填。
2. **判定为纯函数**：`(指标快照, 规则) → 判定结果`，零 IO ⇒ 可穷举单测（与 #8 的 `TraceAdviceResolver`、#7 的做法一致）。
3. **落痕 + 复用通知**：判定结果写入平台告警表（含 `PENDING→FIRING→RESOLVED` 三态，**ACKED 一期不做**——平台告警的"确认"主要是值班动作，二期再谈），通知走**既有**投递链路。
4. **大盘复用既有端点**：不新建前端大盘组件；文档给出 `/actuator/metrics/*` 的**取数清单**与安全边界说明。

### 2.2 非目标（**刻意不做**，防误报）

| 不做 | 理由 |
|---|---|
| 引入 Prometheus/Grafana 栈 | 属**独立决策**（新增外部依赖与运维面）；本设计只到"指标可用 + 规则可判" |
| 新增自愈动作（自动重启/自动降级） | 与既有原则一致：**只告警不自愈**（`EMQX-TLS-DESIGN` §7 同一原则）。自愈的权限与爆炸半径需单独设计 |
| 平台告警的 ACK/指派/评论 | 复用设备告警的成熟交互属二期；一期先保证"能发现、能留痕、能通知" |
| 自定义规则编排 UI | 与"刻意不做"的 M-7 可视化规则编排一致 |
| 跨租户的平台指标下钻 | 安全红线；平台告警只面向平台运维视图 |

---

## 3. 数据设计

### 3.1 新表 `iot_platform_alert`（**与设备告警物理分离**，理由见 §1.3）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 雪花 |
| `tenant_id` | BIGINT | 与其他 IoT 表同构（**必须继承 `TenantBaseEntity`**，并**登记进 `IotTenantIsolationGateTest`**——#8 复核发现的缺口教训） |
| `rule_code` | VARCHAR(48) | 规则码（如 `PLATFORM_EVALUATOR_STALLED`） |
| `dedup_key` | VARCHAR(128) | 去重键（`rule_code` + 维度），**唯一键**：同一问题不重复开单 |
| `active_dedup_key` | VARCHAR(128) NULL | 活跃态去重键（与设备告警同款技巧：终态后置 NULL 以便再次触发） |
| `severity` | VARCHAR(16) | `WARNING`/`CRITICAL` |
| `state` | VARCHAR(16) | `PENDING`/`FIRING`/`RESOLVED`（一期不做 ACKED） |
| `summary` | VARCHAR(512) | 面向运维的一句话（**含实际值**，如"评估器已 45s 未成功（阈值 45s）"） |
| `metric_snapshot` | TEXT | 判定时的指标快照（JSON）——**事后排障的关键**（"当时到底是多少"） |
| `start_ts`/`firing_ts`/`resolved_ts` | DATETIME | 生命期 |
| 审计列 | — | `create_time` 等（base entity） |

**不加外键**（与既有约定一致）；`dedup_key` 建唯一索引，`(tenant_id, state)` 建查询索引。

### 3.2 为什么不复用 `iot_alert_instance`

见 §1.3。**补充一条**：`iot_alert_instance.property_id` 是 `NOT NULL` 语义的一部分
（点位告警靠它），平台规则**天然没有点位** ⇒ 复用等于给一个必填列塞无意义值。

---

## 4. 判定器设计（纯函数）

```
PlatformHealthVerdict evaluate(PlatformHealthSnapshot snapshot, PlatformHealthRule rule)
```

- `PlatformHealthSnapshot`：从 `MeterRegistry` 读出的**只读快照**（各指标当前值 + 上次成功时间戳）。
- **零 IO、零 Spring 依赖** ⇒ 可穷举单测：每条规则至少一正一反 + 边界（恰好等于阈值）+ 指标缺失（首次启动尚无数据）。
- **指标缺失时的行为必须显式**：不能默认"健康"（那会在刚启动/采集坏掉时掩盖问题），
  也不能直接告警（会误报）⇒ 返回 `UNKNOWN` 并**只记日志**，由 §5 的"平台告警不重复刷屏"约束兜住。

---

## 5. 通知与防刷屏（**本能力最容易做坏的地方**）

平台告警与设备告警不同：**一次平台故障会让同一规则反复命中**。因此：

1. **去重**：`active_dedup_key` 唯一 ⇒ 问题未恢复期间**只开一条**，不重复开单。
2. **抑制**：沿用既有 `AlertNotifyThrottle` 的节流思路（平台侧独立配置，避免与设备告警互相挤占）。
3. **恢复要通知**：`FIRING → RESOLVED` 必须发一条（否则值班不知道"可以收工了"）。
4. ⚠️ **待定（产品决策）**：平台告警的收件人是谁？选项：
   (a) 平台运维账号（需引入"平台级收件人"配置）；(b) 复用租户管理员（**不推荐**：平台故障与某个租户无关，
   发给所有租户会造成误报与信息泄露）；(c) 只落库 + 前端展示，一期不发邮件（**最保守**）。
   ⇒ **实施前需用户裁定**，本设计不擅自决定。

---

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **阈值定错** | 要么刷屏（无人再看）、要么永不告警（等于没做） | 阈值**实测后定**（§1.4）；上线初期**只落库不通知**（观察期），确认稳定后再开通通知 |
| **平台告警自身失败** | "监控者先坏" | 判定器**不依赖**被监控的链路（不查 DB 表、只读内存指标）；异常必须 `log.error` 完整堆栈（禁静默） |
| **指标读取开销** | 每次判定读一批 meter | 只读**枚举出的固定指标**（不遍历全部 meter） |
| **多副本重复判定** | 每个副本都判 ⇒ 重复告警 | `dedup_key` 唯一键 + `INSERT ... ON DUPLICATE KEY UPDATE`（与既有幂等键同手法）；一期**不引入分布式锁**（成本不匹配） |
| **新的租户表漏登记门禁** | 跨租户可读平台告警 | 建表**同批**登记进 `IotTenantIsolationGateTest#M1_TENANT_TABLES`（#8 复核发现的缺口正是这类） |

---

## 7. 分批实施计划

| 批次 | 内容 | 依赖 |
|---|---|---|
| **一批** | 新表 + 纯函数判定器（含用例）+ 调度壳 + **观察期**（只落库不通知） | 实测 `/actuator/metrics` 可用性（§1.4） |
| **二批** | 开通通知（收件人裁定后）+ 前端平台告警列表 | 一批观察期数据（用于定阈值） |
| 三批（可选） | ACK/指派/评论；外部大盘接入评估 | 用户决策 |

---

## 8. 验收判据（可测）

1. 判定器对每条规则：**至少一正一反**；阈值**恰好等于**时的行为明确且有用例；**指标缺失**返回 `UNKNOWN` 且不告警。
2. 同一问题持续存在 ⇒ **只开一条**记录（去重用例）；恢复后再触发 ⇒ **能重新开单**（`active_dedup_key` 置 NULL 的用例）。
3. 恢复时**必发**一条恢复通知（用例）。
4. 平台告警表**登记进租户隔离门禁**且门禁通过。
5. 判定器**不查 DB**（架构门禁 `loopsMustNotCallDbOrRpc` 语义 + 用例断言其只依赖入参）。
6. **不新增**任何外部依赖（Prometheus 等）——CI 里 `pom.xml` 无变化。

---

## 9. 未决问题（**实施前需裁定**）

| # | 问题 | 备选 |
|---|---|---|
| 1 | 平台告警的**收件人** | §5 的 (a)/(b)/(c)，**建议 (c)** 起步 |
| 2 | 阈值初值 | 需**先实测**积累观察数据 |
| 3 | 是否引入外部大盘 | 本设计**不涉及**；如需，另立决策 |

---

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-30 | 建立本设计稿。**本稿未实施任何代码改动**。核心结论：不复用设备告警引擎（结构上装不下，§1.3）；判定器做成纯函数（可穷举单测）；一期**只落库不通知**（避免阈值未定就刷屏）；收件人属**产品决策**（§9 待裁定）。未核实项逐条标注（§1.4）。 |
