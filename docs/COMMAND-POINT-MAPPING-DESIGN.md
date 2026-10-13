# 命令点位映射（ref_type=command）设计说明

> 自 issue #224 / M-3 控制面。本文只覆盖 **点位映射对命令点位（`ref_type=command`）的接纳与排除边界**，
> 以及命令下发链路如何按映射解析目标点。前端（ypbin-iot-ui）不在本仓范围。

## 1. 结论

1. **不新增表结构**：`iot_point_mapping` 已有 `ref_type`（`property|command`，默认 `property`）
   与 `raw_address`/`address_type`/`rw`/`enabled`，足以表达「命令 → 协议物理点」。
   `property_id` 列在 `ref_type=command` 时承载 **`iot_command.id`**（列名历史遗留，不改名）。
2. **命令点位只在「控制」链路被接纳**：创建/编辑/查询 + `service_call` 下行解析。
3. **命令点位在「采集/读数」链路必须被排除**：坐标索引、设备规格下发、孤儿巡检、时序/最新值/可用率。
4. 命令点位是**下行写点**：不支持读侧解码字段（缩放/字序/多寄存器/有符号/位域/寄存器形态），
   非默认即显式拒绝（开发阶段严格、不做静默忽略）。

## 2. 接纳 / 排除清单（逐链路）

| 链路 | 入口 | 对 `ref_type=command` | 落点 |
|---|---|---|---|
| 创建映射 | `POST /devices/{id}/points` → `IotPointMappingServiceImpl#create` | **接纳**（校验命令存在、产品已发布、服务可达、无读侧解码字段） | 本任务的 `validateCommandReference` |
| 编辑映射 | `PUT /devices/{id}/points/{id}` → `#update` | **接纳**（同上；归属与跨设备迁移约束不变） | 同上 |
| 查询映射 | `GET /devices/{id}/points` → `#listByDevice` | **接纳**（`refType` 原样回显） | 既有 |
| 命令下发 `service_call` | `POST /devices/{id}/commands` → `CommandInstanceServiceImpl#send` | **接纳并解析**：按 `(device_id, ref_type=command, property_id=command_id, enabled=1)` 取唯一映射，写入下行 payload 的 `point` 字段 | 本任务的 `resolveCommandPoint` |
| 命令下发 `property_set`/`property_get` | 同上 | **不解析**（目标属性走 `ref_type=property` 映射；§7.6 现有 payload 契约不变） | 既有 |
| 属性读数落库 | `/internal/readings` → `AvailabilityServiceImpl#ingest` | **排除**：坐标索引 `PointMappingIndex` 只取 `ref_type=property`，命令点位不产出坐标 ⇒ 读数按「未映射」丢弃并计数 | 本任务的坐标索引过滤 |
| 最新值 / 时序 / 影子 | 由读数派生 | **排除**（随读数被拒而不会写入） | 传递性 |
| 可用率 | `AvailabilityServiceImpl` | **排除**（命令映射不进映射集合，不参与断档判定） | 传递性 |
| 设备规格下发 | `GET /internal/device-specs/tenant` → `DeviceSpecServiceImpl` | **排除**：`points` 只含 `ref_type=property`；否则接入侧会去采集命令点位 | 本任务的规格查询过滤 |
| 孤儿映射巡检 | `RetentionCleanupServiceImpl` → `IotPointMappingMapper#countOrphanMappings` | **排除**：统计只算 `ref_type=property`；命令点位没有 `iot_property` 行，不能算孤儿 | SQL 加 `m.ref_type='property'` |
| 告警规则点位 | `iot_alert_rule_point.property_id`（属性标识） | 不在本任务范围；命令标识不会进读数坐标，故不可达 | 不改 |

## 3. 命令点位模型（复用既有列）

| 列 | 命令点位语义 |
|---|---|
| `ref_type` | 固定 `command` |
| `property_id` | `iot_command.id`（命令定义） |
| `raw_address` / `address_type` | 目标协议物理点（Modbus 寄存器地址 / NodeId / topic…） |
| `rw` | 读写方向（DTO 校验 `R|W|RW`） |
| `enabled` | 停用后不参与下发解析，也不下发规格（与属性点同口径） |
| 缩放/字序/word_count/signed/register_format/bit_* | **不适用**（下行写点），非默认即拒绝 |

## 4. 下行 payload 契约扩展（`service_call`）

有且仅有一条启用的命令映射时：

```json
{
  "requestId": "cmd-xxxx",
  "params": { "target": 26 },
  "point": { "rawAddress": "holding:0", "addressType": "holding", "rw": "W" }
}
```

- 无映射：`point` 字段**不出现**，payload 与 §7.6 B2 完全一致（设备自解析，向后兼容）。
- 多条启用映射：**发布之前**显式业务错误（不静默挑一条）。
- `point` 是**附加**字段，不改变 `requestId`/`params` 语义；MQTT topic 不变。

## 5. 测试覆盖

| 验收点 | 测试 |
|---|---|
| ① 创建/校验命令映射成功 | `IotPointMappingServiceImplTest#commandMappingShouldBeAcceptedAndPersisted`、`#invalidCommandReferenceShouldBeRejected`、`#commandReadDecodeFieldsShouldBeRejected`、`#illegalRefTypeShouldBeRejected` |
| ② 属性读数链路拒绝命令映射 | `PointMappingIndexTest#commandMappingsMustBeExcludedFromCoordinateIndex`（钉住查询的 `ref_type=property`）；真库侧 `IngestPointMappingIT#commandMappingMustNotFeedPropertyReadings`（需 MySQL，`mvn test` 不执行） |
| ③ 设备规格不下发命令映射 | `DeviceSpecServiceImplTest#commandMappingsMustBeExcludedFromDeviceSpec`（钉住查询的 `ref_type=property`） |
| ④ 命令下发按映射解析到点位 | `CommandInstanceServiceImplTest#serviceCallWithCommandMappingMustCarryPoint`、`#serviceCallWithoutCommandMappingKeepsContract`、`#ambiguousCommandMappingMustBeRejectedBeforePublish`、`#propertySetMustNotResolveCommandMapping`；施工侧 `CommandPayloadsTest#serviceCallPayloadWithPoint` |
| 孤儿巡检 SQL 排除命令点位 | `IngestPointMappingIT#orphanCountSqlMustWorkOnRealSchemaWithTenantPlugin`（新增命令点位探针；需 MySQL） |

## 6. 未做 / 待办（如实登记）

- **命令参数 ↔ 寄存器参数的逐参数映射**未建模（issue #224 提到的「参数」）：当前映射只承载目标地址，
  写值的编码仍由设备/接入侧按命令入参处理。若后续要在接入侧直写协议，需要新增「参数映射」列/表。
- 未新增唯一约束：命令点位重复由运行期 fail-fast（>1 条启用映射拒绝下发）兜底；DB 层未加唯一键。
- 独立子代理验收与实际下发联调见验收记录。
