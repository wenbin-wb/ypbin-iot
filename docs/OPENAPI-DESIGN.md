# 开放 API（M-5）设计方案

> ⚠️ **本方案为设计稿、未实现；待用户拍板后实施。**
>
> 本文只描述「打算怎么做」，**当前仓库不存在任何开放 API 实现**：`ypbin-iot` 无 `openapi` 包、无 API Key 表、无限流组件（`deploy/sql/006-iot-schema.sql` 全表清单无 `iot_api_key`；`docs/IOT-PLATFORM-DESIGN.md:763` 仅把它列为**计划**表）。
> 文档落盘不等于功能可用；第 10 节列出全部「需要用户拍板」事项，未拍板项不得开工。

| 项 | 值 |
|---|---|
| 里程碑 | M-5 开放 API（起步）（`docs/TASK-BOARD.md:44`） |
| 现状 | 整块未开始（`docs/PLATFORM-GAP-REPORT-2026-09-28.md:206`） |
| 对标 | 阿里云 IoT 云端 API+多语言 SDK；华为应用侧 API；ThingsBoard REST 全开放（同上行，一手已核） |
| 上游口径 | `docs/IOT-PLATFORM-DESIGN.md` §9（`:705-714`）+ §10（`:716-729`）+ §13（`:766-800`） |
| 本稿立场 | **以上游口径为准**；与现状冲突处以本文第 2.6 节如实登记，不擅自改上游 |
| 作者/日期 | 架构设计稿（会话产出） |

---

## 0. 结论先行（TL;DR）

1. **不做全新端点。** 首期开放的是**现有 controller 端点的子集**，经新增 `/open-api/v1/**` 前缀映射暴露（第 1 节给逐条清单）；未盘点到的端点一律不开放。
2. **鉴权链路：经网关，复用既有身份体系。** 第三方带 API Key 打到网关 `/iot/open-api/v1/**`，网关侧新增 **API Key 认证过滤器**校验后，**转签为既有内部身份头**（`X-User-Id`/`X-Tenant-Id`/`X-Gateway-Signed`），iot 服务侧**零改造**即获得 `IdentityContext` + 租户隔离 + `@SaCheckPermission` 三项既有能力（第 3 节）。**不采用直连 iot 服务**（理由见 3.1）。
3. **契约上，`R.code` 语义与后台一致，但「破例清单」严格收口。** 建议对开放 API **保留 HTTP 200 + `R.code` 主体惯例**（不整体破例），仅对**「鉴权/限流」这一层**破例返回真状态码（`401`/`429`），理由与范围见第 5 节 —— 这是本稿唯一的破例提议，且**需用户拍板**。
4. **规范产出：注解 + 既有 `ypbin-starter-api-doc` 自动生成，不手写规范文件。** 该 starter 已在本地仓库（`ypbin-starter-api-doc:3.6.0`），但 **`ypbin-iot` 当前未依赖它**（`ypbin-service/ypbin-iot/pom.xml` 无此 artifact）—— 这是一个**明确的、低风险的实施前置**。
5. **能力裁剪取「只读优先」。** 首期开放 6 个只读端点 + **1 个受控写端点（命令下发）**；产品/物模型**只读**、管理面写操作**不开放**（第 1.3 节给理由）。

---

## 1. 范围：首期开放哪些能力

### 1.1 盘点方法与口径

先盘点再裁剪，**不凭空设计端点**。盘点范围：`ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller/`（22 个 controller，其中 `Internal*` 8 个）。

盘点口径：
- 「开放」= 第三方可用 API Key 调用，且**不改动既有 controller 的映射**（用前缀重映射，见 3.2）；
- 判定「可开放性」的三条硬约束：**① 只读优先**（写操作要有明确理由）；**② 不触碰平台/跨租户资源**（`ignore-tables` 里的表一律不开放）；**③ 不暴露凭据类数据**。

### 1.2 现有端点全量盘点（依据：各 controller 的 `@RequestMapping`/`@SaCheckPermission`）

| Controller | 基础路径 | 主要权限码 | 端点性质 | 首期开放 |
|---|---|---|---|---|
| `IotDeviceController` | `/devices` | `iot:device:list/create/update/delete` | 台账 CRUD | ✅ 仅 `list` |
| `IotLatestValueController` | `/devices/{deviceId}/latest` | `iot:device:latest` | 只读 | ✅ |
| `IotTimeSeriesController` | `/devices/{deviceId}/series` | `iot:series:get` | 只读 | ✅ |
| `IotAlertController` | `/alerts` | `iot:alert:list/ack/rule-list/rule-save` | 查询 + 处理 + 规则 CRUD | ✅ 仅 `list`/`detail`/`summary` |
| `IotDeviceAlertController` | `/devices/{deviceId}/alerts` | `iot:alert:list` | 只读 | ✅ |
| `IotProductController` | `/products` | `iot:product:list/create/update/delete/publish` | 产品 CRUD + 版本 | ✅ 仅 `list`/`detail` |
| `IotThingModelController` | `/products/{productId}/…` | `iot:product:list/update`、`tsl-export/import` | TSL CRUD + 导入导出 | ✅ 仅 `services`/`properties`/`commands`/`events`/`tsl` 的 **GET** |
| `IotCommandController` | `/devices/{deviceId}/commands` | `iot:debug:send/get` | **写**（下发）+ 查询 | ⚠️ 仅 `POST send` + `GET`（见 1.3） |
| `IotShadowController` | `/devices/{deviceId}/shadow` | `iot:shadow:get/update` | 读 + **写** | ✅ 仅 GET |
| `IotDeviceEventController` | `/devices/{deviceId}/events` | `iot:device:list` | 只读 | ✅ |
| `IotAvailabilityController` | `/devices/{deviceId}/availability` | `iot:availability:get` | 只读 | ✅ |
| `IotPointMappingController` | `/devices/{deviceId}/points` | `iot:point:list/create/update/delete` | 映射 CRUD | ➖ 后置 |
| `IotDeviceTagController` | `/devices/{deviceId}/tags` | `iot:tag:list/create/update/delete` | 标签 CRUD | ➖ 后置（仅 list 可考虑） |
| `IotDeviceGroupController` | `/groups` | `iot:group:list/create/update/delete` | 分组 CRUD | ➖ 不开放 |
| `IotMaintenanceWindowController` | `/maintenance/windows` | `iot:maintenance:list/create/close` | 维护窗 | ➖ 不开放 |
| `DeviceCredentialController` | `/devices/{deviceId}/credential`、`/connection` | `iot:credential:issue/get/revoke` | **设备凭据** | ❌ **绝不开放** |
| `DeviceImportController` | （CSV 批量导入，新增未提交） | `iot:device:import` | 批量写 | ❌ 不开放 |
| `IotTenantLedgerController` | `/tenant-ledger` | `iot:ledger:list/update` | **平台表**（跨租户） | ❌ **绝不开放** |
| `Internal*`（8 个） | `/internal/**` | 无（`X-Internal-Token`） | 机器内部 | ❌ **绝不开放**（见 3.4） |

### 1.3 开放 / 不开放清单与理由

**✅ 开放（首期 7 个能力）**

| # | 开放能力 | 底层端点 | 理由 |
|---|---|---|---|
| O-1 | 设备台账查询 | `GET /devices` | 第三方集成第一个需求就是「我有哪些设备」；只读、租户插件天然隔离 |
| O-2 | 最新值查询 | `GET /devices/{id}/latest` | 对标阿里/华为「设备属性快照」；只读 |
| O-3 | 历史时序查询 | `GET /devices/{id}/series` | 对标「云端 API 查历史数据」；只读，已有 `limit` 上限与坐标校验 |
| O-4 | 告警查询 | `GET /alerts`、`/alerts/{id}`、`/alerts/summary`、`GET /devices/{id}/alerts` | 第三方要做告警联动；只读 |
| O-5 | 产品/物模型只读 | `GET /products`、`/products/{id}`、`/products/{id}/versions`、`/products/{pid}/services|properties|commands|events`、`GET /tsl` | 第三方需解析物模型才能理解点位语义；只读 |
| O-6 | 设备事件 / 可用率只读 | `GET /devices/{id}/events`、`/availability` | 排障与运维报表；只读 |
| O-7 | 命令下发 | `POST /devices/{id}/commands` | **唯一写能力**：开放 API 若不能下发，价值只剩「看数据」；对标三家均开放。风险由作用域 + 独立限流兜住（见 2.3/2.4） |

**❌ 不开放（首期）与理由**

| 不开放项 | 理由 |
|---|---|
| 设备凭据（`/credential`、`/connection`） | **泄露即设备被冒充**。`docs/DEVICE-CREDENTIAL.md` 的明文一次性口径只给管理面；开放 API 暴露等于扩大凭据攻击面 |
| `/internal/**` 全部 | 机器内部契约，只受 `X-Internal-Token` 保护（`InternalTokenGuardWebConfig.java:32-33`）；开放即绕过整套鉴权 |
| `/tenant-ledger/**` | 平台表（`deploy/nacos/ypbin-iot.yaml:15-19` 的 `ignore-tables`），**不按租户隔离** ⇒ 开放等于跨租户泄露 |
| 产品/物模型/映射/标签/分组的 **写** | 属「配置面」而非「集成面」；第三方改物模型会破坏该租户全部设备的点位语义，破坏面不可控 |
| 告警 `ack`/`silence`、规则 CRUD | 改变平台状态且有通知副作用；首期以只读为主，后置评估 |
| 设备台账 `create`/`update`/`delete`/`status` | 台账写会联动 EMQX 凭据与规格下发（`DeviceSpecServiceImpl`），副作用链长 |
| CSV 批量导入 | 大体积写 + 已有并发开发中的功能，不宜同期开放 |
| 影子 **写**（`PUT .../shadow`） | 写影子会下发到设备；与 O-7 能力重叠，首期收敛为只经命令下发 |

> **⚠️ 未核实项**：上表「可开放性」是**基于 controller 签名与权限码的静态判定**，**未逐一实调**每个端点确认其内部是否还有隐藏副作用（例如 `GET` 端点是否触发远程调用/写库）。实施前须对 O-1…O-7 逐条做一次「只读无副作用」核验（第 11 节登记）。

---

## 2. 鉴权模型

### 2.1 以 `IOT-PLATFORM-DESIGN.md` 口径为准（先读、后设计）

上游口径原文（`docs/IOT-PLATFORM-DESIGN.md`）：

- `:712` 认证 = **API Key（租户维度，只存哈希、可轮换、可撤销）+ 限流（R.code=429）**
- `:727` 凭据 = `credential_ref` 本地解析、明文不下发；**API Key 只存哈希**
- `:763` 表 = `iot_api_key`，域 = 开放 API
- `:778` 前缀 = `/openapi/**`，认证 = API Key

本稿**全部继承**这四条，只做「落到当前代码事实」的细化。**未发现与现状的实质冲突**，仅两处**需要澄清的口径差异**，如实登记如下（不擅自改上游）：

> **口径差异 1（前缀）**：上游写 `/openapi/**`（`:778`）。本稿建议实际形如 **`/open-api/v1/**`**。
> 理由：① 仓内**已有** `/open-api` 前缀先例 —— `ypbin-system` 的 `OpenApiDemoController`（`@RequestMapping("/open-api")`，`OpenApiDemoController.java:27`），且网关与 system 的免登录白名单**已放行** `/system/open-api/**`（`deploy/nacos/ypbin-gateway.yaml:103`、`deploy/nacos/ypbin-system.yaml:92`）；沿用同一前缀族可复用既有运维认知；② `/openapi` 在业界多指「OpenAPI 规范文档」，与「开放业务 API」混用易误读。
> 差异仅为路径字面，**语义与上游一致**；若用户要求逐字对齐上游，改配置即可（影响面：网关路由 + 白名单两处）。

> **口径差异 2（Key 归属）**：上游 `:712` 写「租户维度」。本稿建议**落库为「租户 + 应用」两级**：Key 行必须带 `tenant_id`（=「租户维度」不违反），同时带 `app_name`/`app_id` 以支持**同租户多应用独立配额与独立吊销**（否则一个 Key 泄露只能整租户轮换，代价过高）。这是对上游口径的**细化而非否定**。

### 2.2 Key 模型（表结构草案 — 仅设计，不建表）

> 本表为**设计草案**；本任务**不写 SQL、不改任何既有文件**。字段命名沿用上游 `iot_api_key`（`:763`）。

| 字段 | 类型（草案） | 说明 |
|---|---|---|
| `id` | bigint PK | 主键 |
| `tenant_id` | bigint | **租户维度**（上游 `:712`）；归属由创建者上下文决定，**不接受请求方传入** |
| `app_name` | varchar | 应用名（人可读，便于「这是谁的 Key」） |
| `access_key_id` | varchar(32) | **明文可存**的公开标识（形如 `ak_` + 随机），用于定位行；等价于 starter `SignApp.accessKey` 的定位职责 |
| `secret_hash` | char(64) | **仅存哈希**（`HMAC-SHA256`/或 `SHA-256`，见 2.5）；**永不可逆推明文** |
| `secret_prefix` | varchar(12) | 明文前若干位（**仅用于控制台回显辨识**，如 `sk_ab12…`），**不足以还原明文** |
| `scopes` | varchar(512) | 作用域集合（逗号分隔），映射到 `iot:*` 权限码（见 2.3） |
| `status` | tinyint | 启用/禁用（与 `EntityStatus` 既有口径一致，不用裸字面量） |
| `rate_limit_qps` | int | **按 Key 维度**的 QPS 配额（具名默认常量见 2.4） |
| `daily_quota` | int | 日调用配额（可选，`0` = 不限） |
| `ip_whitelist` | varchar(512) | **可选** CIDR 列表，空 = 不限来源（见 2.7） |
| `expire_at` | datetime | 过期时间，空 = 永不过期（对齐 starter `SignApp.expireTime` 语义） |
| `last_used_at` | datetime | 最近调用时间（运维可见性） |
| 审计字段 | — | 继承既有 `TenantBaseEntity`（`create_by`/`create_time`/`update_by`/`update_time`/`del_flag`） |

### 2.3 作用域 → 既有 `iot:*` 权限码映射（**关键：复用而非新造**）

**设计要点：不新造一套权限码**，作用域直接**取值为既有 `iot:*` 权限码**（全量清单见 `deploy/sql/007-iot-data.sql` 的权限码定义）。理由：① `@SaCheckPermission` 已遍布 controller，`IotPermissionProvider` + `StpPermissionAdapter` 已能把权限码喂给 Sa-Token（`IotPermissionProvider.java:33-48`）；② 新造码会导致「同一能力两套命名」，违反命名一致性红线。

| 作用域（= 权限码） | 覆盖的开放端点 |
|---|---|
| `iot:device:list` | O-1 设备台账查询；O-6 事件查询 |
| `iot:device:latest` | O-2 最新值 |
| `iot:series:get` | O-3 历史时序 |
| `iot:alert:list` | O-4 告警查询 |
| `iot:product:list` | O-5 产品/物模型只读 |
| `iot:availability:get` | O-6 可用率 |
| `iot:debug:send` | O-7 命令下发（**高危，默认不授予**，需显式勾选） |

**映射机制（重要，决定实现成本）**：API Key 校验通过后，网关**不**去模拟「某个真实用户」，而是为该请求注入一个**虚拟主体**：`X-User-Id` 取一个**保留的虚拟用户 ID**，其权限码集合 = 该 Key 的 `scopes`。

> ⚠️ **实施前置（必须验证）**：既有 `IotPermissionProvider.getPermissions` 是**按 `userId` 查 `SysCache`**（`IotPermissionProvider.java:37-42`），它**不认识**「虚拟主体 → scopes」这种映射。因此二选一：
> **(a)** 在网关注入的请求上附加一个内部头（如 `X-Api-Scopes`），并让 iot 侧的权限解析**优先读该头**（需改 `IotPermissionProvider` 或新增一个更前置的 `PermissionProvider`）；
> **(b)** 为每个 Key 在 `sys_user` 侧建影子用户（**不推荐**：污染用户表与在线用户视图）。
> 本稿建议 **(a)**，但**这是对既有鉴权链的一处真实改动**，须在实施时单独立项评审并补测试（属 L2 非平凡改动）。

> ✅ **2026-09-30 落地（方案 B2，L2 已评审）**：采纳 **B2**（网关复用**既有 `X-Roles`** 注入 scopes + iot **虚拟 ID 段回落**）。
> 实现与独立复核见 `feat/openapi-virtual-principal-b2`（iot 全量 796/0/0/0；端到端咬合测试 + 变异验证）。

> 🔴 **「待反哺 starter」（按工作规范：本仓只做纯业务，通用机制反哺底层）**：「虚拟主体 → scopes」的
> **通用机制**（保留虚拟 ID 段、scopes 白名单过滤、与 `IdentityHeaderFilter`/`StpPermissionAdapter` 的衔接）
> 本质是 **`ypbin-starter` 的通用能力**（任何下游服务做开放 API 都需要）。本批为**过渡实现**（合入以打通 F-1 前置）；
> 反哺责任：**已在 `wenbin-wb/ypbin-starter` 提 PR #60**（`VirtualPrincipalScopes`，以本实现为蓝本抽成通用组件），本仓随后切换为
> 「依赖 starter 通用实现 + 仅保留业务白名单」。tracker：看板 #11「待反哺 starter」标注。

### 2.4 限流与配额（具名常量，按 Key 维度）

**现状：仓内当前没有限流组件**（`deploy/nacos/ypbin-iot.yaml` 无 sentinel/rate-limit 配置；`ypbin-gateway.yaml` 无 `RequestRateLimiter`）。故限流是**新增能力**，不是接线。

设计（**避免魔法值**，全部具名常量，放在 `OpenApiConstants`）：

| 常量 | 建议默认 | 说明 |
|---|---|---|
| `DEFAULT_RATE_LIMIT_QPS` | `10` | 单 Key 默认 QPS |
| `DEFAULT_DAILY_QUOTA` | `100_000` | 单 Key 日配额（`0` = 不限） |
| `RATE_LIMIT_WINDOW_SECONDS` | `1` | 限流窗口 |
| `ROLLING_COUNTER_TTL_SECONDS` | `86_400` | 日配额计数 TTL |
| `RATE_LIMIT_KEY_PREFIX` | `ypbin:openapi:qps:` | Redis key 前缀 |
| `QUOTA_KEY_PREFIX` | `ypbin:openapi:quota:` | 同上 |

- **维度**：**按 Key（`access_key_id`）**，不按租户 —— 上游 `:712` 的限流与 Key 绑定，租户级汇聚限流**后置**（避免「一个应用打满整租户」的争议面在首期就展开）。
- **算法**：固定窗口 Redis 计数器（`INCR` + `EXPIRE`）即可满足首期；**不引入**令牌桶第三方依赖。
- **超限响应**：`R.code=429`（`GlobalErrorCode.TOO_MANY_REQUESTS`，`R.java`/`GlobalErrorCode` 已有该码），消息给人话与重试建议（见 5.3）。

### 2.5 明文只返回一次 / 库内只存哈希

- **创建时**：服务端生成明文 `secret`（建议 **32 随机字节 = 256 位熵**，与设备口令口径一致：`deploy/nacos/ypbin-iot.yaml` 的 `credential-password-length: 32`），**创建响应中返回一次**，此后任何端点**都不再返回明文**（列表/详情只回 `secret_prefix`）。
- **落库**：只存 `secret_hash`。**哈希选择（取舍）**：
  - 方案 A：`SHA-256(secret)` —— 简单、可查（用 `access_key_id` 定位行后比对）；
  - 方案 B：`HMAC-SHA256(server_pepper, secret)` —— 防「库泄露后离线爆破」更强（secret 是高熵随机串，爆破实际不可行，但 pepper 可阻断跨环境彩虹表）。
  - **建议 B**，pepper 由环境变量注入（**真值不入库、不入配置文件**，同 `deploy/nacos/ypbin-iot.yaml` 现有 `${GATEWAY_SIGN_TOKEN}`/`${EMQX_API_KEY}` 占位符纪律）。
- **比对**：必须 `MessageDigest.isEqual` **常量时间比较**（仓内既有模式：`SignChecker.java` 与 `InternalTokenGuardInterceptor.java` 均已如此）。
- **日志**：明文 secret **绝不进日志**；`access_key_id` 打印前经 `LogSanitizer`（既有模式）。

### 2.6 吊销与轮换

| 操作 | 语义 |
|---|---|
| **吊销** | `status` 置禁用 **或** 删除（软删）；**立即生效**（校验时每请求查库/缓存，不做长 TTL 本地缓存绕过） |
| **轮换** | 同一 Key 行**支持双密钥并存窗口**：新增 `secret_hash`+`secret_prefix` 覆盖前，**保留旧密有效 N 分钟**（建议具名常量 `ROTATION_GRACE_MINUTES = 30`），让第三方平滑切换；宽限期后旧密自动失效 |
| **轮换通知** | 首期不做主动通知（无 Webhook，见第 8 节）；由第三方轮询或人工操作 |
| **审计** | 创建/轮换/吊销**必须**写审计（谁、何时、哪个 Key、动作）—— 见第 6 节 |

> **取舍**：双密钥宽限期比「立即失效」实现复杂（多一列 `prev_secret_hash`/`prev_expire_at`），但**避免第三方切换期的服务中断**。若不接受该复杂度，可选「立即失效 + 要求第三方停服切换」——**需用户拍板**（第 10 节 Q4）。

### 2.7 IP 白名单（可选）

- 字段 `ip_whitelist`（CIDR 列表，逗号分隔）；**空 = 不限来源**。
- 校验位置：**网关**（拿得到真实客户端 IP）。**注意**：网关在 `X-Forwarded-For` 可信性尚未核实的场景下，直接取远端 IP 更安全；**取 XFF 必须先把网关自身加入可信代理**，否则可被伪造（**未核实项**，见第 11 节）。
- 建议：**首期把 IP 白名单做成「可选、默认关闭」**，但**字段与校验代码位**预留，避免后期改表。

---

## 3. 鉴权链路

### 3.1 结论：**经网关**（不直连 iot 服务）

**推荐链路：**

```
第三方 ──(API Key + 签名)──► ypbin-gateway:19000
                              │ ① 路径匹配 /iot/open-api/v1/**
                              │ ② 【新增】OpenApiKeyAuthFilter：解析 Key → 查库 → 校验状态/过期/IP/限流/配额
                              │ ③ 【新增】转签既有内部身份头：X-User-Id(虚拟主体) / X-Tenant-Id(Key 所属租户)
                              │      / X-Roles? / X-Gateway-Signed(= GATEWAY_SIGN_TOKEN)
                              │ ④ StripPrefix=1 剥 "iot"
                              ▼
                       ypbin-iot:18088  /open-api/v1/**
                              │ IdentityHeaderFilter：校验 X-Gateway-Signed ⇒ 构建 IdentityContext ✅
                              │ MP 租户插件：按 X-Tenant-Id 自动隔离 ✅
                              │ @SaCheckPermission：IotPermissionProvider 供权限码 ✅
                              ▼
                        既有 Service / Mapper（零改造）
```

**为什么经网关（关键依据）：**

1. **身份体系已建好，复用成本最低。** iot 是下游服务，**本地 Sa-Token 登录拦截已关闭**（`deploy/nacos/ypbin-iot.yaml:31-32` `security.interceptor: false`），身份**完全依赖网关注入的身份头**；`IdentityHeaderFilter` 还会**强制校验 `X-Gateway-Signed`**，无标记直接拒绝并返回 `403「非法身份来源」`（`IdentityHeaderFilter.java:110-118`，`REJECTED_MESSAGE` 在 `:72`）。⇒ **绕过网关直连 iot 在现有安全设计下根本走不通**。
2. **租户隔离免费获得。** 租户上下文来自 `IdentityContext`/`X-Tenant-Id`，MyBatis-Plus 租户插件据此自动加条件，且 iot 配置 `fail-on-missing-tenant: true`（`deploy/nacos/ypbin-iot.yaml:11`）—— **无租户上下文直接拒绝**。经网关注入 `X-Tenant-Id` 后，O-1…O-7 的租户隔离**无需写一行业务代码**。
3. **注解鉴权继续生效。** `annotation-check` 在 iot 未配置 ⇒ **取 starter 默认 `true`**（`SecurityProperties.java:62`），配合 `IdentityStpLogic`（以身份头为账号来源）与 `IotPermissionProvider`，`@SaCheckPermission` 在无 Sa-Token 会话时仍可用 —— 这正是 2.3 节作用域映射能成立的前提。
4. **网关是既有唯一入口。** iot 路由已存在：`Path=/iot/**` + `StripPrefix=1`（`deploy/nacos/ypbin-gateway.yaml:49-54`）；新增开放 API 路由与既有路由**同族并列**即可，无需新入口。
5. **限流/审计/指标在网关做，最省事且不污染业务。** 网关是唯一能拿到「真实客户端 IP + 原始 Key + 全局 QPS」的位置。

**为什么明确不推荐直连 iot 服务：**
- 直连**撞不过** `X-Gateway-Signed` 校验（`IdentityHeaderFilter` fail-closed）⇒ 要么改掉这套纵深防御（**削弱安全**），要么在 iot 内再造一套鉴权（**重复建设**）；
- iot 服务端口不是设计上的公网入口，直连等于**新开一个公网攻击面**，且绕过网关的统一头清洗（`header-sanitize`，`ypbin-gateway.yaml:66-80`）；
- 限流需在每个 iot 实例各自实现，**分布式配额无法全局准确**。

### 3.2 路由与路径映射（不改既有 controller 映射）

**硬约束：不得修改既有 controller 的 `@RequestMapping`**（会牵动既有前端与内部调用）。
**方案**：新增一个**独立的开放 API 门面包** `cn.ypbin.admin.iot.openapi`，其中放**薄适配 controller**，`@RequestMapping("/open-api/v1/...")`，内部**委托既有 Service**（不复制业务逻辑）。

| 开放路径（经网关后） | 委托目标 |
|---|---|
| `GET /open-api/v1/devices` | `IotDeviceService` 分页（复用 `IotDeviceQuery`） |
| `GET /open-api/v1/devices/{deviceId}/latest` | `LatestValueQueryService.listLatest` |
| `GET /open-api/v1/devices/{deviceId}/series` | `TimeSeriesQueryService.query` |
| `GET /open-api/v1/alerts` / `.../{id}` / `.../summary` | `AlertInstanceService` |
| `GET /open-api/v1/products` / `.../{id}` / `.../{id}/tsl` … | `IotProductService` / TSL 相关 Service |
| `GET /open-api/v1/devices/{deviceId}/events` / `.../availability` | 对应 Service |
| `POST /open-api/v1/devices/{deviceId}/commands` | `CommandInstanceService.send` |

> **取舍（门面 vs 复用既有 controller）**：
> - **门面（推荐）**：① 开放契约与后台契约**可独立演进**（后台改字段不必然破坏第三方）；② 可对开放侧单独加校验/裁剪字段/脱敏；③ 路径版本化干净（`/v1`）。代价：多一层薄类。
> - **直接复用既有 controller**：省代码，但**把后台契约与对外契约焊死**——后台一次字段调整就是一次对外破坏性变更，且无法对开放侧单独限流/脱敏。**不推荐**。

### 3.3 与现有身份体系的关系（注入细节）

| 既有机制 | 开放 API 如何衔接 |
|---|---|
| `IdentityContext`（`IdentityContext.java`，ThreadLocal `LoginUser`） | 由 iot 侧 `IdentityHeaderFilter` 依据网关注入的头**自动构建**，开放 API **无需感知** |
| `X-User-Id` / `X-Tenant-Id` / `X-Roles` | 网关为 API Key 请求**注入虚拟主体**：`X-Tenant-Id` = Key 的 `tenant_id`（**来自库，不来自请求**） |
| `X-Gateway-Signed` | 网关用 `GATEWAY_SIGN_TOKEN` 签发（既有签发逻辑：`buildTrustedHeaders` 模式，`SaTokenGatewayAuthProvider.java`）；iot 侧 `trusted-source-token: ${GATEWAY_SIGN_TOKEN}`（`deploy/nacos/ypbin-iot.yaml:23`）**必须两侧同值** |
| 租户插件 / `fail-on-missing-tenant` | 依赖 `X-Tenant-Id`；**Key 无租户即拒绝**（fail-closed） |
| `@SaCheckPermission` + `IotPermissionProvider` | 见 2.3 的**实施前置**：需让权限解析认识「虚拟主体 → scopes」 |
| 头清洗（`header-sanitize`） | **必须把开放 API 新引入的头**（如 `X-Api-Scopes`）**并入清洗表**，否则第三方可自带该头穿透 → **提权**（这正是 SF-5 在 `ypbin-gateway.yaml:66-80` 记录的同类教训） |

> **⚠️ 安全红线**：虚拟主体的 `X-User-Id` 必须取**保留 ID 段**（如负数或高位段），**不得**与真实用户 ID 空间重叠，否则可能出现「Key 意外持有某个真实用户的权限」。starter 已提示 userId 与 Sa-Token 哨兵值 `-3/-4/-5` 冲突（`IdentityStpLogic.java` 类注释「账号标识」条），**保留段选取必须避开**。

### 3.4 暴露边界：哪些给 API Key、哪些只给内部

| 面 | 谁能访问 | 保护机制 |
|---|---|---|
| `/open-api/v1/**`（新增） | **API Key** | 网关 `OpenApiKeyAuthFilter` |
| 既有 `/devices`、`/products` 等管理面 | **仅登录用户**（既有会话） | 网关 Sa-Token 校验（既有） |
| `/internal/**`（8 个 controller） | **仅机器内部** | **`X-Internal-Token` 保持不变**（`InternalTokenGuardWebConfig.java:32-33`，拦截器 fail-closed + 常量时间比较，`InternalTokenGuardInterceptor.java:52-78`） |
| `/internal/mqtt/**` | EMQX 入站 | `X-Internal-Token` + 唯一真状态码例外（`docs/IOT-PLATFORM-DESIGN.md:781,786`） |

> **红线**：开放 API 的引入**不得**放宽 `/internal/**` 的保护。具体说：`OpenApiKeyAuthFilter` 的**放行/匹配路径必须只覆盖 `/iot/open-api/**`**，**绝不可**写成「凡带 API Key 即放行」——否则等于给 `/internal/**` 开了旁路。

---

## 4. 契约与文档

### 4.1 OpenAPI 规范：**注解 + 代码生成**（不手写）

**取舍：**

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **手写 `openapi.yaml` 并维护** | 完全可控、可先写规范后写码 | **必然漂移**：代码改了文档不改，且无门禁能发现 ⇒ 对第三方「文档说 A、实际是 B」，最伤「有文档可查」的诉求 | ❌ |
| **注解 + 代码生成（SpringDoc）** | 与代码**同源**，改代码即改文档；`springdoc-openapi` 被动维护中但仍是 Spring Boot 3 + MVC 的**事实标准** | 需加依赖；注解噪音 | ✅ **推荐** |
| 手写为主 + CI 比对生成结果 | 兼顾可控与防漂移 | 双份维护 + 需自建 diff 门禁，成本最高 | ➖ 后置 |

**现成能力（关键发现，避免引入来源不明依赖）：**

- 仓内**已有第一方 starter**：`cn.ypbin:ypbin-starter-api-doc`（版本 `3.6.0`，与 `pom.xml:28` 的 `ypbin-starter.version` 一致）。
- 其 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 声明 `ApiDocAutoConfiguration`；配置前缀 **`ypbin.api-doc`**。
- 可用属性（取自其 `spring-configuration-metadata.json`）：`enabled`、`disable-in-prod`、`title`、`description`、`version`、`group-name`、`default-group-enabled`、`order-enabled`、`paths-to-match`、`paths-to-exclude`、`packages-to-scan`、`packages-to-exclude`、**`security-headers`**、`contact.*`、`license.*`。
  - ⇒ **`security-headers` 可直接把 API Key 头写进文档的 security scheme**，无需自己拼 OpenAPI 片段。
  - ⇒ **`paths-to-match` 天然支持「只为 `/open-api/**` 出一份对外规范」**，与 `default-group-enabled` 配合可做「对外文档 = 开放 API 子集」。
  - ⇒ **`disable-in-prod` 默认关闭生产暴露** —— 需**显式评估**：开放 API 的文档若也要在生产可查，必须显式调整（**需用户拍板**，见第 10 节 Q5）。
- **该 starter 由本项目同源作者维护**（与 `ypbin-starter` 同一发布线），**不是来源不明或停滞的第三方依赖** ⇒ 符合 R6/依赖红线。

> **⚠️ 明确的实施前置**：**`ypbin-iot` 当前未依赖 `ypbin-starter-api-doc`**（`ypbin-service/ypbin-iot/pom.xml` 的依赖列表无此 artifact），故 `/v3/api-docs` 与 swagger-ui **在 iot 服务上当前不可用**。启用开放 API 文档需**新增一条依赖**（坐标已在 BOM 内，属常规）。

**现状核实（本仓"先读现状"）：**

- **`OpenApiDemoController` 是 AK/SK 签名示例，不是文档设施。** `ypbin-service/ypbin-system/.../OpenApiDemoController.java:27,30-31`：`@RequestMapping("/open-api")` + `@ApiSign` + `@PostMapping("/demo")`，注释明写「AK/SK + timestamp + nonce + sign」。
- **仓内已有一套可复用的开放 API 签名设施**：`ypbin-starter-sign`（`@ApiSign` / `SignChecker` / `SignInterceptor` / `SignAppProvider` / `SignProperties`，前缀 `ypbin.sign`），且 system 已启用（`deploy/nacos/ypbin-system.yaml` 的 `ypbin.sign.enabled: true` + `mode: ANNOTATION`）。
  - **能力**：HMAC-SHA256、±60s 有效期（`SignProperties.timeout=60`）、**nonce 防重放**（`replayProtect=true`，`RedisNonceStore`/`InMemoryNonceStore`）、**常量时间比较**、**时钟偏移容差 5s**、失败**返回 `R.fail`（HTTP 200）**（`SignInterceptor.writeFail`）。
  - **短板（相对本方案）**：它是**纯签名校验**——`SignAppProvider.findByAccessKey` 只返回 `accessKey/secretKey/appName/expireTime/enabled`，**没有** `tenant_id`、**没有** `scopes`、**没有** 限流/配额、**没有** IP 白名单。
- **4001 api-doc 菜单已存在**：`deploy/sql/002-data.sql:180` 定义菜单 `4001`（路由 `/swagger-ui/index.html`，`IFrameView`），且 IoT 平台模块迁移把它挂到 `3310` 基础管理下（`deploy/sql/migration/2026-09-26-iot-platform-module-menu.sql:57`）。⇒ **前端入口已就绪**，只差后端服务真的有文档端点。

**⇒ 设计结论（重要取舍）**：**复用 `ypbin-starter-sign` 的成熟部分（签名/nonce/时效），补齐它缺的三件（租户、作用域、配额）**，而不是从零写一套。具体：iot 侧（或网关侧）实现自己的 `SignAppProvider`，从 `iot_api_key` 表加载并**扩展**为携带 `tenantId`/`scopes`/配额；签名校验逻辑直接复用 `SignChecker`。

> 但注意：`SignChecker` 是 **Servlet 侧**组件（`jakarta.servlet.http.HttpServletRequest`），而**网关是 WebFlux（Reactor）**（`SaTokenGatewayAuthProvider.authenticate(ServerWebExchange)`）。⇒ **若在网关做签名校验，无法直接复用 `SignChecker`**（需移植或改为在 iot 侧做签名、网关侧只做租户/限流/转签）。这是 3.1 链路的一处**真实实现约束**，须在实施设计时定案（**未定**，见第 11 节）。

### 4.2 版本化策略与兼容性承诺

- **路径版本**：`/open-api/v1/**`（版本在路径中，**不做** Header 协商 —— 对新手更直观，与「简单好用」诉求一致）。
- **兼容性承诺（新增字段安全，删除/改语义是破坏性）**：
  - ✅ **向后兼容**（不视为破坏）：新增**可选响应字段**、新增**可选请求参数**、新增**新端点**、放宽校验。
  - ❌ **破坏性**（须发 `v2`）：删除/重命名字段、改字段类型、改字段语义、收紧必填、改默认值、改 `R.code` 数值含义。
  - **弃用流程**：破坏性变更前**至少一个版本周期**标注弃用，并在文档与响应中提示（首期建议给 `R` 的 message 或新增响应头 `Deprecation`；**具体要求待拍板**）。
- **契约快照门禁**：建议在 CI 中对 `/v3/api-docs` 产物做**快照比对**，出现破坏性差异即红灯（**新增门禁需单独立项**，不塞进功能改动 —— 与 R6/交付门禁红线一致）。

---

## 5. 错误契约

### 5.1 结论：**主体维持 HTTP 200 + `R.code`；仅「鉴权/限流」这一层破例用真状态码**（需拍板）

**为什么建议维持主体惯例（不整体破例）：**

1. **仓内铁律与既有实现一律如此**：`GlobalExceptionHandler` 全部 `@ExceptionHandler` 返回 `R.fail(...)`，**无任何 `@ResponseStatus`/`ResponseEntity`**（`ypbin-starter-web` 的 `GlobalExceptionHandler.java:61-173`）⇒ 若开放 API 整体改用真状态码，等于**在全局异常处理器之外再造一套错误返回**，两套并存必然出现「同样错误、两条路径、两种形状」。
2. **签名设施已是 HTTP 200 口径**：`SignInterceptor.writeFail` 写 `R.fail(message)` 且**不设状态码**（`SignInterceptor.java`）。开放 API 若改真状态码，将与即将复用的签名层**自相矛盾**。
3. **对第三方未必更差**：`R` 信封**始终带 `code` + 人话 `message`**，只要**文档把 `code` 字典写清楚**，第三方解析成本与 HTTP 状态码相当，且**不存在「网关 502/504 也是非 200、与业务错误混在一起」的歧义**。

**为什么仍要在「鉴权/限流」层破例（范围极小）：**

- **依据仓内唯一先例**：`/internal/mqtt/**` 是**唯一**允许真状态码的例外，**理由是「对端只看 HTTP 状态码」**（`docs/IOT-PLATFORM-DESIGN.md:781,786`，含用户 2026-09-26 批准 D2）。
- **开放 API 的鉴权层有同类特征，但强度弱于 MQTT**：第三方 HTTP 客户端库、API 网关、监控普遍**优先看 HTTP 状态码**；`401`/`429` 是**跨语言、跨生态的通用语义**，「HTTP 200 但 code=429」会让通用重试库/监控**看不到限流**。
- **严格收口（防止先例扩散，与上游「不得扩散」的要求一致）**：
  - 破例范围**仅限**：**缺少 Key / Key 无效 / Key 已吊销或过期 / 签名错误** ⇒ `401`；**超 QPS 或超日配额** ⇒ `429`；可选：**IP 不在白名单** ⇒ `403`。
  - **响应体仍必须是 `R` 信封**（`code` 与 HTTP 状态码一致），保证**单一解析路径**。
  - **其余一切业务错误**（设备不存在、参数错误、越权作用域、命令下发失败…）**一律维持 HTTP 200 + `R.code`**。
  - 必须在**该组端点的 Javadoc 中写明破例理由**（沿用 `/internal/mqtt/**` 的硬要求，`docs/IOT-PLATFORM-DESIGN.md:786` 附带硬要求③）。
- **风险（如实告知）**：
  1. **与全局异常处理器不一致** ⇒ 鉴权层**必须在异常处理器之前**（Filter 或网关层）返回，**不能**靠抛 `BusinessException`（那会被转成 200）；这要求实现上**刻意绕开**既有处理器，属易错点。
  2. **契约面分裂**：第三方需知道「鉴权错误看 HTTP、业务错误看 code」。
  3. **若在网关注入身份头后再由 iot 判权限**，则 `401`（鉴权）在网关、`403`（作用域不足）在 iot，**两个位置**都可能返回鉴权类错误 ⇒ 须明确边界，否则同类错误两种状态码。
- **替代方案（若不破例）**：全部 HTTP 200 + `R.code`（`401`/`403`/`429` 仅体现在 `code`），在**文档首屏用醒目示例**说明；代价是通用重试/监控**不敏感**。⇒ **两案利弊并列，需用户拍板（第 10 节 Q1）**。

### 5.2 错误码字典（沿用既有 + 开放 API 专属细化）

复用 `GlobalErrorCode`（`ypbin-starter-core`）既有码，**不新造码值**（避免「同一语义两个码」）：

| HTTP | `R.code` | 语义 | 触发场景 |
|---|---|---|---|
| 200 | `200` | 成功 | 正常调用 |
| 200 / `401` | `401` | 认证失败 | 缺 Key、Key 不存在、签名错误、签发已过期 |
| 200 / `403` | `403` | 无权限 | 作用域不足（Key 无该 `iot:*` 权限）、IP 不在白名单（可选） |
| 200 | `404` | 资源不存在 | 路径不存在 |
| 200 / `429` | `429` | 请求过于频繁 | 超 QPS 或超日配额 |
| 200 | `409` | 业务处理失败 | 单条查跨租户/不存在（**D0.5 口径**：不泄露存在性，`docs/IOT-PLATFORM-DESIGN.md:35,714`） |
| 200 | `500` | 系统内部错误 | 未预期异常 |

**开放 API 专属细分（建议）**：`R.code` 保持上表数值，但 `message` 用**可机读的子类型**或明确人话（见 5.3）；**不建议**为「Key 过期」「配额耗尽」另造一个数值码 —— 会让第三方要维护一张越来越长的码表。

### 5.3 人话错误信息（对新手友好 —— 直接对应「出错看得懂」诉求）

原则：**说清"哪里错 + 怎么改 + 别只说 code"**，且**不泄露安全信息**（不区分「Key 不存在」与「签名错误」的对外措辞——统一为泛化提示，防枚举）。

| 场景 | ❌ 不要这样 | ✅ 建议 `message` |
|---|---|---|
| 缺 Key | `401` | 「请求缺少 API Key。请在请求头 `X-Api-Key` 中携带，格式示例：`X-Api-Key: ak_xxx`」 |
| Key/签名无效 | `签名验证失败` | 「API Key 或签名校验未通过。请确认 accessKey/secretKey 正确、timestamp 为**秒级**时间戳且在 60 秒内、sign 按文档算法生成」 |
| Key 已禁用/吊销 | `应用已禁用` | 「该 API Key 已停用，请联系管理员或使用新的 Key」 |
| Key 已过期 | `应用已过期` | 「该 API Key 已于 `{expireAt}` 过期，请在控制台重新生成」 |
| 超 QPS | `429` | 「调用过于频繁（当前限制 `{qps}` 次/秒，Key=`{ak前缀}`）。请降低频率后重试，建议指数退避」 |
| 超日配额 | `429` | 「今日调用额度已用完（`{used}`/`{quota}`）。额度将于 `{resetAt}` 重置」 |
| 作用域不足 | `没有访问权限` | 「该 API Key 未授予 `{scope}` 权限。当前已授予：`{scopes}`。如需开通请联系管理员」 |
| 跨租户 / 设备不存在 | 「设备不存在」 | **保持泛化**（D0.5：**不泄露存在性**，`docs/IOT-PLATFORM-DESIGN.md:35`）——「设备不存在或不属于当前 API Key 的租户」 |
| 参数错误 | `请求参数有误` | 「参数 `{field}` 不合法：`{原因}`。期望：`{格式示例}`」 |

> **⚠️ 与 D0.5 的一致性**：跨租户**必须**表现为「查不到」而非 `403`，**开放 API 不得破例**（`docs/IOT-PLATFORM-DESIGN.md:35,714,724`）。这对第三方是个**认知落差**（"我有权限但查不到"），**必须在文档显式说明**（否则会被反复提问），但**不能改语义**（一改就泄露存在性）。

---

## 6. 可观测与运维

### 6.1 调用日志（谁、何时、调了什么、耗时、结果）

- **内容**：`timestamp` / `tenantId` / `accessKeyId`（**经 `LogSanitizer`，绝不打明文 secret**）/ `method` / `path` / `status`（HTTP）/ `code`（业务）/ `durationMs` / `clientIp` / `userAgent`。
- **形式**：**结构化 JSON 日志**（既有 starter 已有结构化日志能力）；**首期不落库**（避免为开放 API 单独建日志表）。
- **不记录**：请求体中的敏感字段（设备凭据等 —— 这些端点本就不开放）、明文 secret、完整签名。

### 6.2 审计

- **审计对象**：**Key 的生命周期操作**（创建/轮换/吊销/改配额/改白名单），**不是**每次调用。
- **依据**：`docs/PLATFORM-GAP-REPORT-2026-09-28.md:200` 已把「审计日志」列为 P1 缺口（统一中间件）。**本方案不新增审计表**，而是**复用该缺口落地后的统一审计能力**；在它落地前，Key 生命周期操作**至少**走既有 `@Log` 注解（仓内既有模式，如 `IotCommandController.send` 上的 `@Log("下发 IoT 设备命令")`）。
- **取舍**：若把「每次 API 调用」也写审计，会与 6.1 的日志重复且量级大（**不推荐**）。

### 6.3 指标（复用仓内 Micrometer 风格）

**仓内既有命名风格**：`iot.<域>.<动作>[.<细分>]`，全部 `public static final String METRIC_*` 具名常量（例：`iot.timeseries.write.rows`、`iot.timeseries.write.attempted`、`iot.timeseries.write.failed`（`IotDbTimeSeriesWriter.java:79,82,96`）；`iot.command.failed`（`CommandInstanceServiceImpl.java:99`）；`iot.alert.evaluate.rounds` / `...round.duration`（`AlertMetrics.java:45,48`））。

**建议新增（同名风格，具名常量）：**

| 常量 | 指标名 | 类型 | 用途 |
|---|---|---|---|
| `METRIC_REQUESTS` | `iot.openapi.requests` | Counter | 调用总量（tags: key/endpoint/code） |
| `METRIC_FAILED` | `iot.openapi.failed` | Counter | 失败量 |
| `METRIC_DURATION` | `iot.openapi.duration` | Timer | 耗时（P50/P95/P99） |
| `METRIC_RATE_LIMITED` | `iot.openapi.rate_limited` | Counter | **限流命中数**（运维最关心） |
| `METRIC_QUOTA_EXCEEDED` | `iot.openapi.quota_exceeded` | Counter | 配额耗尽数 |
| `METRIC_AUTH_FAILED` | `iot.openapi.auth.failed` | Counter | 鉴权失败（可按原因分 tag，**对外不区分**） |

**派生指标**：失败率 = `failed/requests`、限流命中率 = `rate_limited/requests`；可直接接入 M-4 的平台自告警阈值（`docs/TASK-BOARD.md:49` 的 #10「平台自告警 + 指标大盘」已有现成指标基建）。

> **⚠️ 标签基数风险**：**禁止**把 `accessKeyId` 全量做成 tag（Key 数量增长会打爆 Prometheus 基数）。建议 tag 只保留 `endpoint`/`code`，**Key 维度只进日志**。

### 6.4 对第三方可见的状态/配额查询端点（可选）

**建议首期做（成本低、诉求直接命中「容易上手」）：**

- `GET /open-api/v1/whoami`：返回**当前 Key 的身份与权限** —— `appName`、`tenantId`、`scopes`、`rateLimitQps`、`dailyQuota`、`usedToday`、`quotaResetAt`、`expireAt`。
- **价值**：第三方接入第一件事就是自检；有它可**大幅降低**「为什么我查不到/调不通」的沟通成本，且**天然回答了「越权为什么查不到」**（把已授予 scopes 摆明）。
- 该端点**只回当前 Key 自身信息**，不涉及任何跨租户数据。

---

## 7. 验收判据（可测：给定 ⇒ 期望）

> 全部为**可自动化**的用例（建议落为集成测试，`-Pit`/测试容器均可）。口径遵循 **D0.5**（跨租户表现为「查不到」，`docs/IOT-PLATFORM-DESIGN.md:35`）。

| # | 给定 | 期望 |
|---|---|---|
| A-1 | **不带**任何 Key 调 `GET /open-api/v1/devices` | **拒绝**：`401`（或其 `R.code=401`，随 Q1 拍板）；响应**不含**任何设备数据；**不**返回 `200/成功` |
| A-2 | 带**不存在**的 Key（随机 `ak_xxx`） | **拒绝** `401`；**message 与"签名错误"不可区分**（防 Key 枚举）；**无** SQL 写入；`iot.openapi.auth.failed` 计数 +1 |
| A-3 | 带**已过期**（`expire_at` 已过）的 Key | **拒绝** `401`，message 含过期时间提示；**不**在过期后放行任何请求 |
| A-4 | Key 有效但**作用域不含** `iot:series:get`，调 `GET .../series` | **拒绝** `403`（权限码不足，**非** 409）；message 列出已授予 scopes |
| A-5 | Key 有效，QPS 设为 `1`，**连续快速**调 2 次 | 第 1 次成功；第 2 次 `429`（或其 `R.code=429`）；`iot.openapi.rate_limited` +1；**业务逻辑只执行 1 次**（未被限流的请求不得已产生副作用） |
| A-6 | Key 有效且作用域/配额充足，调 `GET /devices/{本租户设备}/latest` | `200` + `R.code=200`；返回该设备点位最新值；响应**不含**其它租户字段 |
| A-7 | **A 租户**的 Key 查 **B 租户**的设备（构造 B 租户设备 ID） | **查不到**：单条查 `R.code=409` + 「不存在」泛化 message；**HTTP 不返回 403**；**响应体不含 B 租户任何字段**（**D0.5 断言口径**） |
| A-8 | 列表查询（`GET /open-api/v1/devices`）以 A 租户 Key 调用，而库中存在 B 租户设备 | 结果**只含 A 租户**数据；**总数**也只含 A 租户（不漏计数）；**不泄露** B 租户存在性 |
| A-9 | Key 被**吊销**（`status=禁用`）后立即调用 | **立即**拒绝 `401`；**无**宽限（除非该 Key 正处于 2.6 的轮换宽限期，且**仅旧密可用**）；缓存**不得**导致放行 |
| A-10 | 触发 `429` 后**等待超过限流窗口**（如 2 秒）再调 | **恢复成功**（限流计数器已过期）；证明限流**不是**永久封禁 |
| A-11 | 用有效 Key 但**签名错误**（篡改 sign / timestamp 超 60s / nonce 重复） | 拒绝 `401`；message 提示 timestamp 与 nonce 规则；**nonce 重复必须拒绝**（防重放） |
| A-12 | 在**错误契约**上逐一比对：参数错误 / 设备不存在 / 业务失败 | 除 Q1 拍板的鉴权/限流例外外，**全部** `HTTP 200 + R.code`；`R` 信封字段齐全（`code/message/data/success/timestamp`） |
| A-13 | 第三方**自带** `X-User-Id`/`X-Tenant-Id`/`X-Api-Scopes`/`X-Gateway-Signed` 调开放 API | **网关清洗生效**：自带值**被丢弃/拒绝**，实际生效的是网关注入值；**不得**出现「自带头即提权」 |
| A-14 | 直连 **iot 服务端口**（绕过网关）伪造 `X-User-Id` + `X-Tenant-Id`（**不带** `X-Gateway-Signed`） | **拒绝**（`403「非法身份来源」`，`IdentityHeaderFilter` fail-closed）；**不得**建立身份 |
| A-15 | 带**有效登录会话**（非 API Key）访问 `/internal/**` | 仍被 `X-Internal-Token` 守卫**拒绝**（证明开放 API 未放宽内部面） |

---

## 8. 不做清单（首期明确不做及理由）

| 不做项 | 理由 |
|---|---|
| **多语言 SDK 生成**（Java/Python/JS…） | 对标阿里有 SDK，但 SDK 生成需引入 codegen 工具链 + 多语言构建/发布/版本同步，**成本远超首期收益**；先用「可下载的 OpenAPI 规范 + curl 示例」替代（规范已由 4.1 生成）。**待规范稳定后再单独立项** |
| **OAuth2 / OIDC 授权码流程** | 面向「第三方系统对系统集成」，API Key 足够；OAuth2 引入授权服务器、同意页、token 刷新与 scope 协商，**复杂度与收益不匹配**；且与现有 Sa-Token 体系是两套模型 |
| **Webhook 回调（平台主动推送给第三方）** | **仓内已明确列为刻意不做**：`docs/TASK-BOARD.md` §4「Webhook 通知（**唯一 SSRF 面**）」⇒ 不做，**不重复立项** |
| **GraphQL** | 与既有 REST + `R` 契约并行的第二套查询模型，**双份维护**；需求方（第三方查数据）用 REST 已满足 |
| **开放 API 的写操作扩展**（改物模型/台账/规则/影子的写） | 破坏面大且非核心集成诉求（见 1.3）；**首期只留 `POST commands` 一个写能力** |
| **每租户自定义限流策略的可视化配置** | 首期用「按 Key 的固定字段 + 具名默认常量」即可；动态策略需新的配置面与审批流 |
| **API 市场 / 自助注册开通** | 需自助开通流程、审核、计费联动 ⇒ 与 §4「刻意不做」的计费/套餐冲突；首期由管理员在控制台签发 |
| **计费 / 用量账单** | 仓内已列为刻意不做（`docs/TASK-BOARD.md` §4：计费/套餐/账单） |
| **批量/聚合型开放接口**（一次查多设备） | 需新契约与新限流口径；首期以单设备为主，避免「一个请求打全租户」的放大攻击面 |
| **独立部署的开放 API 服务/模块** | 上游开放问题 Q6「开放 API 是否拆独立模块」**尚未定**（`docs/IOT-PLATFORM-DESIGN.md:15.2`）。**首期按「iot 内独立 openapi 包」**（上游 `:707` 的首选表述），**不拆模块** |

---

## 9. 实施顺序（草案，待拍板后细化）

1. **前置核验**：O-1…O-7 端点只读/副作用逐一核验；`-Pit` 测试基线确认。
2. **数据面**：`iot_api_key` 建表 + 迁移脚本（**须遵守 SQL 双写纪律**，参照 `deploy/sql/migration/` 既有命名与 `rollback/` 配套）。
3. **鉴权**：网关 `OpenApiKeyAuthFilter` + 身份转签 + 头清洗表补录新头；iot 侧权限解析扩展（2.3 前置）。
4. **门面**：`cn.ypbin.admin.iot.openapi` 薄 controller + 路径映射。
5. **限流/配额**：Redis 计数器 + 具名常量 + `429` 路径。
6. **文档**：引入 `ypbin-starter-api-doc` + 配置 `/open-api/**` 分组 + `security-headers` 声明 API Key 头 + 4001 菜单验证。
7. **可观测**：指标 + 结构化日志 + `whoami` 端点。
8. **测试**：A-1…A-15 全部用例 + 独立复核（本方案涉**权限/安全/对外契约** ⇒ R6 判定为 **L2（边界不清时按最高风险维度定级；Key 鉴权含安全面，建议按 L3 对待并做用户确认）**）。

---

## 10. 需要用户拍板的点

| # | 待决问题 | 选项 | 代价 / 影响 |
|---|---|---|---|
| **Q1** | **开放 API 是否破例用真 HTTP 状态码** | **(a) 不破例**：全部 `HTTP 200 + R.code`（`401/403/429` 只在 `code`）<br>**(b) 小范围破例（本稿建议）**：仅鉴权/限流层用 `401`/`429`（+可选 `403`），其余维持 200 | (a) 与仓内铁律**完全一致**、实现最简、无双路径；代价：通用 HTTP 客户端/监控**看不到限流**。<br>(b) 对第三方更符合直觉、监控友好；代价：**与 `GlobalExceptionHandler` 并存两套错误路径**（须刻意绕开异常处理器）、契约面分裂、**存在先例扩散风险**（上游要求 `/internal/mqtt/**` 例外「不得扩散」，`docs/IOT-PLATFORM-DESIGN.md:786`） |
| **Q2** | **首期开放哪些能力** | **(a) 本稿建议**：O-1…O-6 只读 + O-7 命令下发（7 项）<br>**(b) 更保守**：去掉 O-7，**纯只读**<br>**(c) 更激进**：再加设备标签/映射只读、告警 ack | (a) 有实用价值（能控设备）；命令下发是**写操作**，误用可造成现场影响。<br>(b) 风险最低、验收最快；代价：开放 API 只能"看"不能"控"，集成价值打折。<br>(c) 覆盖更全；代价：**验收面与安全评审面同步扩大**，首期拉长 |
| **Q3** | **是否要求 IP 白名单** | **(a) 可选、默认关闭（本稿建议）**<br>**(b) 强制必须配置**<br>**(c) 首期完全不做** | (a) 灵活；代价：默认不设防。<br>(b) 最安全；代价：第三方出口 IP 变动（云函数/容器）即中断，**运维负担转嫁给用户**。<br>(c) 最省；代价：Key 泄露无第二道防线 |
| **Q4** | **Key 轮换是否要双密宽限期** | **(a) 双密钥并存 `30min`（本稿建议）**<br>**(b) 立即失效，要求停服切换** | (a) 第三方平滑轮换、不中断；代价：多 2 列 + 过期清理逻辑 + 更复杂的测试面。<br>(b) 实现最简、语义最干净；代价：轮换必然造成一次服务中断窗口 |
| **Q5** | **生产环境是否暴露 API 文档（`/v3/api-docs`、swagger-ui）** | **(a) 生产开放**（第三方自助查阅）<br>**(b) 生产关闭**（`ypbin.api-doc.disable-in-prod`），仅提供**离线规范文件**下载 | (a) 对"有文档可查"最友好；代价：**生产暴露接口全貌**（含内部端点，若分组配置不当）⇒ 必须严格用 `paths-to-match` 限定 `/open-api/**`。<br>(b) 安全面最小；代价：第三方需另取文档，时效性差 |
| **Q6** | **开放 API 走网关鉴权 还是 允许 iot 侧自校验** | **(a) 网关鉴权（本稿建议）**<br>**(b) iot 侧自校验**（网关仅转发） | (a) 复用既有身份体系与租户隔离，**iot 业务零改造**；代价：网关新增过滤器（WebFlux），**`SignChecker` 是 Servlet 组件不能直接复用**，须移植或分层（签名在 iot、租户/限流在网关）。<br>(b) 可直接复用 `SignChecker`；代价：**绕开网关等于削弱既有安全纵深**，限流无法全局准确，且需在 iot 重复实现租户注入 |

---

## 11. 未核实项 / 已知风险（如实登记）

| # | 未核实项 | 影响 | 建议核实方式 |
|---|---|---|---|
| U-1 | **O-1…O-7 各端点的实际"可开放性"** —— 本文按 controller 签名与权限码**静态判定**，**未逐一实调**确认无隐藏副作用（如 GET 触发远程调用/写库/审计写） | 若某 GET 有副作用，开放后可能被第三方高频触发 | 实施前逐端点走查 Service 实现 + 在有数据的测试库实调一次 |
| U-2 | **网关注入身份头 → iot 权限解析的衔接**（2.3 的 `X-Api-Scopes` 方案）**未做可行性验证**；`IotPermissionProvider` 当前只认 `userId` → `SysCache`（`IotPermissionProvider.java:37-42`） | 若不成立，作用域隔离**无法实现**，O-4/O-7 等按作用域裁剪将失效 | 起一个最小原型：注入 scopes 头 → 断言 `@SaCheckPermission` 按 scopes 判定 |
| U-3 | **`SignChecker`（Servlet）与网关（WebFlux）的组件不兼容** | 决定 3.1 链路里"签名在哪一层校验" | 实施设计时定案；两案（网关移植 / iot 侧验签）择一 |
| U-4 | **`X-Forwarded-For` 可信性**（IP 白名单取真实客户端 IP 的前提）**未核实** | 若直接信 XFF，白名单可被伪造绕过 | 核实网关前置代理拓扑；必要时只用远端 IP |
| U-5 | **`GATEWAY_SIGN_TOKEN` 在开放 API 场景的作用域**：API Key 请求转签后用的是**同一把**网关签名密钥。密钥泄露后即可伪造任意租户身份（含开放 API 主体） | 与既有 SF-6b 风险同源但**面被放大**（多了"第三方 Key → 租户身份"这条路径） | 评估是否为开放 API 引入**独立签名密钥**（需网关侧支持双密钥） |
| U-6 | **`ypbin-starter-api-doc` 在 iot 服务的实际行为**（未实跑：iot 当前未依赖它，无法验证 `/v3/api-docs` 与 swagger-ui 是否在 iot 上正常暴露） | 4.1 的"注解生成"结论强度受限 | 加依赖后实跑 `/v3/api-docs` 与 `/swagger-ui/index.html` 确认 |
| U-7 | **保留虚拟用户 ID 段**的具体取值（须避开 Sa-Token 哨兵 `-3/-4/-5`，见 `IdentityStpLogic` 类注释） | 取值不当可能与真实用户/哨兵冲突 ⇒ **提权** | 实施前定段并加断言测试 |
| U-8 | **生产机 `ypbin-prod`** 本次**未登录、未做任何操作**（任务要求只读参考，且不做部署）；本文所有"生产现状"结论均来自仓内文件 | 生产实际配置可能与仓内 Nacos 样例有漂移 | 若需生产口径，另行只读核实 |

---

## 12. 引用索引（仓内证据）

| 结论 | 证据位置 |
|---|---|
| 开放 API 整块未开始 | `docs/PLATFORM-GAP-REPORT-2026-09-28.md:206`；`docs/TASK-BOARD.md:44` |
| API Key 口径（哈希/轮换/限流） | `docs/IOT-PLATFORM-DESIGN.md:712,727,763,778` |
| `/openapi/**` 前缀（上游） | `docs/IOT-PLATFORM-DESIGN.md:778` |
| 真状态码唯一例外（`/internal/mqtt/**`） | `docs/IOT-PLATFORM-DESIGN.md:781,786` |
| 跨租户「查不到」口径（D0.5） | `docs/IOT-PLATFORM-DESIGN.md:35,714,724` |
| iot 关闭登录拦截、租户 fail-closed | `deploy/nacos/ypbin-iot.yaml:11,31-32`；`:23`（`trusted-source-token`） |
| iot 平台表 `ignore-tables` | `deploy/nacos/ypbin-iot.yaml:15-19` |
| 网关路由与短名剥离 | `deploy/nacos/ypbin-gateway.yaml:49-54` |
| 网关头清洗（整体覆盖语义） | `deploy/nacos/ypbin-gateway.yaml:66-80` |
| `/system/open-api/**` 已在免登录白名单 | `deploy/nacos/ypbin-gateway.yaml:103`；`deploy/nacos/ypbin-system.yaml:92` |
| `@ApiSign` 示例与 `ypbin.sign` 配置 | `ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/controller/OpenApiDemoController.java:13,27,30-31`；`deploy/nacos/ypbin-system.yaml`（`ypbin.sign` 段） |
| 4001 api-doc 菜单 | `deploy/sql/002-data.sql:180`；`deploy/sql/migration/2026-09-26-iot-platform-module-menu.sql:57` |
| 内部守卫（fail-closed + 常量时间） | `ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/config/InternalTokenGuardWebConfig.java:32-33`；`.../InternalTokenGuardInterceptor.java:52-78` |
| 权限数据源（`userId` → `SysCache`） | `ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/core/IotPermissionProvider.java:33-48` |
| `iot:*` 权限码清单 | `deploy/sql/007-iot-data.sql` |
| 既有指标命名风格 | `.../timeseries/IotDbTimeSeriesWriter.java:79,82,96`；`.../service/impl/CommandInstanceServiceImpl.java:99`；`.../alert/AlertMetrics.java:45,48` |
| 全局异常处理（全 200 + `R.code`） | `ypbin-starter-web` 的 `cn/ypbin/starter/web/handler/GlobalExceptionHandler.java:61-173` |
| `IdentityContext` / 身份头常量 | `ypbin-starter-security` 的 `identity/IdentityContext.java`、`identity/IdentityHeaders.java:30-34,45` |
| `IdentityHeaderFilter` 强制来源标记 | `ypbin-starter-security` 的 `identity/IdentityHeaderFilter.java:72,110-118` |
| `annotation-check` 默认 true | `ypbin-starter-security` 的 `autoconfigure/SecurityProperties.java:50-62` |
| `IdentityStpLogic` 把身份头接入注解鉴权 | `ypbin-starter-security` 的 `identity/IdentityStpLogic.java`（类注释） |
| 签名设施（`SignChecker`/`SignAppProvider`/`SignProperties`） | `ypbin-starter-sign:3.6.0` 的 `core/SignChecker.java`、`core/SignAppProvider.java`、`autoconfigure/SignProperties.java`、`interceptor/SignInterceptor.java` |
| API 文档 starter 能力与属性 | `ypbin-starter-api-doc:3.6.0` 的 `META-INF/spring-configuration-metadata.json`、`META-INF/spring/...AutoConfiguration.imports` |
| starter 版本 | `pom.xml:28`（`ypbin-starter.version = 3.6.0`） |

> **本稿未修改任何既有文件**（仅新建本文档）；未写 SQL、未写代码、未部署、未 commit。
