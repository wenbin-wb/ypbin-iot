# 订阅编排：下沉到 iot-starter 的边界与遗留（iot-starter #37）

> **状态**：2026-10-18 完成（`ypbin-iot` 侧）。本文登记「哪些已下沉、哪些仍留宿主、为什么」，
> 供后续维护者避免重复排查，也作为**技术债清单**（下次 iot-starter 发版时逐条评估能否删除）。

## 一、已下沉（宿主代码已删）

`AccessSubscriptionPlanner` 从 **639 行降到 383 行**（其中约 200 行是 javadoc；
下列职责全部由 `ypbin-iot-spring-boot-starter` 的 `SubscriptionOrchestrator`
（`DefaultSubscriptionOrchestrator`）承担：

| 原宿主职责 | 现在的归属 | 说明 |
|---|---|---|
| 整批 vs 逐点位分流（按协议码 + `payload-format` 硬编码） | 框架 `ProtocolDescriptor#resolveAddressing(device)` + `SubscriptionAddressingPolicy` | **协议级默认 + 按形态细分**：Modbus/MQTT/OPC UA = `BATCH`；TCP = 协议级 `PER_ADDRESS`，但**按形态覆盖**：`key-value ⇒ BATCH`、`binary/text/number ⇒ PER_ADDRESS`（`release/0.5.0` 反哺，宿主已不再判断 `payload-format`） |
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

### ④ key-value 的 TCP 退避：**已闭环**（框架已反哺，宿主代码已删）

> **2026-10-19 更新**：iot-starter `release/0.5.0` 已把这条技术债反哺进框架
> （前置状态：宿主保留约 15 行最小退避表，见本节下方的「历史记录」）。

**框架侧新增**（`ypbin-iot-core`）：
- `SubscriptionAddressingPolicy`（record）：`defaultAddressing` + `byVariant`（形态 → 寻址方式覆盖表）
  + `resolve(deviceId)` ⇒ `Resolution{addressing, variantKnown, variantMiss}`；
- `SubscriptionAddressingPolicy.VariantResolver`（函数式接口）：`String resolveVariant(DeviceSpec)`；
- `ProtocolDescriptor` 新增 `addressingPolicy()` / `resolveAddressing(device)`，
  与 `Builder#subscriptionAddressingByVariant(Map)` / `#variantResolver(...)`；
- `TcpAdapter` 现在按 `payload-format` **细分声明**：`key-value ⇒ BATCH`、`binary/text/number ⇒ PER_ADDRESS`，
  协议级默认仍取保守侧 `PER_ADDRESS`（形态未声明 ⇒ 兜底 + WARN，不静默按整批）。

**宿主侧删除（本批）**：
- `AccessSubscriptionPlanner` 的 `keyValueBackoff` 表 + `KeyValueBackoff` record + `armKeyValueBackoff()`；
- `subscribeKeyValueOnce()`（绕开框架直调 `session.subscribe` 的方法）；
- 「`isTcp(device) && tcpKeyValuePayloadFormat` ⇒ 自己分流」的分支与 `tcpKeyValuePayloadFormat` 字段；
- `AccessIotProtocolConfiguration` 里读取 `TcpProperties`/`TcpPayloadFormat` 的分流判定与相关 import。

**结果**：`AccessSubscriptionPlanner` **495 → 383 行**（其中约 200 行 javadoc），
且 `grep -rn "session.subscribe" ypbin-service/ypbin-access/src/main` = **0** —— 宿主不再直调协议层。

**行为不变**：key-value 仍是一次多地址订阅（框架按形态声明 BATCH），binary 仍逐点位单订阅
（PER_ADDRESS）。退避执行者从宿主换成框架 `SubscriptionBackoffPolicy`，但**退避能力未丢**。

<details>
<summary>历史记录（2026-10-18，技术债阶段）</summary>

- **原因**：框架当时的 `subscriptionAddressing` 是**协议级**声明（TCP 恒 `PER_ADDRESS`），
  无法随 `payload-format=key-value` 变化；而 key-value 依赖「一次多地址订阅」。
- **临时处置**：宿主保留约 15 行最小退避表（起点 30s、翻倍、封顶 2min），绕开框架编排直调 `session.subscribe`。
- **为什么不用租约周期兜底**：租约周期（默认 30s+）与退避策略不是一回事；用租约兜底等于**取消退避**
  （每 tick 都重试），会对故障设备持续放大对端压力——这正是原实现要防的。
</details>

## 四、行数说明（如实登记）

issue 的审查稿估计「`AccessSubscriptionPlanner` < 150 行」，实测 **383 行**（其中约 200 行 javadoc）。
**未达该估计值**，原因：
1. 框架语义映射（4 处差异）与「为什么不直调协议层」的说明性注释；
   （原第 1 条「保留 key-value 分支与退避表」已于 2026-10-19 随框架反哺**删除**，不再是原因）；
2. 框架语义映射（4 处差异）需要注释说明，否则后来人会按旧语义改错；
3. 本仓「注释即设计」的既有风格（大段类/方法 javadoc 解释「为什么」）。

lead 决策：**接受该行数，不为压行数牺牲注释**；把「为什么不完全下沉」记在本文件。

## 五、相关用例

| 用例 | 钉住什么 |
|---|---|
| `AccessSubscriptionPlannerTest#backoffMustNotTouchSessionAndMustRetryAfterWindow` | 退避窗口内**零** `session.subscribe` 调用，跨窗口必重试 |
| `AccessSubscriptionPlannerTest#keyValueVariantMustSubscribeBatchThroughFramework` | ④ 已闭环：key-value 走框架整批（1 次调用带 3 地址） |
| `AccessSubscriptionPlannerTest#binaryVariantMustSubscribePerAddressThroughFramework` | ④ 已闭环：binary 仍逐点位（3 次单地址），两种形态不混淆 |
| `AccessSubscriptionPlannerTest#keyValueBackoffMustBeHandledByFramework` | ④ 已闭环：宿主退避表已删，**退避仍生效**（窗口内 `verify(never())`） |
| `AccessSubscriptionPlannerTest#modbusWordCountTwoInvalidAddressMustSkipDeviceNotTick` | ② + 失败可观测（计数器非 0） |
| `AccessSubscriptionPlannerTest#modbusWordCountTwoMustSubscribeBaseAndNext` | 多寄存器展开仍正确（`holding:0` + `holding:1`） |
| `AccessSubscriptionPlannerTest#tcpDevice4PointsMustSubscribePerAddress` | TCP 逐点位（UP-4 契约） |
| `AccessSubscriptionPlannerTest#tcpKeyValueWithMultiplePointsMustSubscribeOnceWithAllAddresses` | key-value 一次多地址（1 次调用带 4 个地址） |
| `AccessReadingMappingTest` | 点位 → 读数映射口径未受订阅迁移影响 |
