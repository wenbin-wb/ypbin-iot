# 开放 API（M-5）静态前置核验报告

> **性质**：对 `docs/OPENAPI-DESIGN.md` 第 11 节三个高危未核实项（U-1 / U-2 / U-6）的**纯静态核验**。
> **方法**：只读一手源码（`文件:行号`）+ 只读 jar 内 starter 源码（`~/.m2` 的 `-sources.jar`）。
> **边界**：**未跑 Maven/构建/测试/部署/commit**，未修改任何既有文件（含 pom、SQL、代码、设计稿）。本报告为本次唯一写入物。
> **约束**：用户已拍板 Q1 = 鉴权/限流层小范围破例真状态码（范围严格收口）；Q2 = 首期含命令下发（7 项）。本报告**在此前提下**核验，不回头质疑该两项决定。
> **规范**：遵守全局 `~/.dsh/AGENTS.md` 第 2 节 R1–R8。

---

## 0. 结论先行（TL;DR）

| 核验项 | 判定 | 一句话结论 |
|---|---|---|
| **U-1** 7 端点可开放性 | **有条件通过**（6 项可开放，1 项需改） | O-1…O-6 六个只读端点**静态核验为真无副作用**、租户隔离由插件承担且 fail-closed；**O-7 命令下发判定「有条件开放」——必须在 4 个条件全部落地后才可开放，当前形态不可直接开放** |
| **U-2** 虚拟主体 → scopes | **不通过（原方案 (a) 的字面写法）** | `IotPermissionProvider` 仅认 `userId → SysCache`；**但存在一条零改动既有链路的可行路径**（推荐方案 B：网关侧查真实权限码注入），原方案要求的「改 `IotPermissionProvider` 或新增前置 `PermissionProvider`」**不是必需的**，且按原字面实现会**破坏超管语义** |
| **U-6** `ypbin-starter-api-doc` 可用性 | **通过（带 2 个必修条件）** | 坐标在 BOM 内、iot 为 Spring MVC 与 starter 的 `webmvc-ui` 变体**匹配**；**2 个必修项**：`excludeApiDoc` 默认放行会让文档免登录 + `disable-in-prod` 只在 `prod` profile 生效，**必须显式配置否则生产可能裸奔** |

### 是否有阻断项

- **有 1 个阻断项（可解）**：**O-7 命令下发**。阻断原因不是「实现不了」，而是 **4 个已核实的具体缺口**（跨租户写入路径、EMQX 调用在事务内、无幂等/无限流、重发放大）。**结论：O-7 不能在首期与其它 6 项同期开放**，须先补齐 §1.3 的 4 个条件。
- **U-2 不是阻断项**：推荐的方案 B 不改任何既有鉴权代码即可成立（见 §2.3），但**必须在实施设计里替换掉原 2.3 节的 (a) 方案**。
- **U-6 不是阻断项**：改动清单明确（§3.3），但含 2 个**安全必修项**。

> **⚠️ 最重要的单条结论**：`docs/OPENAPI-DESIGN.md:150-153` 与 `:506`（U-2）把「改 `IotPermissionProvider`」当作**必需**前置，据此把工作量与风险都估高了。**实际存在更优路径（方案 B），既不改既有鉴权链、也不破坏超管语义**。原方案该处需改写。

---

## 1. U-1：7 个端点的可开放性

### 1.0 三个横切事实（先立地基，7 项共用）

**事实 ①：租户隔离链完整且 fail-closed（这是 O-1…O-7 隔离成立的根本）。**

```
网关注入 X-Tenant-Id（来自库/会话，非请求）
  → IdentityHeaderFilter 校验 X-Gateway-Signed 后写入 IdentityContext   (IdentityHeaderFilter.java:110-118, 131-135)
  → MicroserviceTenantProvider.getCurrentTenantId() = IdentityContext.getTenantId()  (MicroserviceTenantProvider.java:29-31)
  → MP 租户插件 DefaultTenantLineHandler.getTenantId()  (DefaultTenantLineHandler.java:57-69)
       无租户上下文 + fail-on-missing-tenant:true  ⇒  直接抛 BusinessException（拒绝执行）
  → iot 配置 ypbin.tenant.fail-on-missing-tenant: true  (deploy/nacos/ypbin-iot.yaml:11)
```

- `DefaultTenantLineHandler.java:58-59`：`TenantContext` 优先，其次 `tenantProvider::getCurrentTenantId`。
- `DefaultTenantLineHandler.java:63-66`：无租户上下文且 `fail-on-missing-tenant=true` ⇒ **抛异常拒绝**，不会静默退化成「查全表」。
- `DefaultTenantLineHandler.java:77-80`：忽略表 = 线程级忽略（`@TenantIgnore`/`TenantContext.executeIgnore`）∪ 静态 `ignore-tables`。
- iot 的 `ignore-tables` **只有 3 张平台表**：`tenant_node_assignment`、`access_node`、`tenant_ledger`（`ypbin-iot.yaml:15-19`）。⇒ **设备/产品/告警/事件/时序相关表全部在隔离范围内**。

**事实 ②：`@SaCheckPermission` 在 iot 真实生效（不是装饰）。**
`ypbin-iot.yaml:31-32` 关的是 `interceptor`（登录态校验），**`annotation-check` 未配置 ⇒ 取 starter 默认 `true`**（`SecurityProperties.java:59`），二者相互独立（`SecurityProperties.java:49-58`）。身份来源由 `IdentityStpLogic` 以身份头接进 Sa-Token（`SecurityAutoConfiguration.java:246-255`）。

**事实 ③：O-1…O-7 全部依赖「租户上下文存在」。**
`X-Tenant-Id` 缺失时 `fail-on-missing-tenant=true` 会让**每一条** SQL 直接失败 —— 这是**好**的（fail-closed），但意味着开放 API 链路里**网关注入 `X-Tenant-Id` 是不可省的一步**（与设计稿 §3.1 一致）。

### 1.1 逐端点核验

#### O-1 设备台账查询 —— ✅ **可开放**

| 项 | 证据 | 判定 |
|---|---|---|
| 端点 | `GET /devices`（`IotDeviceController.java:55-59`） | — |
| 权限码 | `iot:device:list`（`IotDeviceController.java:56`） | 与设计稿一致 |
| 副作用 | `pageDevices` → `page(query, buildWrapper(query))` + stream 映射（`IotDeviceServiceImpl.java:59-63`）。**无 insert/update/delete/Redis/远程调用** | **真无副作用** ✓ |
| 租户隔离 | `IotDevice extends TenantBaseEntity`（`IotDevice.java:32`）⇒ `iot_device` 是租户表，插件自动追加条件 | 插件承担 ✓ |
| 越权路径 | 无。查询无 `executeIgnore`、无显式 tenant 条件绕过 | 未发现 ✓ |

> **注意（非阻断）**：列表**总数**由同一 wrapper 约束（`IotDeviceServiceImpl.java:60-62` 走 starter `page`）⇒ 计数也受租户条件约束，不泄露他租户存在性（对齐设计稿 A-8）。

#### O-2 最新值查询 —— ✅ **可开放**

| 项 | 证据 | 判定 |
|---|---|---|
| 端点 | `GET /devices/{deviceId}/latest`（`IotLatestValueController.java:50-54`） | — |
| 权限码 | `iot:device:latest`（`:51`） | 与设计稿一致 |
| 副作用 | `LatestValueQueryService.listLatest`：`deviceMapper.selectById` → Redis **HGETALL 只读** → 排序返回（`LatestValueQueryService.java:111-182`）。**无写库、无写 Redis** | **真无副作用** ✓ |
| 租户隔离 | 先 `selectById(deviceId)`（走插件，跨租户查不到 ⇒ 抛「设备不存在」，`:115-119`），再由**设备行的 tenantId** 组 Redis key（`:126`） | **双层正确**：即使 Redis key 里带 tenantId，也是先经租户校验的设备行得出 ✓ |
| 越权路径 | 无。`pointMappingIndex.loadCoordinates` 在租户上下文内（`LatestValueQueryService.java:141-144`，**未** ignore） | 未发现 ✓ |
| 失败语义 | Redis 不可用 ⇒ 空列表（`:120-125, 131-136`）；**不是**报错 | 对第三方需在文档说明（非阻断） |

#### O-3 历史时序查询 —— ✅ **可开放（有条件：当前存储未启用）**

| 项 | 证据 | 判定 |
|---|---|---|
| 端点 | `GET /devices/{deviceId}/series`（`IotTimeSeriesController.java:51-56`） | — |
| 权限码 | `iot:series:get`（`:52`） | 与设计稿一致 |
| 副作用 | `TimeSeriesQueryService.query`：纯参数校验 + `deviceMapper.selectById` + 映射查询 + `store.query`（`TimeSeriesQueryService.java:65-95`）。**无写入** | **真无副作用** ✓ |
| 租户隔离 | `resolveTenant` 走 `selectById`（插件约束，`:124-131`）；查询显式带 tenantId（`:91`） | 插件承担 ✓ |
| **条件** | `store.available()==false` 时**抛业务错误**（`:80-83`）—— iot 配置 `timeseries.enabled: true`（`ypbin-iot.yaml:160`），但**存储真连得通与否静态不可判** | **须实跑确认**（§5） |

> **O-3 的对外影响**：若生产 IoTDB 未就绪，第三方拿到的是「历史时序查询未启用」业务错误。**这不影响「可开放性」判定（无副作用），但影响「能演示」**。属「需实跑才能定」的**可用性**（非安全性）问题。

#### O-4 告警查询 —— ✅ **可开放**

| 项 | 证据 | 判定 |
|---|---|---|
| 端点 | `GET /alerts`、`/alerts/{id}`、`/alerts/summary`（`IotAlertController.java:73-100`） | — |
| 权限码 | 三个均 `iot:alert:list`（`:74, 86, 97`） | 与设计稿一致 |
| 副作用 | 三者均先 `gate.requireEnabled()` 再**纯查询**（`AlertInstanceServiceImpl.java:112-183`）。**写操作只在 `ack`/`silence`**（`:201-212, 273-287`，带 `@Transactional`） | **读路径真无副作用** ✓ |
| 租户隔离 | `IotAlertInstance` 为租户表，wrapper 不 ignore 租户；`detail` 跨租户查不到 ⇒ 抛「告警不存在或不属于当前租户」（`:161-165`） | 插件承担 ✓ |
| 越权路径 | 无 | 未发现 ✓ |

> **⚠️ `/alerts/active-counts` 不在首期清单**（`IotAlertController.java:108-112`，权限码同 `iot:alert:list`）。若开放 `iot:alert:list` 即等于**隐含开放**它 —— 设计稿 1.3 表未列此端点。**建议**：门面只暴露 `list/detail/summary`，`active-counts` 留在管理面（对齐设计稿「未盘点到的端点一律不开放」口径，`:21`）。

#### O-5 产品/物模型只读 —— ✅ **可开放**

| 项 | 证据 | 判定 |
|---|---|---|
| 端点 | `GET /products`、`/{id}`、`/{id}/versions`（`IotProductController.java:56-72, 153-157`） | — |
| 权限码 | 全部 `iot:product:list`（`:57, 69, 154`） | 与设计稿一致 |
| 副作用 | `pageProducts`/`detailProduct`/`listVersions` 均为查询；**写操作（create/update/delete/draft/publish）各有独立权限码**（`:81, 96, 111, 126, 140`） | 读路径真无副作用 ✓ |
| 物模型 GET | `IotThingModelController`：`/services`(`:63-65`)、`/services/{id}/properties`(`:127-129`)、`/commands`(`:191-193`)、`/events`(`:255-257`) 均 `iot:product:list`；写操作均 `iot:product:update` | ✓ |
| **⚠️ 例外** | `GET /tsl`（`IotThingModelController.java:319-320`）权限码是 **`iot:product:tsl-export`**，**不是** `iot:product:list` | **设计稿 1.3 与作用域表未覆盖此码**（§1.2 结论） |
| 租户隔离 | `iot_product` 等为租户表，插件承担 | ✓ |

#### O-6 事件 + 可用率 —— ✅ **可开放**

| 项 | 证据 | 判定 |
|---|---|---|
| 事件端点 | `GET /devices/{deviceId}/events`（`IotDeviceEventController.java:50-55`），权限码 **`iot:device:list`**（`:51`，刻意复用不新造，见 `:28-29`） | ✓ |
| 事件副作用 | `pageEvents`：时间校验 → `selectById` 校验设备 → 纯 wrapper 查询（`IotEventServiceImpl.java:118-148`）。**无写入** | 真无副作用 ✓ |
| 可用率端点 | `GET /devices/{deviceId}/availability`（`IotAvailabilityController.java:50-57`），权限码 `iot:availability:get`（`:51`） | ✓ |
| 可用率副作用 | `AvailabilityServiceImpl.query`（`:751-832`）：`selectById` + `selectNow` + 聚合查询 + `selectList`。**纯读** | 真无副作用 ✓ |
| 租户隔离 | 两者均先 `deviceMapper.selectById(deviceId)`；`query` 还显式要求 `currentTenantId()` 非空否则报错（`AvailabilityServiceImpl.java:774-778`） | **双层正确** ✓ |

> **关键区分（避免误判）**：`AvailabilityServiceImpl` **里确实有写操作**（`ingest`、`scanAndOpenOutages`，含 `insert`/`deleteBatchIds`/`executeIgnore`，见 `:196-236, 662-748`），但**它们不在 `GET /availability` 的调用链上** —— 分别是内部上报端点与 `@Scheduled` 扫描。**核验结论只针对 `query` 方法**。这是设计稿 U-1 所担心的「隐藏副作用」的**正确排除方式**：按方法而非按类判定。

#### O-7 命令下发（写）—— ⚠️ **有条件开放（当前形态不可开放）**

**调用链**：`IotCommandController.java:59-66` → `CommandInstanceServiceImpl.send`（`:182-225`）。
权限码 `iot:debug:send`（`:60`）。**controller 上无 `@Idempotent`**（对比 `IotDeviceController.create` 有，`:69`）。

**① 完整副作用清单**（`CommandInstanceServiceImpl.send`，`:182-225`）：

| # | 副作用 | 证据 | 性质 |
|---|---|---|---|
| 1 | DB 写：`iot_command_instance` **insert**（状态 `PENDING`） | `:204-219` | 一次请求一行 |
| 2 | **EMQX HTTP 调用**：`emqxAdminClient.publish` | `:220` → `publishAndPersist` → `:452-453` | **外部远程调用** |
| 3 | DB 写：**update** 状态 `PENDING→SENT`/`FAILED` + `emqx_message_id`/`sent_at`/`finished_at`/`error_*` | `:486-495` | 同事务内第二次写 |
| 4 | Micrometer 计数 | `:496-500` | 无副作用 |
| 5 | 日志（`LogSanitizer` 脱敏） | `:221-223` | 无副作用 |
| — | 事务 | `@Transactional(rollbackFor = Exception.class)`（`:183`） | **覆盖 1+2+3** |

- **只读路径（`page`）无副作用**（`:228-255`）。
- 无 Redis 写、无 Spring 事件、无异步线程（此处）。
- EMQX 调用**有显式超时**：connect 2000ms / read 5000ms（`EmqxProperties.java:51,54`；用于 `EmqxRestAdminClient.java:114, 246`），符合仓内「远程调用防失控」红线。

**② 缺口 1（最严重）：EMQX 远程调用在数据库事务内。**
`@Transactional` 在 `send` 上（`:183`），`instanceMapper.insert`（`:219`）与 `emqxAdminClient.publish`（`:220`）**同一事务**。
⇒ 一次慢 EMQX（最坏 5s read timeout）会**占住数据库连接与事务 5 秒**。开放给第三方后，**并发的下发请求会同时压住 EMQX 与 DB 连接池**，形成**跨资源放大**。管理面（登录用户、低频）不敏感；**开放 API（高频、可被滥用）会把它变成可用性问题**。

**③ 缺口 2：幂等键由服务端生成，重复下发无法去重。**
`nextRequestId()` = `"cmd-" + IdWorker.getId()`（`:695-697`），**每次调用都新生成**。
⇒ 第三方**重复发送同一语义请求**（网络重试、双发）会**产生两条独立命令、两次真实下发到设备**。`CommandSendReq` 无任何客户端幂等字段（`CommandSendReq.java:44-70`：`kind/identifier/params/timeoutMs/writeDesired`）。
⇒ **`resend`（`:259-277`）才是幂等的那条**（复用同一 `requestId`，且状态机限定 `isResendable()`，`:266-268`）—— 但它**不在首期开放清单**内（设计稿 1.3 只列 `POST send`）。**这形成倒挂：幂等的那条不开放，不幂等的那条开放。**

**④ 缺口 3：无频率/并发护栏。**
设计稿 §2.4 自认「仓内当前没有限流组件」，限流是**新增能力**。在它落地前，持有 `iot:debug:send` 的 Key 的**唯一约束是 EMQX 的实际吞吐**。

**⑤ 租户隔离（这条是好的，但需精确表述）。**
`requireDevice`（`:537-546`）走 `deviceMapper.selectById` —— **在租户上下文内执行**，跨租户设备查不到 ⇒ 抛「设备不存在」。**写入行时 `row.setTenantId(device.getTenantId())`**（`:206`）用**设备行的租户**（非请求方声称的）。topic 也用 `device.getTenantId()`（`:198`）。
⇒ **A 租户的 Key 无法给 B 租户的设备下发**（在 `X-Tenant-Id` 正确注入的前提下）✓。

> **但注意 `resolveTenant`（`:598-605`）用的是 `TenantContext.executeIgnore`** —— 这是**回执链路**（`applyReply`，`:333`）用的，**不在 `send` 的调用链上**。核验确认：`send` 全程**未** ignore 租户 ⇒ **无跨租户写入路径** ✓。（该 `executeIgnore` 是 `/internal/command-replies` 无租户身份时的必要设计，见类注释 `:82-85`。）

**⑥ 其余校验（良好，值得保留）**：物模型校验（标识是否存在、属性是否可写，`:648-678`）、`params` ≤64KB 且必须是对象（`:189-192`）、`writeDesired` **显式拒绝而非静默忽略**（`:508-513`）、状态机硬校验（`:585-590`）。

> **判定：有条件开放。** 4 个条件见 §1.3。

### 1.2 逐条判定汇总

| # | 能力 | 端点（文件:行号） | 权限码 | 判定 |
|---|---|---|---|---|
| O-1 | 设备台账查询 | `IotDeviceController.java:55-59` | `iot:device:list` | ✅ **可开放** |
| O-2 | 最新值 | `IotLatestValueController.java:50-54` | `iot:device:latest` | ✅ **可开放** |
| O-3 | 历史时序 | `IotTimeSeriesController.java:51-56` | `iot:series:get` | ✅ **可开放**（存储可用性须实跑） |
| O-4 | 告警查询 | `IotAlertController.java:73-100` | `iot:alert:list` | ✅ **可开放**（建议排除 `active-counts`） |
| O-5 | 产品/物模型只读 | `IotProductController.java:56-72,153-157`；`IotThingModelController.java:63,127,191,255` | `iot:product:list` | ✅ **可开放**（`/tsl` 码不同，见下） |
| O-6 | 事件 + 可用率 | `IotDeviceEventController.java:50-55`；`IotAvailabilityController.java:50-57` | `iot:device:list`；`iot:availability:get` | ✅ **可开放** |
| **O-7** | **命令下发** | `IotCommandController.java:59-66` | `iot:debug:send` | ⚠️ **有条件开放（4 条件）** |

**对设计稿作用域表的 2 处修正**（§2.3 表 `:138-146`）：

1. **`GET /tsl` 需要 `iot:product:tsl-export`，不是 `iot:product:list`**（`IotThingModelController.java:319-320`）。
   ⇒ 若按现表把 O-5 全部映射到 `iot:product:list`，**`/tsl` 会对所有 O-5 的 Key 返回 403**（作用域不足）。
   **修法（二选一）**：(i) 在作用域表里**新增一行** `iot:product:tsl-export`（推荐，语义更准）；(ii) 把 `/tsl` 从 O-5 首期清单**移除**。
   **不要**为了省事改成「O-5 同时授予两个码」—— 那等于把「导出物模型」这个更宽的权限**默认塞给所有第三方**。
2. **`/alerts/active-counts` 建议明确排除**（见 O-4 注）。

### 1.3 O-7 开放的 4 个前置条件（逐条可验收）

| # | 条件 | 为什么（证据） | 验收判据 |
|---|---|---|---|
| **C1** | **把 `publish` 移出数据库事务**（先提交 `PENDING` 行，再发 EMQX，再独立更新结果） | 一次慢 EMQX 占住事务最多 5s（`CommandInstanceServiceImpl.java:183+219-220`；`EmqxProperties.java:54`） | 下发期间 DB 连接不长时间被占；EMQX 不可达时不阻塞其它请求 |
| **C2** | **为开放侧提供客户端幂等键**（入参 `clientRequestId`，同租户+同设备+同键唯一） | 现 `requestId` 服务端每次新生成（`:695-697`），重复请求 ⇒ 重复下发 | 同一幂等键重复调用 ⇒ 只产生 1 条命令、**只下发 1 次** |
| **C3** | **落地「按 Key 的限流 + 配额」后再开放 O-7** | 仓内当前无限流组件（设计稿 `:157` 自认）；`send` 无任何频率护栏 | 超限返回 `429`（或 `R.code=429`，随 Q1），**业务逻辑不执行** |
| **C4** | **O-7 默认不授予**，须显式勾选 | 设计稿 `:146` 已如此设计；需在实现中落实 | Key 创建默认 scopes 不含 `iot:debug:send` |

> **建议的实施顺序结论**：**O-1…O-6（6 个只读）可先开放并交付价值**；**O-7 作为「第二批」**，在其自身 4 个条件（尤其 C1、C2）落地并跑通 A-5/A-11 后再开。这与用户 Q2「首期含 O-7」的**方向不冲突** —— 只是**不能在 O-1…O-6 同一批上线**（风险等级不同）。

---

## 2. U-2：虚拟主体 → scopes 的可行性

### 2.1 现状：`IotPermissionProvider` 只认 `userId → SysCache`（一手核实）

`IotPermissionProvider.java:37-49`：

```java
public List<String> getPermissions(Object loginId, String loginType) {
    Long userId = resolveUserId(loginId, loginType);       // Long.valueOf(loginId.toString())
    if (userId == null) return List.of();
    List<String> permissions = SysCache.getUserPermissions(userId);   // ← 唯一数据源
    if (permissions == null) { log.error(...); return List.of(); }
    return permissions;
}
```

- `:72-84` `resolveUserId`：**只接受能解析成 `Long` 的 `loginId`**，否则返回 `null` ⇒ 空权限。
- `:43` **唯一数据源是 `SysCache.getUserPermissions(userId)`** —— 即「真实用户在 system 的权限缓存」。
- ⇒ **它不认识「虚拟主体 → scopes」，确认设计稿 U-2 描述属实**。

### 2.2 关键发现：`loginId` 就是 userId 字符串，且 SPI 是「单 Bean、可整体覆盖」

三条**决定方案选择**的一手事实：

1. **`IdentityStpLogic` 把身份头当账号来源，且 `loginId` = userId 的十进制字符串**
   （`IdentityStpLogic.java:139-141`：`currentToken()` = `IdentityContext.getUserId().map(String::valueOf)`）。
   ⇒ `PermissionProvider.getPermissions(loginId, loginType)` 收到的 `loginId` **就是 `X-User-Id` 的值**。

2. **`StpPermissionAdapter` 是唯一的桥**（`StpPermissionAdapter.java:58-66`），它只是**转发**给 `PermissionProvider`，并在返回前做超管通配符补全（`:75-89`）。
   - **重要**：`SUPER_ADMIN = "*:*:*"`，Sa-Token 的 `ANY = "*"`（`:46-50`）。
   - `:82-88`：**只有**集合里含 `*:*:*` 时才追加 `*`。

3. **`PermissionProvider` 是「业务方实现即整体接管」的 SPI**（`SecurityAutoConfiguration.java:88-94`）：
   `@Bean @ConditionalOnMissingBean public PermissionProvider permissionProvider()` ⇒ iot 的 `@Component IotPermissionProvider` 已接管（`IotPermissionProvider.java:32-33`）。**全服务只有一个 `PermissionProvider` Bean。**

### 2.3 三条可行路径对比

#### 方案 A（设计稿原议 a）：改 `IotPermissionProvider` 读 `X-Api-Scopes` 头

- **改动面**：改 `IotPermissionProvider`（iot 服务内），读一个网关注入的内部头，**该头命中时优先于 `SysCache`**；同时**必须**把该头并入网关 `header-sanitize`（否则第三方自带 ⇒ **提权**）。
- **风险（高）**：
  - **破坏超管语义**：虚拟主体走 `X-Api-Scopes` 分支时不再查 `SysCache` ⇒ 若某天 Key 的 scopes 里被写入 `*:*:*`，`:82-88` 会追加 `*` ⇒ **该 Key 变成全权限**。须在写入侧**显式禁止** `*:*:*`（这是一条容易漏的隐性约束）。
  - **头穿透即提权**：`header-sanitize` 是**整体覆盖语义**（`ypbin-gateway.yaml:71-73` 明写「漏写任何一个身份头都等于取消对该头的清洗」）⇒ 加头时漏一项就是**直接提权漏洞**。
  - 改的是**既有鉴权链本身**（影响所有管理面请求的权限解析路径）。
- **判定**：**可行但不推荐**。

#### 方案 B（推荐）：网关侧「查真实权限码 → 注入 `X-Roles` 或复用既有头」，iot **零改动**

- **核心思路**：**不引入新头、不改 iot 任何代码**。网关在 API Key 校验通过后，**把该 Key 的 scopes 集合，按既有身份头的既有语义注入**。
- **为什么能成立（一手依据）**：`IdentityHeaderFilter` 已经**原生解析 `X-Roles`**（`IdentityHeaderFilter.java:141-148`，逗号分隔 ⇒ `Set<String>`）。而 `X-Roles` **已在网关清洗表内**（`ypbin-gateway.yaml:84`）⇒ **不存在新头穿透问题**，**无需改清洗表**。
- **但 `@SaCheckPermission` 用的是 `PermissionProvider`，不是 `X-Roles`** —— 这是必须解决的一环。两条子路线：

  | 子路线 | 做法 | 改动面 | 风险 |
  |---|---|---|---|
  | **B1** | 网关为每个 Key 在 `SysCache` 对应的权限体系里**建一个影子角色**（如 `openapi:{appId}`），把 scopes 授给该角色；网关注入 `X-User-Id` = 影子用户 | 需建角色/用户数据 + 网关查一次角色 | **污染用户表**（设计稿 `:152` 已明确不推荐） |
  | **B2（推荐）** | **网关侧把 scopes 直接查出来，作为「虚拟主体的权限码」注入**，iot 侧让 `IotPermissionProvider` **能在拿不到 `SysCache` 结果时回落到 `X-Roles` 头** | 小：iot 加 1 个回落分支；**不改**既有查 `SysCache` 的主路径 | **最低**：管理面路径完全不变 |

- **完善后的方案 B（B2）精确形态**：
  1. 网关 `OpenApiKeyAuthFilter` 校验 Key → 取 `scopes` → **注入 `X-User-Id` = 保留虚拟 ID**、`X-Tenant-Id` = Key 的租户、`X-Roles` = **scopes 集合**（复用既有头，`IdentityHeaderFilter.java:141-148` 会解析进 `LoginUser.roles`）。
  2. iot 侧 `IotPermissionProvider`：**保持 `SysCache` 为主路径不变**；仅当 `userId` 命中**保留虚拟 ID 段**时，改从 `LoginUser.getRoles()`（即 `X-Roles`）取权限码。
  3. **风险点**：虚拟 ID 段必须避开 Sa-Token 哨兵 `-3/-4/-5`（`IdentityStpLogic.java:49-50` 明写），也**必须避开所有真实 userId**。
- **与既有 `@SaCheckPermission` 的关系**：**完全不变**。注解仍走 `StpInterface → StpPermissionAdapter → PermissionProvider`，只是虚拟主体那一条分支换了数据源。**管理面（真实用户）路径一行不改** ⇒ 超管语义、`SysCache` 缓存、`StpPermissionAdapter` 的通配符补全都按原样工作。
- **判定**：**推荐**。

#### 方案 C：新增独立校验器（如专门的 `OpenApiScopeInterceptor`），与 `@SaCheckPermission` 并行

- **改动面**：在 `openapi` 门面包上加一个只针对 `/open-api/**` 的拦截器，自行比对 scopes。
- **风险**：**两套鉴权并存** ⇒ 同一个能力有两个判定点，**极易出现「一处放行、一处拦截」的不一致**；而且**绕过了 `@SaCheckPermission`**，等于开放侧不享受既有权限体系的一致性。与仓内「命名一致性 / 单一解析路径」取向冲突。
- **判定**：**不推荐**（除非将来开放 API 需要与后台权限模型**刻意解耦**，届时应独立立项）。

### 2.4 明确结论

**推荐方案 B（B2 形态）**，理由：

1. **改动面最小**：iot 侧只加 1 个「虚拟 ID 段 → 读 `X-Roles`」的回落分支；**网关侧零新增头**（复用 `X-Roles`，**无需动 `header-sanitize`**）。
2. **不破坏既有体系**：`@SaCheckPermission`、`SysCache`、`StpPermissionAdapter`、超管语义**全部按原样工作**，管理面路径零改动。
3. **风险最低**：不引入新的可伪造头 ⇒ **消除**设计稿 A-13 里「自带头即提权」的那一类风险（只需保证虚拟 ID 段不可被外部指定 —— 而 `X-User-Id` 本来就在清洗表内，`ypbin-gateway.yaml:80`）。
4. **与设计稿 §3.3 的虚拟主体设想兼容**，只是**换了一个载体**（用既有 `X-Roles` 而非新增 `X-Api-Scopes`）。

**若不成立会怎样（作用域隔离失效的后果）** —— 如实说明：

- `IotPermissionProvider.getPermissions` 对虚拟主体返回 `List.of()`（空） ⇒ **所有 `@SaCheckPermission` 端点一律 403** ⇒ **开放 API 完全不可用**（fail-closed，是**安全**的失败，但功能全废）。
- **更危险的另一种失败**（若实现时图省事「虚拟主体返回全部 iot 权限」）：**作用域隔离形同虚设** —— 任何一把 Key（哪怕只该看数据）都能**下发命令**、能读**所有**产品/告警 ⇒ **越权**。这是必须用 F-1 断言（§5）挡住的方向。
- ⇒ **所以「虚拟主体默认必须返回空权限、只能由 scopes 显式授予」是一条硬约束**，不能靠「中间件顺手放行」的自然倾向。

---

## 3. U-6：`ypbin-starter-api-doc` 在 iot 的可用性

### 3.1 一手参照（jar 内源码，`~/.m2`）

> 本报告**未**依赖设计稿转述的 metadata，而是**直接读取了 starter 3.6.0 的 `-sources.jar`**（`~/.m2/repository/cn/ypbin/ypbin-starter-api-doc/3.6.0/ypbin-starter-api-doc-3.6.0-sources.jar`，解压到 `/tmp` 只读查看）。

| 项 | 一手证据 | 结论 |
|---|---|---|
| 自动配置声明 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` = `cn.ypbin.starter.apidoc.autoconfigure.ApiDocAutoConfiguration` | ✓ |
| 生效条件 | `ApiDocAutoConfiguration.java:59-63`：`@ConditionalOnClass(OpenAPI.class)` + `@ConditionalOnProperty("ypbin.api-doc.enabled", havingValue="true", matchIfMissing=true)` | **`enabled` 默认 true** |
| **关键**：starter **不自带** SpringDoc | `@ConditionalOnClass(OpenAPI.class)`（`:60`）⇒ **必须由使用方提供 SpringDoc** | 见 3.2 |
| 传递依赖 | starter pom 含 `org.springdoc:springdoc-openapi-starter-webmvc-ui`（`META-INF/maven/.../pom.xml`） | **是 webmvc 变体** ⇒ 见 3.2 |
| 属性默认值 | `ApiDocProperties.java:35-71` | 见下表 |
| 环境后置处理器 | `ApiDocDefaultsEnvironmentPostProcessor.java`（`spring.factories` 注册） | 见下 |

**属性默认值**（`ApiDocProperties.java`，与设计稿 `:299` 的转述**一致**，已一手复核）：

| 属性 | 默认值 | 行号 |
|---|---|---|
| `enabled` | `true` | `:35` |
| `disableInProd` | **`true`** | `:38` |
| `pathsToMatch` | **`["/**"]`** | `:59` |
| `pathsToExclude` | `["/error", "/actuator/**"]` | `:62` |
| `securityHeaders` | `["Authorization", "X-Request-Id", "X-Tenant-Id", "X-Version"]` | `:71` |
| `defaultGroupEnabled` | `true` | `:53` |

**`disable-in-prod` 的真实语义（设计稿未展开，此处补全 —— 这是一个安全关键点）：**

`ApiDocDefaultsEnvironmentPostProcessor.java:41-57`：

```java
Boolean disableInProd = environment.getProperty("ypbin.api-doc.disable-in-prod", Boolean.class, true);
if (!disableInProd) return;                                    // 显式 false ⇒ 不做任何事
List<String> activeProfiles = List.of(environment.getActiveProfiles());
if (activeProfiles.stream().noneMatch(p -> "prod".equalsIgnoreCase(p))) return;   // ← 靠 profile！
defaults.put("springdoc.api-docs.enabled", "false");
defaults.put("springdoc.swagger-ui.enabled", "false");
environment.getPropertySources().addLast(...);                 // ← 最低优先级
```

⇒ **`disable-in-prod=true` 的「生产」判定 = 激活的 profile 里含 `prod`**（不区分大小写）。
⇒ **若生产环境没有激活 `prod` profile，`disable-in-prod` 完全不生效**，`/v3/api-docs` 与 swagger-ui **照常暴露**。这与 `management.endpoints` 那段注释（`ypbin-iot.yaml:202-214`）反映的安全意识水平相比，**是一个容易被忽略的缺口**。

### 3.2 iot 的匹配性（关键结论：**匹配**）

| 检查项 | 证据 | 结论 |
|---|---|---|
| iot 是否 MVC | `ypbin-service/ypbin-iot/pom.xml:102` 依赖 `ypbin-starter-web`；`ypbin-starter-web` 依赖 `spring-boot-starter-web`（starter pom 一手核实）；iot pom **无** webflux/reactive | ✅ **Spring MVC** |
| starter 的 SpringDoc 变体 | starter pom：`springdoc-openapi-starter-webmvc-ui` | ✅ **webmvc 变体 ⇒ 与 iot 匹配** |
| iot 当前是否已有 api-doc | `grep -rn "api-doc" --include=pom.xml .` **零命中**；`ypbin-service/ypbin-iot/pom.xml` 依赖列表无此 artifact | ❌ **确认未依赖**（与设计稿 `:305` 一致） |
| 是否已有 springdoc | `grep -rn "springdoc\|swagger" --include=pom.xml .` **零命中** | ❌ 全仓未引入 |
| 坐标版本管理 | 根 `pom.xml:37` `ypbin-starter.version=3.6.0`；`:48-53` 导入 `ypbin-starter-bom` | ✅ **版本由 BOM 管，无需写版本号** |

### 3.3 精确改动清单

**A. 依赖（1 处）**

| 文件 | 改动 | 依据 |
|---|---|---|
| `ypbin-service/ypbin-iot/pom.xml` | 在 `<dependencies>` 内加：`<dependency><groupId>cn.ypbin</groupId><artifactId>ypbin-starter-api-doc</artifactId></dependency>`（**不写 version**，由 BOM 管） | 根 `pom.xml:48-53`；starter pom 自带 webmvc-ui |

> **连带效果（必须知情）**：该依赖会**传递引入 SpringDoc 全家桶**（含 swagger-ui 静态资源）到 iot 运行时。

**B. 配置（`deploy/nacos/ypbin-iot.yaml`，新增 `ypbin.api-doc` 段）**

| 配置键 | 建议值 | 理由 |
|---|---|---|
| `ypbin.api-doc.paths-to-match` | `["/open-api/v1/**"]` | **安全必修**。默认 `["/**"]`（`ApiDocProperties.java:59`）会把**全部管理面端点**（含 `/internal/**` 之外的 `/devices`、`/products`、`/alerts` 等）写进对外规范 ⇒ **等于向第三方泄露后台接口全貌** |
| `ypbin.api-doc.default-group-enabled` | `true`（配上面的 match 即可） | 得到「对外文档 = 开放 API 子集」 |
| `ypbin.api-doc.security-headers` | `["X-Api-Key", "X-Timestamp", "X-Nonce", "X-Sign"]`（按最终签名方案定） | 覆盖默认的 `Authorization/X-Tenant-Id/...`（`ApiDocProperties.java:71`）—— 默认那套是**管理面**的头，对外文档里出现 `X-Tenant-Id` 会**误导第三方以为可以自选租户** |
| `ypbin.api-doc.title` / `version` | 如 `IoT 开放 API` / `v1` | 文档质量 |
| `ypbin.api-doc.disable-in-prod` | **显式**写出并确认生产 profile | 见下方「风险 R-2」 |

**C. 是否需要路由/网关放行 —— 结论：需要，且是安全必修**

- **路由**：**不需要新增**。既有 `Path=/iot/**` + `StripPrefix=1`（`ypbin-gateway.yaml:49-54`）会把 `/iot/v3/api-docs`、`/iot/swagger-ui/**` 转发到 iot。✅
- **网关鉴权白名单**：`exclude-paths` **没有**放行文档路径（`ypbin-gateway.yaml:94-110` 逐条核对：只有 `/auth/*`、`/system/open-api/**`、`/ai/share/**` 等）⇒ **未登录访问 `/iot/v3/api-docs` 会被网关拦下**。
  ⇒ **这是好事（默认安全）**：设计稿 Q5 若选「生产开放」，需**显式**加白名单（并承担「生产暴露接口全貌」的代价）；若选「生产关闭」，**保持现状即可**。
- **iot 服务内**：`ypbin-iot.yaml:31-32` 已关 `interceptor`，且 starter 有 `excludeApiDoc` 默认 `true`（`SecurityProperties.java:67-68`；`SaTokenWebConfigurer.java:82-84` 在 SpringDoc 存在时自动放行 `/v3/api-docs/**`、`/swagger-ui/**` 等，见 `:57-59`）
  ⇒ **iot 服务自身不会**因为注解鉴权拦住文档端点。**但注意：这层「自动放行」只在 iot 直接可达时才是决定性的** —— 经网关时网关才是第一道门。

**D. 与既有 `OpenApiDemoController` / 4001 菜单的协同**

| 对象 | 一手证据 | 结论 |
|---|---|---|
| `OpenApiDemoController` | `ypbin-service/ypbin-system/.../OpenApiDemoController.java`，`@RequestMapping("/open-api")`（设计稿 `:309` 转述，本次**未逐行复核**该文件） | **它是 system 的 AK/SK 签名示例，不是文档设施** —— 与 iot 的 api-doc **无冲突**（不同服务、不同前缀族） |
| 4001 api-doc 菜单 | `deploy/sql/002-data.sql:180`（路由 `/swagger-ui/index.html`，`IFrameView`）；`deploy/sql/migration/2026-09-26-iot-platform-module-menu.sql:57` 挂到 `3310` | 菜单路由是**相对路径** `/swagger-ui/index.html` |
| **协同风险** | 该菜单指向的是**哪个服务**的 swagger-ui？**前端若直连 iot 端口则不成立**（iot 端口非公网入口，见设计稿 §3.1 论证）；若经网关则是 `/iot/swagger-ui/index.html` | **⚠️ 需实跑确认**（§5 F-3）：菜单路由**未带服务短名**，且网关 `exclude-paths` 未放行 ⇒ **该菜单很可能指向 system 或需要另行配置**。**不要假定它接入 iot 后自动可用。** |

### 3.4 风险清单

| # | 风险 | 依据 | 严重度 |
|---|---|---|---|
| **R-1** | **`paths-to-match` 默认 `/**` ⇒ 把全部后台端点写进对外规范** | `ApiDocProperties.java:59` | **高（信息泄露）** —— 必须显式收窄 |
| **R-2** | **`disable-in-prod` 只在激活 `prod` profile 时生效** | `ApiDocDefaultsEnvironmentPostProcessor.java:45-52` | **高（生产裸奔）** —— 必须核实生产 profile |
| **R-3** | `security-headers` 默认含管理面头（`X-Tenant-Id` 等）⇒ 误导第三方 | `ApiDocProperties.java:71` | 中 |
| **R-4** | 4001 菜单是否指向 iot、是否可用 —— **未核实** | `002-data.sql:180`；网关白名单 | 中（体验，非安全） |
| **R-5** | 传递引入 swagger-ui 静态资源到 iot 运行时 | starter pom | 低（`webjars` 类资源） |
| **R-6** | 与 `management.endpoints` 同一服务内的暴露面叠加 | `ypbin-iot.yaml:202-214` 已有安全注释 | 低 |

---

## 4. 附加项（低成本）

### U-4：`X-Forwarded-For` 在网关侧**不可信** ⇒ **IP 白名单当前不成立**

**结论：不可信。当前形态下「按 XFF 做 IP 白名单」会被伪造绕过。**

一手证据（**全链路无一处处理 XFF**）：

| 检查 | 证据 | 结果 |
|---|---|---|
| 网关是否有转发头过滤器 | `find ypbin-gateway -name "*.java"` 仅 2 个文件（`GatewayApplication`、`SaTokenGatewayAuthProvider`）；`grep -rn "Forwarded\|forwarded\|remoteAddress\|getClientIp\|X-Real"` **零命中** | ❌ **无** |
| starter 网关是否有 | `ypbin-starter-cloud-gateway` 源码：`GatewayAuthGlobalFilter`/`HeaderSanitizeGlobalFilter`/`RequestIdGlobalFilter`，`grep -rn "Forwarded\|forwarded\|remoteAddress\|clientIp"` **零命中** | ❌ **无** |
| 清洗表是否含 XFF | `ypbin-gateway.yaml:76-86` 清洗表 = 5 个身份头 + `X-Gateway-Signed`，**不含 `X-Forwarded-For`** | ❌ **客户端可自带 XFF 直达下游** |
| iot 是否处理 XFF | `ypbin-iot.yaml` 无 `forward-headers`/`trusted-proxies` 配置 | ❌ **无** |
| 唯一相关配置 | `ypbin-system.yaml:74,80` 的 `trust-forwarded: true` —— **是 system 自己的限流/埋点用途，与 iot 开放 API 网关侧无关** | 不适用 |

⇒ **双重问题**：
1. **网关自身不计算真实客户端 IP**（无 `remoteAddress` 使用）；
2. **XFF 未被清洗** ⇒ 第三方可自带 `X-Forwarded-For: <任意>` 穿透到下游。

**对设计稿的影响**：`docs/OPENAPI-DESIGN.md:198` 已如实登记为未核实项，**核验后结论明确**：
- ✅ **「只取远端 IP（`exchange.getRequest().getRemoteAddress()`）更安全」—— 这条建议成立**，应作为首期实现口径。
- ❌ **「取 XFF 必须先把网关自身加入可信代理」—— 仓内当前没有任何可信代理机制可实现它**，首期**不要**走 XFF。
- ⇒ **设计稿 Q3「IP 白名单」的实际可选项收窄**：若网关前面还有一层（Nginx/LB），则 `remoteAddress` 会是**那层代理的地址** ⇒ 白名单会**全部误拒**。**这是 Q3 决策必须知道的约束。**

### U-5：转签复用同一把网关签名密钥 —— **建议为开放 API 引入独立密钥（但非首期阻断）**

**结论：风险面确实被放大；建议独立密钥，理由是「爆炸半径」而非「当前有洞」。**

一手证据：

| 项 | 证据 | 说明 |
|---|---|---|
| 同一把密钥贯穿全栈 | `ypbin-gateway.yaml:92`（签发）、`ypbin-iot.yaml:23`、`ypbin-system.yaml:43`、`ypbin-ai.yaml:15`、`ypbin-access.yaml:27`、`ypbin-common.yaml:82` 全部 `${GATEWAY_SIGN_TOKEN}` | **一把密钥 = 全平台身份可信根** |
| 值从哪来 | `deploy/.env.example:56` `GATEWAY_SIGN_TOKEN=REPLACE_WITH_RANDOM_GATEWAY_SIGN_TOKEN`；`install.sh` 导入时替换 | 真值不入库 ✓（符合凭据红线） |
| 校验是常量时间比较？ | `IdentityHeaderFilter.java:168-175`：`trustedSourceToken.equals(actual.trim())` | **⚠️ 这里是 `String.equals`，不是常量时间比较** |
| 但已有纵深防御 | 网关清洗 `X-Gateway-Signed`（`ypbin-gateway.yaml:86`）；`HeaderSanitizeGlobalFilter` order = `HIGHEST_PRECEDENCE+1`（`:52-54`），签发在 +2 ⇒ **顺序正确**（`:68-69` 注释已说明） | 客户端无法预置/探测该头 ✓ |

**风险分析（如实、不过度）：**

- **当前没有可利用的洞**：`X-Gateway-Signed` 已被清洗 + 密钥不在客户端 ⇒ 伪造不出来。
- **风险的本质是爆炸半径**：密钥一旦泄露（配置泄漏、日志、Nacos 泄露），攻击者可**直接伪造任意租户的任意身份**。开放 API **新增了一条「第三方 Key → 转签为租户身份」的路径** ⇒ 但注意：**这条路径并不需要密钥**（网关自己用密钥签发，第三方拿的是 API Key）。⇒ **密钥泄露的风险面并没有因开放 API 而「新增」，而是「同源放大」**：原本只有管理面身份，现在开放面的身份也走同一信任根，**轮换一次要影响所有服务**。
- **`String.equals` 非常量时间**：在「值不可猜、且头已被清洗」的前提下**不构成实际漏洞**（时序攻击需要能反复探测，而客户端根本递不进这个头）。**但**：若将来为开放 API 新增任何**可被客户端自带**的签名头（如 `X-Api-Scopes`，方案 A），**就必须**改成常量时间比较（仓内既有取向：`SignChecker`、`InternalTokenGuardInterceptor` 均已如此，设计稿 `:181` 已引）。

**建议（结论）：**

| 建议 | 理由 | 优先级 |
|---|---|---|
| **首期不阻断**：继续复用同一把 `GATEWAY_SIGN_TOKEN` | 风险面确为「同源放大」而非「新增漏洞」；独立密钥需网关支持双密钥签发（当前 `GatewayAuthProvider` SPI 不区分来源，`SaTokenGatewayAuthProvider.java:92-108` 只有一套 `buildTrustedHeaders`） | — |
| **中期建议独立密钥**：为开放 API 引入 `OPENAPI_SIGN_TOKEN`，双密钥并存、按路径选择 | ① 爆炸半径隔离（开放面密钥泄露 ≠ 管理面沦陷）；② 轮换解耦（第三方 Key 轮换不必动全平台密钥） | 中 |
| **若采纳方案 A（新增可自带头）⇒ 必须先做常量时间比较** | `IdentityHeaderFilter.java:174` 现为非常量时间 | 高（条件触发） |
| **实施设计里补一条**：`GATEWAY_SIGN_TOKEN` 的轮换流程须覆盖 iot 开放 API | 现状 `tools/` 有 Nacos 轮换工具（`tools/rotate-*`），需确认覆盖新路径 | 中 |

> **对设计稿 `:509`（U-5）的修正**：原文写「密钥泄露后即可伪造任意租户身份（含开放 API 主体）」——**成立**。但「与既有 SF-6b 风险同源但面被放大（多了『第三方 Key → 租户身份』这条路径）」的表述**略有误导**：第三方 Key **不能**用来伪造身份（他们不知道签名密钥），放大的是**信任根共用**而非**新增攻击路径**。建议按上述口径改写，避免实施者误判优先级。

---

## 5. 未核实项：静态核验到此为止，哪些必须实跑

> 以下为**静态代码无法判定**的事项。**不要**把本报告 §1–§4 的结论当成「已实跑验证」。

| # | 必须实跑的事项 | 为什么静态不可判 | 建议验证方式 | 阻断谁 |
|---|---|---|---|---|
| **F-1** | **虚拟主体 → scopes 真的生效**（方案 B 的落地验证） | **已实测通过（2026-10-01，F-1 复跑 + dev 端到端，见 TASK-BOARD）** -- 本行为实现后的复验记录 | 涉及 Spring 装配顺序、`SysCache` 行为、Sa-Token 内部匹配 | 起最小原型：注入虚拟 `X-User-Id` + `X-Roles=iot:series:get`，**断言**该 Key 调 `/series` **通过**、调 `/devices`（无该 scope）**403**、调 `/commands` **403** | **O-1…O-7 全部**（作用域隔离的前提） |
| **F-2** | **`@SaCheckPermission` 在开放 API 链路真的执行**（含 `loginType` 取值） | **已实测通过**（同 F-1 批次；无权限虚拟主体 403） | iot 关了 `interceptor`，只靠 `annotation-check` 默认 true | 用一个**无任何权限**的虚拟主体调任一带注解端点，**必须 403** | **O-1…O-7 全部** |
| **F-3** | **`ypbin-starter-api-doc` 加依赖后 iot 真能起、`/v3/api-docs` 真能出** | 未加依赖（本任务禁改 pom）；依赖冲突/装配条件需运行时才暴露 | 加依赖后实跑 `/iot/v3/api-docs` 与 `/iot/swagger-ui/index.html`；并核对 **4001 菜单**指向是否正确（§3.3 D） | **U-6** |
| **F-4** | **生产 profile 是否含 `prod`**（决定 `disable-in-prod` 是否生效） | 静态只看得到代码逻辑，看不到生产启动参数 | 核对生产启动命令/Nacos 的 `spring.profiles.active` | **U-6 R-2** |
| **F-5** | **O-3 的 IoTDB 是否真连得通**（决定 `/series` 是否可用 vs 报「未启用」） | `timeseries.enabled: true`（`ypbin-iot.yaml:160`）只说明配了，不说明存储可达 | 实跑一次 `GET /devices/{id}/series` | **O-3 可用性**（非安全性） |
| **F-6** | **O-7 改动后的事务边界与幂等**（C1/C2） | 事务/超时行为必须在真库上验证 | 造 EMQX 慢响应，观察 DB 连接占用；并发发同一幂等键，断言只下发一次 | **O-7** |
| **F-7** | **限流/配额真的生效**（C3） | 限流是**新增能力**，仓内当前不存在 | 按设计稿 A-5 / A-10 用例 | **O-7（也影响全局限流）** |
| **F-8** | **网关前置拓扑**（有无 Nginx/LB ⇒ `remoteAddress` 是不是真客户端） | 静态看不到部署拓扑 | 在生产网关上看一次 `remoteAddress` 实际值 | **U-4 / Q3 IP 白名单** |
| **F-9** | **`OpenApiDemoController` 的逐行复核** | 本次未打开该文件（设计稿 `:309` 转述） | 读 `ypbin-service/ypbin-system/.../OpenApiDemoController.java` | **U-6 协同**（低） |
| **F-10** | **生产实际 Nacos 配置与仓内样例是否漂移** | 本任务未登录生产机（设计稿 U-8 已登记） | 另做只读核实 | 全部结论的**生产适用性** |

> **一句话**：本报告核验的是**「代码与配置是否支持设计稿的假设」**（静态可判）；**不代表**「功能已可用」。**F-1、F-2 是开放任何端点前就必须跑通的前置**。

---

## 6. 实施顺序建议（基于本次核验结论）

**可以立刻做（静态已放行，无阻断）**

1. **O-1…O-6 门面包 + 路由**（`cn.ypbin.admin.iot.openapi`）：6 个只读端点静态均判定**真无副作用**。
2. **U-2 方案 B 定稿**：把设计稿 §2.3 的 (a) 方案替换为 B2（网关复用 `X-Roles` 注入 + iot 虚拟 ID 段回落），**然后立刻做 F-1/F-2 原型**（这是唯一阻断全部端点的事项）。
3. **U-6 依赖 + 配置**：加 `ypbin-starter-api-doc`；**同步**配好 `paths-to-match: ["/open-api/v1/**"]` 与 `security-headers`（**别用默认值**）。
4. **修正设计稿作用域表**：新增 `iot:product:tsl-export` 一行；明确排除 `/alerts/active-counts`。

**必须先跑起来验证才能做**

5. **F-1 + F-2**：虚拟主体 + scopes 的端到端断言（**做任何端点开放之前**）。
6. **F-3 + F-4**：api-doc 实跑 + 生产 profile 确认。
7. **O-7 的 C1/C2/C3/C4**：事务外置、客户端幂等键、限流落地、默认不授予 —— **全部完成并跑通 A-5/A-11 后**，O-7 才开放。
8. **U-4 / Q3**：确认网关前置拓扑后，再决定 IP 白名单是否可行（否则首期按「不做」处理）。

**建议的批次划分**

| 批次 | 内容 | 前置 |
|---|---|---|
| **第 1 批** | O-1…O-6 只读 + 鉴权（`/whoami`）+ 限流 + 文档 | **F-1、F-2** 通过 |
| **第 2 批** | **O-7 命令下发** | 第 1 批上线 **且** C1/C2/C3/C4 全部落地（F-6/F-7 通过） |

---

## 7. 对 `docs/OPENAPI-DESIGN.md` 的具体修改建议（本任务不改该文件，仅登记）

| 位置 | 现状 | 建议改为 | 依据 |
|---|---|---|---|
| `:150-153`、`:506`（U-2） | 把「改 `IotPermissionProvider` 或新增前置 Provider」当作**必需**前置，并列为 L2 高风险改动 | 改为**推荐方案 B2**（复用 `X-Roles` + 虚拟 ID 段回落），说明**可不动既有鉴权链**、**不引入新头**、**无需改 `header-sanitize`** | §2.3/§2.4 |
| `:138-146`（作用域表） | O-5 全部映射到 `iot:product:list` | **新增** `iot:product:tsl-export` 行（`GET /tsl`） | `IotThingModelController.java:319-320` |
| `:72`（O-4 清单） | 未提 `/alerts/active-counts` | 明确**排除**该端点（尽管权限码相同） | `IotAlertController.java:108-112` |
| `:90`、`:505`（U-1） | 「未逐一实调确认是否有隐藏副作用」 | **改为**：已静态逐方法核验，O-1…O-6 **真无副作用**；**O-7 有 4 个缺口**（引入 §1.3 的 C1–C4） | §1.1 |
| `:509`（U-5） | 「面被放大（多了『第三方 Key → 租户身份』这条路径）」 | 改为：**信任根共用导致爆炸半径放大**；第三方 Key **不能**伪造身份（不知签名密钥）；建议中期独立密钥 | §4 U-5 |
| `:198`（IP 白名单） | 「取 XFF 必须先把网关自身加入可信代理」 | 改为：**仓内无任何可信代理机制**；首期**只用 `remoteAddress`**；且须先确认网关前置拓扑（F-8） | §4 U-4 |
| `:302`（`disable-in-prod`） | 「默认关闭生产暴露 —— 需显式评估」 | 补全：**靠 `prod` profile 判定**（`ApiDocDefaultsEnvironmentPostProcessor.java:45-52`）；无 `prod` profile 则**不生效** | §3.1 |
| `:299`（`security-headers`） | 「可直接把 API Key 头写进文档的 security scheme」 | 补全：**默认值是管理面头**（含 `X-Tenant-Id`），须**显式覆盖** | `ApiDocProperties.java:71` |

---

## 8. 本次核验的工作边界（如实声明）

- ✅ **只读**完成：全部结论基于仓库内一手源码、`deploy/nacos/*.yaml`、`~/.m2` 内 starter 的 `-sources.jar`。
- ✅ **未执行**：Maven、构建、测试、部署、commit、任何 SQL。
- ✅ **未修改**：`docs/OPENAPI-DESIGN.md`、`docs/TASK-BOARD.md`、任何 pom、SQL、代码。
- ✅ **未触碰**工作树里另一代理的未提交改动（`DeviceImportController` 等）。
- ✅ **唯一写入**：本文件 `docs/OPENAPI-PREFLIGHT.md`。
- ⚠️ **未联网**：本任务全部结论均可用仓内一手证据自证，**不涉及会变化的对外事实**，故未做联网检索（符合 R1：本机代码/文档属一手证据，优先于联网）。
- ⚠️ **§3.3 D 对 `OpenApiDemoController` 的描述引自设计稿转述**，本次**未逐行打开该文件**（登记为 F-9）。
---

## 9. F-1 / F-2 **实跑结果**（2026-09-30，生产实测）

> 本文 §5 把 F-1/F-2 列为「**做任何端点开放之前**」的阻断项。本节是它们的**实跑结论**（不再是静态推断）。

### 9.1 方法（可复现）

- **链路位置**：直连 iot `127.0.0.1:18084`，并**自行供给**签名头 `X-Gateway-Signed`
  （取自生产 `/opt/ypbin/ypbin-iot/deploy/.env` 的 `GATEWAY_SIGN_TOKEN`，**只报长度 len=64、从不打印值**）。
  理由：F-1/F-2 要验的是「**网关注入身份之后**的那条链」（`IdentityHeaderFilter` → 租户插件 →
  `@SaCheckPermission`），网关侧的 `OpenApiKeyAuthFilter` 尚未实现，故不经过它。
- **被验服务**：生产 `ypbin-iot`（`Up 14 hours`，制品含 starter 3.6.0）。
- **只读性**：全部为 `GET`，未写任何数据、未改任何配置、未部署。

### 9.2 F-2：**注解鉴权真的执行** → ✅ **通过**

| 场景 | `X-User-Id` | `X-Roles` | 端点（所需权限） | 结果 | 判读 |
|---|---|---|---|---|---|
| 虚拟主体、无任何权限 | `-9999` | — | `GET /devices`（`iot:device:list`） | `code:403` | **被拦** ✓ |
| 同上 | `-9999` | — | `GET /products`（`iot:product:list`） | `code:403` | **被拦** ✓ |
| 同上 | `-9999` | — | `GET /devices/1/commands`（`iot:debug:get`） | `code:403` | **被拦** ✓ |
| **对照**：真实用户 | `1` | — | `GET /devices` | `code:200` | **放行** ✓（证明不是"一律拒绝"） |
| **对照**：真实用户 | `1` | — | `GET /products` | `code:200` | 放行 ✓ |
| **对照**：缺签名 | `1` | — | `GET /devices` | `code:403` | 签名校验**仍在生效**（本测试未绕过安全） |

**结论**：`@SaCheckPermission` 在生产链路**确实执行**（不是被静默跳过的空转），
且「无权限 ⇒ 403」「有权限 ⇒ 200」两个方向都成立 ⇒ **F-2 通过** ✓。

> ⚠️ **一处需要解释的观察（已查清，非拦截缺口）**：`GET /devices/1/messages` 对**虚拟主体 404**，
> 但同一路径对**真实用户也是 404**、不存在的路径（`/no-such-path-xyz`）**同样 404** ⇒
> 该 404 是「**路由不存在**」（Spring 无 handler，被全局处理器包进 `R` 信封），
> 而**不是**业务 404。根因：`/messages` 是**看板 #8 新增**的端点，**生产尚未部署**
> ⇒ 与鉴权无关。**记录此点是因为"虚拟主体 404、真实用户也 404"是唯一能把两者区分开的证据**，
> 单看虚拟主体的结果会误判成"注解漏拦"。

### 9.3 F-1：**虚拟主体 → scopes 真的生效** → ❌ **不通过**

同一个「需 `iot:device:list`」的端点 `GET /devices`，逐一注入各候选 scope 位：

| 场景 | 结果 | 判读 |
|---|---|---|
| 虚拟主体 `-9999`，**不带** scope | `code:403` | 基线（预期拒绝） |
| 虚拟主体 `-9999` + `X-Roles: iot:device:list` | `code:403` | **scope 未生效** ✗ |
| 虚拟主体 `-9999` + `X-Api-Scopes: iot:device:list` | `code:403` | **该注入位未被读取** ✗ |
| 虚拟主体 `-9999` + 两个头同时带 | `code:403` | 仍不生效 ✗ |
| 虚拟主体（**正数**高位段）`900000001` + `X-Roles` | `code:403` | 与符号无关，**同样不生效** ✗ |
| 真实用户 `1` + `X-Roles: bogus:code`（**乱码**） | `code:200` | 🔴 **决定性**：权限只来自库，`X-Roles` **完全被忽略** |

**结论**：**scopes → 权限的映射机制当前根本不存在**。权限解析是
`IotPermissionProvider.getPermissions` → `SysCache.getUserPermissions(userId)`，
**只认"库里的真实用户"**；虚拟主体不在用户表 ⇒ 权限恒为空 ⇒ 无论注入哪个 scope 位都是 403。
这与设计稿 §2.3 的「⚠️ 实施前置」**完全一致**（设计当时就指出该 Provider 不认识"虚拟主体 → scopes"）。

**F-1 因此未通过** ⇒ 按交接文档「**F-1/F-2 … 不过就停 ✗**」的要求，
**本批未开放任何端点、未写任何开放 API 业务代码**。

### 9.4 下一步（唯一阻断项的最小修复路径）

| # | 动作 | 说明 |
|---|---|---|
| 1 | 定稿 **B2 方案**（网关复用 `X-Roles` 注入 scopes + iot **虚拟 ID 段回落**） | 取代设计 §2.3 的 (a) 候选（`X-Api-Scopes` 实测亦未被读取，但 B2 复用既有头、少引入一个新头，**且新头还需并入网关清洗表**） |
| 2 | 实现「虚拟主体 → scopes」的权限解析（**L2 非平凡安全链改动**） | ⚠️ 设计 §2.3 已注明**须单独立项评审并补测试**；**本批刻意未实施**（避免绕开该评审要求） |
| 3 | 虚拟 ID 段选取须**避开** Sa-Token 哨兵值 `-3/-4/-5`（`IdentityStpLogic` 类注释已提示） | 实测已确认正/负虚拟 ID 当前行为一致，故选取自由度在哨兵值之外 |
| 4 | 复跑 **F-1**（本节的表**即复跑脚本的期望矩阵**）→ 通过后再进入第 1 批（O-1…O-6） | F-1 通过前**不得**开放端点 |

### 9.5 本次实跑的边界（如实声明）

- ✅ **已做**：生产只读探测（`GET` × 十余次）+ 一处签名头供给。
- ❌ **未做**：未部署、未改配置、未写数据、未建表、未实现任何开放 API 代码。
- ❌ **未验**：`OpenApiKeyAuthFilter`（尚未实现）、限流/配额、Key 哈希与吊销、API 文档（F-3）、
  生产 profile（F-4）。
- ⚠️ **本次未打印任何密钥值**（仅长度 len=64），也未把密钥写入任何文件或输出。
