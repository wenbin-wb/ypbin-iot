# ypbin-access 指标清单（运维关注点 + 机器校验）

> **用途**：把「指标已注册但没人知道该看什么」变成一张可执行的清单——每条指标都有 ①口径（何时变化）
> ②建议关注/阈值 ③注册位置。（源自 R8-9「安全网成本与可观测性」立项，该技术债台账已退役。）
> **覆盖范围**：**`ypbin-access` 服务**的全部指标（`iot.access.*`，共 **35** 条）。
> iot 服务的告警/入站/时序指标（`iot.alert.*`、`iot.ingest.*`、`iot.timeseries.*`、`iot.platform_alert.*`）见
> `docs/PLATFORM-ALERTING-DESIGN.md` 与 `deploy/PROD-OPS-NOTES.md`——本文件不重复登记，避免两处漂移。
> **机器校验**：`MetricsRegistryGateTest` 断言本清单里的**每个指标名**都能在其注册文件里找到
> （并断言清单非空），防止「文档写了、代码删了」或「代码改了名、文档没跟」的静默漂移。

## 1. 怎么读

- **暴露面**：`http://<access-host>:18086/actuator/metrics/<指标名>`；18086 **只绑回环**、`exposure.include` 只有
  `health,metrics,info`（安全边界见 `deploy/nacos/ypbin-access.yaml` 的 management 段注释——`*` 会连带 heapdump/env）。
- **计数类（Counter）**：看「一段时间内是否增长」——**增长即事件发生**，多数指标的期望值是 0；
- **计时器类（Timer）**：看 `count`（次数）、`max`（单次最坏，**R8-2 的判据**）、`total_time`（累计）；
- **仪表类（Gauge）**：看当前值（如时钟偏移、待上报条数）。

**阈值口径说明**：access 现有指标**没有一条需要精确阈值**才能用——除两条计时器外，全部是「增长即异常」的计数。
计时器的阈值来自**约束**而不是拍脑袋：tick 必须显著小于租约 TTL（`ttl=30s`，见 `docs/LEASE.md`），
单次规格取数最坏值由客户端超时钉住（connect 1s + read 5s = 6s，见 `DeviceSpecFeignConfiguration`）。

## 2. 指标清单

### 2.1 租约与调度 tick（R8-2 的核心可观测面）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.lease.tick.duration` | Timer | 每个调度 tick（自检+续约+重领+对账）测一次 | **`max` 应远小于 TTL(30s)**；`max` 接近 20s 即需按 R8-2 加时间预算 | AccessLeaseManager.java |
| `iot.access.lease.renew.success` | Counter | 一次续约失败计数之外的正常路径（见下条对照） | 稳定增长；**长时间不增长 = tick 没在跑** | AccessLeaseManager.java |
| `iot.access.lease.renew.failure` | Counter | 续约请求异常/非成功信封 | **增长即异常**（网络或 iot 服务问题） | AccessLeaseManager.java |
| `iot.access.lease.revoked` | Counter | 服务端告知某租户不再归本节点（被回收/接管） | 少量正常（交接）；**短时间内大量 = 抖动或双主** | AccessLeaseManager.java |
| `iot.access.lease.self_fenced` | Counter | 本节点按本地判据停采某租户（含 TTL 越界导致的自我 fence） | **期望 0**；增长 ⇒ 查 tick 耗时与租约 TTL 关系（R8-2） | AccessLeaseManager.java |
| `iot.access.lease.node_fenced` | Counter | 服务端判定**整节点**失效 ⇒ 整体停采并重新注册 | **期望 0**；增长 ⇒ 查注册/续约链路与 nodeId 冲突 | AccessLeaseManager.java |
| `iot.access.lease.acquired` | Counter | 成功领取租户 | 扩容/重启后短时增长属正常 | AccessLeaseManager.java |
| `iot.access.lease.clock_skew_seconds` | Gauge | 本机时钟相对 DB 时钟的偏移（秒） | **`\|值\| > 5` 需关注**（告警阈值常量 `SKEW_WARN_SECONDS`） | AccessLeaseManager.java |
| `iot.access.lease.clock_skew.deferred` | Counter | 偏移「跳变」被暂缓采纳（等读数一致或达暂缓上限） | 少量正常；**持续增长 = NTP 抖动或 DB 时钟异常** | AccessLeaseManager.java |

### 2.2 配置版本对账（`config_epoch` → 重取清单）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.config.reconcile.duration` | Timer | 每次对账（拉 epoch + 逐租户按需重取清单）测一次 | **`max` 是 R8-2 的直接判据**：N 个变更租户 × 单次取数耗时应留在 TTL 的一个零头内 | ConfigEpochReconciler.java |
| `iot.access.config.changed` | Counter | 某租户的 `config_epoch` 与已应用值不一致（即「要采什么」变了） | 有变更才增长；**健康期长时间为 0 属正常** | ConfigEpochReconciler.java |
| `iot.access.config.check.failure` | Counter | 对账请求异常或返回非成功信封 | **增长即异常**（iot 不可达 / 契约不符） | ConfigEpochReconciler.java |
| `iot.access.config.reconcile.not_applied` | Counter | 版本号已变化但**未能完成**对账（同一版本号只计一次） | **期望 0**；持续增长 = 取数一直失败（每轮会重试） | ConfigEpochReconciler.java |
| `iot.access.config.reconcile.forced` | Counter | 周期安全网的强制对账（每 tick 至多 1 个租户） | 稳态下按「轮转周期」缓慢增长属预期；**加速增长 = 变更信号缺失** | ConfigEpochReconciler.java |
| `iot.access.config.reconcile.deferred` | Counter | 因**时间预算用尽**（`ypbin.access.reconcile-budget-ms`，默认 15s）被推迟到下一 tick 的租户次数（R8-2） | **期望 0**；持续增长 ⇒ 与 `reconcile.duration` 的 `max` 一起看：本轮确实做不完（调大预算或按 R8-2 其它选项） | ConfigEpochReconciler.java |

### 2.3 设备规格取数

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.spec.load.duration` | Timer | 每次**单租户**设备清单取数测一次 | **`max` 逼近客户端最坏值 6s（connect1+read5）**即需排查下游；这是 R8-2 的时间预算输入 | IotProtocolTenantLinkManager.java |
| `iot.access.spec.reconcile.applied` | Counter | 对账后确实按最新配置应用了清单 | 与 `config.changed` 对照：**变了却长期不 applied = 卡住** | IotProtocolTenantLinkManager.java |
| `iot.access.spec.empty` | Counter | 上游返回空清单（该租户没有设备） | 稳态 0；增长 ⇒ 设备被误删/上游过滤错 | IotProtocolTenantLinkManager.java |
| `iot.access.spec.failure` | Counter | 清单取数失败（与「确实没有设备」分开计数） | **增长即异常**；持续失败会进退避重试 | IotProtocolTenantLinkManager.java |
| `iot.access.spec.backoff.skipped` | Counter | 取数失败退避窗口内跳过本轮（不打远端） | 与 `spec.failure` 成对出现属预期（防打爆下游） | IotProtocolTenantLinkManager.java |
| `iot.access.device.rebind` | Counter | 规格变化触发的重新绑定（REMOVE/ADD） | 有变更才增长 | IotProtocolTenantLinkManager.java |
| `iot.access.device.rebind.deferred` | Counter | 重绑被推迟（如空清单退避窗口内） | 少量正常；长期增长 ⇒ 迟迟不收敛 | IotProtocolTenantLinkManager.java |
| `iot.access.connection.invalid` | Counter | 连接参数非法（端点/协议解析失败） | **期望 0**；增长 ⇒ 台账里的端点写错 | HttpDeviceSpecSource.java |

### 2.4 订阅（G1 自愈链路）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.subscribe.success` | Counter | 订阅成功（异步回调里确认后才记） | 首启/变更后增长属预期 | AccessSubscriptionPlanner.java |
| `iot.access.subscribe.failure` | Counter | 订阅失败（同步抛或异步失败），会进指数退避 | **增长即异常**（协议栈/设备侧问题） | AccessSubscriptionPlanner.java |
| `iot.access.subscribe.backoff.skipped` | Counter | 失败退避窗口内跳过本轮订阅 | 与 `subscribe.failure` 成对属预期 | AccessSubscriptionPlanner.java |
| `iot.access.subscribe.inflight.skipped` | Counter | 上一轮订阅尚未完成 ⇒ 本轮不重复发起（G1 在途去重） | 少量正常（异步慢）；长期增长 = 订阅长时间不完成 | AccessSubscriptionPlanner.java |

### 2.5 读数上报（egress 微批）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.egress.sent` | Counter | 一批读数成功上报 | 应随采集持续增长（**不增长 = 采集或上报断了**） | HttpAccessReadingSink.java |
| `iot.access.egress.accepted` | Counter | 上游确认接收的条数 | 与 `sent` 对照看批大小 | HttpAccessReadingSink.java |
| `iot.access.egress.dropped` | Counter | 上报失败后**丢弃**的条数（不重试，避免拖垮服务） | **增长即数据缺口**；需与断档口径一起看 | HttpAccessReadingSink.java |
| `iot.access.egress.failed` | Counter | 上报失败的批次数 | **增长即异常**（网络/上游过载） | HttpAccessReadingSink.java |
| `iot.access.egress.invalid` | Counter | 上报内容非法（被上游拒绝） | **期望 0**；增长 ⇒ 编码/口径 bug | HttpAccessReadingSink.java |
| `iot.access.egress.pending` | Gauge | 当前缓冲区内待上报条数 | 稳态接近 0；**持续高位 = 上报跟不上采集** | HttpAccessReadingSink.java |

### 2.6 值规范化（原「解码」；2026-10-13 随 starter 0.3.0 切分职责）

> **指标变更登记（2026-10-13）**：`iot.access.decode.failure` 已随「帧解析过渡层」删除而**下线**
> （其注册文件 `DecodeFailureMetrics.java` 已删除）。其中「非 UTF-8 / 无键值字段 / selector 未命中」
> 三类失败由 starter 0.3.0 `payload-format=key-value` 的 `TcpValueSelector` 以 **BAD 质量** +
> `iot.tcp.*` 指标（如 `iot.tcp.selector.not-found` / `iot.tcp.payload.not-key-value` /
> `iot.tcp.payload.not-utf8`）承接，宿主原样穿透、不再重复计数；「类型规范化」三类失败由下表新指标承接。

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.value.normalize.failure` | Counter（按 `reason` 标签：`not-numeric` / `not-boolean` / `unknown-data-type` / `non-finite` / `not-utf8`） | 宿主物模型值规范化失败（TCP 类型规范化三类；`non-finite` = Modbus float 解出 NaN/±Inf，见 §4.4.6；`not-utf8` = Modbus `register_format=string` 字节串不是合法 UTF-8，见 §4.5） | **期望 0**；出现即按 `reason` 定位点位 `data_type` 与设备帧内容 | PointValueNormalizeMetrics.java |
| `iot.access.modbus.pair.stale` | Counter | Modbus 多寄存器点位暂存组**陈旧/同角色重复**被整组丢弃的次数（组内最早到达的字距本次 > 一个采集周期，或同一 `word_index` 重复到达；v3 起 N=1..32） | **期望 0**；增长 ⇒ 采集周期/寄存器地址配对异常或重复订阅（见 `VALUE-DECODE-DESIGN.md` §4.3.4/§4.5.3） | ModbusRegisterMetrics.java |

### 2.7 指标上报链路（access → iot 平台自告警）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.access.metrics.report.failure` | Counter | 指标快照推送给 iot **失败**次数（默认每 15s 推一次） | **期望 0**；增长 ⇒ 推送链路断 ⇒ 平台侧相关规则判「不可判定」 | AccessMetricsReporter.java |

### 2.8 平台告警自身的可观测性（`iot.platform_alert.*`，产出在 iot 服务）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.platform_alert.access.report.lag` | Gauge | 距最近一次 access 上报的毫秒数；`-1` = 从未收到 | ≥ 45s ⇒ 上报中断（`PLATFORM_INSTANCE_UNREACHABLE`） | AccessMetricsReportStore.java |
| `iot.platform_alert.notify.send` | Counter | 通知尝试次数（**含被开关/空收件人拦下**） | 想看"到底有没有尝试发"就看他：开了 FIRING 却不涨 ⇒ 通知链断 | PlatformAlertNotifier.java |
| `iot.platform_alert.notify.succeeded` | Counter | 投递成功次数（站内信 + 邮件分别计） | 应与 `send` 相等；不等 ⇒ 有失败 | PlatformAlertNotifier.java |
| `iot.platform_alert.notify.failed` | Counter | 投递失败次数（异常 + 对端返回失败） | **期望 0**；增长 ⇒ 看 `last_error`/系统邮件配置 | PlatformAlertNotifier.java |


### 2.9 iot 服务侧补充登记（不属 ypbin-access；`MetricsRegistryGateTest` 只校验 `iot.access.*`）

| 指标 | 类型 | 口径（何时变化） | 建议关注 / 阈值 | 注册文件 |
|---|---|---|---|---|
| `iot.lease.insert_skipped` | Counter | 首次分配批量 `INSERT IGNORE` 时被唯一键跳过的归属行数（含「软删归属行占住唯一键」这一静默失效形态，M3） | **期望 0**；增长 ⇒ 有租户可能被静默跳过：先查该租户是否有软删归属行，再看是否多副本并发抢先 | LeaseServiceImpl.java |
| `iot.timeseries.value.type_missing` | Counter | 落库时该属性**取不到物模型 `data_type`**，或同一归一标识出现**类型冲突** ⇒ 该读数**丢弃**（不猜、不落文本兜底） | **期望 0**；增长 ⇒ 物模型缺失/被改坏，或点位标识在不同产品下类型不一致：先查 `iot_property.data_type` | AvailabilityServiceImpl.java |
| `iot.timeseries.value.not_numeric` | Counter | 属性声明为**数值类型**（int/long/decimal）但读数**无法解析为数值**（如 `"abc"`）⇒ 丢弃 + 节流 WARN | **期望 0**；增长 ⇒ 设备/映射把非数值送到数值点（建模或解码配置错） | AvailabilityServiceImpl.java |
| `iot.timeseries.value.precision_lost` | Counter | 属性声明为**整数型**且 `\|v\| > 2^53`（`value_double` 无法精确表示）⇒ **仍写 `value_double`**（保住"同一属性同一列"不变量）+ 节流 WARN | **期望 0**；增长 ⇒ 该属性应建模为 **`string`**（用 `long` 承载 ID/序列号会静默近似） | AvailabilityServiceImpl.java |

> 说明：该指标产在 **ypbin-iot** 服务（租约分配路径），既不在本清单原本的 ypbin-access 范围，
> 也不经 access→iot 指标快照上报；此处只做**登记**（名字/口径/注册位置）。
> 平台自告警目前**未**为该 meter 加规则（属未完成项），需要时在 `PlatformHealthSnapshot` 的枚举指标里补上。

## 3. 与 R8 其它条目的关系

- **平台自告警（2026-10-10）**：本清单的 `iot.access.config.reconcile.deferred`、`iot.access.lease.tick.duration`（max）、 `iot.access.metrics.report.failure` 由 access 每 15s 推送给 iot，进入 4 条平台自告警规则（`PLATFORM_RECONCILE_DEFERRED`/`PLATFORM_TICK_SLOW`/`PLATFORM_INSTANCE_UNREACHABLE`/`PLATFORM_LEDGER_BUMP_FAILING`） ⇒ 平台告警页 + 邮件/站内信可见；iot 侧心跳 `iot.platform_alert.access.report.lag`。
- **R8-2**（对账拖长 tick）：本清单的 `iot.access.lease.tick.duration` + `iot.access.config.reconcile.duration`
  + `iot.access.spec.load.duration` 是「先可观测、再改调度」的前置；方案 A（时间预算）已实施，其效果由
  `iot.access.config.reconcile.deferred` 直接观测（期望 0；增长即说明预算被顶到）。
- **R8-9 B**（本清单的由来）：新增上述 3 条计时器；其余条目此前已注册但从未登记（即「可观测只是潜在」）。
- **未做**：未接 Prometheus/Grafana（本仓无采集端）；若要真正"上大盘"，需先决定是否引入采集组件（单独提案）。

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-09 | 建立本清单：把 `ypbin-access` 的 **33 条**指标逐条登记（口径 / 建议关注 / 注册文件），并新增 3 条计时器（tick / 对账 / 单租户取数）；配套门禁 `MetricsRegistryGateTest` 防清单与代码漂移。 |
| 2026-10-09（R8-2 实施批） | 登记新增的 `iot.access.config.reconcile.deferred`（对账时间预算推迟计数）⇒ 共 **34** 条；门禁反向检查同时补上「前缀常量 + 后缀片段」风格（此前只认完整字面量，**真的漏过这条新指标**——已由门禁自身抓出）。 |
| 2026-10-18（M3 加固） | 补充登记 iot 服务侧 `iot.lease.insert_skipped`（首次分配被唯一键跳过的归属行数）；注明不在 access 门禁覆盖范围内。 |
