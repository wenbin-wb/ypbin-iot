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
| ~~Modbus 寄存器 16/32 位、有符号/无符号、字序~~ | **已由 §4.3 v1 落地（2026-10-13）**：`word_count`/`signed` 新增、`byte_order` 复用为字序；IEEE754 浮点与 BADC/DCBA 字节交换**仍不在 v1**（见 §4.3.6） | 见 §4.3；验收：8 组矩阵 + 长度不足 + 配对/陈旧 + 向后兼容 |
| 位域（bit offset/length） | 同上，缺列 | 增 `bit_offset`/`bit_length`；验收：越界位宽报错、单 bit 取布尔 |
| ~~字符串寄存器（多寄存器拼 UTF-8）~~ | **已由 §4.5 v3 落地（2026-10-13）**：扩 `register_format=string`、`word_count` 放宽 1..32、固定 UTF-8（**不加 `encoding` 列**） | 见 §4.5；验收：中文/ASCII 混合、尾部 NUL、全 NUL、非法 UTF-8、单寄存器、跨字边界 |
| 「整帧即值」（无键的裸值帧） | 需要一个**显式**的地址类型声明（否则只能靠「帧里没有 `=` 就拿整帧当值」这种猜测） | `AddressType` 增 `tcp-line`（或映射增 `decode_format` 列）；验收：裸值帧解出数值、且键值帧仍走键路径（互不串味） |
| 十六进制/JSON 负载 | 未涉及本次缺陷；且需要显式声明 | 同「整帧即值」：新增解码格式声明后再实现；**不建议**把 `byte[]` 转 hex 充数（无值语义、曲线仍画不出） |
| **其余 `byte[]` 交付路径的宿主侧解码**：MQTT `payload-format=binary`、OPC UA `ByteString` 节点 | TCP 已由 starter `key-value` 承接帧解析；这两条仍交付原始 `byte[]`，宿主不认领 ⇒ 被出口闸门丢弃（见 §3） | 由对应协议模块的 `payload-format`（MQTT 可用 `text`/`number`）或宿主协议解码器处理；验收：负载能按业务协议解出值且不再被闸门丢弃。⚠️ OPC UA 这条**未实机验证**（无模块/设备） |

> 这些都要动**平台数据模型**（列/枚举/校验/前端表单），故不在本次「修一条链路」的范围内，登记为后续增量；
> 本次只做**能不做假设就做对**的那部分。

---

### 4.3 Modbus 寄存器解码 v1（**已实施，2026-10-13**）

> 目标：落地 §4.2 第一行「Modbus 寄存器 16/32 位、有符号/无符号、大小端」。字段契约已冻结
> （`word_count`/`signed` 新增 + 复用 `byte_order`），本小节是实现口径；与前端并行，**命名不得各自发挥**。

#### 4.3.1 字段契约（冻结）

| 列 | 类型 | 语义 |
|---|---|---|
| `word_count` | `INT NOT NULL DEFAULT 1` | 连续寄存器个数；本版合法值 `1`\|`2`；默认 1 = 今天的行为 |
| `signed` | `TINYINT NOT NULL DEFAULT 0` | `0`=无符号 / `1`=有符号（整型）；**Java 侧用 `Boolean`**（与 `enabled` 同一约定） |
| `byte_order`（复用） | `VARCHAR(16) NULL` | **字序（word order）**：`big`=高字在前（ABCD）/`little`=低字在前（CDAB）；仅 `word_count=2` 生效 |

接口字段（camelCase，前端契约）：`wordCount`（1\|2）、`signed`（**Boolean，JSON `true/false`**——与实体/`enabled` 同一约定；
DB 列仍是 `TINYINT NOT NULL DEFAULT 0`）、`byteOrder`（已存在，`big`\|`little`）。`address_type` 保持既有取值，本版不改语义。

#### 4.3.2 生效范围与校验（不猜、明显错配拦住）

| 条件 | 规则 |
|---|---|
| 仅 **Modbus**（`holding`/`input` 寄存器） | 三个字段生效 |
| `word_count=2` | `address_type ∈ {holding,input}`；`raw_address` 必须是显式 `type:offset` 且 `type` 与 `address_type` 逐字一致；`byte_order` 为 null ⇒ 按 `big` |
| `word_count=1` | `byte_order` 忽略（写 `big` 等价于不写）；`signed` 作用于 16 位值 |
| `coil`/`discrete`（位类型） | **新字段严格**：`word_count≠1` 或 `signed=true` ⇒ **拒绝**（位类型没有宽度/符号语义） |
| 非 Modbus（tcp/mqtt/opcua） | **新字段严格**：`word_count≠1` 或 `signed=true` ⇒ **拒绝**（新字段任何非默认值必然是配错，fail-fast） |
| `dataType` | `word_count=2` 或 `signed=true` 时要求 `iot_property.data_type ∈ {int,long}`（v1 仅整型） |
| `word_count=2` + 传统 5 位编号（如 `40001`） | **v1 拒绝**并给出可执行报错：`word_count=2 需要显式 type:offset 形式（如 holding:0）；传统 5 位编号不支持多寄存器` |
| **`byteOrder`（旧字段，宽容）** | 在「非 Modbus / 位类型 / `word_count≠2`」的点位上**忽略**（不拒绝）：它是既有自由文本列、历史上从未被消费；若因一个没人看过的旧值把编辑历史行拦下，是误伤 |

> **新字段严格 / 旧字段宽容的不对称是有意的**：`wordCount`/`signed` 是本版新增、语义明确，任何非默认值都只可能出现在
> 寄存器解码意图下 ⇒ 非适用场景一律 fail-fast；`byteOrder` 是历史遗留的自由文本，过去写入过什么值不可知，故仅在
> 真正生效的场景（Modbus + `word_count=2`）校验取值（`big`/`little`），其余场景忽略。

#### 4.3.3 解码矩阵（8 组 + 长度不足）

输入：`R0` = base 寄存器（SDK 交付 16 位无符号 `Integer` 0..65535）、`R1` = base+1。

| word_count | signed | byte_order | 规则 | 输出类型/域 |
|---|---|---|---|---|
| 1 | false | 忽略 | `value = R0` | `Integer` 0..65535（**原样透传，与今天逐字节一致**） |
| 1 | true | 忽略 | `signExtend16(R0)` | `Integer` -32768..32767 |
| 2 | false | big | `(R0 << 16) \| R1` | `long` 0..4294967295 |
| 2 | false | little | `(R1 << 16) \| R0` | `long` 0..4294967295 |
| 2 | true | big | `signExtend32((R0 << 16) \| R1)` | `long` -2147483648..2147483647 |
| 2 | true | little | `signExtend32((R1 << 16) \| R0)` | `long` -2147483648..2147483647 |

- 8 组用例 = `byte_order{big,little} × word_count{1,2} × signed{false,true}`（`word_count=1` 时 `byte_order` 两组同值，仍按 8 组覆盖）；
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

- IEEE754 浮点（需 `dataType=float` + 完整字序/字节序枚举，单独立项）；
- **BADC / DCBA 字内字节交换**（`byte_order` 本版只表达**字序**，不支持字节序交换；要支持需扩枚举并单独评审）；
- `coil`/`discrete` 的宽度/符号；
- 字符串寄存器（多寄存器 UTF-8）与位域（§4.2 各自单独立项）；
- `word_count=2` 的传统 5 位编号形式（见 §4.3.2 的明确报错）；
- 一个点位跨不同寄存器区（base 与 base+1 必须同 `address_type`）。

#### 4.3.7 向后兼容、DDL 与门禁

- 默认 `word_count=1, signed=false` ⇒ 16 位无符号**原样透传**，现有点位行为逐字节不变（回归用例钉住）；
- `word_count=1` 时**传统 5 位编号照常可用**（向后兼容不受影响，回归用例钉住）；
- `byte_order` 列注释统一改为**字序**（big=高字在前 ABCD / little=低字在前 CDAB），并写明不支持 BADC/DCBA；006 与 M1 迁移双写、跑等价门禁；
- DDL：ALTER **同时**写入 `007-iot-data.sql` 末尾与 `deploy/sql/migration/2026-10-13-iot-modbus-register-schema.sql`
  （排序最后）；更新 `iot-migrations.list`；登记 `ItSchema.EXTRA_SCHEMA_SCRIPTS`；跑 `tools/check-iot-sql-equivalence.sh`；
- 新指标 `iot.access.modbus.pair.stale` 登记 `docs/METRICS.md`（`MetricsRegistryGateTest` 覆盖）；
- `AccessPointMappingDto` 带 `wordCount`/`signed`（`byteOrder` 已有），`DeviceSpecServiceImpl.toPoint` 复制。

**落地清单（2026-10-13）**：`ModbusRegisterAddress`（显式地址规则，iot-api，校验与接入侧共用）、
`ModbusRegisterDecoder`（8 组矩阵纯函数）、`PointMappingDataListener`（半字配对/失败语义）、
`AccessSubscriptionPlanner`（`word_count=2` 扩 `base+1` + 按 raw 去重）、`ModbusRegisterMetrics`
（`iot.access.modbus.pair.stale`）、`IotPointMappingServiceImpl.validateRegisterDecode`（校验）。

---

### 4.4 Modbus 寄存器解码 v2（register_format + float32 + 位域）—— **已实施，2026-10-13**

> 目标：在 §4.3 v1（`int`）之上新增 `float`（IEEE754 单精度，2 寄存器）与 `bits`（单寄存器位域）。
> 契约 v2 已冻结；与前端 task-18 并行，命名不得各自发挥。字符串寄存器**不在本版**（issue #248）。

#### 4.4.1 列契约（冻结）

| 列 | 类型 | 语义 |
|---|---|---|
| `register_format` | `VARCHAR(16) NOT NULL DEFAULT 'int'` | 寄存器值形态：`int`\|`float`\|`bits` |
| `bit_offset` | `INT NULL` | 仅 `bits`：起始位（0..15） |
| `bit_length` | `INT NULL` | 仅 `bits`：位宽（1..16-`bit_offset`） |

接口字段（camelCase）：`registerFormat`、`bitOffset`、`bitLength`（+ v1 的 `wordCount`/`signed`/`byteOrder`）。

#### 4.4.2 形态校验矩阵（**新字段严格 / `byte_order` 历史宽容**）

| register_format | 适用协议/地址类型 | word_count | signed | bit_offset/bit_length | byte_order | data_type（本设计建议，**待你确认**） |
|---|---|---|---|---|---|---|
| `int`（默认） | Modbus `holding`/`input`；v1 规则不变 | 1\|2 | v1 规则 | **必须为 null**（新字段严格） | v1（仅 `word_count=2` 生效） | v1（`word_count=2`/`signed=true` ⇒ int\|long） |
| `float` | Modbus `holding`/`input` | **必须 2** | **必须 false**（true ⇒ 拒绝） | **必须为 null** | 生效：big=ABCD / little=CDAB（null⇒big） | **`decimal`** |
| `bits` | Modbus `holding`/`input` | **必须 1** | **必须 false** | **必须同时给**；`0≤offset≤15`、`1≤length≤16-offset` | **忽略** | `length=1` ⇒ `bool`；`length>1` ⇒ `int`\|`long` |

> **形态与 `word_count` 不相容时是「拒绝」，不是「静默改成期望值」**：`float` 且 `word_count≠2` ⇒ 拒绝；
> `bits` 且 `word_count≠1` ⇒ 拒绝。UI 会把它们置为固定值并禁用，但 API 直连调用必须 fail-fast——
> 静默纠正会让人以为配置生效了。

- **非 Modbus**（tcp/mqtt/opcua）与 **位类型**（coil/discrete）：`register_format≠int` 或 `bit_offset/bit_length` 非空 ⇒ **拒绝**（新字段严格）；
- `register_format` 非法取值 ⇒ 拒绝（Req `@Pattern` + 服务层双保险）；
- **`data_type` 约束只对 `float`/`bits` 两种新形态生效**；`int` 保持 v1 行为（不做新的 data_type 约束），
  否则会给现有点位引入新的拒绝路径 = 破坏向后兼容；错误文案写清「该形态要求属性类型是 X」；
- 所有拒绝都给**可执行文案**（说清该填什么/去掉什么），不许静默忽略；
- `byte_order`：`bits` 形态忽略；`int`/`float` 按上表（它是历史自由文本列，沿用 v1 的宽容口径）。

#### 4.4.3 IEEE754 组装与产出

- 32 位组装与 v1 一致：`big` ⇒ `(R0<<16)|R1`、`little` ⇒ `(R1<<16)|R0`（`R0`=base、`R1`=base+1；null⇒big）；
- `float f = Float.intBitsToFloat((int) raw32)`；
- **NaN/±Inf ⇒ 该点位 BAD + 计数**（规则与理由见 §4.4.6），不产出值；
- 有限值 ⇒ **`new BigDecimal(Float.toString(f))`**（float32 最短可回读形态；口径与理由见 §4.4.7）；
- 8 组必测：`big/little` × 正/负 × {`0.0`、`-0.0`、`1.5`、`3.4028235E38`、`NaN`、`+Inf`}；`-0.0` 经 `BigDecimal` 归一为 `0.0`（如实记录，IEEE 符号零对曲线无意义）；
- 不支持 IEEE754 双精度（4 寄存器，后续）。

#### 4.4.4 bits 取值语义

- `raw` 是 SDK 交付的 16 位无符号 `Integer`（0..65535）：`bits = (raw >>> bit_offset) & ((1L << bit_length) - 1)`；
- `bit_length=1` ⇒ 产出 **`Boolean`**（true/false）；`bit_length>1` ⇒ 产出 **无符号 `Integer`**（0..65535，**不**符号扩展、**不**做缩放前转换）；
- 产出后仍走既有 `AccessReading.applyScale`：`length>1` 的整数可被 scale/offset 变换；**`length=1` 的 Boolean 不参与 `applyScale`**
  （它只吃 Number/BigDecimal）⇒ **布尔位域不缩放**（文档与测试都要体现，避免有人以为配了 scale 就生效）；
- `byte_order` 对 bits 无意义 ⇒ 忽略；越界（`offset+1>16` 等）由映射校验拒绝；直连 DB 的坏数据在 listener 里按 BAD + 日志暴露，不静默。

#### 4.4.5 复用 v1 骨架（不另起一套）

- `float` 强制 `word_count=2` ⇒ 自动复用 v1 的 `AccessSubscriptionPlanner.expandAddresses`（扩 base/base+1）与
  `PointMappingDataListener.onMultiRegister`（半字配对/陈旧计数）；只在「两半齐了之后怎么解释」分流：
  `int` → `decodePair`（v1 不变）、`float` → `decodeFloatBits`；
- `bits` 强制 `word_count=1` ⇒ 走单寄存器路径，按 `register_format` 分流到 `decodeBits`；
- 失败语义完全沿用 v1：任一寄存器 BAD/缺失/非数值 ⇒ 点位 BAD，绝不用 0/陈旧一半；`iot.access.modbus.pair.stale` 不变。

#### 4.4.6 NaN/Inf 规则（最终，附理由）

**规则**：`float` 解出 NaN/±Inf ⇒ 该点位 **BAD** 并计数，**不产出值、不落库**。

**理由**：
1. 它们不是有效测量；写进曲线/统计会污染均值/最值/告警阈值；
2. iot 侧 `ReadingValueMapper.looksLikeDecimal` 会拒 `NaN`/`Infinity` ⇒ 只能落 `value_text`，成为文本垃圾（且 `Double.parseDouble` 语义特殊）；
3. `AccessReading.applyScale` 的 `new BigDecimal(number.toString())` 对 NaN/Inf 不可表示（会抛）⇒ 不拦就会在缩放路径炸。

**计数**：复用 **`iot.access.value.normalize.failure{reason=non-finite}`**（在 `NormalizeFailure` 增 `NON_FINITE`），
**不新增指标**。理由：该指标已是「宿主侧值无法规范化」的有界原因集，NaN/Inf 正属此类，且 `MetricsRegistryGateTest` 已覆盖该名字。
**`docs/METRICS.md` 必须把 `non-finite` 显式列进该指标的 `reason` 列表**（新 reason 必须登记，否则等于没登记）。

#### 4.4.7 float → BigDecimal 精度口径（最终，附理由）

**规则**：`new BigDecimal(Float.toString(f))` —— 取 **float32 的十进制最短可回读表示**；
**不做** `setScale`/四舍五入（配置了 `scale_factor`/`offset_value` 的仍由既有 `applyScale` 统一处理）。

**为什么用 `Float.toString` 而不是 `Double.toString`/`BigDecimal.valueOf((double) f)`**：
1. `Float.toString(f)` 保证 **round-trip 恒等**：`Float.parseFloat(Float.toString(f)) == f`；
   它给出**能唯一确定该 float32 的最短十进制**，不声明超出 float32 精度的位数。
2. `BigDecimal.valueOf((double) f)` 走 `Double.toString` ⇒ 对 float32 会输出 **17 位精确值**：
   `0.1f → 0.10000000149011612`、`23.4f → 23.399999618530273`、`3.4028235E38f → 3.4028234663852886E+38`。
   这会把「float32 只能唯一确定到 ~7 位有效数字」伪装成「设备报了 17 位小数」，污染曲线/阈值/CSV 导出，
   运维会误判成采集问题。`Float.toString` 给出 `0.1`/`23.4`/`3.4028235E+38`，是**不有损**的最短表示。
3. 反面同样要避免：`new BigDecimal(double)` 会得到二进制展开噪声（`1.1000000238418579`）；也不做任何截断/四舍五入
   （那才是有损）。
4. 若点位配了 `scale_factor`/`offset_value`，由既有 `applyScale` 统一 `setScale(6, HALF_UP)` —— 那是**配置的缩放语义**，
   不是解码精度损失；与 v1 int 路径一致（无缩放自然形态、有缩放走同一 `applyScale`）。
5. `-0.0f`：`BigDecimal` **没有负零** ⇒ `new BigDecimal("-0.0")` 归一到 `0.0`（IEEE 符号零对曲线/统计无意义，如实记录）。

#### 4.4.8 DDL/门禁与迁移文件名修正

- 007 末尾追加 ALTER；**migration 文件名用 `2026-10-13-iot-modbus-v2-register-format-schema.sql`**（不是
  `...register-format-schema.sql`）：等价脚本按**文件名字典序**拼接，`register-format...` 会排在 v1 的
  `register-schema...` **之前**，与 007 的追加顺序（v1 后 v2）不一致 ⇒ 门禁必红；`v2-` 前缀保证排最后
  （若你坚持原名，我改用 `2026-10-14-...` 以日期递增）；
- 更新 `iot-migrations.list` + `ItSchema.EXTRA_SCHEMA_SCRIPTS`，**顺序与目录字典序一致**（v1 之后）；跑 `bash tools/check-iot-sql-equivalence.sh` 并贴结果。

#### 4.4.9 未支持（保持登记）

IEEE754 双精度（4 寄存器）；BADC/DCBA 字内字节交换；有符号位域；
**字符串寄存器（issue #248）已由 §4.5 v3 落地**（固定 UTF-8，不含 `encoding`；BADC/DCBA 仍不支持）；
`register_format` 的其它取值；`float` 的 `bit_*` 与 `bits` 的 `signed`/多寄存器。

**落地清单（2026-10-13）**：`ModbusRegisterDecoder.decodeFloatBits/decodeBits`、`PointMappingDataListener`（float 复用 v1 配对、
NaN/±Inf ⇒ BAD + `iot.access.value.normalize.failure{reason=non-finite}`、bits 单寄存器且布尔不缩放）、
`IotPointMappingServiceImpl.validateFloatFormat/validateBitsFormat`（int 保持 v1）、实体/DTO/Req/Resp 新字段、
DDL（007 + `migration/2026-10-13-iot-modbus-v2-register-format-schema.sql`）。

---

### 4.5 Modbus 寄存器解码 v3（`register_format=string`：多寄存器拼 UTF-8）—— **已实施，2026-10-13**

> 目标：issue #248。契约 v3 的 4 条规则已由 Lead 冻结；与前端 task-18 并行，命名不得各自发挥。
> 本版**不新增数据库列**：`register_format` 扩第 4 个取值 `string`，固定 **UTF-8**（不加 `encoding` 列）。

#### 4.5.1 列 / 取值口径（冻结）

| 列 | 类型 | 语义 |
|---|---|---|
| `register_format` | `VARCHAR(16) NOT NULL DEFAULT 'int'` | 取值扩为 `int`\|`float`\|`bits`\|`string`；`string` 本版固定 **UTF-8**，不新增 `encoding` 列 |
| `word_count` | `INT NOT NULL DEFAULT 1` | `int`/`float`：`1`\|`2`；`bits`：`1`；`string`：**`1..32`**（≤64 字节）；**33+ 拒绝** |

接口字段（camelCase）：`registerFormat` 增加 `string`；`wordCount` 对 string 放宽到 `1..32`
（Req `@Max` 随之放宽，服务层按形态再收窄；`int`/`float`/`bits` 的既有范围与拒绝路径不变）。

`string` 校验（新字段严格，与 v2 同风格）：
- `signed` 必须 false（null/false 均可；true ⇒ 拒绝）；
- `bit_offset`/`bit_length` 必须为 null；
- `data_type` 必须 `string`；
- `word_count` 必须 `1..32`；
- `address_type ∈ {holding,input}`；**`word_count>1` 时** `raw_address` 必须是显式 `type:offset`
  且 type 与 `address_type` 一致（复用 v1 多寄存器地址规则）；`word_count=1` 时传统 5 位编号照常
  （无需扩地址，保持向后兼容）；
- 非 Modbus / 位类型 ⇒ 拒绝；所有拒绝都给可执行文案（说清该填什么/去掉什么）。

`int`/`float`/`bits` 的既有规则**一律不变**（含 int 不做 data_type 约束），必须有回归用例。

#### 4.5.2 填充与解码（冻结）

- **按寄存器顺序**：`word_index=0..N-1`（`base..base+N-1`），每寄存器**高字节在前**（Modbus 本体序）
  拼成字节串：`bytes[2i] = (word[i] >> 8) & 0xFF`、`bytes[2i+1] = word[i] & 0xFF`；
- **去掉尾部连续 `0x00`**；**不去空格**（空格可能是数据本身）；**中间 `0x00` 保留**（不截断）；
- **严格 UTF-8**（`CharsetDecoder` + `CodingErrorAction.REPORT`）：非法即点位 **BAD**，不替换字符、不静默；
- **全 NUL ⇒ GOOD + 空字符串**（设备确实报了「未填」；判 BAD 会把「没填」误报成「解析失败」）。
  ⚠️ **显示歧义登记**：空串在页面上既可能表示「设备未填」，也可能表示「内容就是空」；本版按「设备未填」处理，
  不做额外标记；
- **非法 UTF-8 ⇒ 点位 BAD + 计数** `iot.access.value.normalize.failure{reason=not-utf8}`：
  在 `NormalizeFailure` 增 `NOT_UTF8`，并在 `docs/METRICS.md` 的 reason 列表显式登记；
- `byte_order` 对 `string` **忽略**；**不支持** BADC（寄存器内字节交换）与 DCBA（寄存器倒序）——
  保持 §4.2/§4.3.6 登记，要支持须先扩枚举并单独评审。

#### 4.5.3 泛化后的配对（宿主侧）

**复核结论（v2 暂存结构是否够用）**：**不够**。v2 的 `pairBuffer` 是 `Map<String, Half>`，
`Half(wordIndex, value, ts)` 只存**一个** half，配对分支按「已有 half + 本次 half」两两合成，**假设恰好两个 half**；
string 要 `N` 个（1..32）⇒ 必须泛化为「按 `word_index` 收齐 N 个」。

**设计（复用同一套路径，不另起一套）**：
- `registerByAddress`：`word_count>1` 的点位把 `base+i` 映射到 `(point, i)`（`i=0..N-1`）；
  `word_count=1` 仍只映射 base；
- `pairBuffer` 值改为 `PendingWords(expected, Half[] slots, oldestTs)`（仍在同一 `pairLock` 短临界区内读写）；
- 任一 half 到达：若暂存存在且（任一同角色 slot 已填 / `oldestTs` 超容差）⇒ 整组丢弃 +
  `iot.access.modbus.pair.stale`，再以新值重建；否则填 slot；**N 个 slot 全齐** ⇒ 按 `word_index`
  升序合成一条读数并清空；
- 任一寄存器 **BAD** ⇒ 立即产出该点位 BAD + 清空暂存（沿用 v1/v2，绝不用陈旧一半/0 拼值）；
- 暂存有界：每设备至多「`word_count>1` 点位数」组，每组 ≤32 个 `Half` 槽（上界 = 32 × 多寄存器点位数 条引用，不随回调次数增长）；未齐的组在会话重建时随旧 listener 失效；
- `isMultiRegister` 从 `word_count==2` 泛化为 `word_count>1`；`string` 的 `word_count=1` 走单寄存器路径。

**订阅扩地址**：`AccessSubscriptionPlanner.expandAddresses` 从只扩 `base`/`base+1` 泛化为 `base..base+N-1`
（新增 `ModbusRegisterAddress.plus(raw, delta)`，`next` 委托它）；仍参与既有按 `raw` 去重。
⚠️ **大 N 由 SDK 分块**：Modbus 单次读上限/分块由 `modbus4j` 侧处理，宿主**不复刻**分块逻辑；
即便 SDK 把 `base..base+N-1` 拆成多次读，回调仍逐寄存器到达，宿主按同一 `word_index` 配对/拼接
（顺序由 `word_index` 保证，与到达批次无关）。

#### 4.5.4 值类型落地（**已定：方案 A 窄口径，仅 Modbus string**）

**已查清（一手）**：
1. `PointValueNormalizer` 只在 `protocol=tcp` 分支调用 ⇒ **Modbus `String` 不经过它**，不会被 TCP 类型规范化改动；
2. `AccessReading.applyScale` 只变换 `Number`/`BigDecimal` ⇒ `String` 原样返回；
3. `HttpAccessReadingSink.toObservation` 用 `String.valueOf(value)` 上报 ⇒ 文本原样发出；
4. **但** iot 侧 `ReadingValueMapper` 按**词法形态**落列：含字母/前导零的串 ⇒ `value_text`；
   **纯数字串**（如 `"123456"`）⇒ `value_double`（现有已登记限制见 `docs/IOT-PLATFORM-DESIGN.md:575`）。

**决策（Lead，2026-10-13）**：采用方案 A，但**范围收窄到仅 Modbus `register_format=string`**：
- 新增**可选**标记 `forceText`（缺省 null/false = 既有词法落列 ⇒ 对现有数据零影响）；
- **仅** string 点位置位；int/float/bits 与缺省标记的其它读数**保持词法落列、逐字节不变**；
- TCP `data_type=string` 的同类问题**不在本次范围**（可能有历史数据，改落列会让既有曲线/查询取不到值），
  另开 issue 登记（见 §4.5.6）。

#### 4.5.5 未支持（保持登记）

- BADC/DCBA（寄存器内字节交换与寄存器倒序）；
- GBK/其它编码（将来「先扩枚举再支持」）；
- IEEE754 双精度（4 寄存器）；有符号位域；
- 字符串寄存器跨不同寄存器区（`base..base+N-1` 必须同 `address_type`）；
- **TCP `data_type=string` 的物模型类型覆盖**（同一词法落列缺陷；历史数据兼容需单独评估，见 §4.5.6）。

#### 4.5.6 值类型落地的实现落点与理由（方案 A 窄口径）

**交接点：access 的 `PointMappingDataListener.emit(..., forceText)`。**
- 这里是**唯一**同时拿到「`register_format`」与「解码后值」的层：它知道该点位是不是 string，
  且是所有取值路径（单/多寄存器）的汇聚点；
- 透传链：`AccessReading` 加第 7 个组件 `forceText`（保留 6 参构造 = false，向后兼容）→
  `HttpAccessReadingSink.toObservation` 写入 `ReadingObservationDto.forceText`（可选，缺省 null）→
  iot 侧 `AvailabilityServiceImpl.collectSeriesPoints` 透传到 `TimeSeriesPoint.forceText` →
  `IotDbTimeSeriesWriter` 调 `ReadingValueMapper.map(value, forceText)`；
- `ReadingValueMapper.map(raw, true)` **不猜、不 trim**，原样落 `value_text`；
  `map(raw)`/`map(raw,false)` 保持既有词法规则（回归用例钉住）；
- **为什么不放 iot 侧按 `data_type` 查**：那需要在写入热路径逐批查物模型
  （`IOT-PLATFORM-DESIGN.md:575` 明确登记的代价）；显式标记由 access 在已知形态时置位，零额外查询；
- **为什么不改 `ReadingValueMapper` 的缺省行为**：缺省一变会影响所有来源（含 TCP string 历史数据）；
  显式标记把影响面收窄到 Modbus string。

**TCP `data_type=string` 同类问题（另开 issue 登记，不在本次范围）**：同一词法落列缺陷；
影响面是 TCP string 点位**可能已有历史数据落 `value_double`**，改落列会让既有曲线/查询取不到值 ⇒
需先评估数据兼容（迁移/双读/回填），单独立项。

#### 4.5.7 落地清单（2026-10-13）

`ModbusRegisterDecoder.decodeStringUtf8`（严格 UTF-8 + 去尾 NUL）、`ModbusRegisterAddress.plus`（`next` 委托）、
`PointMappingDataListener`（`PendingWords` 泛化配对 + string 单/多寄存器 + `forceText`）、
`AccessSubscriptionPlanner.expandAddresses`（base..base+N-1）、`NormalizeFailure.NOT_UTF8` +
`PointValueNormalizeMetrics` 预注册、`IotPointMappingServiceImpl.validateStringFormat`、
Req/实体/DTO 字段注释与 `@Pattern/@Max`、DDL 列注释（007 + v1/v2 迁移，SQL 等价门禁绿）、
`AccessReading`/`ReadingObservationDto`/`TimeSeriesPoint`/`ReadingValueMapper`/`IotDbTimeSeriesWriter`
的 `forceText` 透传。

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
