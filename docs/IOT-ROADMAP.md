# ypbin-iot 增量路线（admin 基座 + IoT 业务）

> **⚠️ 方向变更（2026-09-20 用户拍板）**：以后**不再按「最小可跑通增量」推进，一律以最完善的功能与设计展开**。
> 平台级**完整设计总纲**见 [`IOT-PLATFORM-DESIGN.md`](IOT-PLATFORM-DESIGN.md)（物模型对齐 IoTDA 结构：产品→服务→属性/命令+事件扩展、
> 整个平台一次性完整设计，含里程碑 M-1~M-7）。本文件的增量编号保留作历史与推进参照，
> **当前/后续工作以设计文档 §14 里程碑为准**：先 M-1 物模型域（与 3b-2 协议栈并行），再 M-2 数据面……

> 本仓 = `ypbin-admin` 的 fork。**基座只更新基础功能**，IoT 业务按下面的增量逐个加。
> 每个增量都必须遵守 `SYNC.md` 的纪律：只改白名单内的 admin 文件、其余一律新增文件/新模块、
> 跑门禁、**外委复核**、SQL 双写（`006/007` 与 `migration/*`）并跑 `tools/check-iot-sql-equivalence.sh`。

## ✅ 增量 1：最小可跑通的业务域（已完成，`c49302f`）

- `ypbin-service-api/ypbin-iot-api`：实体 `IotDevice`（`@TableName("iot_device")` extends `TenantBaseEntity`）
  + 查询/请求/响应模型。
- `ypbin-service/ypbin-iot`：第 3 个业务域、**独立部署单元（18084）**、`BaseServiceImpl` + Controller
  （权限码 `iot:device:{list,create,delete}`）、`IotPermissionProvider`（**不实现它端点必然 403**）、
  `repackage`（**不声明产物就不是可执行 jar**）。
- 部署：`Dockerfile`、`docker-compose.yml` 的 `ypbin-iot` 块、`deploy/nacos/ypbin-iot.yaml`、
  `install.sh` 的服务与配置清单、网关 `Path=/iot/**` + `StripPrefix=1`。
- 纪律与门禁：`SYNC.md` + `Sync Whitelist`（白名单 + SQL 等价）+ `Upstream Sync Check`（干跑合并）。
- 复核：两轮（首轮 PASS(有条件) → P0/P1 修复 → 限定复验 → 末轮确认），证据含「权限告警 1→0」的负向对照、
  `java -jar` 实跑、白名单门禁变异会红、SQL 等价脚本 green→red→green。

## ✅ 增量 2：租户节点归属与租约（已完成）

- 表 `tenant_node_assignment`（平台表，租户插件按 `ignore-tables` 忽略）+ 契约 DTO（14 个）+
  `LeaseEpochRules` 纯判据；服务端状态机与内部端点 `/internal/lease/**`（6 个）+ 失效扫描 + 启动自检 + 指标。
- **并发靠数据库 CAS**（单条原子 UPDATE / `INSERT IGNORE` / 批量续期），不依赖进程内锁 ⇒ 多副本只有一个赢家；
  **无循环内 DB 调用**（架构门禁 `loopsMustNotCallDbOrRpc` 会拦）。
- 语义与取舍写在 **`docs/LEASE.md`**（含「epoch 每次归属变更都推进」——它解决了旧栈 ADR-0001 的悬空项；
  以及「本地 epoch 落后不再吊销、改为回执带服务端 epoch」）。
- 测试：`LeaseEpochRulesTest`（纯判据 6 条）+ `NacosTenantIgnoreConfigTest`（配置门禁 4 条）+ `LeaseServiceImplTest`（CAS 守卫/分支/语义 21 条）；
  单测还抓出过一个真实缺陷：`maxTenants=null`（默认「不限」）曾因 `ConcurrentHashMap` 不接受 null 而 NPE。
- 待办（M0b）：节点注册表落库（同时让**容量**变成数据库级原子：节点行 + `SELECT ... FOR UPDATE`）、
  可分配租户改读台账表、**真库并发用例**（多副本抢同一租户，旧栈用 200 轮抓出过双主）、
  到期时间改用数据库时钟（防跨副本时钟偏移）、`ILeaseClient`（随增量 3）。
- **跨仓待办（需在 admin 仓做，本仓靠同步获得）**：网关拒绝 `/internal/**` 路径 + 把 `X-Internal-Token`
  加进头部剥离名单（当前 `POST /iot/internal/lease/release` 会被转发进服务，见 `docs/LEASE.md` 的已知缺口）。

## 增量 2 原始设计说明（保留供追溯）

**目标**：把「哪个节点采哪个租户」变成可查、可续约、可失效、可接管的服务端权威。


- 表：`tenant_node_assignment`（tenant_id / access_node / epoch / lease_expire_at / state）
  + 必要的索引；SQL 双写（006/007 风格 + `migration/`）。
- `ypbin-iot-api`：租约契约 DTO（register / acquire / renew / release / assignment / epochs）。
- `ypbin-iot`：租约状态机（注册、领取、续约、释放、失效扫描、epoch 推进）、内部端点
  （`/internal/lease/**`，走 admin 既有入站可信校验）、启动自检、Micrometer 指标。
- 原子性与互斥：**必须**用事务/行锁保证「同一租户同一时刻只有一个 owner」——
  旧仓在这里踩过「单副本并发也能双主」的坑（60 轮出现 12 次双主），修法是公平锁 + 原子 compute。
- 测试：状态机单测 + **并发双主用例**（这是关键回归）+ epoch 规则。
- 参考实现（可直接读、按 admin 的 DB/租户体系重写）：旧仓 `ypbin-iot-cloud` 的
  `ypbin-iot-cloud-core/src/main/java/cn/ypbin/iotcloud/core/lease/*` 与其 `docs/adr/0001-*.md`（P3 四轮复核结论）。

## 增量 3：access 接入单元（有状态、独立部署）

- 新 app（第 6 个部署单元，端口建议 18086）：`ypbin-service/ypbin-access`（+ `-api`）。
- 依赖 **`ypbin-iot-starter`**（协议层，独立仓，通过 `ypbin-iot-bom` 引入；本仓 CI 需先取源安装并**锁 SHA**）。
- 三个宿主 SPI（`DeviceRegistry` / `ConnectionSpecProvider` / `DataSink`）+ `IotTenantLinkManager`
  （租约归属 → **真建链/真断链**）+ self-fencing 三路径（revoked / nodeFenced / 本地过期）+ 周期重领。
- 三个必守耦合（读 iot-starter 源码得到，写死也要遵守）：`ypbin.iot.devices.enabled` 必须为 true、
  变更 revision 严格递增、就绪期 `loadAll()` 与 `ApplicationRunner` 的先后。
- 参考实现：旧仓 `ypbin-iot-cloud` 的 `ypbin-iot-cloud-access/*`（P4b 三轮复核，含**真实 socket** e2e：
  会话 0→1→0、OS 层 EOF、跨租户不误伤）。

## 增量 4：前端（admin-ui fork，同模式）

- 新建 `ypbin-admin-ui` 的 fork（`upstream` 指向 admin-ui），按同样的白名单纪律加 IoT 页面。
- 页面：设备台账（列表/新增/删除）、租约与接管状态；菜单/权限码已由增量 1 的 SQL 提供
  （`page.iot.device.title` / `/iot/devices` / `iot:device:*`）。

## 增量 5：数据上行与存储

- `DataSink` 从「只计数」换成「上报到 `ypbin-iot`（含租户上下文）」；设备时间序列/最新值存储选型。
- 参考旧仓 README/spec 的取舍记录（EMQX、批量写入、背压、丢弃计数）。

## 收尾

- IoT 逻辑搬完后归档 `ypbin-iot-cloud`（保留历史与 ADR/契约文档），并把其 `docs/` 里仍有效的部分
  迁到本仓 `docs/`。

## 增量 3b：部署接线（已完成 3b-1）与协议栈（3b-2）

### ✅ 3b-1 部署接线（已完成）
- 新增 `deploy/nacos/ypbin-access.yaml`（节点标识/容量/续约与重领节奏/内部凭证；**不引入
  `ypbin-common.yaml`**——它会带 `spring.datasource.*` 把 DataSource 自动配置拖起来，而 access 无 JDBC 驱动）、
  `ypbin-service/ypbin-access/Dockerfile`。
- `deploy/docker-compose.yml` 新增 `ypbin-access` 服务（18086、`INTERNAL_TOKEN`、**逐副本唯一的
  `ACCESS_NODE_ID`**、依赖 nacos + ypbin-iot）；`deploy/install.sh` 的服务清单与 Nacos 配置清单各加一项；
  `deploy/.env.example` 加 `ACCESS_NODE_ID`。
- **验收（真机）**：把该配置文件通过 `--spring.config.additional-location` 直接喂给产物启动 →
  `access 启动自检通过：node=e2e-file-1 renewIntervalMs=10000 acquireIntervalMs=15000` → `Started AccessApplication`
  → `租户领取完成：node=e2e-file-1 持有租户=[22, 11]` → 服务端归属 `ACTIVE/e2e-file-1`（证明 node-id 来自配置文件）。

### ⚠️ 3b 的两项待决（需要决策，未擅自改）
1. **`install.sh` 硬编码指向上游仓**：`SCRIPT_URL`/`GITEE_SCRIPT_URL` 默认是 `wenbin-wb/ypbin-admin`，
   且 `NACOS_DIR="$ROOT/ypbin-admin/deploy/nacos"` 也写死 `ypbin-admin` 目录名 ⇒ **fork 的一键部署脚本目前部署的是 upstream，
   不是本仓**。需要决定：本仓部署目录是继续叫 `ypbin-admin`（改动最小、与现有运维脚本兼容），还是改名 `ypbin-iot`
   （语义正确、但所有路径/文档/运维习惯都要跟着改）。
2. **协议栈接入（3b-2）**：`ypbin-iot-bom` + 协议模块 + 三个宿主 SPI + 真建链/断链。按教训三十二，
   接入前必须**读 iot-starter 的装配源码**（不看 README），且它的 SNAPSHOT 未发布 ⇒ CI 必须
   **显式取源并锁定 SHA**，源码树不能落在本仓工作目录内（否则「仓内每个 pom 都必须有归属」的门禁会转红）。

## 增量 3b-2 协议栈接入清单（源码级侦察结论，2026-09-20）

> 侦察对象：`ypbin-iot-starter`（**读源码，不读 README**——教训三十二）。以下均为该仓一手事实（文件+行）。

### 一、坐标与模块
- BOM：`ypbin-iot-bom`（版本走 `${revision}`，**当前未发布到 Central**）；核心 `ypbin-iot-core`、
  运行时 `ypbin-iot-runtime`、自动配置 `ypbin-iot-spring-boot-starter`、
  协议模块 `ypbin-iot-protocol-{tcp,mqtt,modbus,opcua}`、`ypbin-iot-test` / `ypbin-iot-integration-tests`。
- 本仓接入方式：新增 `ypbin-service/ypbin-access` 依赖（协议先上 **tcp**）+ 在 `ypbin-service-api/pom.xml`
  或一处 dependencyManagement 里 import 该 BOM（**注意 `ypbin-service-api/pom.xml` 在白名单内**）。

### 二、access 必须实现的三个宿主 SPI（`ypbin-iot-core/src/main/java/cn/ypbin/iot/core/spi/`）
| SPI | 方法（一手签名） | 在 access 侧的落地 |
|---|---|---|
| `DeviceRegistry` | `List<DeviceSpec> loadAll()`；`default ValidationResult validate(DeviceSpec)`；`void addChangeListener(Consumer<DeviceChange>)` | `loadAll()` **必须只返回本节点当前租约内的设备**（按租约 held 集合过滤）；`addChangeListener` 挂到 3a 的租约/对账事件 |
| `ConnectionSpecProvider` | `Optional<ConnectionSpec> find(String connectionId)` | 从 `iot_device` 台账（经 iot 服务）取连接参数；**返回 `Optional.empty()` 时必须让框架走「跳过并告警」而不是抛** |
| `DataSink` | `String name()`；`void write(DataBatch)`；`default void close()` | 上行落库/转发（增量 5 数据面）；3b-2 先用日志/内存实现占位 |

### 三、三个「会静默失效」的耦合（必须配门禁或断言，否则 3b-2 会假成功）
1. **`DeviceChange.revision` 单调性**（`DeviceChange.java:24-34`）：框架记录每设备最后已应用的 revision，
   **收到 ≤ 它的变更直接丢弃**。⇒ 宿主的变更事件必须**真正递增** revision；不递增 = 静默忽略（无异常、无日志）。
   落地：access 侧发变更时必须带单调递增 revision，并加一条「同 revision 重复投递被丢弃」的回归用例。
2. **`ypbin.iot.enabled` 与 `ypbin.iot.devices.enabled` 是两个开关**：`IotAutoConfiguration` 由
   `@ConditionalOnProperty(prefix=IotProperties.PREFIX, name="enabled", …)`（`:64`）控制；各协议模块另有
   自己的 `prefix.enabled`（如 tcp 模块的 `TcpAutoConfiguration`）。**关掉 devices 只关设备引导**（变更通道仍接线），
   而关掉 `ypbin.iot.enabled` 会让整条链路不装配 ⇒ 两种「没数据」现象要能区分。
3. **就绪期顺序**：`loadAll()` 与框架的 `ApplicationRunner` 的先后决定首轮绑定看到的状态；
   access 的**启动握手（注册/领取）必须在框架 loadAll 之前完成**，否则首轮会按「无租户」引导设备。

### 四、CI 与依赖（教训三十二的两条硬要求）
- **SNAPSHOT 未发布 ⇒ CI 必须显式取源并锁定 SHA**（跟随分支 = 同代码不同时间结果不同）；
- **iot-starter 源码树绝不能落在本仓工作目录内**（否则「仓内每个 pom 都必须有归属」门禁转红——旧栈 CI 实测失败）；
- 依赖接入后需重跑：`ypbin-architecture-tests`（模块归属/发布规则）+ 白名单门禁（`pom.xml` 变更需同步 `SYNC.md`）。

### 五、替换缝（3a 已备好，3b-2 只需新增自动配置）
3a 的 `LoggingTenantLinkManager` 已去掉 `@Component`，由 `AccessLeaseConfiguration`（`@AutoConfiguration`
+ `@Bean @ConditionalOnMissingBean`）装配，并有源码门禁守着（四处变异全咬）。⇒ 3b-2 提供真实现时
**只需新增一个 `@AutoConfiguration` + `@Bean`**，判定逻辑（`AccessLeaseManager`）一行不动。
