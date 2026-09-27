# 读数「值解码」设计与缺口登记（VALUE-DECODE-DESIGN）

> **用途**：说清「TCP 帧 → 平台规范值」这条链路上**谁该解码、现在还缺什么、本轮做到哪、后面怎么替换**。
> 一手材料（全部本机可复现）：starter 源码（tag `v0.1.0`）、本仓源码、生产容器里的 fat jar 清单、
> 生产 IoTDB / MySQL 真值。
> 最后更新：**2026-09-27**。
> 关联条目：[`STARTER-FEEDBACK.md`](STARTER-FEEDBACK.md) **SF-6**（反哺 starter）、
> [`IOT-ROADMAP.md`](IOT-ROADMAP.md) 四点二十一（落地记录）、[`ACCESS-ENABLE.md`](ACCESS-ENABLE.md) §6.3（现场证据）。

---

## 1. 结论先行

| 问题 | 结论（证据） |
|---|---|
| `[B@<hash>` 是谁产生的？ | **两段合成**：① 帧的**原始字节**来自 starter 的 TCP 协议模块——`ypbin-iot-protocol-tcp` 的 `TcpSession#dispatch` 把整帧 `byte[]` 直接当作 `PointValue.value`（tag `v0.1.0` = `878a493`，该文件第 **295** 行 `PointValue.good(address, payload, ...)`，`payload` 自第 221 行的 `Consumer<byte[]>`）；② access 的 `HttpAccessReadingSink` 用 `String.valueOf(reading.value())` 把它字符串化 ⇒ `[B@<identityHash>`。**不是** IoTDB 读出来的字节数组（生产库里存的就是字面文本，见 §1.1）。 |
| 解码该在哪一层？ | **理想在协议层**：姊妹模块 MQTT 有 `MqttPayloadFormat`（TEXT/NUMBER/BINARY，`MqttSession#decode` 按配置解码并给出 BAD 值），**TCP 没有等价能力**，且 core 里没有任何「宿主可插的解码 SPI」⇒ 这是**框架侧能力缺口**。 |
| 本轮选哪条路？ | **B 过渡实现 + A 反哺材料**：access 侧加一层显式解码钩子（`ValueDecoder` / `TextFrameValueDecoder`），同时按仓规开 **SF-6** 反哺 starter 并登记替换路径。理由见 §1.2。 |

### 1.1 一手证据（生产真值）

- 生产 IoTDB（`SELECT time, property_id, value_double, value_text, quality FROM iot.reading WHERE device_id='9300012'`）：
  `value_text='[B@2e2bd4eb'`、`value_double=null`、`quality='GOOD'`
  ⇒ 库里是**字面文本**，所以「IoTDB 读出来是 byte[]」的说法不成立（该说法见 `deploy/PROD-OPS-NOTES.md` 第 383 行，**判定为错误归因**，已在本文与 `ACCESS-ENABLE.md` §6.3 更正）。
- 生产 access 容器 fat jar：含 `BOOT-INF/lib/ypbin-iot-protocol-tcp-0.1.0.jar` 与 `HttpAccessReadingSink`、`PointMappingDataListener`（`docker exec … grep -a` 实测）。
- 该设备的点位映射（MySQL `iot_point_mapping`，设备 9300012）：
  `raw_address=holding:0/1/100/200`、`address_type=holding`、`scale_factor/offset_value/byte_order` 全 `NULL`。
- 对应物模型属性（`iot_property`）：`temperature`=`decimal`、`humidity`=`int`、`serialNo`=`string`、`demoBoundary`=`string`。

### 1.2 为什么选 B（过渡实现）而不是只做 A

1. **生产必须现在就修**：A 要新发一个 starter 版本（0.2.0 → Central）再改本仓依赖并重新部署，属跨仓发布列车，本会话无法端到端闭环；
2. **宿主侧解码本身是框架认可的形态**：MQTT 的 `BINARY` 模式注释就写明「原样交付字节：宿主自行按业务协议解析」——所以本层是**过渡**而非「硬造 workaround」；且它把「解码规则声明在哪」明确落在点位映射（`raw_address`）与物模型（`data_type`）上，不新增平台自有语义；
3. **可完整回滚**：一个 bean + 一个解码类，删掉即回到旧行为；**替换路径**已写明（§5）。

---

## 2. 现状：可用的解码参数（以实体/DDL 为准）

`iot_point_mapping`（`deploy/sql/006-iot-schema.sql` 第 230 行起 / 实体 `IotPointMapping`）：

| 字段 | 有/无 | 本轮用途 |
|---|---|---|
| `raw_address` / `address_type` | 有 | TCP 透传下 `raw_address` 即帧里的**键**（`address_type` 现无 TCP 成员，见 §4 未做项） |
| `scale_factor` / `offset_value` | 有 | 线性变换，作用在**解码后**的值上（`AccessReading.applyScale`） |
| `byte_order`（big/little） | 有 | **文本帧无意义**；寄存器级大小端解码见 §4（参数不足，未实现） |
| 寄存器**位宽**（16/32 位） | **无** | §4 未做项（需新增列） |
| **有符号/无符号** | **无** | §4 未做项（需新增列） |
| **位域**（bit offset/length） | **无** | §4 未做项（需新增列） |
| 数据**类型** | 不在映射表 | 在 `iot_property.data_type`（`VARCHAR(16) NOT NULL`），本轮由 `DeviceSpecServiceImpl` 随点位下发（`AccessPointMappingDto.dataType`） |

> `iot_product.data_format`（`json`/`binary`，「预留编解码插件」）是**产品级**的编解码占位，当前未参与取值路径；本轮不引入它，避免两处各说一套。

---

## 3. 本轮的过渡实现（已落地）

```
协议栈 TCP 会话 ──byte[]──► PointMappingDataListener ──► ValueDecoder(按协议码选) ──► 规范值
                                                            └─ 失败 ⇒ 丢弃 + 计数(reason) + WARN（脱敏）
                                                             AccessReading.applyScale ⇒ AccessReadingSink
```

- 扩展点：`cn.ypbin.admin.access.decode.ValueDecoder`（`supports(protocol)` + `decode(raw, addressKey, dataType)`）；
  实现以 `@Bean @ConditionalOnMissingBean` 装配（**不是** `@Component`——母仓教训三十一 + 源码门禁 `IotSeamConventionTest`）。
- 本轮实现：`TextFrameValueDecoder`，只认 `tcp`：
  1. 严格 UTF-8 解码（非法字节 ⇒ `NOT_UTF8`，**绝不**产出 U+FFFD）；
  2. 帧按 `,`／`;`／换行分字段，取 `KEY=VALUE`；键 = `raw_address`（大小写不敏感、去空白、重复键先出现者生效）；
  3. 按 `iot_property.data_type` 规范化：`int`/`long` ⇒ 整数（越界即失败，不截断）、`decimal` ⇒ `BigDecimal`、
     `bool` ⇒ `true`/`false`、其余 ⇒ 文本原样；
  4. 任一步不满足 ⇒ 具名失败（`DecodeFailure`：`empty-payload`/`not-utf8`/`not-key-value-frame`/`key-not-found`/
     `empty-value`/`not-numeric`/`not-boolean`/`unknown-data-type`）。
- 失败可见性：指标 **`iot.access.decode.failure{reason=…}`**（取值集合 = 上面的枚举，有界）+ WARN
  （只记设备/点位/地址/**载荷长度**与原因，**不 dump 帧内容**）。
- 兜底闸门：`HttpAccessReadingSink` 若发现值仍是 `byte[]` ⇒ 计数（`iot.access.egress.invalid`）+ WARN + 丢弃
  ⇒ `[B@…` **在任何单条路径走偏时也不会入库**。
- 不改既有取值路径：非 `byte[]` 载荷、以及没有解码器认领的协议码 ⇒ **原样透传**（Modbus/OPC UA/MQTT 行为不变）。

---

## 4. 真实设备解码：扩展点与映射方式

### 4.1 已实现

| 协议 / 帧形态 | 声明方式 | 产出 |
|---|---|---|
| TCP 文本键值帧 `TEMP=23.5,SEQ=12` | `raw_address='TEMP'` + 属性 `data_type=decimal` | `23.5` → `value_double` |
| TCP 文本键值帧中的文本点 | `raw_address='SN'` + `data_type=string` | `007` → `value_text`（原样） |
| TCP 文本键值帧中的布尔点 | `raw_address='SW'` + `data_type=bool` | `TRUE`/`1` → `true` → `value_text`（§5.2.1 布尔存文本） |
| 任意协议的**已解码**值（Modbus/OPC UA/MQTT） | 无需声明 | 原样透传 |

### 4.2 未实现（如实声明 + 方案）

| 需求 | 为什么没做 | 建议落法（含验收） |
|---|---|---|
| Modbus 寄存器 16/32 位、有符号/无符号、大小端 | `iot_point_mapping` **没有位宽/符号列**；用「载荷长度猜位宽」或「默认无符号」都是猜，违反「不猜」 | 映射表增 `word_count`（1/2）、`signed`（0/1），与既有 `byte_order` 组合成寄存器解码；验收：`byte_order=big/little` × `word_count=1/2` × `signed` 的 8 组用例 + 长度不足用例 |
| 位域（bit offset/length） | 同上，缺列 | 增 `bit_offset`/`bit_length`；验收：越界位宽报错、单 bit 取布尔 |
| 字符串寄存器（多寄存器拼 UTF-8） | 同上，缺列 | 增 `word_count` + `encoding`；验收：中文/半角混合、奇数字节 |
| 「整帧即值」（无键的裸值帧） | 需要一个**显式**的地址类型声明（否则只能靠「帧里没有 `=` 就拿整帧当值」这种猜测） | `AddressType` 增 `tcp-line`（或映射增 `decode_format` 列）；验收：裸值帧解出数值、且键值帧仍走键路径（互不串味） |
| 十六进制/JSON 负载 | 未涉及本次缺陷；且需要显式声明 | 同「整帧即值」：新增解码格式声明后再实现；**不建议**把 `byte[]` 转 hex 充数（无值语义、曲线仍画不出） |

> 这些都要动**平台数据模型**（列/枚举/校验/前端表单），故不在本次「修一条链路」的范围内，登记为后续增量；
> 本次只做**能不做假设就做对**的那部分。

---

## 5. 反哺与替换路径（**必读**）

1. **反哺**：`STARTER-FEEDBACK.md` **SF-6**（现象/证据/影响/期望能力/验收标准/会被替换掉的临时实现），并在
   starter 仓开同名 issue（链接见该条）。
2. **期望的框架能力**（二选一即可）：
   - **A（推荐）** 给 TCP 模块加 `ypbin.iot.protocol.tcp.payload-format`（`text`/`number`/`binary`，与 MQTT 同名同语义，
     `text` 用**严格 UTF-8**、非法即 BAD），并给 `PointValue` 一个「按点位声明取帧内字段」的宿主钩子
     （例如 `SubscribeRequest` 上带 `valueSelector`）；
   - **B** 在 core 增 `ValueDecoder` SPI（协议模块解码前回调宿主），语义与本仓的 `ValueDecoder` 对齐。
3. **替换动作（框架能力就位后）**：
   - 删除 `cn.ypbin.admin.access.decode`（3 个类 + 1 个实现）与 `AccessIotProtocolConfiguration` 里的解码器 bean；
   - `PointMappingDataListener` 去掉解码分支（回到「协议栈给什么就用什么」）；
   - 设备/产品侧改配置：`ypbin.iot.protocol.tcp.payload-format=text`；
   - **保留** `raw_address` 作为帧键的约定（框架侧若采用 B 方案，这条约定继续成立）；
   - 届时 `iot.access.decode.failure` 指标随实现一起下线，改由框架的 BAD 值/消息键承接。
4. **过渡期的生效范围（明确边界）**：仅 `protocol=tcp` 的 `byte[]` 载荷；只覆盖文本键值帧；
   其它协议与其它形态不受影响；它**不修复** §3.1 记录的「TCP 只投递订阅地址列表第 0 个」这一框架限制。

---

## 6. 排障速查

| 现象 | 指标 | 常见原因 |
|---|---|---|
| 该设备完全没新行，`reason=key-not-found` | `iot.access.decode.failure{reason="key-not-found"}` | 点位映射 `raw_address` 与帧里的键不一致（本次演示数据就是从 `holding:0` 改成 `TEMP`） |
| `reason=not-key-value-frame` | 同上 | 帧不是 `KEY=VALUE`（裸值/JSON/二进制）⇒ 见 §4.2 |
| `reason=not-utf8` | 同上 | 二进制寄存器帧；需寄存器级解码参数（§4.2） |
| `reason=unknown-data-type` | 同上 | `iot_property.data_type` 缺失或不在 9 类之内 |
| `reason=not-numeric` | 同上 | 值非数值或整数越界（越界**不截断**） |
| 值仍是 `[B@…`（理论上不该出现） | `iot.access.egress.invalid` 增长 | 解码层之外的新路径绕过了 `ValueDecoder`（请补挂解码器，而不是放宽闸门） |
