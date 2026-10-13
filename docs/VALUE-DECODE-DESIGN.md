# 读数「值解码」设计与缺口登记（VALUE-DECODE-DESIGN）

> **用途**：说清「TCP 帧 → 平台规范值」这条链路上**谁该解码、现在还缺什么、本轮做到哪、后面怎么替换**。
> 一手材料（全部本机可复现）：starter 源码（tag `v0.1.0`）、本仓源码、生产容器里的 fat jar 清单、
> 生产 IoTDB / MySQL 真值。
> 最后更新：**2026-10-13**。
> 关联文档：[`ACCESS-ENABLE.md`](ACCESS-ENABLE.md) §6.3（现场证据）。框架侧反哺（原 SF-6）已由
> starter v0.2.0 的 `ypbin.iot.protocol.tcp.payload-format`（`text`/`number`/`binary`）承接；
> **v0.3.0（2026-10-12）追加 `key-value`（帧内字段选择 / valueSelector）** ⇒ 本仓「帧解析」过渡层
> 已删除；**物模型类型规范化仍留在宿主**（SDK 明确不做类型转换），职责切分与理由见 §5。

---

## 1. 结论先行

| 问题 | 结论（证据） |
|---|---|
| `[B@<hash>` 是谁产生的？ | **两段合成**：① 帧的**原始字节**来自 starter 的 TCP 协议模块——`ypbin-iot-protocol-tcp` 的 `TcpSession#dispatch` 把整帧 `byte[]` 直接当作 `PointValue.value`（tag `v0.1.0` = `878a493`，该文件第 **295** 行 `PointValue.good(address, payload, ...)`，`payload` 自第 221 行的 `Consumer<byte[]>`）；② access 的 `HttpAccessReadingSink` 用 `String.valueOf(reading.value())` 把它字符串化 ⇒ `[B@<identityHash>`。**不是** IoTDB 读出来的字节数组（生产库里存的就是字面文本，见 §1.1）。 |
| 解码该在哪一层？ | **理想在协议层**：姊妹模块 MQTT 有 `MqttPayloadFormat`（TEXT/NUMBER/BINARY，`MqttSession#decode` 按配置解码并给出 BAD 值），**TCP 没有等价能力**，且 core 里没有任何「宿主可插的解码 SPI」⇒ 这是**框架侧能力缺口**。 |
| 本轮选哪条路？ | **先 B 过渡实现，再随 starter 交付替换**：2026-09-27 先在 access 侧加过渡解码层（`ValueDecoder` / `TextFrameValueDecoder`）；starter **v0.3.0（2026-10-12）**交付 `payload-format=key-value`（valueSelector）后，**「帧解析」部分已删除**。**「物模型类型规范化」仍留在宿主**——SDK 只做字段选择、不做类型转换（其类注释明确），且它是 `applyScale` 与落列形态的前置。职责切分见 §5。 |

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

## 3. 当前实现（2026-10-13 职责切分后）

```
TCP 帧 byte[] ──► starter TcpSession（payload-format=key-value）
                     └─ TcpValueSelector：UTF-8 + KEY=VALUE 拆分 + 按 raw_address(selector) 取值
                          ├─ 失败 ⇒ 该点位 BAD（value=null + qualityReason）+ SDK 侧 iot.tcp.* 指标
                          └─ 成功 ⇒ PointValue(value=String)
                     ──► PointMappingDataListener（宿主）
                          ├─ BAD ⇒ 原样穿透（不规范化、不计宿主失败）
                          ├─ GOOD + protocol=tcp ⇒ PointValueNormalizer（按 iot_property.data_type 规范化）
                          │     └─ 失败 ⇒ 丢弃 + iot.access.value.normalize.failure{reason} + WARN（脱敏）
                          └─ AccessReading.applyScale ⇒ AccessReadingSink
```

**职责边界（关键，别再混为一谈）**：

| 环节 | 承接方 | 说明 |
|---|---|---|
| UTF-8 校验、`KEY=VALUE` 拆分、按 `raw_address`（= selector）取键 | **starter 0.3.0** `TcpValueSelector` | 由 `ypbin.iot.protocol.tcp.payload-format=key-value` 启用；失败以 BAD 质量 + `iot.tcp.*` 指标显式表达 |
| **物模型类型规范化**（`int`/`long`/`decimal`/`bool`/文本） | **宿主** `cn.ypbin.admin.access.egress.PointValueNormalizer` | SDK 类注释明确「不做数据类型转换，类型属于宿主物模型语义」；见下「为什么不能删」 |
| 线性缩放/偏移 | **宿主** `AccessReading.applyScale` | 只对 `Number`/`BigDecimal` 生效 ⇒ **依赖上一步** |
| 落 `value_double` / `value_text` | iot 侧 `ReadingValueMapper` | 按值的**词法形态**判定 ⇒ 也依赖类型规范化（如 bool `1` 必须先变 `true`） |

**🔴 为什么「类型规范化」不能随帧解析层一起删**（2026-10-13 复核，独立纠正）：

1. `AccessReading.applyScale` 只吃 `Number`/`BigDecimal`。SDK 交付 `String` ⇒ 直接删会让
   `scaleFactor`/`offsetValue` **静默失效、读数错值**（不是格式问题）；
2. `ReadingValueMapper` 按词法落列：bool `1/0` 必须先规范成 `true/false` 才落 `value_text`（与切换前一致）；
3. `int`/`decimal` 收到非数值、`bool` 收到非法形态、`dataType` 缺失/未知时，切换前是
   「丢弃 + 计数 + WARN（不猜）」；这条纪律必须保留。

**失败可见性（切分后）**：

- **帧/选择失败**（非 UTF-8、无键值字段、selector 未命中、值为空）⇒ SDK 以 BAD 质量表达，
  指标 `iot.tcp.selector.not-found` / `iot.tcp.payload.not-key-value` / `iot.tcp.payload.not-utf8` 等；
  宿主 **BAD 原样穿透**（不重复计数）。
- **类型规范化失败**（非数值/非布尔/类型未知）⇒ 宿主丢弃 + 计数
  **`iot.access.value.normalize.failure{reason=…}`**（取值集合有界）+ WARN（只记设备/点位/地址/**值长度**与原因，**不 dump 值内容**）。
- 旧指标 `iot.access.decode.failure` 随帧解析层**一并下线**（登记见 `docs/METRICS.md` §2.6）。

**兜底闸门不变**：`HttpAccessReadingSink` 若发现值仍是 `byte[]` ⇒ 计数（`iot.access.egress.invalid`）+ WARN + 丢弃
⇒ `[B@…` 在任何单条路径走偏时也不会入库。当前已知的 `byte[]` 交付路径是 MQTT `payload-format=binary` 与
OPC UA `ByteString`（生产 access fat jar 不含这两个协议模块，属潜伏项）。

## 4. 真实设备解码：扩展点与映射方式

### 4.1 已实现

| 协议 / 帧形态 | 声明方式 | 产出（2026-10-13 切换后） |
|---|---|---|
| TCP 文本键值帧 `TEMP=23.5,SEQ=12` | `payload-format=key-value` + `raw_address='TEMP'`（selector） + 属性 `data_type=decimal` | SDK 选字段 → 宿主规范化 `BigDecimal(23.5)` → `value_double` |
| TCP 文本键值帧中的文本点 | 同上，`raw_address='SN'` + `data_type=string` | SDK 选字段 → 宿主原样保留 `007` → `value_text` |
| TCP 文本键值帧中的布尔点 | 同上，`raw_address='SW'` + `data_type=bool` | SDK 选字段 → 宿主 `TRUE`/`1` → `true` → `value_text`（§5.2.1 布尔存文本） |
| 任意协议的**已解码**值（Modbus/OPC UA/MQTT 非 binary） | 无需声明 | 非 `String` 原样透传（宿主规范化只作用于 TCP 的 `String`） |

### 4.2 未实现（如实声明 + 方案）

| 需求 | 为什么没做 | 建议落法（含验收） |
|---|---|---|
| Modbus 寄存器 16/32 位、有符号/无符号、大小端 | `iot_point_mapping` **没有位宽/符号列**；用「载荷长度猜位宽」或「默认无符号」都是猜，违反「不猜」 | 映射表增 `word_count`（1/2）、`signed`（0/1），与既有 `byte_order` 组合成寄存器解码；验收：`byte_order=big/little` × `word_count=1/2` × `signed` 的 8 组用例 + 长度不足用例 |
| 位域（bit offset/length） | 同上，缺列 | 增 `bit_offset`/`bit_length`；验收：越界位宽报错、单 bit 取布尔 |
| 字符串寄存器（多寄存器拼 UTF-8） | 同上，缺列 | 增 `word_count` + `encoding`；验收：中文/半角混合、奇数字节 |
| 「整帧即值」（无键的裸值帧） | 需要一个**显式**的地址类型声明（否则只能靠「帧里没有 `=` 就拿整帧当值」这种猜测） | `AddressType` 增 `tcp-line`（或映射增 `decode_format` 列）；验收：裸值帧解出数值、且键值帧仍走键路径（互不串味） |
| 十六进制/JSON 负载 | 未涉及本次缺陷；且需要显式声明 | 同「整帧即值」：新增解码格式声明后再实现；**不建议**把 `byte[]` 转 hex 充数（无值语义、曲线仍画不出） |
| **其余 `byte[]` 交付路径的宿主侧解码**：MQTT `payload-format=binary`、OPC UA `ByteString` 节点 | TCP 已由 starter `key-value` 承接帧解析；这两条仍交付原始 `byte[]`，宿主不认领 ⇒ 被出口闸门丢弃（见 §3） | 由对应协议模块的 `payload-format`（MQTT 可用 `text`/`number`）或宿主协议解码器处理；验收：负载能按业务协议解出值且不再被闸门丢弃。⚠️ OPC UA 这条**未实机验证**（无模块/设备） |

> 这些都要动**平台数据模型**（列/枚举/校验/前端表单），故不在本次「修一条链路」的范围内，登记为后续增量；
> 本次只做**能不做假设就做对**的那部分。

---

### 4.3 Modbus 寄存器解码 v1（**设计定稿，2026-10-13；实施前评审本小节**）

> 目标：落地 §4.2 第一行「Modbus 寄存器 16/32 位、有符号/无符号、大小端」。字段契约已冻结
> （`word_count`/`signed` 新增 + 复用 `byte_order`），本小节是实现口径；与前端并行，**命名不得各自发挥**。

#### 4.3.1 字段契约（冻结）

| 列 | 类型 | 语义 |
|---|---|---|
| `word_count` | `INT NOT NULL DEFAULT 1` | 连续寄存器个数；本版合法值 `1`\|`2`；默认 1 = 今天的行为 |
| `signed` | `TINYINT NOT NULL DEFAULT 0` | `0`=无符号 / `1`=有符号（整型） |
| `byte_order`（复用） | `VARCHAR(16) NULL` | **字序（word order）**：`big`=高字在前（ABCD）/`little`=低字在前（CDAB）；仅 `word_count=2` 生效 |

接口字段（camelCase，前端契约）：`wordCount`（1\|2）、`signed`（**0\|1 整数，不是 Boolean**——与 DDL `TINYINT` 一致）、
`byteOrder`（已存在，`big`\|`little`）。`address_type` 保持既有取值，本版不改语义。

#### 4.3.2 生效范围与校验（不猜、明显错配拦住）

| 条件 | 规则 |
|---|---|
| 仅 **Modbus**（`holding`/`input` 寄存器） | 三个字段生效 |
| `word_count=2` | `address_type ∈ {holding,input}`；`raw_address` 必须是显式 `type:offset` 且 `type` 与 `address_type` 逐字一致；`byte_order` 为 null ⇒ 按 `big` |
| `word_count=1` | `byte_order` 忽略（写 `big` 等价于不写）；`signed` 作用于 16 位值 |
| `coil`/`discrete`（位类型） | `word_count≠1` 或 `signed≠0` ⇒ **拒绝**（位类型没有宽度/符号语义） |
| 非 Modbus（tcp/mqtt/opcua） | 三字段必须为默认（`1/0/null`）⇒ 否则**拒绝**（明显错配）；默认值不改变行为 |
| `dataType` | `word_count=2` 或 `signed=1` 时要求 `iot_property.data_type ∈ {int,long}`（v1 仅整型） |
| `word_count=2` + 传统 5 位编号（如 `40001`） | **v1 拒绝**：主机侧算 `base+1` 需复刻 SDK 的段基址/1-based 规则（第二份真源）；显式 `type:offset` 无歧义 |

#### 4.3.3 解码矩阵（8 组 + 长度不足）

输入：`R0` = base 寄存器（SDK 交付 16 位无符号 `Integer` 0..65535）、`R1` = base+1。

| word_count | signed | byte_order | 规则 | 输出类型/域 |
|---|---|---|---|---|
| 1 | 0 | 忽略 | `value = R0` | `Integer` 0..65535（**原样透传，与今天逐字节一致**） |
| 1 | 1 | 忽略 | `signExtend16(R0)` | `Integer` -32768..32767 |
| 2 | 0 | big | `(R0 << 16) \| R1` | `long` 0..4294967295 |
| 2 | 0 | little | `(R1 << 16) \| R0` | `long` 0..4294967295 |
| 2 | 1 | big | `signExtend32((R0 << 16) \| R1)` | `long` -2147483648..2147483647 |
| 2 | 1 | little | `signExtend32((R1 << 16) \| R0)` | `long` -2147483648..2147483647 |

- 8 组用例 = `byte_order{big,little} × word_count{1,2} × signed{0,1}`（`word_count=1` 时 `byte_order` 两组同值，仍按 8 组覆盖）；
- 32 位无符号用 `long` 承载 `0..2^32-1`，**不截断、不溢出**；符号按 two's complement；
- **长度不足**（任一寄存器缺失/响应短/BAD）⇒ 该点位 **BAD**，**不得用 0 充数**。

#### 4.3.4 配对与失败语义（宿主侧，本任务难点）

- **订阅扩地址**：`word_count=2` ⇒ 订阅 `base` 与 `base+1`（同 `address_type`）；`raw_address` 仍是**基址**，不把宽度编码进去；
- listener 建 `registerAddress → (point, wordIndex)` 映射：base→0、base+1→1；`word_count=1` 只映射 base；
- **GOOD**：`word_count=1` ⇒ 立即解码/上报；`word_count=2` ⇒ 暂存 `(wordIndex → 值+时刻)`，两半齐 ⇒ 合成**一条**读数并清空；
- **BAD**：任一寄存器 BAD ⇒ 立即产出该点位 **BAD**（带 SDK 的 `qualityReason`）并清空暂存；**绝不用陈旧一半拼值、绝不用 0 充数**；
- **陈旧一半**：同一 half 再次到达，或另一 half 与暂存 half 的时刻差 > 容差（取采集周期，未知时 5s）⇒ 丢弃暂存 + 计数
  **`iot.access.modbus.pair.stale`**，再用新值暂存；**不与新值混**；
- **暂存有界**：每设备至多「`word_count=2` 的点位数」条；会话变化/重订阅 ⇒ 新建 listener（旧暂存随之失效）；
  `forget` 后下次订阅同样新建；未完成的半字在无后续回调时最多驻留到 listener 被替换（不无界增长）；
- **不产生重复读数**：多地址单订阅下一次读只产出每点位一条。

#### 4.3.5 订阅扩地址规则

- 仅 `word_count=2` 扩；扩出的地址参与既有按 `raw` 去重（两个点位可共享寄存器）；
- 地址算法：v1 只接受显式 `type:offset`；`next = type:(offset+1)`；`offset+1 > 65535` ⇒ 校验期拒绝；
- 非 Modbus / `word_count=1`：订阅地址与今天完全一致。

#### 4.3.6 未支持（保持登记，不在本版）

IEEE754 浮点（需 `dataType=float` + 完整字节序枚举）；BADC/DCBA 字内字节交换（需扩 `byte_order` 枚举并单独评审）；
`coil`/`discrete` 的宽度/符号；字符串寄存器（多寄存器 UTF-8）与位域（§4.2 各自单独立项）；
`word_count=2` 的传统 5 位编号形式；一个点位跨不同寄存器区（base 与 base+1 必须同 `address_type`）。

#### 4.3.7 向后兼容、DDL 与门禁

- 默认 `word_count=1, signed=0` ⇒ 16 位无符号**原样透传**，现有点位行为逐字节不变（回归用例钉住）；
- DDL：ALTER **同时**写入 `007-iot-data.sql` 末尾与 `deploy/sql/migration/2026-10-13-iot-modbus-register-schema.sql`
  （排序最后）；更新 `iot-migrations.list`；登记 `ItSchema.EXTRA_SCHEMA_SCRIPTS`；跑 `tools/check-iot-sql-equivalence.sh`；
- 新指标 `iot.access.modbus.pair.stale` 登记 `docs/METRICS.md`（`MetricsRegistryGateTest` 覆盖）；
- `AccessPointMappingDto` 带 `wordCount`/`signed`（`byteOrder` 已有），`DeviceSpecServiceImpl.toPoint` 复制。

---

## 5. 职责切分、删除条件与 starter 反馈（**必读**）

### 5.1 删除条件如何被满足（2026-10-13）

原删除条件（本文件 2026-09-27 版）：**「starter 交付 `valueSelector` 或宿主可插解码 SPI」**。
starter **v0.3.0（2026-10-12）**交付了 `ypbin.iot.protocol.tcp.payload-format=key-value`，
其 `TcpValueSelector` 把订阅地址（`PointAddress.raw`）当 selector，从 `KEY=VALUE` 帧里为每个点位取值
（同一帧可服务 0..N 个点位）⇒ **帧解析部分的前置条件已满足**，故删除：

- `cn.ypbin.admin.access.decode`（`ValueDecoder` / `TextFrameValueDecoder` / `DecodeFailure` / `DecodeOutcome` / `DecodeFailureMetrics`）；
- `AccessIotProtocolConfiguration` 的解码器 bean 与 `AccessSubscriptionPlanner`/`PointMappingDataListener` 的解码器注入；
- 指标 `iot.access.decode.failure`（帧/选择失败由 SDK `iot.tcp.*` 承接，见 `docs/METRICS.md` §2.6）。

### 5.2 为什么「物模型类型规范化」不删（留在宿主）

`TcpValueSelector` 的类注释原文：**「本类不做数据类型转换，帧里的值以 `String` 交付——类型属于宿主物模型语义。」**
这是 starter 的**有意边界**，不是缺口。于是「文本 → 物模型类型」必须留在宿主，理由见 §3 的四点
（`applyScale` 只吃 `Number`/`BigDecimal`、`ReadingValueMapper` 词法落列、`NOT_NUMERIC` 拒绝语义）。
落地为 `cn.ypbin.admin.access.egress.PointValueNormalizer`（与删除前的 `canonicalize` 逐字等价）。

### 5.3 保留的契约

- **`raw_address` 仍是「帧内字段键」**：SDK 侧 `PointAddress.raw` 就是 selector，本约定不变；
- `scale_factor`/`offset_value` 仍作用在**规范化后**的值上（`AccessReading.applyScale`）；
- `data_type` 仍是类型规范化的唯一事实源（`iot_property.data_type` → `AccessPointMappingDto.dataType`）。

### 5.4 给 starter 的设计反馈（待决策，不在本任务内动 starter）

`key-value` 解决了**字段选择**，但「宿主可插解码 SPI / 协议层类型转换」这条需求**仍然存在**：
MQTT `payload-format=binary` 与 OPC UA `ByteString` 仍交付 `byte[]`，只能被出口闸门丢弃（§4.2）。
两条路（**待决策，本仓不预判**）：
- **接受现状 + 文档讲清**：宿主只保留 TCP 的物模型类型规范化，其余 `byte[]` 路径由各协议模块自己的
  `payload-format` 解决；
- **回头给 starter 提宿主可插解码 SPI**（原 §5 方案 B）：让协议模块解码前回调宿主，语义与本仓已删的
  `ValueDecoder` 对齐。建议按 starter 的版本列车另行评估。

### 5.5 订阅形态：key-value 下已实施「一次多地址订阅」（2026-10-13）

- **key-value 形态**：TCP 走**一次多地址订阅**（`AccessSubscriptionPlanner.subscribe` 的通用多地址路径）：
  一个 `SubscribeRequest` 携带全部**按 `raw` 去重**的地址、一个共享 `PointMappingDataListener`，
  设备级跟踪/退避用既有 `inFlight` + `subscribedSessions` 机制。收益：N 点位从 N 次订阅降为 1 次，
  同一帧只解析/派发一次。
- **其它形态（binary/text/number）或配置不可得**：保持**逐点位单订阅**（UP-4 契约，
  `subscribeTcpPerAddress`）——非 key-value 的 TCP 是「单点位流」，`TcpSession#subscribe` 对
  `addresses().size() > 1` fail-fast。
- **分流判据 = 配置形态**（`ypbin.iot.protocol.tcp.payload-format`）：由
  `AccessIotProtocolConfiguration` 从 SDK `TcpProperties.payloadFormat()` 解析后传入 planner
  （`ObjectProvider` 可选注入，缺失时按非 key-value）。**回退配置即回退订阅形态**：把
  `payload-format` 改回 binary/text/number，订阅自动回到逐点位单订阅，无需第二个开关，
  也天然是滚动回退手段。
- **去重**：key-value 下 SDK 对**每个地址条目**各产出一个 `PointValue`，重复 `raw` 会产生重复读数
  ⇒ 多地址请求按 `raw` 去重（非 TCP 路径保持既有行为不动）。
- **仍未覆盖 / 未核实**：SDK「多地址单订阅、逐 selector 产出」目前只有 starter 侧单测证据，
  本机无真实 TCP 设备 ⇒ **端到端未验**。上线后确认点：① `iot.access.subscribe.success` 每设备**一次**
  （而非 N 次）；② `iot.tcp.selector.not-found` 不增长；③ 各点位仍 GOOD 且无重复读数；
  ④ 订阅失败时 `iot.access.subscribe.failure` 按**设备级 1 次**（而非 N 次）。

### 5.6 生产帧形态普查（2026-10-13，只读；结论：本次切换对**在用设备**安全）

> 为什么必须先查：`payload-format` 是**接入节点级**配置（不是按设备配的）——只要有一台
> TCP 设备发的是非键值帧，「全局切 `key-value`」就会变成生产事故。故切换前对生产库做了一次**只读**普查。

**方法**（全部 `SELECT`，未做任何写入；表名与列名见 `deploy/sql/006-iot-schema.sql`）：

```sql
-- 设备按协议/状态分布
SELECT protocol, status, is_deleted, COUNT(*) FROM iot_device GROUP BY 1,2,3;

-- 每个 TCP 设备的点位清单 + 活跃度（last_good_at 是"最近一次 GOOD 读数"）
SELECT d.device_code, d.status, COUNT(pm.id) AS pts,
       GROUP_CONCAT(DISTINCT pm.raw_address ORDER BY pm.raw_address SEPARATOR '|') AS raws,
       MAX(dl.last_good_at) AS last_good, MAX(dl.last_observed_at) AS last_seen,
       MAX(dl.open_outage_id) AS open_outage
FROM iot_device d
LEFT JOIN iot_point_mapping pm ON pm.device_id = d.id AND pm.is_deleted = 0
LEFT JOIN device_liveness  dl ON dl.device_id = d.id AND dl.is_deleted = 0
WHERE d.protocol = 'tcp' AND d.is_deleted = 0
GROUP BY d.id, d.device_code, d.status ORDER BY MAX(dl.last_good_at) DESC;
```

**结论**

1. 生产 **13 台设备全部是 `demo-dev-*` 演示夹具**（无真实客户设备）：TCP 10、Modbus 1（已停用）、MQTT 1、OPC-UA 1。
2. **唯一在采的 TCP 设备是 `demo-dev-curve`（9300012）**：其 4 个点位 `TEMP|HUMIDITY|SERIALNO|BOUNDARY`
   与模拟器帧 `TEMP=…,HUMIDITY=…,SERIALNO=…,BOUNDARY=…,SEQ=…` **逐键对应**，
   且 `last_good_at` = 普查时刻（持续在采）⇒ **它就是键值帧**，切 `key-value` 后行为不变。
3. 其余 9 台 TCP 夹具的点位仍是 `holding:0`/`holding:1` 形态，属 **#217 之前的历史错位残留**
   （`4456fe1f` 已把 9300012 的 3 个点位从 `holding:1/100/200` 订正为键名，见
   `deploy/sql/fixes/2026-10-12-iot-demo-tcp-multi-key.sql`）。它们**早已离线**：`last_good_at`
   为 5～18 天前，且全部挂着未闭合断档（`open_outage_id` 非空）。旧行为下它们本就是 `key-not-found`
   （BAD），新行为下是 `selector-not-found`（BAD）⇒ **新旧等价**，切换只换消息键、不改变状态。

**因此**：本次切换的"炸半径"仅限演示夹具，对在用采集链路无影响。

> ⚠️ 若将来要复活这 9 台夹具（例如做断档/维护窗口演示），**必须先把 `raw_address` 对齐成帧里的键**
> （照 #217 的做法）——这与本次切换无关，是既有的演示数据错位；否则它们会稳定 BAD。


## 6. 排障速查

| 现象 | 指标 | 常见原因 |
|---|---|---|
| 该设备完全没新行，selector 未命中 | SDK `iot.tcp.selector.not-found`（BAD 质量） | 点位映射 `raw_address` 与帧里的键不一致（本次演示数据就是从 `holding:0` 改成 `TEMP`） |
| 帧不是键值格式 | SDK `iot.tcp.payload.not-key-value`（BAD 质量） | 帧不是 `KEY=VALUE`（裸值/JSON/二进制）⇒ 见 §4.2 |
| 帧非 UTF-8 | SDK `iot.tcp.payload.not-utf8`（BAD 质量） | 二进制寄存器帧；需寄存器级解码参数（§4.2） |
| `reason=unknown-data-type` | `iot.access.value.normalize.failure{reason="unknown-data-type"}` | `iot_property.data_type` 缺失或不在 9 类之内 |
| `reason=not-numeric` | `iot.access.value.normalize.failure{reason="not-numeric"}` | 值非数值或整数越界（越界**不截断**） |
| `reason=not-boolean` | `iot.access.value.normalize.failure{reason="not-boolean"}` | bool 点收到非 `true/false/1/0` 形态 |
| 值仍是 `[B@…`（理论上不该出现） | `iot.access.egress.invalid` 增长 | 新路径交付了未解码 `byte[]`（MQTT binary/OPC UA 见 §4.2；不要放宽闸门） |
