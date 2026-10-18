# 订阅编排：下沉到 iot-starter 的边界与遗留（iot-starter #37）

> **状态**：2026-10-18 完成（`ypbin-iot` 侧）。本文登记「哪些已下沉、哪些仍留宿主、为什么」，
> 供后续维护者避免重复排查，也作为**技术债清单**（下次 iot-starter 发版时逐条评估能否删除）。

## 一、已下沉（宿主代码已删）

`AccessSubscriptionPlanner` 从 **639 行降到 ~390 行**（其中约 200 行是 javadoc），
下列职责全部由 `ypbin-iot-spring-boot-starter` 的 `SubscriptionOrchestrator`
（`DefaultSubscriptionOrchestrator`）承担：

| 原宿主职责 | 现在的归属 | 说明 |
|---|---|---|
| 整批 vs 逐点位分流（按协议码 + `payload-format` 硬编码） | 框架 `ProtocolDescriptor#subscriptionAddressing()` | **协议级声明**：Modbus/MQTT/OPC UA = `BATCH`，TCP = `PER_ADDRESS` |
| 多寄存器地址展开（`base+1..base+N-1`） | 框架 `AddressExpansionExtension` + `ModbusAddressExpansion` | 地址算术归协议模块（`iot-starter` 已实现 Modbus 版） |
| 在途去重（同设备同会话并发订阅合并） | 框架 `DeviceState#pending` | 宿主不再维护 `inFlight` 判重表（只留一个轻量标记用于返回值口径，见下） |
| 部分成功只补失败地址 | 框架 `DeviceState#succeeded` + `SubscriptionOutcome#retryAddresses()` | 绝不重复给已成功地址挂监听（否则重复读数） |
| 按「设备 + 会话实例」退避 | 框架 `SubscriptionBackoffPolicy`（`ypbin.iot.subscription.backoff.*`） | 会话实例变化即作废退避 |
| `SubscriptionBackoff` / `TcpSubscriptionState` 等状态类 | 已删 | 框架内部状态 |

## 二、仍留宿主的三件（框架不可能知道）

1. **哪些设备归我**：查询本节点已绑定的 `DeviceSession`（租约归属语义，业务侧独有）。
   `devicesWithoutSession()` 仍由宿主实现，供 `IotProtocolTenantLinkManager` 判断是否把
   「建链失败」交给框架的 `DeviceBindRetrier`。
2. **点位 → 地址映射**：把设备规格里的点位 JSON（`AccessPointMappingDto`）翻译成框架的
   `SubscriptionPoint`（含 `word_count`）。JSON 结构与字段名是本仓契约。
3. **读数出口接线**：为每台设备建 `PointMappingDataListener`（物模型规范化、质量判定、落列口径）。

## 三、4 处与迁移前的语义差异（已按 lead 决策处置）

### ① 退避窗口内的返回值口径
- **迁移前**：退避窗口内不调用 `session.subscribe`，`subscribe()` 返回 0。
- **现在**：框架返回 `SubscriptionStatus.SKIPPED_BACKOFF`（已完成 future），宿主**不算发起**（返回 0）。
- **行为未变**：退避窗口内 `session.subscribe` 仍**一次都不调用**（有用例 `backoffMustNotTouchSessionAndMustRetryAfterWindow`
  逐轮 `verify(times(1))` 钉住）。

### ② 地址扩展失败的处理
- **迁移前**：`word_count=2` 但地址是传统 5 位编号 ⇒ 扩展抛异常 ⇒ **整设备本轮跳过**，返回 0。
- **现在**：框架把该点位记为 `SubscriptionFailure`（`iot.common.subscription.address-expansion-unsupported`），
  **设备级仍算发起**（返回 1），但**不订阅该点位地址、也不退而只订基址**。
- **采集结果一致**（都不订该点位）；差异仅在「发起计数」与「失败粒度」。
- ⚠️ **可观测性**：框架不调用 `recordError`，故宿主在 `PARTIAL` 分支记 WARN + 按地址级累加
  `iot.access.subscribe.failure`。**这是该形态的唯一可观测出口**——没有它，「发起 1 次但点位永远无数据」
  会变成新的静默失效。

### ③ `subscribe.failure` 指标口径（**保持地址级**）
- 按 lead 决策：`PartIAL`/`FAILED` 时按 `outcome.failures().size()` 累加，**不**改为设备级。
- 理由：运维看板是契约；口径从地址级突变设备级会让同一故障看起来轻 N 倍（静默弱化）。

### ④ key-value 的 TCP 仍由宿主退避（**技术债**）
- **原因**：框架的 `subscriptionAddressing` 是**协议级声明**，TCP 恒为 `PER_ADDRESS`；
  而 `payload-format=key-value` 依赖「一次多地址订阅」（SDK 对每个 selector 各产出一个 `PointValue`）。
  改走框架声明会退化成 N 次单地址订阅（读数值仍正确，但请求数 1→N）。
- **处置**：宿主保留约 15 行的最小退避表（起点 30s、翻倍、封顶 2min，与迁移前同口径），
  绕开框架编排直调 `session.subscribe`。
- **为什么不能用租约周期兜底**：租约周期（默认 30s+）与退避策略不是一回事；用租约兜底等于**取消退避**
  （每 tick 都重试），会对故障设备持续放大对端压力——这正是原实现要防的。
- **删除条件**：iot-starter 支持「按设备配置订阅寻址方式」（而非协议级）后，删掉 `keyValueBackoff`
  表与 `subscribeKeyValueOnce()`，全部交给框架。
  —— 对应 iot-starter #37 的已知限制，**已在该 issue 中记录**。

## 四、行数说明（如实登记）

issue 的审查稿估计「`AccessSubscriptionPlanner` < 150 行」，实测 **~390 行**（其中约 200 行 javadoc）。
**未达该估计值**，原因：
1. 保留 ④ 的 key-value 分支与其退避表（约 15 行代码 + 说明注释）；
2. 框架语义映射（4 处差异）需要注释说明，否则后来人会按旧语义改错；
3. 本仓「注释即设计」的既有风格（大段类/方法 javadoc 解释「为什么」）。

lead 决策：**接受该行数，不为压行数牺牲注释**；把「为什么不完全下沉」记在本文件。

## 五、相关用例

| 用例 | 钉住什么 |
|---|---|
| `AccessSubscriptionPlannerTest#backoffMustNotTouchSessionAndMustRetryAfterWindow` | 退避窗口内**零** `session.subscribe` 调用，跨窗口必重试 |
| `AccessSubscriptionPlannerTest#keyValueBackoffMustStillWork` | ④ 的技术债行为（key-value 退避仍生效） |
| `AccessSubscriptionPlannerTest#modbusWordCountTwoInvalidAddressMustSkipDeviceNotTick` | ② + 失败可观测（计数器非 0） |
| `AccessSubscriptionPlannerTest#modbusWordCountTwoMustSubscribeBaseAndNext` | 多寄存器展开仍正确（`holding:0` + `holding:1`） |
| `AccessSubscriptionPlannerTest#tcpDevice4PointsMustSubscribePerAddress` | TCP 逐点位（UP-4 契约） |
| `AccessSubscriptionPlannerTest#tcpKeyValueWithMultiplePointsMustSubscribeOnceWithAllAddresses` | key-value 一次多地址（1 次调用带 4 个地址） |
| `AccessReadingMappingTest` | 点位 → 读数映射口径未受订阅迁移影响 |
