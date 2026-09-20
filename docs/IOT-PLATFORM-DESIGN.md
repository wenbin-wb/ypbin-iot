# ypbin-iot 平台完整设计（IOT PLATFORM COMPLETE DESIGN）

> **状态**：**v1.0 定稿**（2026-09-20）。复核历程：首轮外委复核判 **FAIL**（9 项必改，含
> `tenant_config_epoch` 未落地却标"已落地"、spec 悬空引用、IoTDA"官方三层含事件"失准、dataType 漏 long、
> 3b-2 目标态画成现状、M0b/provisioning 未映射里程碑等）→ 已全部修复 → 复评 **PASS（有条件）**
> → 6 项非阻塞建议一并清理。未核实项在文中以 ⚠️ 标注（IoTDB 细节：本会话网络受限，M-2 前须一手补核）。
> **定位**：自用 / 对外开源的物联网平台脚手架，预留半商用（继承 `IOT-CLOUD-SPEC.md` v4 的定位）。
> **形态**：`ypbin-iot` = `ypbin-admin` 的 fork + IoT 业务域，按 admin 的「业务域」模式增量添加。
> **方向（2026-09-20 用户拍板）**：以后不再按「最小可跑通增量」推进，一律以**最完善的功能与设计**展开；
> 本次为**整个平台一次性完整设计**，先出设计文档，经外委复核后再进路线与实现。
>
> **与既有文档的关系**：本文档是平台级**完整设计总纲**。
> `IOT-CLOUD-SPEC.md`（v4，旧独立栈 `ypbin-iot-cloud` 的 spec）的**机制性结论一律继承**（租约/失效检测/
> EMQX ACL 矩阵/可用率定义/多租户底线/Flyway 策略/入站可信校验），本文档不重述其推理，只在其上按
> 「ypbin-iot 形态 + 完整规格」做**增补与重写**；`docs/LEASE.md` 是租约机制的实施说明（已落地），本文档引用它。
> `docs/IOT-ROADMAP.md` 的增量路线按本文档 §14 重排为「完整规格」方向。

---

## 0. 决策记录（2026-09-20 评审要点）

| # | 决策 | 结论 |
|---|---|---|
| **D0.1** | 设计策略 | **整个平台一次性完整设计**（物模型/设备/数据面/控制面/配置面/规则告警/开放 API/多租户安全/前端/部署可观测），不再做最小切片 |
| **D0.2** | 物模型语义 | **对齐华为云 IoTDA 官方结构**：`产品(Product) → 服务(Service) → 属性(Property)/命令(Command)`；**事件（Event）为本平台在服务层的扩展**（IoTDA 的事件仅存在于运行期 topic/API payload，不在其 TSL 结构内）；不采用旧 spec A7 的「四类平铺」 |
| **D0.3** | 交付次序 | 先出完整设计文档 → 外委独立复核（R1–R8）→ 复核通过 → 更新路线 → 分阶段实现 |
| **D0.4** | 与 3b-2 关系 | 3b-2（协议栈接入）技术路径不变；但其 `DeviceRegistry.loadAll()` 的「采什么点位」依赖本文档 §3（物模型）+ §4（设备台账）⇒ 物模型完整设计**优先于或并行于** 3b-2 落地 |

**沿用 spec v4 的既有决策（不再重述推理，只列编号）**：
A1 access 独立部署 ｜ A2 部署单元收敛 ｜ A3 租户上下文只信网关注入 ｜ A4 租户插件强制注入 +
忽略表清单 ｜ A5 数据面走 EMQX 总线 ｜ A6 链路↔租户绑定来自设备台账 ｜ A7（**本文档 D0.2 取代**）｜
A8 平台层不做协议细节 ｜ A9 台账=租约+启动拉取+变更推送+周期对账 ｜ A10 主题命名空间+ACL 隔离 ｜
A11 变更推送以 epoch 为准。

---

## 1. 目标 / 非目标 / 硬约束

### 1.1 目标
| 用途 | 设计目标 |
|---|---|
| 自用（厂里/家里真实设备） | 接上真实设备长期稳定跑：断线重连、**可用率与断档可度量** |
| 对外开源 | 别人能跑起来：一键启动、快速开始、许可边界清晰、CI 真实 |
| 半商用预留 | 多租户隔离做对、开放 API 可用（**不做**计费/SLA） |
| 学习/展示 | 架构完整、边界清晰、文档可读、**物模型语义对齐主流平台** |

### 1.2 非目标
❌ 计费/套餐/账单　❌ SLA 与工单　❌ 自研组态编辑器　❌ 自研 MQTT Broker　❌ 自研时序库　❌ 设备 OTA　❌ 规则引擎可视化编排　❌ 大屏/数字孪生

### 1.3 硬约束
| 约束 | 影响 |
|---|---|
| 本机约 5.8G 内存，不跑容器 IT 与全量前端构建，一次只跑一个重型命令 | 本地不能同时起全部中间件；集成测试外移 CI |
| `ypbin-iot-starter` 未发布到 Central（实测 404） | CI 显式取源并锁定 SHA；源码树不落仓内 |
| 母仓编码铁律（RED） | 见 §10 安全与 §15 门禁 |

---

## 2. 总体架构与数据流

### 2.1 部署视图（目标态；3b-2 落地前的现状差异见文末注）

```
浏览器 / 第三方系统（HTTPS）
        │ 统一鉴权 + 租户上下文注入 + 剥离伪造头 + 入站可信校验
┌───────▼─────────────────────────────────────────┐
│ ① gateway（ypbin-gateway，既有）路由/鉴权/身份头   │
└──┬───────────────────────────────┬──────────────┘
   │                               │ Feign(内部,显式超时)
┌──▼──────────────────┐            │
│ ② business           │◄───────────┘
│   ├ ypbin-system(既有)│
│   ├ ypbin-auth(既有)  │
│   └ ypbin-iot(18084,本次主角)
│      product/tsl/device/point/影子/rule/alarm/data
└──┬──────────────────┘
   │ 租约(/internal/lease/**) + 变更事件 + 心跳
┌──▼──────────────────────────────────────────────┐
│ ③ access（18086，有状态，可扩缩）★                 │
│   现状：租约客户端 + self-fencing（增量 3a，已落地） │
│   目标态(3b-2)：+ iot-starter 宿主 DeviceRegistry/ │
│   ConnectionSpecProvider/DataSink + 真建链/断链    │
└──┬──────────────────────────────────────────────┘
   │ 南向：Modbus / OPC UA / MQTT / TCP
┌──▼──────────────┐
│ 现场设备 / 网关   │
└─────────────────┘
数据面总线：EMQX（$iot/dev/** 设备树 / $iot/svc/** 内部面 / $events/** 连接事件）【目标态】
基础设施：MySQL(元数据/租约) · IoTDB(时序)【目标态】 · Redis(最新值/影子/缓存) · EMQX【目标态】 · Nacos
```
> **现状差异注（2026-09-20）**：access 目前**未接 iot-starter**（3b-2 未完成），无 EMQX 总线/时序库；
> 图中 EMQX、IoTDB、`$iot/**` 主题、access 协议栈宿主均为**目标态**；另：图中 business 画为单单元是 **A2 收敛目标**，
> 现状 `deploy/docker-compose.yml` 中 system/auth/iot 为各自独立容器。

### 2.2 三条数据流（完整规格）

**① 数据面（设备→平台，上行）**
```
直连协议(Modbus/OPC UA/TCP)：设备 ──建链采集──▶ access
MQTT 设备：设备 ──pub $iot/dev/{t}/{d}/data──▶ EMQX ──▶ access 订阅 $iot/dev/**
汇合：access ──pub $iot/svc/data/{t}──▶ EMQX ──▶ business(ypbin-iot.data 写入)
      business ──▶ IoTDB(时序点) + Redis(最新值) + 断档判定(outage_event)
      business ──▶ 规则引擎(条件匹配) ──▶ 告警/通知/联动命令
```

**② 控制面（平台→设备，命令下行）**
```
UI/OpenAPI → business(core.device)
  ① 校验：权限 + 物模型命令可执行 + 设备在线 + 输入按命令 paras 校验
  ② Feign → access（显式超时；失败带可区分消息键）
  ③ access 按协议写点位：
      直连协议：直接写（iot-starter 写路径）
      MQTT 设备：access pub 到 $iot/dev/{t}/{d}/cmd/**
  ④ 回执：成功/超时/失败原因逐项返回（批量写按项标记）
```

**③ 配置面（台账变更→access）**
```
business 变更台账（产品/设备/点位映射/凭据）
  └─ 同一事务递增 tenant_config_epoch ──▶ 发事件 $iot/svc/config/{t}/device-changed
        ▼ access
   新增设备→建链 ｜ 改连接参数→断链重建 ｜ 改点位映射→只重载映射(不断链)
   删除设备→解绑+断链+吊销凭据+踢在线会话
   周期对账兜底：批量拉取所有租户 epoch，不一致才拉全量
```

### 2.3 模块归属（ypbin-iot 仓内新增模块）

| 部署单元 | 模块 | 职责 |
|---|---|---|
| business | `ypbin-iot-api`（契约） | 实体/DTO/Feign 接口/`LeaseEpochRules`（已落地，本文档 §12 扩展） |
| business | `ypbin-iot`（业务） | 产品/物模型/设备/点位/影子/分组/规则/告警/数据写入/开放 API |
| access | `ypbin-access`（+`-api`） | 租约客户端/self-fencing（已落地）+ 协议栈宿主（3b-2） |

> 部署单元收敛沿用 spec A2：access 独立；business 内系统/auth/iot 装配为部署单元，
> 是否再拆 `iot-openapi` 等**有触发条件**（见 §9）。

---

## 3. 物模型域（核心，对齐 IoTDA 三层）

> 一手依据：华为云 IoTDA 官方《创建产品》与《离线开发产品模型》（support.huaweicloud.com，
> 访问 2026-09-20）。官方结构：产品模型 = 产品信息(devicetype-capability.json) + 服务能力
> (servicetype-capability.json)，服务内定义属性与命令；事件是否属于服务的进一步核实见 §3.6。

### 3.1 结构与命名规范

```
产品 Product（一类设备：协议 + 物模型 + 采集模板）
  └── 服务 Service（能力域：一个可复用/可组合的能力单元；对齐 IoTDA 官方结构）
       ├── 属性 Property（可读/可写的数据点，如 temperature）
       ├── 命令 Command（可执行的指令，如 SET_PERIOD）
       └── 事件 Event（平台在服务层的扩展：主动上报的告警/故障/状态，如 overTemperature；
                       IoTDA 的事件仅存在于运行期 topic/API payload，不在其 TSL 结构内）
```

**命名规范**（服务/属性/命令**对齐 IoTDA 一手规范**；产品编码与事件标识为平台扩展约定，无 IoTDA 官方对应）：
| 对象 | 规范 | 示例 |
|---|---|---|
| 产品编码 productCode | 小写字母数字连字符，租户内唯一（平台约定） | `water-meter-v1` |
| 服务标识 serviceId | 单词首字母大写（PascalCase，IoTDA 规范） | `WaterMeterBasic` |
| 属性标识 identifier | 首单词小写驼峰（camelCase，IoTDA 规范） | `batteryLevel` |
| 命令标识 identifier | 全大写 + 下划线（IoTDA 规范） | `SET_READ_PERIOD` |
| 事件标识 identifier | 首单词小写驼峰（平台约定，对齐属性风格） | `overTemperature` |

### 3.2 产品（Product）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| tenant_id | bigint | 租户（租户插件注入） |
| product_code | varchar(64) | 编码，租户内唯一 |
| product_name | varchar(128) | 名称 |
| protocol | varchar(16) | `tcp`/`modbus`/`mqtt`/`opcua`（与 iot-starter 协议码一致） |
| data_format | varchar(16) | `json`（默认）｜`binary`（预留编解码插件） |
| device_type | varchar(64) | 设备类型描述（IoTDA deviceType） |
| manufacturer_id / manufacturer_name | varchar(128) | 厂商信息（可选） |
| status | varchar(16) | `draft`/`published`（物模型状态） |
| remark | varchar(255) | 备注 |
| created_at / updated_at | datetime | 审计 |

### 3.3 服务（Service）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| tenant_id | bigint | 租户 |
| product_id | bigint | 所属产品 |
| service_id | varchar(64) | 服务标识（PascalCase，产品内唯一） |
| service_name | varchar(128) | 服务名称 |
| option | varchar(16) | `master`/`mandatory`/`optional`（对齐 IoTDA） |
| sort | int | 排序 |
| description | varchar(255) | 描述 |

### 3.4 属性（Property）——字段对齐 IoTDA 一手

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| tenant_id / service_id | bigint | 租户 / 所属服务 |
| identifier | varchar(64) | 属性标识（camelCase，服务内唯一） |
| property_name | varchar(128) | 属性名 |
| data_type | varchar(16) | `int`/`long`/`decimal`/`string`/`bool`/`enum`/`date_time`/`json_object`/`array`（对齐 IoTDA 在线开发 9 类；**显式映射**：`bool`↔`boolean`、`date_time`↔`dateTime`、`json_object`↔`jsonObject`、`array`↔`stringList`（字符串数组）；导出 TSL 时按右侧官方名输出） |
| required | boolean | 是否必选（IoTDA 为非功能字段，我们用于校验语义） |
| access_mode | varchar(8) | `R`（只读）/`W`（可写）/`RW`（可读写）——即 IoTDA method（R/W/RW），此处用自说明命名 |
| min / max | decimal | 数值范围（int/decimal 生效） |
| step | decimal | 步长 |
| max_length | int | 字符串长度（string 类生效） |
| unit | varchar(32) | 单位（英文，如 `C`/`%`/`kPa`） |
| enum_list | json | 枚举取值表（code/name），enum 生效 |
| default_value | varchar(255) | 默认值 |
| expand | json | 扩展（如缩放系数、告警阈值参考） |
| sort | int | 排序 |

### 3.5 命令（Command）——字段对齐 IoTDA 一手

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| tenant_id / service_id | bigint | 租户 / 所属服务 |
| identifier | varchar(64) | 命令标识（大写+下划线，服务内唯一） |
| command_name | varchar(128) | 命令名 |
| input_params | json | 入参定义（字段同 §3.4 参数语义：paraName/dataType/required/min/max/step/maxLength/unit/enumList） |
| output_params | json | 出参定义（同入参） |
| timeout | int | 命令超时（毫秒，缺省走全局默认，M-3 控制面里程碑定） |
| sort | int | 排序 |

### 3.6 事件（Event）

**结论：事件作为平台在服务层的扩展纳入物模型**（与属性、命令并列；**注意**：IoTDA 官方 TSL 结构（devicetype/servicetype-capability.json）服务内只有属性与命令，**不含事件**——事件在 IoTDA 是**运行期**概念，仅存在于预置 topic 与 API payload，不落入其物模型 JSON。因此本文档的事件是「语义对齐 IoTDA 事件上报、结构上平台自建」）。
一手依据（华为云 IoTDA 官方，访问 2026-09-20/21）：
① 用户指南《创建产品》：「服务能力」= 把设备能力拆成服务，每个服务定义「属性、命令以及命令的参数」——**未含事件**，故事件不能声称"官方 TSL 结构"；
② IoTDA 预置 Topic 体系包含**事件上报**（`$oc/devices/{id}/sys/events/up`，与属性上报/消息上报/命令下发/设备影子并列）——事件上报语义存在；
③ 设备接入开发 PDF 中 `services` 对象含「设备服务的事件列表」字段——运行期服务挂事件。
> 落地：TSL 导出在服务对象上**扩展 events 数组**（字段对齐属性语义）；事件上报数据（如告警/故障/状态变更）进入规则引擎条件（§8）与告警闭环。

事件表结构（identifier/name/data_type/required/min/max/max_length/unit/enum_list，对齐 §3.4 属性字段语义）。

### 3.7 TSL JSON（物模型序列化，对齐 IoTDA 双文件结构）

物模型落库为关系表（§3.2–3.6），同时提供**可导出的 TSL JSON**（对齐 IoTDA 的
`devicetype-capability.json` + `servicetype-capability.json` 双文件结构，含版本化）：

```jsonc
// 产品级（devicetype-capability.json）
{
  "devices": [{
    "manufacturerId": "acme", "manufacturerName": "ACME",
    "protocolType": "modbus", "deviceType": "WaterMeter",
    "serviceTypeCapabilities": [
      { "serviceId": "WaterMeterBasic", "serviceType": "WaterMeterBasic", "option": "Mandatory" }
    ]
  }]
}
// 服务级（servicetype-capability.json）
{
  "services": [{
    "serviceType": "WaterMeterBasic",
    "commands": [
      {
        "commandName": "SET_READ_PERIOD",
        "paras": [ { "paraName": "value", "dataType": "int", "required": true,
                     "min": 1, "max": 24, "step": 1, "maxLength": 10, "unit": "hour" } ],
        "responses": [ { "responseName": "SET_READ_PERIOD_RSP",
                         "paras": [ { "paraName": "result", "dataType": "int", "required": true } ] } ]
      }
    ],
    "properties": [
      { "propertyName": "registerFlow", "dataType": "int",  "method": "R", "unit": "L" },
      { "propertyName": "batteryLevel", "dataType": "int",  "method": "R", "unit": "%" },
      { "propertyName": "readPeriod",   "dataType": "int",  "method": "W", "unit": "hour",
        "min": 1, "max": 24 }
    ],
    "events": [ { "eventName": "overTemperature", "dataType": "int", "unit": "C" } ]
  }]
}
```

> 导出方向：平台表 → TSL JSON（对外对齐 IoTDA）；导入方向：TSL JSON → 平台表（在线/离线开发），
> 导入必须全字段校验（命名规范/类型枚举/引用完整性），失败逐项报错，禁静默丢弃。

### 3.8 物模型版本化

| 机制 | 说明 |
|---|---|
| 状态机 | `draft → published`；已发布可建新草稿迭代 |
| 版本号 | `product_version`（语义化 `v{major}.{minor}`）；发布时递增 |
| 生效语义 | 设备绑定**产品+版本**；采集按绑定版本的点位映射执行；换版本=设备重新部署映射（断链重建按 §2.2③） |
| 变更纪律 | 版本发布后同版本**不可变**；变更走新版本 ⇒ 与 epoch/影子/历史数据可追溯对齐 |

### 3.9 点位映射（PointMapping）

把物模型逻辑点位（product 下 service.property/command）映射到协议物理地址：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint | 主键 |
| tenant_id / device_id | bigint | 租户 / 设备 |
| property_id | bigint | 关联属性（或命令） |
| raw_address | varchar(128) | 协议内地址（Modbus 寄存器地址/OPC UA NodeId/寄存器类型偏移） |
| address_type | varchar(16) | `holding`/`input`/`coil`/`discrete`/`nodeid`/`topic`… |
| poll_interval_ms | int | 采集周期（0=仅订阅不轮询，对齐 iot-starter `pollInterval`） |
| scale_factor / offset | decimal | 缩放：`value = raw * scale + offset` |
| byte_order | varchar(16) | 字节序（大端/小端） |
| rw | varchar(8) | 读写权限（与属性 access_mode 联动校验） |
| enabled | boolean | 启用/停采 |

> **约束**：点位映射必须引用**已发布版本**的属性；协议能力不支持时按 iot-starter 的
> `UnsupportedCapabilityException` 显式失败，不得静默（I6）。

### 3.10 设备影子（Device Shadow）

| 对象 | 语义 |
|---|---|
| `reported` | 设备上报的最新属性值（服务端权威最新值，落 Redis） |
| `desired` | 平台期望值（命令/配置下发后待设备确认） |
| 合并规则 | 查询返回 `reported` 优先，无则回退 `desired` 合并视图 |
| 时序 | 影子只存**最新值**；历史走时序库（§5.2）；属性值带 `ts`（采集时刻） |

影子与 `device` 表、`data_point`（时序）、Redis 最新值的一致性见 §5.3。

### 3.11 分组与标签

| 对象 | 说明 |
|---|---|
| `device_group` | 设备分组（租户内，树形或平铺）；产品级/设备级可分组 |
| `device_tag` | 设备标签（key/value，灵活检索与规则条件引用） |
| 用途 | 列表过滤、规则引擎条件目标、批量命令/配置作用域 |

---

## 4. 设备域（Device）

### 4.1 设备台账（升级既有 `IotDevice`）

现状 `IotDevice` 仅 deviceCode/deviceName/protocol/endpoint/remark；完整规格的设备台账**含以下字段**（对齐 spec §5）：

| 字段 | 说明 |
|---|---|
| product_id / product_version | 绑定产品与版本（**新增**，关联 §3） |
| endpoint | 连接端点（tcp://host:port 等） |
| credential_ref | 凭据引用（不透明，access 本地解析；对齐 spec §3.1⑤：按设备一份，MQTT 天然一台一份） |
| online_status | 在线状态（`online`/`offline`/`unknown`；来源见 §4.3） |
| shadow_json | 影子（reported/desired） |
| last_seen_at | 最后心跳/上报时刻 |
| status | `enabled`/`disabled` |

### 4.2 凭据与认证
- **MQTT 设备**：每设备一份 EMQX 账号（ACL 限 `$iot/dev/{t}/{d}/**`）；凭据轮换=新旧并存+到期踢旧会话。
- **直连协议**（Modbus/OPC UA/TCP）：设备口令单份无法并存 → 新口令失败回退旧口令 + 告警计数。
- `credential_ref` 不透明、本地解析、明文永不下发（spec §3.1⑤，M0b 前定死契约）。
- 认证形态三选一（EMQX 内建/外部 HTTP/mTLS）见 spec §12.4(C)，M-2 选型落地（§14）。

### 4.3 在线状态（两条路分别写，不混用）
- MQTT 设备 → business 订阅 EMQX `$events/client_connected|disconnected`（已进 ACL 矩阵）。
- 直连协议 → access 自行探测（连接/断线/重连）。

---

## 5. 数据面（Data Plane）

### 5.1 上报链路（完整规格）

```
设备上报 → access（协议解析→点位映射→DataSink 入队[有界队列]）→ 微批出口
   → EMQX $iot/svc/data/{t} → business(ypbin-iot.data 写入)
      ├─ 校验：租户归属（设备必须属于该租户）、点位存在、值类型合法
      ├─ IoTDB：时序点写入（ts 默认采集时刻，设备时钟不可信）
      ├─ Redis：最新值（含 quality）
      └─ 断档判定：quality=GOOD 才续「有效数据」；连续 >K×采集周期无有效数据 → outage_event
```

- **热路径禁 DB/RPC**（iot-starter I5）：access 的 `DataSink.write` 只入队，由固定平台线程消费者批量落库。
- 有界队列 + 背压 + **丢弃必须计数**（接入层/出口两层分别计数，spec §12.5）。
- 补采：仅当协议支持历史读取（如 OPC UA HA）时作为**可选能力**，默认不做。

### 5.2 时序存储（IoTDB，选型 Apache-2.0）

> ⚠️ **核实状态（2026-09-20）**：本会话网络受限（iotdb.apache.org / github 不可达），无法按一手来源复核
> IoTDB 乱序/TTL/聚合细节；以下基于 `IOT-CLOUD-SPEC.md` v4 §12.5 的记录（含其"乱序 memtable 默认开启"一说，
> 本会话未能复核该句的一手来源）。**结论强度受限**：设计决策不依赖这些细节；M-2 实现前必须按一手来源补核。

| 设计点 | 结论（⚠️ 未一手核实，M-2 前补核） |
|---|---|
| 存储布局 | 本平台决策：按租户分 database（多租户时序隔离）；设备时序按 `{tenant}.device.{deviceId}` 组织 |
| 时间序列 | 每设备每属性一条 time series：`ts, value, quality` |
| 乱序处理 | spec 记录：1.x 默认接受乱序（乱序 memtable），进独立乱序空间 + 后续与顺序文件合并；代价=写放大/合并压力（M-2 实测项） |
| 数据保留 | TTL 策略（Q2 开放问题，M-2 前定） |
| 查询 | 原始值/降采样聚合（AVG/MAX/MIN/SUM/COUNT）/最新值（M-2 前按官方语法补核） |
| 写入 | access→business 批量写入；单点写放大控制（M-2 实测项） |
| 许可 | Apache-2.0（spec v4 §7 已一手核实，2026-09-15；本会话网络不可达未能复核，维持 spec 结论） |

### 5.3 最新值（Redis）

| 键 | 值 | 说明 |
|---|---|---|
| `iot:latest:{tenantId}:{deviceId}:{propertyId}` | `{value, quality, ts}` | 最新值（影子 reported 的数据源之一） |
| `iot:shadow:{tenantId}:{deviceId}` | `{reported, desired}` | 影子文档 |
| 一致性 | 写入：IoTDB 落库成功后更新 Redis（先时序后最新值，允许最终一致窗口） | 读多写少，Redis 为准 |

### 5.4 断档与可用率（继承 spec §12.5 定义，逐台达标）

```
可用率（逐台设备）= 1 - Σ(断档时长) / 统计总时长（时间口径，排除可配置维护窗口）
断档定义          = 连续 > K×采集周期 无「有效数据」（K 默认 2，可配；有效=quality=GOOD）
双条件验收        = 每台可用率 ≥ 99.5%  且  最长单次断档 ≤ max(10min, 10×采集周期)（可按设备覆盖）
数据来源          = outage_event（device_id, start_ts, end_ts, duration_sec, reason）
```

---

## 6. 控制面（Control Plane）

### 6.1 命令下行（继承 spec §4.3 + 物模型校验）

```
UI/OpenAPI → business(core.device)
  ① 权限校验 + 设备在线 + 命令属于设备绑定版本的 service
  ② 入参按命令 input_params 定义校验（dataType/required/range/enum/unit）
  ③ Feign → access（显式超时，失败带可区分消息键）
  ④ access 写点位：直连协议直接写 / MQTT pub $iot/dev/{t}/{d}/cmd/**
  ⑤ 回执：成功/超时/失败原因逐项返回（批量按项标记，禁整批异常完成——iot-starter I7）
```

### 6.2 影子读写

| 操作 | 语义 |
|---|---|
| 读影子 | 返回 reported 优先 + desired 合并视图 |
| 写 desired | 更新 Redis 影子 + 触发命令/配置下发（异步），设备上报后回填 reported |
| 属性上报 | 更新 reported + 最新值 + 时序 |

---

## 7. 配置面（租约/epoch/变更推送）

**已落地**（增量 2/3a + `docs/LEASE.md`，本文档直接继承，不再重述）：

| 机制 | 结论 |
|---|---|
| 归属 | `tenant_node_assignment`（tenant_id/access_node/lease_expire_at/epoch/state），DB CAS 单赢家 |
| 内部端点 | `/internal/lease/**` 六端点（register/acquire/renew/release/assignment/epochs），`InternalTokenGuardInterceptor` 保护 |
| 失效检测 | 过期→`PENDING_TAKEOVER`；self-fencing 三路径（revoked/nodeFenced/本地过期） |
| epoch | 归属每次转移递增（**已落地**）；`tenant_config_epoch`（台账变更同事务递增）为**设计项**，继承 spec §3.1③，M-2 前落地 |
| 变更推送 | 事件 `$iot/svc/config/{t}/device-changed` + 周期对账（批量拉 epoch，不一致才拉全量） |

**M0b 补项（纳入本设计，不缩水）**：节点注册表落库（容量数据库级原子）、可分配租户改读台账表、
真库并发用例、到期时间改用数据库时钟、`ILeaseClient` 超时/重试显式化。

**跨仓待办（admin 仓改，本仓靠同步获得）**：网关拒绝 `/internal/**` + `X-Internal-Token` 进剥离名单；
网关剥离名单显式追加 `X-Gateway-Signed`（spec §4.4-2，防客户端伪造出站签名头穿透入站校验）。

---

## 8. 规则引擎与告警

> 目标：数据面之上的**条件触发**能力，不做可视化编排（非目标 §1.2）。
> 设计取向：参考主流开源 IoT 平台的规则链/条件-动作模型思路（**仅吸收思路，不照抄实现**——
> 母仓纪律：参考项目不留品牌词、须重构实现），本平台落地为**声明式条件（JSON）+ 动作**的轻量引擎，
> 条件与动作均可用平台自身 DTO 表达，便于后续模板化。

### 8.1 规则（Rule）
| 字段 | 说明 |
|---|---|
| id / tenant_id | 主键 / 租户 |
| rule_code / rule_name | 编码 / 名称 |
| **条件** | 组合条件：`设备/分组/标签/属性/事件 + 比较算子 + 阈值 + 时间窗口`（JSON，可配） |
| **动作** | 告警生成 ｜ 通知（webhook/邮件/短信占位） ｜ 命令下发 ｜ 转发 |
| 抑制窗口 | 同规则同目标在窗口内不重复触发（防风暴） |
| enabled / status | 启停 |

### 8.2 告警（Alarm）
| 字段 | 说明 |
|---|---|
| id / tenant_id / device_id / rule_id | 归属 |
| level | `info/warning/critical` |
| content | 内容（含点位/值/时间） |
| status | `active/ack/cleared` |
| first_at / last_at | 触发起止 |
| 处理 | 确认、清除（手动/自动恢复）、通知渠道 |
| 保留期 | 告警历史保留策略（M-4 定） |

### 8.3 闭环
`条件命中 → 生成告警(去重+抑制) → 通知 → 人工确认/自动恢复 → 记录全生命周期`

---

## 9. 开放 API（Open API）

| 设计点 | 结论 |
|---|---|
| 形态 | `ypbin-iot` 内独立 `openapi` 包（或条件触发拆模块），不复用后台会话令牌 |
| 认证 | API Key（租户维度，只存哈希、可轮换、可撤销）+ 限流（R.code=429） |
| 能力 | 设备数据查询/命令下发/属性上报（对接第三方系统） |
| 契约 | 与后台同一套 DTO/R；越权返回 HTTP 200 + R.code=403 |

---

## 10. 多租户与安全（底线，不缩水）

| 项 | 结论 |
|---|---|
| 租户上下文 | 只信网关注入（A3）；`X-Tenant-Id` 等身份头在网关剥离名单（admin 既有） |
| 落库强制 | MyBatis-Plus 租户插件 + `ignore-tables` 清单（平台表：tenant/日志/字典/全局配置/`tenant_node_assignment`；`tenant_config_epoch` 落地后同列） |
| 越权用例 | **每服务必测**：A 租户 token 访问 B 租户数据 → HTTP 200 + R.code=403；access 跨租户订阅拒绝 |
| 入站可信 | `InternalTokenGuardInterceptor`（fail-closed + 常量时间比较，admin 既有模式） |
| EMQX ACL | spec §12.4(A) 主题×主体矩阵逐条配平；设备只能 pub 自己的 `$iot/dev/{t}/{d}/**`；除 access 外不得订阅设备树 |
| 凭据 | `credential_ref` 本地解析、明文不下发；API Key 只存哈希 |
| 输入校验 | 外部输入一律校验（长度/枚举/JSON 结构），输出防 XSS 与敏感泄露（母仓铁律） |

---

## 11. 前端（admin-ui fork）

| 设计点 | 结论 |
|---|---|
| 形态 | 新建 `ypbin-admin-ui` 的 fork（upstream=admin-ui），白名单纪律同后端 |
| 页面 | 产品/物模型（TSL 编辑：服务/属性/命令/事件）、设备台账、点位映射、影子与在线调试、规则/告警中心、数据趋势（ECharts）、租户/权限（复用） |
| 同步 | 以 admin-ui 为上游按节奏同步，差异记 `docs/FRONTEND-DIVERGENCE.md`（spec §12.9） |

---

## 12. 数据模型总表（全表清单）

> DDL 草案在实现阶段产出；此处列**表级清单**与关键字段引用章节。租户插件忽略表见 §10。

| 表 | 域 | 引用 |
|---|---|---|
| `iot_product` | 物模型 | §3.2 |
| `iot_product_version` | 物模型版本 | §3.8 |
| `iot_service` | 物模型 | §3.3 |
| `iot_property` | 物模型 | §3.4 |
| `iot_command` | 物模型 | §3.5 |
| `iot_event` | 物模型 | §3.6 |
| `iot_device` | 设备 | §4.1（**扩展既有表**） |
| `iot_device_group` / `iot_device_tag` | 分组标签 | §3.11 |
| `iot_point_mapping` | 点位映射 | §3.9 |
| `iot_shadow`（Redis 为主，可选落库） | 影子 | §3.10 |
| `tenant_node_assignment`（已落地）／ `tenant_config_epoch`（设计项，M-2 前落地） | 租约 | §7 |
| `iot_rule` / `iot_alarm` | 规则告警 | §8 |
| `outage_event` | 断档 | §5.4 |
| `data_point`（IoTDB） | 时序 | §5.2 |
| 最新值（Redis） | — | §5.3 |
| `iot_api_key` | 开放 API | §9 |

---

## 13. API 契约总览

| 域 | 端点（前缀） | 说明 |
|---|---|---|
| 产品/物模型 | `/iot/products` `/iot/products/{id}/tsl` | CRUD + TSL 导入导出 + 版本发布 |
| 设备 | `/iot/devices` | 台账 CRUD + 绑定产品 + 凭据管理 |
| 点位映射 | `/iot/devices/{id}/points` | 点位映射 CRUD |
| 影子 | `/iot/devices/{id}/shadow` | 影子读写 |
| 命令 | `/iot/devices/{id}/commands/{commandId}` | 命令下发（§6.1） |
| 数据 | `/iot/devices/{id}/data` | 时序查询/最新值/聚合 |
| 规则/告警 | `/iot/rules` `/iot/alarms` | 规则 CRUD / 告警列表与处理 |
| 开放 API | `/openapi/**` | API Key 认证 |
| 内部 | `/internal/lease/**` | 租约（已落地，仅 access） |
| 通用 | 统一 HTTP 200 + `R.code`；集合永不 null；分页 `PageResult`；时间 `yyyy-MM-dd HH:mm:ss`（GMT+8）；Long 转字符串 | 母仓铁律 |

---

## 14. 里程碑与实施路线（完整规格方向）

> 用户已拍板「不再最小可跑通，一律最完善展开」。本表为**设计落地顺序**，每个里程碑交付**完整规格**的对应域
> （不砍功能；验收以本文档各章节为准）。

| 里程碑 | 交付（完整规格） | 验收要点 |
|---|---|---|
| **M-1 物模型域** | 产品/服务/属性/命令/事件 + 版本化 + TSL 导入导出 + 点位映射 + 影子 + 分组标签（§3/§4/§12 相关表） | 建产品→定义 TSL→绑定设备→映射点位→影子可读可写；越权用例绿 |
| **M-2 数据面** | access 协议栈（3b-2）+ 上报链路 + IoTDB + Redis 最新值 + 断档/可用率 + **M0b 收口**（节点注册表落库、可分配租户读台账、真库并发用例、数据库时钟、`tenant_config_epoch` 落地、`ILeaseClient` 超时/重试显式化——完整清单见 §7）+ **EMQX provisioning 选型落地（Q4）**（§4.2/§7/§5） | 真实 socket 采数→页面看值；可用率口径可算；多副本并发单赢家；provisioning 生效 |
| **M-3 控制面** | 命令下行 + 影子同步 + 在线调试（§6） | 命令有超时与可区分失败；断网重连自动恢复 |
| **M-4 规则告警** | 规则引擎 + 告警闭环 + 通知（§8）+ **平台自身可观测与告警阈值**（§15.2 Q7） | 规则真触发并通知；抑制窗口生效；平台告警阈值生效 |
| **M-5 开放 API + 前端** | openapi + admin-ui fork 全部页面（§9/§11） | 第三方 API Key 可用；前端全流程可用 |
| **M-6 平台加固与发布** | 可用率真实验收（≥1 周）+ 许可声明 + README + 接入 ypbin-site | 别人按 README 30 分钟内起起来看到数据 |

> 与 3b-2 的关系：M-1（物模型域）与 3b-2（协议栈）并行推进，M-2 前合并验证「映射→采集→落库」闭环。
> §7 的「M0b 补项」与 §4.2 的 provisioning 决策（Q4）均**在 M-2 收口**，消除旧里程碑编号。

---

## 15. 门禁与开放问题

### 15.1 门禁（继承 spec §9.1 的 13 项，不缩水）
架构约束（ArchUnit+自检）｜源码规范｜模块发布边界｜配置元数据｜NullAway｜依赖收敛｜元数据漂移｜
覆盖率快照｜覆盖率门禁（指令≥0.80/分支≥0.64）｜集成测试（-Pit）｜SBOM｜spotless｜preflight。

### 15.2 开放问题（Q，须在实现对应里程碑前定）
| # | 问题 | 影响 |
|---|---|---|
| Q1 | ~~事件是否纳入物模型服务层~~ **已定（2026-09-20）**：纳入，证据见 §3.6 | 物模型表结构 ✅ |
| Q2 | 数据保留策略（原始点保留多久/是否降采样） | IoTDB TTL 与容量 |
| Q3 | 命令超时全局默认值 | §3.5/§6.1 |
| Q4 | EMQX provisioning 形态三选一（内建/HTTP/mTLS） | §4.2 |
| Q5 | 通知渠道首期范围（webhook/邮件/短信） | §8.2 |
| Q6 | 开放 API 是否拆独立模块 | §9 |
| Q7 | **平台自身可观测与告警阈值**：指标（micrometer+Prometheus）/结构化日志/链路追踪（OTel）范围，以及**平台自告警阈值**（服务不可用/成功率/丢弃率/磁盘水位/租约指标——LEASE.md 已埋 `iot.lease.takeover|expired|revoked` 但阈值未定） | §2.1 部署视图（可观测为平台横切能力）；M-4 收口 |

### 15.3 风险（继承 spec §14 + 新增）
1. 全平台一次性设计面大，**实现必须分里程碑**（§14），避免「四个半成品」。
2. 物模型三层语义与既有 `IotDevice`（无 product_id）的迁移：既有表需增量扩展（SQL 双写纪律）。
3. iot-starter 未发布：CI 锁 SHA 取源（教训三十二）。
4. 影子/最新值/时序三方一致性：允许最终一致窗口，须有对账用例。

