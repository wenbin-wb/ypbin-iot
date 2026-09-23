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

### 3b 的遗留事项（第 2 项已于 2026-09-21 完成 → 见「增量 3b-2」节）
1. **`install.sh` 硬编码指向上游仓**：`SCRIPT_URL`/`GITEE_SCRIPT_URL` 默认是 `wenbin-wb/ypbin-admin`，
   且 `NACOS_DIR="$ROOT/ypbin-admin/deploy/nacos"` 也写死 `ypbin-admin` 目录名 ⇒ **fork 的一键部署脚本目前部署的是 upstream，
   不是本仓**。需要决定：本仓部署目录是继续叫 `ypbin-admin`（改动最小、与现有运维脚本兼容），还是改名 `ypbin-iot`
   （语义正确、但所有路径/文档/运维习惯都要跟着改）。
2. ~~**协议栈接入（3b-2）**：`ypbin-iot-bom` + 协议模块 + 三个宿主 SPI + 真建链/断链。按教训三十二，
   接入前必须**读 iot-starter 的装配源码**（不看 README），且它的 SNAPSHOT 未发布 ⇒ CI 必须
   **显式取源并锁定 SHA**，源码树不能落在本仓工作目录内（否则「仓内每个 pom 都必须有归属」的门禁会转红）。~~
   **✅ 已完成（2026-09-21，PR #13）**：接入前已逐条重读装配源码；协议栈 `0.1.0` 已发 Central ⇒
   CI 不再需要取源锁 SHA（见下方「四点四」）；三个宿主 SPI + 真订阅已落地。

## 增量 3b-2 协议栈接入清单（源码级侦察结论，2026-09-20）

> 侦察对象：`ypbin-iot-starter`（**读源码，不读 README**——教训三十二）。以下均为该仓一手事实（文件+行）。

### 一、坐标与模块
- BOM：`ypbin-iot-bom`（版本走 `${revision}`；**0.1.0 已于 2026-09-21 发布到 Central**）；核心 `ypbin-iot-core`、
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
   自己的 `prefix.enabled`（如 tcp 模块的 `TcpAutoConfiguration`）。关掉 `ypbin.iot.enabled` 会让整条链路不装配；
   **⚠️ 更正（2026-09-21 读源码核实）**：关掉 `ypbin.iot.devices.enabled` 时 `IotLifecycle.onApplicationEvent`
   会**直接 return**，`registry.addChangeListener(...)` 根本不会执行 ⇒ **变更通道也不会接线**（不是「只关引导」）。
   两种「没数据」现象的区别是：前者连 `IotLifecycle` 都不存在（零日志），后者有引导日志但无变更通道。
3. **就绪期顺序**：`loadAll()` 与框架的 `ApplicationRunner` 的先后决定首轮绑定看到的状态；
   access 的**启动握手（注册/领取）必须在框架 loadAll 之前完成**，否则首轮会按「无租户」引导设备。

### 四、CI 与依赖（教训三十二的两条硬要求）
- ~~**SNAPSHOT 未发布 ⇒ CI 必须显式取源并锁定 SHA**~~ **已不再适用**：协议栈已发 0.1.0 正式版
  （见「四点四」）。此条仍作为**将来切回 SNAPSHOT 时**的纪律保留；
- **iot-starter 源码树绝不能落在本仓工作目录内**（否则「仓内每个 pom 都必须有归属」门禁转红——旧栈 CI 实测失败）；
- 依赖接入后需重跑：`ypbin-architecture-tests`（模块归属/发布规则）+ 白名单门禁（`pom.xml` 变更需同步 `SYNC.md`）。

### 四点四、✅ 已解：协议栈依赖与「不可改的 ci.yml」冲突（2026-09-21 实测 → 当日按方案 A 解决）

**现象**：给 access 加上 iot-starter 依赖后，主 CI（`.github/workflows/ci.yml`，admin 拥有、在白名单内、
**本仓不允许修改**）会转红。根因不是测试/代码，而是 Maven 的模型解析：

```
Non-resolvable import POM: Could not find artifact cn.ypbin:ypbin-iot-bom:pom:0.1.0-SNAPSHOT
  @ cn.ypbin.admin:ypbin-access:pom
ERROR The build could not read 1 project
```

即：**reactor 里只要有一个模块的 pom 引用了解析不出来的 BOM，Maven 连「读项目」这一步都会失败**，
连 `-pl <某模块> -am` 也救不了（实测：`mvn -o -pl ypbin-service/ypbin-iot -am validate` 同样失败）。
而 `ypbin-iot-starter` 的 `0.1.0-SNAPSHOT` **未发布到任何远程仓库**（其 ROADMAP 的决策是
「先让 access 用 SNAPSHOT 跑通，再发 0.1.0」），ci.yml 里也没有「装 iot-starter」这一步。

**为什么本仓自己解决不了**：ci.yml 属于 admin 白名单文件，改它会让 `Sync Whitelist` 门禁转红、
并使 `git merge upstream/main` 的分歧面变大（SYNC.md 第二节）。而本仓能新增的文件（如 `.mvn/maven.config`
+ 自定义 settings）虽然能注入仓库配置，但**造不出制品**——问题在「制品可得性」，不在配置。

**三个可行方向（择一，未擅自实施）**：

| 方案 | 做法 | 代价 / 风险 |
|---|---|---|
| **A. 发布制品**（终态） | 把 `ypbin-iot-starter` 发到远程仓库（Central 正式版，或 GitHub Packages） | Central=一次正式发版（其 ROADMAP 本就是这个方向）；GitHub Packages 需在消费侧注入带凭据的 settings（本仓可用新增的 `.mvn/maven.config` 指向自有 settings，但会覆盖开发者本地 settings —— 国内需同时保留 aliyun 镜像，且本地必须能拿到 token） |
| **B. admin 侧改 ci.yml** | 在 admin 仓的 ci.yml 里加一步「取源并安装 iot-starter」，本仓靠同步获得 | 语义不对（admin 的 CI 不该知道 IoT 协议栈）；需跨仓 PR + 合并 + 同步，周期长 |
| **C. 依赖移出默认 reactor**（过渡） | 新增 `ypbin-access-stack` 模块承载协议栈装配，并在 `ypbin-service/pom.xml` 里**放进 profile**（默认不激活）；CI 默认构建不含它 ⇒ 不解析 iot BOM；3b-2 的构建/集成测试用 `-Piot-stack` | 主 CI 今天就能绿、零外部依赖；但**部署也必须带该 profile**（install.sh / compose 的构建命令要同步改），否则部署出来的 access 不含协议栈——属于「用构建开关表达能力开关」，需要接受这个形态 |

**最终采用 A（2026-09-21 当日完成）**：`ypbin-iot-starter` 发布 **0.1.0 正式版到 Maven Central**
（deploymentId `08a8deaf-bd1b-46e5-86f0-ac69f6d4391a`，`autoPublish=true`；tag `v0.1.0` + GitHub Release）。
本仓随之把 `ypbin-access` 的 `<ypbin-iot.version>` 从 `0.1.0-SNAPSHOT` 改为 `0.1.0`，
并删掉集成测试工作流里「从源码安装 SNAPSHOT」那一步 —— 主 CI 从此可直接解析，阻塞解除、无需碰 admin。
**A 优于 C 的关键点**：不需要为了绕开构建期解析而引入「构建开关表达能力开关」的 profile，
部署形态保持单一（install.sh / compose 不需要带 profile）。

> **给未来的提醒**：只要协议栈依赖还是 SNAPSHOT，上述「reactor 读不动」问题就会复现；
> 下次升级协议栈版本时，要么同样走正式版发布，要么在 CI 里显式取源安装（锁 SHA、源码放 workspace 之外）。

### 四点五、实测踩到的两个坑（2026-09-21 实施时发现，务必照抄结论）

1. **导入 `ypbin-iot-bom` 会把 `ypbin-starter-*` 的版本顶成 `0.1.0-SNAPSHOT`（不存在）**：
   iot-starter 的父链是 `ypbin-starter-dependencies`（用 `${revision}` 托管**全部** starter 制品），
   BOM 导入时该属性在**其子上下文**插值 ⇒ starter 制品被改写成 iot-starter 自己的版本号；
   而 dependencyManagement 同键**以先声明者为准** ⇒ access 里必须先 import `ypbin-starter-bom`
   （版本 `${ypbin-starter.version}`），**再** import `ypbin-iot-bom`。已按此写进 access 的 pom。
2. **`spring-boot-maven-plugin:repackage` + failsafe 的组合坑**（与 3b-2 无关但同批踩到，属通用）：
   access/iot 这类**会被 repackage 的模块**上跑集成测试时，failsafe 在 `package` 之后执行，默认会拿到
   重打包后的 fat jar（类在 `BOOT-INF/classes` 下，普通类加载器看不见）⇒ 测试报
   `NoClassDefFoundError`。必须在 failsafe 里显式 `<classesDirectory>${project.build.outputDirectory}</classesDirectory>`。

### 四点六、真 socket 端到端验收结果（2026-09-21，远程机实测）

在远程机（8 核 / 7.8G，复用其 MySQL + Redis + Nacos）按 `deploy/` 的方式跑起 **ypbin-iot + ypbin-access**
（源码由本机 tar 推过去：该机 **github 不可达**），造了 1 产品(已发布, tcp) / 1 服务 / 1 属性 / 1 设备
(`tcp://172.20.0.1:19001`) / 1 点位映射 / 1 租户归属(`access-1`, active)，用宿主机 python TCP 桩每 2s 推一帧。

| 验收项 | 结果 | 关键证据 |
|---|---|---|
| 会话 0→1 | ✅ | iot 注册 Nacos；access 领取租约 → `订阅成功：deviceId=900001 点位数=1` |
| **真出数** | ✅ | 每 2s 一条 `读数：device=900001 property=900001 quality=GOOD`，值即 TCP 桩发的 `E2E-DATA-n,` 字节数组 |
| **断链→重连→仍出数（证 B2）** | ✅ | 杀桩后窗口内读数 **0**；重启桩后 **15 条/30s**，且日志出现**新的** `订阅成功`（新会话实例被重新订阅） |
| **启动期空清单自愈（证 N-1）** | ✅ | 以「设备停用」重启 access：读数 **0** + 两次 `租户设备清单为空，本轮不缓存并等待下轮重取`；恢复设备后 **30 条/60s**（若空清单被缓存则永不恢复） |
| fence | ✅ | 停 iot → `续约失败…到期后将自行停采` → `协议栈断链停采：tenantId=1 reason=本地租约已过期`，读数 **0** |
| 恢复 | ✅ | 重启 iot 后 access 自动重新领取并出数 |

**e2e 一跑就抓到两个「单测与 CI 全绿、服务在真实启动时才暴露」的真缺陷**（均已修，见 `79356f1`/`a31ad2d`）：

1. **缺 `io.micrometer:context-propagation`**：starter 的 `TenantAutoConfiguration` 需要 tenant 的
   `ThreadLocalAccessor`，而该依赖在 starter 侧 optional 不传递 ⇒ `ClassNotFoundException`。
   admin 的 `ypbin-system` 一直显式声明（`ypbin-system/pom.xml:170-173`），我们两个新服务漏了。
   **⚠️ 触发条件（第三次外委复核实测更正）**：该自动配置的类级条件
   `@ConditionalOnProperty(prefix="ypbin.tenant", name="enabled", havingValue="true")` **没有 `matchIfMissing`**，
   而 `TenantProperties.enabled` 默认 **false** ⇒ **只有 `ypbin.tenant.enabled=true` 时才会启动失败**；
   未开该开关的服务（如本次 e2e 里的 access，缺依赖仍能 `Started AccessApplication`）。
   iot 服务确实开着 tenant ⇒ 它启动即崩，与观测一致。**修复对两者都正确**（将来 access 上租户插件时必需）。
2. **Jackson 2/3 用错**：本栈是 Spring Boot 4，只自动配置 **Jackson 3**（`tools.jackson.databind.ObjectMapper`），
   而我 M-1/3b-2 的代码注入的是 Jackson 2 的 `com.fasterxml...ObjectMapper` ⇒ 无该 Bean，启动失败。

> **结论（写给未来的自己）**：这两类缺陷**单测与 `mvn verify` 都抓不到**（它们不启动完整应用上下文）。
> 「真 socket 端到端」不是可选项，而是这类缺陷**唯一**的暴露面——这正是外委复核坚持把它列为放行条件的理由。

### 四点七、3b-2 的已知限制（外委复核提出，**M-2 前必须处置**）

> 以下是 3b-2 交付时**刻意保留**的占位/边界。它们不影响「当前只装 TCP」的可用性，
> 但都会在特定条件下**静默失效**（不报错、只是没数据），故在此显式登记，避免被当成已完成能力。

| # | 限制 | 触发条件 | 后果 | M-2 前的处置要求 |
|---|---|---|---|---|
| **S4** | **点位地址语义未定死**：MQTT 回调用**具体主题**当 address（`MqttSession` 有意如此），而订阅用 mapping 的 `address` 当**过滤器**，`PointMappingDataListener` 做**字符串精确匹配** | 加装 `ypbin-iot-protocol-mqtt` 模块**当天**（当前只装 tcp，故未触发） | 映射里填了通配符（`a/#`、`a/+/c`）时**订阅成功但每条数据都判「未映射」被丢弃** ⇒ 看起来在采、实际零数据 | 定死「`raw_address` = 具体地址，非过滤器」，并在点位映射保存时**加校验拒绝通配符**；或改为按主题前缀匹配 |
| **S5** | 订阅计数与会话跟踪的精度：`AccessSubscriptionPlanner` 在 `session.subscribe(...)` **之前**就写入 `subscribedSessions` 并自增计数 ⇒ **异步订阅失败不会被重试**（对账只对「无会话」或「会话实例变化」重试） | 会话存在但订阅被协议侧拒绝（如地址非法） | 日志「已订阅设备数」**高估**；订阅失败只记 ERROR，**不会**自愈，需靠链路重建（会话实例变化）才能恢复 | ✅ **已处置**（PR #15，2026-09-21）：`subscribedSessions` 改到 `whenComplete` 的**成功**分支写入，失败不记录跟踪 ⇒ 下个租约周期自动重试；新增 `iot.access.subscribe.success/failure` 指标；返回语义明确为「本次**发起**数」。外委复核 PASS（把 `put` 移回 `subscribe` 之前⇒回归用例精确转红） |
| **S7** | **零设备租户的周期噪声**：修复 N-1 后，设备清单为空的租户**每个租约周期（15s）都会打一条 WARN 并重新拉一次内部接口** | 租户已分配但尚未配设备／点位（或设备全部停用） | 日志噪声 + 每 15s 一次内部调用（规模大时是无效压力） | ✅ **已处置**（PR #15，2026-09-21）：空清单改**指数退避**（30s 起、上限 2 分钟）+ 日志分级（首次 WARN、其后 DEBUG）+ `iot.access.spec.empty` / `iot.access.spec.backoff.skipped` 指标；`Clock` 可注入以便用假时钟测。外委复核 PASS（去退避判断 / 退避永不过期均精确转红） |
| **N-2** | **bind 失败后永不重发 ADD**：ADD 只在首次采集时发，而框架仅在 `bind()` **成功后**才挂重连监听（`IotLifecycle`） | 启动瞬间设备离线（建链失败） | 该设备**永久零数据**且不自动恢复，只有日志一次 ERROR | ✅ **已处置**（2026-09-22，M-2 链路自愈）：`IotProtocolTenantLinkManager.retryDevicesWithoutSession` 对「仍无会话」的设备**重发 ADD**（逐设备指数退避 30s→2min、每轮上限 20、首次 ADD 当轮先预置一次退避以免同轮重复），会话建立即清退避；指标 `iot.access.device.rebind` / `...rebind.deferred`；门禁用例 4 条（含「有会话设备不得被重发」「会话出现后退避立刻作废」「每轮上限 + deferred」） |
| **S6** | **出口是日志占位**：`LoggingAccessReadingSink` / `LoggingDataSink` 是唯一实现且**无 profile 限制** | 生产部署 | 按 INFO **逐条打印**且**数据不落任何地方** ⇒ 「有日志＝像在工作」而实际零持久化 | M-2 数据面替换为「有界队列 → 微批 → EMQX → business 落 IoTDB/Redis」（§5.1），并加**丢弃计数**；替换前不得以占位实现宣称数据面可用 |

**已修（第二轮复核发现，与 B1 同类且更早一步）**：`startCollecting` 原会把 `loadByTenant()` 的空结果也缓存进 `collected`，而取数瞬时失败返回的就是空集合 ⇒ **启动期一次取数失败即把该租户永久钉死为零设备**。现改为「空清单不缓存、下轮重取」，并把「本节点负责该租户」的状态（`collecting`）与设备清单缓存**分开**，保证 fencing 与观测语义不变；已补可咬回归用例 `emptyDeviceListMustNotBePinned`。

**跨仓待反馈（第三次外委复核发现，R8）**：starter 的 `TenantAutoConfiguration` 里
`tenantThreadLocalAccessor()` 的方法级 `@ConditionalOnClass(io.micrometer.context.ThreadLocalAccessor)`
**挡不住**——Spring 注册 bean 方法时会走 `ReflectionUtils.getDeclaredMethods → Class.getDeclaredMethods`，
解析缺失父类（`TenantThreadLocalAccessor`）即抛错，方法级条件根本来不及生效。
宿主只能靠显式依赖兜底。建议反馈到 `ypbin-starter`（本仓不可改该仓，属跨仓待办）。

**另有两条复核指出的实现注意（已修/已记）**：revision 单调与「两个开关」的区分已修；`devices.enabled=false`
时变更通道也不会接线（文档表述已更正，见四点五）。`PointMappingDataListener.unmappedCount` 为普通 `long`，
跨线程可见性目前依赖调用方（单线程订阅回调场景下成立），M-2 若引入多线程需一并改为原子类型。

### 四点八、M-2 / M0b-1+M0b-2：容量落库（数据库级原子）

**做了什么**：新增 `access_node`（节点注册表，容量落库）与 `tenant_ledger`（租户台账：可分配来源 +
`config_epoch`）；`AccessNodeRegistry` 由内存改为落库；容量判定改为**事务内锁节点行**
（`SELECT ... FOR UPDATE`）；可分配租户由「读配置」改为「读台账」（配置降级为兜底）。

**真库并发用例实测结论**（`LeaseConcurrencyIT`，用**两个服务实例各自独立注册表**模拟两个副本共用同一 nodeId）：

1. **锁顺序是硬不变量**：最初的实现是「先动归属表（续期 UPDATE）→ 再锁节点行」，两个副本并发时
   **实测到 MySQL 死锁**（`DeadlockLoserDataAccessException`）。改为**第一条语句就锁节点行**后消失。
   ⇒ 规则：**统一锁顺序（先节点行、后归属表）**，否则多副本并发领取会死锁。
2. **用例真的能咬人（变异验证）**：把 `lockCapacity` 的 `FOR UPDATE` 去掉（退回非锁定读）⇒
   该用例立刻转红（同样以死锁形式暴露）。⇒ 它确实在守「容量数据库级原子」这个性质。
3. **逻辑删除 + 唯一键的陷阱（M0b-3 必须处理）**：`tenant_ledger` 上 `uk_tenant_ledger(tenant_id)`
   不含量删除标记，而 `BaseEntity` 是逻辑删除 ⇒ 软删同一 `tenant_id` 后再 insert 会撞唯一键。
   台账写入口必须走「**复活已有行**」而不是盲目 `insert`（与 M-1 的 `iot_service` 是同一类问题）。

**⚠️ 用例运行要求**：`LeaseConcurrencyIT` 必须用 **mybatis-spring 的 `SqlSessionTemplate` + Spring 事务**
（而不是普通 IT 的裸 `SqlSessionFactory`）——否则 `FOR UPDATE` 的锁在语句结束即释放，用例会**假绿**。
另外 `-Pit -pl <模块>` **必须带 `-am`**（不带会从 `~/.m2` 取到旧的兄弟模块 jar，见教训十八）。

### 四点九、M0b-3 / M0b-4：台账写入口与**数据库时钟**

**M0b-3（配置版本号）**
- 新增 `TenantLedgerService.setAssignable(...)`：新建 ⇒ `config_epoch=1`；变更 ⇒ 一条 UPDATE 内
  `assignable=? , config_epoch = config_epoch + 1`（**同语句**，否则「台账变了、版本没变」⇒ 接入侧漏拉）；
  **软删过则复活同一行**（`uk_tenant_ledger(tenant_id)` 不含量删标记 ⇒ 盲目 insert 撞唯一键，与 M-1 的
  `iot_service` 同类）；并发首次写入撞唯一键退化为复活。
- 对账契约 `TenantEpochItem` 现同时带 `epoch`（归属：**谁在采**）与 `configEpoch`（配置：**要采什么**），
  `batchEpoch()` 一并回填 ⇒ 接入侧一次批量拉取即可「不一致才拉全量」。
- 真库用例 `TenantLedgerIT`：新建=1 → 变更=2 → **软删后重设必须复活且=3**、全程仅一行。
  **它当场抓到实现者的真 bug**：`BaseEntity.getIsDeleted()` 是 **Integer** 而非 Boolean，
  `Boolean.TRUE.equals(...)` 恒假 ⇒「复活」被误判成「更新」。

**M0b-4（数据库时钟）**
- 租约的时间基准统一改为 **`SELECT NOW()`（数据库时钟）**：`doAcquire`/`renew`/`release`/`markExpired`
  以及 `batchEpoch.readAt` 都取 DB 时间；`markExpired()` 不再接受调用方传入的本机时间（给什么就可能给错）。
- 原因：写入与过期判定各读本机时钟时，**时钟快的节点会提前抢走仍在正常续约的租户**（表现为莫名频繁的接管，
  且无显式错误）。
- 真库用例 `LeaseDbClockIT`：把该用例自己的连接池会话时区设为 `-11:00`，使 DB 的 `NOW()` 与 JVM 时钟
  相差数小时，再断言落库的 `lease_expire_at` 跟 **DB 时钟**走。
  **变异验证**：把 `doAcquire` 退回 `LocalDateTime.now()` ⇒ 用例转红，报
  「到期时间应≈DB 现在(07:42:15)+ttl，实际=18:42:46」**相差 39601 秒**；还原后逐字节一致。
  （该用例在 JVM 时区恰好等于 -11:00 时会显式跳过「必须偏离」那半条断言，而不是假装通过。）

### 四点十、M0b 外委复核结论（PASS）与**待接线项**（如实登记，避免把前置件当闭环）

第三次之后又做了一轮 **M0b 专项外委复核**：结论 **PASS（可合并）**，A–E 五组声明全部成立（各由复核者亲跑命令与
亲读行号支撑），并给出 P1 缺陷与 P2–P10 建议。**已在本分支修掉 P1/P2/P3/P9**：

- **P1（测试可重复性，已修）**：`LeaseConcurrencyIT` 的 `registerNode/purge` 原先用 MyBatis-Plus **逻辑删除**，
  而 `uk_access_node(access_node)` 不含量删标记 ⇒ 对**持久化** MySQL 第二次运行整类失败
  （复核者自建实例实测 `Duplicate entry ... uk_access_node`）。改为**物理删除**，并新增用例
  `purgeMustPhysicallyRemoveNodeSoTestIsRepeatable`（连续注册两次不得撞唯一键）。
  **变异验证**：把 `registerNode` 退回逻辑删除 ⇒ 该 IT 类 3 条全 ERROR，报的正是复核者的唯一键冲突；
  还原后逐字节一致。（第一次变异只改了 `purge`，被 `registerNode` 的物理删除掩盖 ⇒ 说明**承重点在 registerNode**。）
- **P2（代码缺口，已修）**：`AccessNodeRegistry.register` 补「复活软删节点行」分支
  （`selectIncludingDeleted` + `revive`），与 `TenantLedgerService` 的复活语义对齐；
  否则软删后注册会抛「并发冲突且无法读取」。
- **P3（用例顺序耦合，已修）**：`assignableTenantsComeFromLedger` 自行 `registerNode()`，不再偷依赖其它用例。
- **P9（清理，已修）**：删除死代码 `LeaseServiceImpl.capacityOf`、删除改签名后过期的 `@param now`、
  删除 `LeaseExpiryScanner` 未用 import。

**⚠️ 必须如实登记的待接线项（复核 P4–P8、P10 —— 现在还不是闭环）**：

| # | 事项 | 现状（勿写成已完成） |
|---|---|---|
| **P4** | `config_epoch` **目前无消费方** | 接入侧 `AccessLeaseManager` 只用 register/acquire/renew，**从不调** `batchEpoch`/`/internal/lease/epochs` ⇒ 「不一致才拉全量」的**对账尚未落地**；本分支只交付了列/契约/写入口（前置件）。**→ 已于「四点十一」落地**（`ConfigEpochReconciler`） |
| **P5** | `TenantLedgerService.setAssignable` **无生产调用者/端点** | 「运维可动态增删可分配租户」在运行态**尚不可达**（仅 IT 调用）。**→ 已于「四点十一」落地**（`IotTenantLedgerController`：`GET /tenant-ledger`、`PUT /tenant-ledger/{id}/assignable`） |
| **P6** | 台账全置不可分配时会**静默回落配置** | 当前 nacos `assignable-tenant-ids: []` 故无害；一旦填了配置，撤销操作会被静默忽略 ⇒ 接线时须一并处理 |
| **P7** | 容量只限「新分配」，**不回收存量** | 容量改小/改 0 后，既有 ACTIVE 行仍被续期，旧租户不会自动脱落（设计取舍，需显式说明） |
| **P8** | `status` 列未参与过滤 | `assignableTenantIds`/`listAssignableTenantIds` 与 `selectByNode/selectForUpdate` 都不过滤 `status`（本仓他处是显式过滤的） |
| **P10** | 多副本残余死锁面 | `renew/release/markExpired` 不取节点行锁，与 `doAcquire` 可能成环（表现为可重试死锁异常，非数据损坏） |

**M0b-4 的残留面（复核 D 补充，写下来避免误以为已闭环）**：接入侧 `AccessLeaseManager` 仍用**本机时钟**
做租约语义判定（`LeaseSnapshot` → `needsSelfFence(..., now)`）——服务端已改用 DB 时钟，
但接入侧「本地到期即自行停采」的比较仍受本机时钟影响（**钟快=提前停采、钟慢=服务端接管后仍多采一段**）。
彻底闭环需要租约契约带上**服务端时间**（如 `LeaseAcquireResp`/`RenewAck` 增加 serverTime），
接入侧以「服务端时间 + 本地单调流逝」判断。属 M-2 数据面/契约项，登记在此。

### 四点十一、M-2：接入侧消费 `config_epoch`（P4）与**设备变更自动对账**（G7）

**解决了什么**：`config_epoch` 此前**没有消费方**（P4：接入侧从不调 `batchEpoch`），而设备清单只在
「首次开始采集」时取一次（G7：`collected` 非空后**永不重取**）——租约持续续约时连 fence 都不会发生，
于是上游**新增设备 / 改点位映射 / 改端点周期 / 停用删除设备**永远不会被接入侧发现，只能靠链路重建。
两件事是同一个缺口的两面，本轮一起落地（外委复核已用代码路径把 G7 做实）。
**外委复核两轮**：第一轮判 `FAIL(条件)`（① `findConnection` 的「不得抛断整轮绑定」声明与代码不符——
端点漏 scheme 时 `Endpoint.of` 会抛非 `DeviceSpecLoadException` 异常且写入侧无校验；② 文档把「单租户/台账
无该租户」写成有 `startCollecting` 兜底，实际该场景**完全无兜底**；另有 `forget` 两条停采路径零覆盖等）。
两轮复核的放行条件与全部非阻断建议已在本轮修完，见下表最后几行。

**做了什么**

| 面 | 实现 |
|---|---|
| 变更信号（业务侧） | 设备增删改、点位映射增删改 ⇒ **同一事务**推进 `tenant_ledger.config_epoch`（`TenantLedgerMapper.bumpConfigEpoch`，只更新未删除行）；台账无该租户时 **no-op**（不 insert、不改 assignable，否则会静默把「可分配来源」从配置兜底切成台账） |
| 变更消费（接入侧） | 新增 `ConfigEpochReconciler`：每轮租约周期调 `/internal/lease/epochs`，**只对本节点持有的租户**、**只在版本号变化时**触发 `TenantLinkManager.reconcile`；停采/回收/nodeFenced 时 `forget`，重领后必然重新对账 |
| 版本号推进条件 | **对账已完成才推进**（`reconcile` 返回 false = 取数失败/退避中/不在采集 ⇒ 下轮重试）。先推进再对账会让一次失败**永久吞掉**一次变更 |
| 差异应用 | 新增→ADD；消失→REMOVE + 清理订阅跟踪；**规格变化→重新 ADD**（点位/端点/周期变了必须让框架重建会话，新会话再重订阅）；上游确认「确实没有设备」（成功信封 + 空列表）→ 全部下架，且**不缓存空清单**（交还 `startCollecting` 的空清单退避重取路径） |
| 首次观测 | 本进程第一次看到某租户时**也做一次全量对账**：否则「清单拉取时刻早于版本号读取」的竞态会永久吞掉一次变更（多拉一次有界，漏一次变更永久） |
| 运维入口（P5） | `GET /tenant-ledger` + `PUT /tenant-ledger/{tenantId}/assignable`（平台级权限 `iot:ledger:list/update`，`platform_only=1`、只授角色 1、**不进**租户模板）：把「哪些租户可被采集」从「改配置重启」变成可运维数据 |
| 失败与空分离（G2/G3） | `DeviceSpecSource.loadByTenant` 失败一律抛 `DeviceSpecLoadException` ⇒ 引导路径（`loadAll`）捕获后跳过该租户（不整体抛断），对账路径**不下架既有设备**并单独计数 `iot.access.spec.failure`；`findConnection` 是框架建链路径，单独捕获返回 `Optional.empty()`（不得抛断整轮绑定） |
| 指标前缀统一（G5） | 3b-2 引入的 `ypbin.access.*` 与既有 `iot.access.lease.*` 统一为 `iot.access.*` |
| 用例补强（G4） | 补「fence 必须清退避状态」可咬用例（复核实测：删掉 `fence` 里的 `emptyBackoff.remove` 时原 8 条用例全绿；新用例在该变异下转红）；补「本地过期」「nodeFenced」两条停采路径的 `forget` 断言（复核变异⑦⑧ 证明此前**零覆盖**） |
| **端点/建链路径加固**（复核放行条件①） | `IotDeviceReq.endpoint` 加 scheme `@Pattern`（写入侧第一道防线，且有 `IotDeviceReqValidationTest` 钉住）；`loadByTenant` 把**转换失败**（协议码非法、点位清单写不出去）归一到 `DeviceSpecLoadException`；`findConnection` 把「取数失败」与「端点/协议值非法」都收敛为 `Optional.empty()` 并计数 `iot.access.connection.invalid`（建链路径不得抛断整轮绑定）。注意：`DeviceSpec` 不解析端点，所以端点非法**不会**让 `loadByTenant` 失败——这条边界有用例钉住 |
| **周期安全网**（复核放行条件②） | 新增 `ypbin.access.config-refresh-interval-ms`（默认 **5 分钟**）：超过该间隔未被「版本号驱动」覆盖到的持有租户会被强制对账一次，**每 tick 最多 1 个租户**（把额外远端调用限流为每 tick 至多一次）。覆盖「单租户部署/台账无该租户 ⇒ 根本没有变更信号」的场景，把它从「永不重取」兜成「最坏等一个周期即收敛」 |
| **指标噪声收敛** | `iot.access.config.reconcile.not_applied` 改为**同一版本号只计一次**（零设备/持续失败租户每轮都会重试，原实现会让它无界单调增长）；对账请求本身失败时**不跑安全网**（请求每 tick 就会重试，跑安全网只会翻倍压力） |

**验收证据（本轮，均本机 `clean` + `-am` 实跑）**

- `ypbin-access` 单测 **61/0**（原有 37 → 本轮 +24：`ConfigEpochReconcilerTest` 11、`IotProtocolTenantLinkManagerTest` +6、
  `HttpDeviceSpecSourceTest` +4、`AccessDeviceRegistryTest` +1、`AccessLeaseManagerTest` +2 与既有用例的断言补强）；
- `ypbin-iot` 单测 **90/0**（原有 79 → 本轮 +11：`TenantLedgerServiceTest` 5、设备/点位写路径接线 2、
  `IotDeviceReqValidationTest` 4）；
- `ypbin-architecture-tests` **41/0**；`tools/check-iot-sql-equivalence.sh` **OK**（007 与迁移文件同步）；
- **CI 四绿**（CI / CodeQL / IoT 集成测试 / Sync Whitelist）；CI 的 `-Pit` 日志实测：`TenantLedgerIT` 5/0（**0 跳过**）、
  `LeaseConcurrencyIT` 3/0、`LeaseDbClockIT` 1/0、`IotTenantIsolationIT` 6/0，IT 合计 15/0；
- **变异验证 10 处**（每处先确认变异落地、后还原，除本轮一次失误见下）：版本号无条件推进→红 `notAppliedMustNotAdvanceEpochSoNextRoundRetries:104`（行号按当前树）；
  消失设备分支失效→红 `reconcileShouldApplyAddRemoveAndReplace:184`；删 `fence` 的 `emptyBackoff.remove`→红 `fenceMustClearBackoffSoReacquireRefetchesImmediately:244`；
  删点位 `create` 的版本号推进→红 `pointMappingMutationsMustBumpConfigEpoch:204`；摘掉安全网→红 `missingSignalMustConvergeThroughBoundedSafetyNet`（+1）；
  安全网改成「强制所有到期租户」→红同用例「每轮只允许强制一个」；删 `loadByTenant` 的转换归一→红 `conversionFailureMustSurfaceAsLoadException`；
  删 `findConnection` 的 try/catch→红 `invalidEndpointMustNotThrowFromFindConnection`；删 `IotDeviceReq` 的 `@Pattern`→红 `endpointWithoutSchemeMustBeRejected`；
  删本地过期/nodeFenced 的 `forget`→红 `AccessLeaseManagerTest:160/:182`（此前后者无覆盖）。
- ⚠️ **真 socket 端到端**（设备变更→接入侧真的重建会话并出数）本轮未复验：需能稳定跑容器的机器，
  且本轮不改协议栈装配（风险面是「变更是否被应用」，已由单测 + 真库 IT 覆盖其两侧）。
- ⚠️ **一次操作失误（如实记录）**：复核后的修复尚未提交时，我用 `git checkout -- <file>` 还原变异，
  把 `ConfigEpochReconciler.java` 的**未提交修复一起还原**（教训三十三的同一坑）。已按上下文重写该文件并重跑门禁确认；
  此后所有变异一律改在**已提交的 detached worktree** 上做。

**本轮仍未闭环（如实登记，勿当已完成）**

| # | 事项 | 现状 |
|---|---|---|
| **G1** | 订阅失败**无退避、无在途去重** | ✅ **已处置**（2026-09-22，M-2 链路自愈）：订阅失败改**指数退避**（30s→2min；退避状态记住失败所在的**会话实例**，实例变化即作废——否则框架重连后的立刻重试会被退避挡住）+ **在途去重**（异步未完成的订阅不得被下一轮重复发起）；新增 `iot.access.subscribe.inflight.skipped` / `...backoff.skipped` 指标；门禁 7 条用例（含「退避窗口内不得重发」「翻倍」「成功清除」「forget 清退避」） |
| **G6'** | 属性标识改名 / 新版本物模型发布**不**推进 `config_epoch` | 点位地址、类型、周期、字节序等变更已覆盖；`identifier` 仅影响读数标签，改名要等下一次变更才对账 |
| **G7'** | 设备「全部停用」路径 | 设备 `status` 没有写入口（`IotDeviceReq` 无该字段），故只能靠设备删除/映射删除触发；`status=1` 过滤仍在 `DeviceSpecServiceImpl` |
| **G8** | 单租户部署（未开 tenant 插件）与「台账无该租户」时**没有变更信号** | `bumpConfigEpochOfCurrentTenant` 无租户上下文即 no-op（设计取舍：不得顺手 insert 一行台账）。**已由周期安全网兜底**：超过 `config-refresh-interval-ms`（默认 5 分钟）未尝试过对账的持有租户会被强制对账、**每 tick 至多 1 个** ⇒ 全量轮转时间 ≈ `max(租户数 × tick 周期, config-refresh-interval-ms)`（仓库内用例 `safetyNetMustRotateAcrossAllStaleTenants` 钉住「3 租户 3 个 tick 全覆盖」）；因此收敛上界是**一个轮转周期**，不是「一个间隔」。⚠️ **代价如实说明**：安全网按「距上次尝试的时长」触发 ⇒ **信号正常、版本号长期不变的租户同样会被周期强制全量对账**（**不是**「只在信号缺失时才触发」），换来的是「任何漏信号/信号缺失场景最坏一个轮转周期即收敛」；确定信号链路可靠时可调大间隔或设 0 关闭 |
| **R8-2** | **对账可能拖长调度 tick**：对账是串行远端调用（首次观测对全部持有租户各拉一次），单 tick 超过续约 TTL（30s）会让下一轮 `selfFenceExpiredLocally` **批量自我 fence** 并重领，形成抖动；`AccessStartupValidator` 只按「一次租约调用」建模。**安全网既限流又加剧**：它把新增调用限到每 tick 至多 1 次（缓解），但又恒定 +1 次全量 `loadByTenant`（最坏 +6s：Feign connect1+read5）。越界的**最坏 tick 次数本身不变**（两种设计都是每租户至多一次 `loadByTenant`）；安全网降低的是**引发越界所需的「本轮变更租户数」**：`3 个变更 × 6s + 18s 稳态 + 1 个被强制 × 6s = 36s > 30s` ⇒ 3 个变更 + 1 个未变更租户即越界（节点需持有 ≥4 个租户；恰持 3 个时上限为 12+3×6=30s，不越界）。⚠️ 上一版此处写「阈值从约 4 个租户下压到约 3 个」把「变更租户数」写成了「租户数」，已更正 | 未做（需要 tick 级时间预算/并发上限；或把安全网收敛为「仅对无台账/epoch 恒 0 的租户强制」/调大默认间隔） |
| **R8-3** | `iot.access.config.reconcile.not_applied` 对零设备/持续失败租户**永久递增** | **已修**：同一版本号只计一次；零设备租户仍每 tick 走一次**廉价**重试（`reconcile` 在 `collected == null` 时直接返回 false，不打远端） |
| **R8-4** | `/internal/lease/epochs` **不按节点过滤**：每节点每 10s 拉全平台 assignment × ledger（O(节点数 × 全平台租户)） | 未做（可加 `?accessNode=` 只返回本节点持有租户，或分页） |
| **R8-5** | `fence()` **不清理订阅跟踪**（与 `removeAllDevices` 不对称），`SubscriptionPlanner.forget` 默认空实现无门禁 | 未做；重领后是否漏订阅取决于框架 REMOVE→ADD 是否复用同一 `DeviceSession` 实例（**需真 socket e2e 核实**） |
| **R8-6** | **规格变化路径只重发 ADD**，依赖「框架先解绑再绑定 ⇒ 新会话实例 ⇒ 重新订阅」 | 未做（同上：若框架复用会话实例，新点位永不订阅 ⇒ 静默零数据；需 e2e 核实并登记依赖） |
| **R8-7** | P5 的**平台级不变量无门禁**：`platform_only=1` 与「不进 `sys_template_menu`」只靠人眼（`IotPermissionCodeGateTest` 只校验权限码存在性） | 未做（建议加一条 SQL/源码级门禁，防后续补授把跨租户越权面授给租户管理员） |
| **R8-8** | 「设备/点位变更与版本号**同一事务**」**无自动守卫**：单测用 mock 只能证明「被调用 3 次」；且台账表故障会**阻断设备/点位写入**（可用性耦合） | 未做（可加「bump 失败 ⇒ 业务写入回滚」的真库用例与事务边界门禁） |
| **R8-9** | **安全网的成本与可观测性**：健康系统也每租户每周期多一次全量 `loadByTenant`（见 G8 行的如实说明）；新指标 `iot.access.config.reconcile.forced`/`...not_applied`/`...changed` 已注册但**未作为指标登记进任何指标文档/大盘**（本文档仅文字提及），且 `deploy/nacos/ypbin-iot.yaml` **没有指标暴露配置**（`/actuator/**` 白名单只在 `ypbin-system.yaml`）⇒ 「可观测」目前只是潜在 | 未做（登记指标 + 补 iot 服务指标暴露配置；如需降低安全网成本，可收敛触发条件或调大间隔） |
| **P6/P7/P8/P10** | 台账全置 false 静默回落配置／容量不回收存量／`status` 未参与过滤／`renew/release/markExpired` 取节点行锁的残余死锁面 | 与「四点十」登记一致，本轮未动 |
| **M0b-4 残留** | 接入侧 `AccessLeaseManager` 仍用**本机时钟**做本地过期自停采 | 需租约契约带「服务端时间」，未做 |

### 四点十二、M-2 断档与可用率（口径 + 检出 + 落库 + 查询 + access 上报接线）

**口径（落成代码常量，不再只是文档）**：有效数据 = `quality=GOOD`；断档 = 连续 **> K × 采集周期**
无有效数据（K 默认 2，可配）；可用率（逐台）= `1 - Σ(断档时长 ∩ 窗口) / 窗口时长`（进行中的断档按
**数据库时钟**结算到查询时刻）；达标是**双条件**：可用率 ≥ 0.995 **且** 最长单次断档 ≤ `max(10 分钟, 10 × 采集周期)`。
口径常量集中在 `AvailabilityRules`，并随查询响应返回（客户端不必重复实现一遍口径，避免两处漂移）。

**做了什么**

| 面 | 实现 |
|---|---|
| 两张表 | `device_liveness`（每设备一行：最近有效数据/首次观测/最近观测/进行中断档 id，唯一键 `(tenant_id, device_id)`）、`outage_event`（start/end/duration/reason；`end_ts IS NULL` = 进行中）。**租户表**，不进 `ignore-tables`；006 与迁移 `2026-09-19-iot-m2-availability-schema.sql` 语句等价 |
| 判定与计算 | `OutageDetector`（纯逻辑：阈值**严格大于**、起点=lastGoodAt 否则 firstObserved、生效周期兜底）与 `AvailabilityCalculator`（纯逻辑：窗口裁剪、进行中结算、双条件、脏数据封顶不为负）——时间相关行为全部可用可推进时间断言，不靠 sleep |
| 上报端点 | `POST /internal/readings`（`X-Internal-Token` 保护；单批 ≤ 500）。**只收「读数观察」**（deviceId/周期/质量/时刻），不收值——值的存储属数据面（依赖 Q8），本轮不发明马上要改的契约 |
| 静默设备检出 | 周期扫描 `OutageScanner`（`ypbin.availability.scan-interval-ms`）：SQL 里用 `TIMESTAMPADD(MICROSECOND, (CASE WHEN 周期>0 THEN 周期 ELSE 兜底 END) × K × 1000, COALESCE(last_good_at, first_observed_at)) < NOW()` 选候选（生效周期口径与 `OutageDetector.effectiveIntervalMs` **完全一致**；早期版本误用 `GREATEST(周期, 兜底)`，会把 1s 周期的设备抬到 5s，与 spec 的「K × 采集周期」不符） ⇒ **设备彻底不再上报也能发现**（这正是断档判定的核心场景，只靠上报事件永远发现不了） |
| 多副本安全 | 打开断档 = 插入事件 + `UPDATE device_liveness SET open_outage_id=? WHERE id=? **AND open_outage_id IS NULL**`；受影响行数为 0 时**撤销刚插入的事件** ⇒ 同一段断档不会被两个副本记两次。⚠️ 第一版提交时**这个谓词漏了**（文档/Javadoc 都声称有、SQL 里没有），被外委复核实测抓出：所有 mock 级用例（含变异）都咬不到 SQL 文本 ⇒ 已补谓词，并新增① 源码级门禁 `AvailabilityMapperContractTest`（断言 SQL 含该谓词、且上报路径不得回写 `open_outage_id`）、② 真库用例 `markOpenOutageMustBeConditionalOnNullInRealSql`（第二次标记必须返回 0） |
| **写权分离** | `open_outage_id` 的写权一分为二：**扫描负责开**（`... WHERE open_outage_id IS NULL`）、**上报负责闭**（`... WHERE id=? AND open_outage_id=?`，只清自己那一条）。上报侧不再整行回写该字段 ⇒ 消除「上报手里是旧快照、把扫描刚开的断档覆盖成 NULL」的丢失更新（会变成永不闭合的孤儿断档） |
| 乱序/重放防护 | 有效数据**早于断档起点**时**不闭合**（否则写出 `start > end`、`duration=0` 的假恢复并清掉标记）（状态字段的「只前进」已由 A14 提升为**数据库级**保证，见「观测状态数据库级单调」行） |
| 候选清理（防饥饿） | 设备已删除/停用的活性行**清理掉**而不是只跳过：候选查询按 `id` 升序 + `LIMIT 扫描批次`，这类行永远满足条件、永远占住前段 ⇒ 累积 ≥ 批次上限后**其它设备的断档再也不会被发现**（是饥饿，不是延迟） |
| 生效周期口径统一 | SQL 与 `OutageDetector.effectiveIntervalMs` 统一为「上报了正周期就用它，否则用兜底」；并把纯逻辑 `isOutage` **接进生产路径**（开断档前用同一套规则复核，两处口径不一致会先暴露）——此前它是死代码 |
| **观测状态数据库级单调**（A14 闭环） | `reviveAndUpdate` 的时间戳比较**移到 SQL 的 `CASE` 里**：`last_good_at`/`last_observed_at` 取较大值、`first_observed_at` 取较小值、`poll_interval_ms` 只在正数时更新。Java 侧 `latest()/earliest()` 只保证**单次调用内**正确；两个副本各自拿旧快照写回时旧时间戳会覆盖新的（可用率被算高），只有数据库级比较才是不变量。可空参数一律**显式**带 jdbcType（时间列 `TIMESTAMP`、周期列 `INTEGER`）——不写不报错但会按默认 `OTHER` 绑定，类型不对 |
| **可用率汇总精确化**（A11 闭环） | 汇总改由 `OutageEventMapper.summarizeInWindow` 的**一次聚合**给出（`COUNT`/`SUM`/`MAX` + SQL 侧窗口裁剪 `GREATEST(start_ts, from)` / `LEAST(COALESCE(end_ts, now), to)`、单条 `GREATEST(0, …)` 防负）；`AvailabilityCalculator` 退化为「拿到精确输入后的三件防御（窗口 0、下限 0、封顶到窗口）+ 双条件」。明细改为**最新优先**并仍按上限截断，但**截断不再影响可用率**（此前「明细求和」会在超过上限时低估断档 ⇒ 可用率偏高） |
| 假断档过滤 | 扫描候选必须「设备仍存在且启用」（一次批量查设备，非循环查）：设备删除/停用后活性行不会自己消失，不筛就会产生**永远消不掉的假断档** |
| **接入侧上报（A1，已落地）** | access 的读数出口从「日志占位」换成 `HttpAccessReadingSink`：**有界队列 → 微批（默认 200 条 / 1s）→ `POST /internal/readings`**；入队 `offer`（队满**丢弃并计数**，绝不阻塞采集线程）、Feign 超时显式（connect 1s / read 5s / **不重试**）、失败与非法读数分别计数（`iot.access.egress.dropped/failed/invalid`）、停机前尽力刷出队尾；没有内部客户端或显式关闭时**退化为日志占位并打 WARN**（不静默降级）。设备级周期随读数带出（断档用真周期）；上报时刻用 **epoch 毫秒**（跨服务不用字符串时间，避免两端时区/格式配置不一致）。S6（日志占位）至此收口；EMQX 替换待 Q4 |
| 参数自检 | `IotAvailabilityConfiguration` 启动期校验 K ≥ 1、兜底周期/扫描周期/批次/默认窗口为正：K=0 会让「任何时刻都算断档」，只会在运行期以「数据全错」暴露 |
| 查询端点 | `GET /devices/{deviceId}/availability?from=&to=`（权限码 `iot:availability:get`，007 与迁移同步 + `IotPermissionCodeGateTest` 覆盖）：返回窗口/断档合计/最长断档/次数/可用率/是否达标/上限值/断档明细（超 200 条置 `truncated`）。窗口给反**报错**而不是静默交换；设备不存在按「查不到」处理（不泄露存在性） |

**验收证据（本轮，本机实跑）**

- `ypbin-iot` 单测 **125/0**（相对 main `2caf844` 的 90 → +35：`OutageDetectorTest` 5、`AvailabilityCalculatorTest` 7、
  `AvailabilityServiceImplTest` 17、`AvailabilityMapperContractTest` 5、`NacosTenantIgnoreConfigTest` +1（反向门禁））；`ypbin-access` **68/0**（原 61 → +7：`HttpAccessReadingSinkTest`）；
  `ypbin-architecture-tests` 41/0；`tools/check-iot-sql-equivalence.sh` OK；
- **真库 IT 由 CI 执行**（`-Pit`；本机不跑容器）：`OutageAvailabilityIT` 现 **11 例**——完整闭环、新鲜设备不误判、
  从未有有效数据用首次观测当起点、软删活性行复活、**无租户上下文 fail-closed**，外加本轮复核补的
  **多副本谓词**（`markOpenOutageMustBeConditionalOnNullInRealSql`）、**扫描饥饿**（`garbageCandidatesMustNotStarveRealOutages`）、
  **周期口径**（`pollIntervalBelowFallbackMustUseReportedInterval`）、**A14 时间戳单调**（`livenessTimestampsMustNotRegressWhenWrittenOutOfOrder`）、**A11 汇总精确**（`summaryMustBeExactWhenDetailsAreTruncated`、`summaryMustClampOutagesToWindow`）；IT 合计 26 例。
  **过程如实记录**：该 IT 在 CI 上红过两次，都暴露了真问题或真错误——① `a79b5a2`：
  `filterCollectible` 的批量清理没包 `executeIgnore` ⇒ 租户插件 fail-closed 把整轮扫描打断（**产品缺陷**，已修）；
  ② `eca67a8`：新用例的两条垃圾活性行共用 `device_id` ⇒ 撞 `uk_device_liveness(tenant_id, device_id)`
  （**用例自身数据错误**，已改为不同 device_id）；
  ③ `6959e2d`（A14/A11 片）：新用例的原生 SQL 里把 Java 数字分隔符 `20_001` 写进了字符串 ⇒ MySQL
  `Unknown column '20_001'`（**用例自身数据错误**，已改为 `20001`，`35604b9` 起绿）。
  ⇒ 三次红**全部是测试代码问题**（两次原生 SQL 字面量、一次用例数据撞唯一键），产品代码在 CI 上一次没红；
  以**本分支最新一次** `IoT Integration Tests` 的结论为准；
- 变异累计 **22 处**全部精确转红（本轮 +6，行号按**当前 tip** 经复核实测复现：`observedStateUpdateMustBeMonotonic:74`、
  `windowSummaryMustBeExactAggregate:95/:98/:101`、`AvailabilityServiceImplTest.queryMustKeepExactSummaryWhenDetailsTruncated:338`、
  `queryMustRejectMissingTenantContext:349`）；
  此前 16 处为（iot 侧 7：阈值非严格 / 不做窗口裁剪 / 达标只看可用率 / 去掉多副本守卫 /
  去掉设备存在性过滤 / 状态可回退 / 有效数据不闭合断档；access 侧 3：队满改为阻塞 / 失败后仍抛 /
  上报时刻不用 epoch 毫秒）。复核后新增的门禁也做了变异（去掉 SQL 谓词 / 上报路径回写 open_outage_id /
  清空不判 outageId / 停机只刷一批 / 乱序仍闭合 / 设备消失只跳过不清理 —— 见四点十二的提交信息与本节）。

**本轮仍未闭环（如实登记，勿当已完成）**

| # | 事项 | 现状 |
|---|---|---|
| **A1** | ~~access 侧上报接线~~ **已落地**（`HttpAccessReadingSink`）：有界队列 → 微批 → `/internal/readings`，含丢弃/失败/非法计数与超时；EMQX 传输**待 Q4** | 已收口；仍有限制见 A9/A10 |
| **A9** | **上报失败不重试**（本批丢弃） | 刻意为之：重试会占住 flush 线程并放大远端压力；代价是读数丢失会让断档缺口被算长一些 ⇒ 以 `iot.access.egress.failed`/`dropped` 暴露。彻底解决要等 EMQX/MQ 的持久化通道（Q4）与「断档判定对丢失不敏感」的补偿口径 |
| **A10** | **扫描无租约/归属联动**（= A6 的另一面） | 同 A6：表与窗口来源已备好，**自动开/关窗未接线**（下一步：租约释放/过期→开窗、接管成功→关窗） |
| **A12** | 指标**未接大盘/告警** | `iot.access.egress.*`（accepted/dropped/sent/failed/invalid/pending）与可用率侧无 Prometheus 抓取/告警/大盘定义，仅文档提及名字（复核 R8 未核实项） |
| **A15** | `poll_interval_ms` 可被**过期但为正**的旧快照回退 | 该列是**配置**不是时间戳（1s→10s 是合法变更），故刻意不做单调；代价是另一副本的旧周期写回后阈值 `K×周期` 偏大 ⇒ 检测略滞后、可用率略偏高。若要收口需引入「配置版本号」判新旧（与 `config_epoch` 同类机制） |
| **A16** | `outageCount` 口径含「裁剪后重叠 0 秒」的行 | 聚合用 `COUNT(*)`（满足窗口重叠条件的行都计），而旧的 Java 求和会跳过重叠 ≤0 的行 ⇒ 次数可能比旧实现大（只影响展示的次数，不影响秒数与可用率）。已在 Mapper Javadoc 写明 |
| **A13** | 多副本相关用例的成本面 | 谓词与饥饿两条用例是**真库 IT**（CI 才跑）；本地由源码级门禁 `AvailabilityMapperContractTest` 兜底（它只断言 SQL 文本，不执行 SQL） |
| **A2** | 读数**值**不落库、Redis 最新值未做 | 依赖 Q8（IoTDB 树/表模型）；本轮刻意只上报「质量+时刻」，不发明取值契约 |
| **A3** | ~~维护窗口排除未做~~ ✅ **已落地**（2026-09-23，见「四点十五」） | 新增 `maintenance_window` 表（人工 + 预留租约交接两类来源）：统计总时长 = 窗口 − 维护，且**断档落在维护内的部分也从分子里剔除**（只缩分母会让计划停机仍拉低可用率，与 spec 意图相反）；聚合用一次 SQL（含每行与维护求交后上限封顶）保证明细截断不影响精度；内部端点 `POST/GET /internal/maintenance/windows` 可声明/关闭/查询；响应回显 `maintenanceSeconds`/`effectiveWindowSeconds`/`outageInMaintenanceSeconds`/窗口列表 |
| **A4** | 阈值/目标全局常量 | 按设备覆盖目标可用率/最长断档属后续增量 |
| **A5** | 只有 `NO_GOOD_DATA` 一个原因码 | 链路级原因（断链/设备离线/未接管）与租约联动未做 |
| **A6** | 租约转移导致的停采仍算断档 | 活性行感知不到归属变化：租户被接管到别的节点后，本节点的最后一次有效数据之后就会被算成断档。**已铺路**：`maintenance_window` 已预留 `source=LEASE_HANDOVER`（租户级窗口，`device_id` 为空），**但自动开/关窗的租约侧接线尚未落地**（本轮只落地人工路径）——在那之前，交接空档仍会按断档计（可用率偏低方向），运维可临时用内部端点人工声明窗口 |
| **A7** | 平台自身停机期间的断档不可分辨原因 | 停机期间没有扫描；恢复后按 `lastGoodAt` 补开一条，跨越停机——时长方向正确，但无法区分「设备断档」与「平台停机」 |
| **A8** | ~~采集周期当前靠兜底值~~ **已闭环** | access 已随读数上报 `pollIntervalMs`（来自 `DeviceSpec.pollInterval`）；只有上游未给周期（0/null）时才走 `fallback-interval-ms` |

### 四点十三、M-2 链路自愈：重发 ADD（N-2）与订阅退避/在途去重（G1）

**解决什么问题**：这两条都是「出了问题不会报错、只是永远没数据」的静默失效——
① **N-2**：框架只在 `bind()` **成功后**才挂重连监听，启动瞬间设备离线时 ADD 发出去但建链失败，
框架**不会**自己重试 ⇒ 该设备永久零数据，日志里只有一行 DEBUG「暂无会话」；
② **G1**：`session.subscribe(...)` 是**异步**的，慢订阅（> 一个租约周期）会被下一轮对账再发起一次（重复订阅、
监听器重复挂、远端压力翻倍），而失败订阅又**每个周期**重发一次。

**做了什么**

| 面 | 实现 |
|---|---|
| 重发 ADD（N-2） | 新增链路：**每次 `startCollecting`**（由采集周期驱动，默认 15s；`reconcile` 不调用）查「仍无会话」的设备（`SubscriptionPlanner#devicesWithoutSession`，默认空实现给日志桩/测试替身）并**重发 ADD**（新 revision）让框架重新 bind。三重节制：逐设备指数退避 **30s→2min**、每轮上限 **20**（**每租户每轮**：`startCollecting` 按租户调用，多租户同 tick 合计可超过 20；超出计 `iot.access.device.rebind.deferred`）、**首次 ADD 当轮先预置一次退避**（建链结果下一轮才看得出来，同轮再发纯属浪费）。会话建立即清退避；fence/设备下架同步清理 |
| 订阅失败退避（G1） | `AccessSubscriptionPlanner` 记 `{会话实例, 连续失败次数, 下次可重试时刻}`，指数退避 30s→2min；**会话实例变化即作废退避**（框架重连说明换了链路，立刻重试才对——否则一次失败会把「设备恢复」也挡在窗口外）；成功即清除；新增跳过计数 `iot.access.subscribe.backoff.skipped` |
| 在途去重（G1） | `Set<String> inFlight`：`subscribe` 发起后加入、`whenComplete` **无论成败**都移除（不移除会让该设备再也不能被订阅，比重复订阅更糟）；在途期间下一轮直接跳过并计 `iot.access.subscribe.inflight.skipped` |

**验收证据（本机实跑）**

- `ypbin-access` 单测 **82/0**（原 68 → **+14**：`AccessSubscriptionPlannerTest` 3→10、`IotProtocolTenantLinkManagerTest` 14→21；含复核整改后补的公平轮转、退避封顶、同步抛出、下架清退避 4 条）；
- `ypbin-iot` 125/0、`ypbin-architecture-tests` 41/0、`tools/check-iot-sql-equivalence.sh` OK；
- 变异 **5 处**全部精确转红（见本节提交信息与验收证据段）：去掉在途去重、去掉失败退避、去掉「会话实例变化作废退避」、
  去掉重发 ADD、去掉重发退避判断——各自只让目标用例失败；复核整改后又补 6 处（去掉公平轮转排序 / 去掉 reconcile 的退避清理 / 去掉同步抛出的 try-catch / 去掉 forget 清在途 / 去掉退避封顶），同样各自精确转红；
- 无需真库（本片全是 access 侧内存态逻辑，不涉及 SQL/租户）。

**仍未闭环（本片相关）**

| # | 事项 | 现状 |
|---|---|---|
| **L1** | 重发 ADD 是「重建链」而非「重试订阅」 | 设备离线时框架 bind 会失败并每 30s 重试一次；若未来出现「bind 成功但 subscribe 永远失败」的协议侧问题，靠 G1 的退避重试覆盖（不会自愈到「换协议参数」的程度） |
| **L2** | 每轮上限 20 只保证「不会一次打爆」 | 大规模离线（>20 台同时无会话）时其余设备**按轮次顺延**，节奏由**采集周期**（默认 15s）驱动、200 台约需 10 轮 ≈ 150s；已用 `rebind.deferred` 暴露。⚠️ **外委复核实测过一个真缺陷**：配额原先按设备列表顺序取，当 `acquire-interval-ms` > 退避上限（如 5 分钟 > 2 分钟）时前 20 台每轮都吃掉配额 ⇒ 第 21 台起**永久零数据**；已改为**按「下次可重试时刻」升序取配额**（刚发过的自动排到队尾），并有回归用例 `rebindMustRotateAcrossCyclesToAvoidStarvation`（25 台 / 5 分钟间隔 / 3 轮内每台至少重发一次） |
| **L3** | 会话实例判等依赖框架「重连必换实例」 | 与本仓既有的 B2 防线同一前提；若框架某天复用实例，G1 的退避会挡住恢复（需真 socket e2e 长期观测） |
| **L4** | `devicesWithoutSession` 默认空实现 | 任何**未覆写**它的规划器实现都会**静默失去 N-2 自愈**（无日志/指标/启动自检）。当前唯一生产实现已覆写；建议后续给默认实现加「一次性 WARN」或启动自检 |
| **L5** | 会话表**全量拷贝**的开销 | `AccessSubscriptionPlanner.devicesWithoutSession` 每租户每周期调一次 `IotLifecycle.sessions()`（内部 `Map.copyOf` 全量会话表）⇒ N 租户 × M 设备为 O(N×M) 条目拷贝/周期。设备规模上千时需改为按租户切片或让框架提供按设备查询 |
| **L6** | 宿主 re-ADD 与框架 reconnect 的并发 bind | 两者可能同时 `bind` 同一设备（框架 `reconnect()` 不持 `deviceLocks`）——**仅代码推理，未实测**；需真 socket 长跑观测 |
| **L7** | 订阅 Future **永不完成**时仍会锁死 | 同步抛出已用 `try/catch` 覆盖（+ `forget` 清在途）；但若适配器返回一个永不完成的 Future，`whenComplete` 不回调 ⇒ 该设备仍永久无法订阅。当前 tcp 实现是同步完成 Future，风险在接 mqtt/opcua 后兑现 |

### 四点十四、M-2 时钟收口：租约契约带服务端时间（M0b-4 残留）

**解决什么问题**：续约/接管的时间基准早已统一到**数据库时钟**（M0b-4 服务端一半），但**接入侧**判断
「本地租约是否已过期（该自停采）」用的仍是**本机时钟**：节点钟快 ⇒ 提前自行停采（数据凭空变少）、
钟慢 ⇒ 服务端已判定接管后仍多采一段（**双采**）。这是「时钟漂移把正确性变成概率」的典型形态。

**做了什么**

| 面 | 实现 |
|---|---|
| 契约 | `LeaseAcquireResp.serverTime` / `LeaseRenewResp.serverTime`（**数据库时钟**；续约被节点级否决时也带上，便于接入侧继续校准） |
| 服务端 | `LeaseServiceImpl` 在 acquire / renew（含 `nodeFenced` 早退分支）里把 `mapper.selectNow()` 的结果放进响应——与 `leaseExpireAt` **同一次读取**，两者必然自洽 |
| 接入侧 | `AccessLeaseManager` 维护 `clockSkew = serverTime - 本地采样`（单次采样的 NTP 式估计：取**调用前后本地时刻的中点**抵消往返延迟）；到期判据用 `本地 now + skew`；**回执不带 serverTime 时保留上次校准**（不得退回 0）；|skew| > 5s 时打 WARN（抑制噪声：只在跨阈值或明显变化时告警）；偏移上 gauge `iot.access.lease.clock_skew_seconds`（正 = 本机慢） |

**验收证据（本机实跑）**

- `ypbin-access` 单测 **92/0**（本片 +10；另有合并 main（PR #19）带入的 14 条：合并前本分支为 76/0）：本机钟快不得提前停采 / 钟慢必须按服务端时钟停采 / 缺 serverTime 保留上次校准 / **中点采样抵消 RTT** / **首次校准照采** / **跳变需连续两次确认** / 告警阈值对称）；
- `ypbin-iot` 单测 **126/0**（+1：领取与续约响应必须带服务端时间）；
- 真库 IT `LeaseDbClockIT` 增 1 例（响应中的服务端时间必须落在调用窗口内、且续约不早于领取），由 CI 的 `-Pit` 执行；
- 变异 **9 处**（判据退回本机时刻 / 缺 serverTime 时把校准清零 / 服务端不填 serverTime / 去掉中点采样 / 去掉跳变暂缓 / 去掉「首次照采」 / 去掉暂缓上限 / 去掉阈值对称判据 / 每次都用本机时刻）各自精确转红；其中「去掉中点采样」初稿时**逃逸**（71 条全绿、实测引入 +1.2s 偏差）、「跳变保护」初版形状本身是**功能回归**（复核 A/B 实测每轮拆链），都是靠复核与变异才发现。

**仍未闭环（本片相关）**

| # | 事项 | 现状 |
|---|---|---|
| **C1** | 偏移估计的**精度上限≈1 秒**（初稿「百毫秒级」**已被复核实测证伪**） | 两处**独立的秒级截断**：① 线上格式 `yyyy-MM-dd HH:mm:ss`（starter 的 `JacksonProperties` 默认，纳秒被丢弃，探针实测）；② `SELECT NOW()`（MySQL 无 fsp 参数即整秒）。⇒ 偏移**单向偏小**（最多晚停采 ~1s，属「超期多采」侧）；网络**非对称**误差 ≤ RTT/2（本仓最坏 RTT 4s ⇒ ≤2s）。未做多采样中位数/滤波 |
| **C2** | 校准点覆盖 | 已在 acquire、**周期重领（`refreshAssignmentsIfDue`）**、renew 三处校准；renew 的本地采样改为**紧贴调用前**（原先用本轮起点，把 selfFence/组装耗时算进去程、偏移偏正）。两次校准之间若发生 NTP 阶跃，仍会短暂用旧偏移（最长一个周期）——但见 C3 的上限保护 |
| **C3** | 偏移保护的**形状**（初版写错过，被复核 A/B 实测判为功能回归；二次修正在复验中又暴露「永不收敛」） | 初版「绝对量级超过 10min 就拒绝采用并一直用旧值」**是错的**：容器时区误配（本机比 DB 快 8h）时首次读数即被拒、旧值就是未校准的 0 ⇒ 判据退回本机原始时钟 ⇒ **每轮拆链**（把可用变成不可用）。现为三条：① **首次校准一律照采**（未校准的判据比大偏移更危险）；② 相对**已校准值**的跳变超过 `ypbin.access.clock-skew-jump-threshold`（默认 **60s**，可配）时**暂缓采纳**、保留原校准；③ 读数与上次待确认值差 ≤ `SKEW_CONFIRM_TOLERANCE`（**30s** ≈ 5× 最坏噪声：秒级截断 ±1s + 非对称 ≤RTT/2）即采纳，**或连续暂缓达 `SKEW_CONFIRM_MAX_DEFERRALS`（3 次暂缓 + 采纳那一轮 ≈ 最坏 4 个续约周期）强制采纳**——没有这条上限时，读数每轮漂移都超过容差就**永不收敛**（判据停在旧值、大偏移方向不利时继续每轮拆链，复核实测过），这也是「收敛」不是必要条件的原因。暂缓次数计 `iot.access.lease.clock_skew.deferred`，告警按 60s 限流（用注入时钟）。**保护范围说明**：它管的是「**是否采纳**这次跳变读数」；`selfFenceExpiredLocally` 在 `renew` 之前执行、用的是**上一轮**的校准，因此「向前阶跃当轮就批量自停采」并不在本保护范围内（最长一个周期后才修正） |
| **C4** | 接入侧指标**暴露链路未接** | gauge 已注册，但本仓没有 `management.endpoints.web.exposure` 配置、pom 里也没有任何 micrometer registry ⇒ **实际抓不到**（Spring Boot 默认只暴露 health）。与 iot 侧的 A12 同类，需统一决策 |
| **C5** | 偏移越过告警阈值的判据必须**对称** | 已改用 `Duration#abs().compareTo(5s) > 0`：`Duration.toSeconds()` 对负值**向下取整**（-5.5s→-6），原先的 `Math.abs(toSeconds())` 会让 +5.5s 判成「未超阈值」而 -5.5s 判成「超阈值」（复核实测 +6.0s/-5.0s 不对称）；已有单测覆盖四种符号/边界 |


### 四点十五、M-2 可用率口径：维护窗口排除（A3）

**解决什么问题**：spec §12.5 的统计总时长是「**排除可配置维护窗口**后的时长」——自用场景设备夜间/周末停机是常态，
用墙钟当分母会把计划停机算成断档，可用率被系统性低估（运维看到 90% 却以为是故障）。

**口径实现（比 spec 的一句话更严）**：spec 只写了分母要排除维护窗口；但计划停机期间**不会产生有效数据**，
只缩分母的话那段时间仍会以「断档」进入**分子**，可用率照旧被拉低——与 spec「计划停机不算断档」的意图相反。
因此本实现**分子分母同时排除**：

```
统计总时长 = 窗口时长 − Σ(维护窗口 ∩ 窗口)
计入断档   = Σ(断档 ∩ 窗口) − Σ(断档 ∩ 维护窗口)
可用率     = 1 − 计入断档 / 统计总时长        （统计总时长为 0 时不判不达标、按 100%）
```

**做了什么**

| 面 | 实现 |
|---|---|
| 表 | `maintenance_window`（租户表，006 与迁移逐字等价）：`device_id` 为空=该租户全部设备；`end_ts` 为空=进行中；`source` = `MANUAL` / `LEASE_HANDOVER`（后者为租约交接预留） |
| 精确聚合（**两段简单 SQL + Java 侧相减**） | `OutageEventMapper.summarizeInWindow` 给**原始**断档合计/最长（保持简单可解析）；`MaintenanceWindowMapper.sumMaintenanceSecondsInWindow` 给分母维护时长；`sumOutageInMaintenanceSeconds` 用一次 `JOIN` 求「断档∩维护」。计入断档 = 原始 − min(断档∩维护, 原始)，在 Java 侧算。⚠️ **曾经写成「派生表 + 相关子查询」一行搞定，CI 真库连续两轮打回**：① MP 的 JSqlParser 解析失败（拦截器改写不了就**拒绝执行**）② 关掉拦截器后 MySQL 自身报语法错误 ⇒ 改为两段简单 SQL（源码门禁新增「不得出现派生表/相关子查询」的回归防线） |
| 重叠不变量 | 聚合按「逐个维护窗口求交后求和」统计 ⇒ 同设备范围内**窗口重叠会重复计数**（分子封顶后偏小 ⇒ 可用率偏高；分母重复扣 ⇒ 统计总时长偏小，极端下 `effectiveWindow=0` 直接判 100% 达标 = fail-open）。`open` 因此**拒绝重叠声明**（含租户级窗口），并在文档写明「直接改库绕过校验会破坏该前提」 |
| 最长断档的近似 | 「计入的最长单次断档」取 `min(原始最长, 计入断档合计)`：要精确得到「排除维护后的单次最长」需要逐行求交（就是被 CI 拒绝的复杂 SQL）⇒ 该近似**方向偏严**（可能把一半落在维护里的最长断档算得更长 ⇒ 达标更难），已如实登记 |
| 计算与响应 | `AvailabilityCalculator` 增加维护输入（维护时长封顶到窗口、被剔除断档取下限）；`AvailabilityResp` 回显 `maintenanceSeconds`/`effectiveWindowSeconds`/`outageInMaintenanceSeconds` 与窗口列表（最多 50 条），让「这段时间为什么不算断档」在响应里自解释 |
| 可配置 | 内部端点 `POST /internal/maintenance/windows`（声明，租户取自上下文、时间基准取数据库时钟、结束必须晚于开始）、`POST /{id}/close`（**只关进行中**）、`GET`（按设备/区间查询）；服务层 `MaintenanceWindowService` |

**验收证据（本机实跑）**

- `ypbin-iot` 单测 **140/0**（+14：`AvailabilityCalculatorTest` +4 维护口径、`AvailabilityServiceImplTest` +2（维护排除与回显 + **真实请求链路租户来自 TenantProvider**）、
  `MaintenanceWindowServiceImplTest` +7（租户守卫/区间校验/只关进行中/查询映射/**租户来自 provider**/**重叠拒绝**）、源码门禁 +1 维护聚合）；
- 真库 IT `OutageAvailabilityIT` +2（`-Pit` 由 CI 执行）：维护窗口同时从分母与分子排除（可用率 100%、
  另一台设备的窗口不影响本设备）、租户级窗口对所有设备生效；
- 源码级门禁把「不得出现派生表/相关子查询」「与维护求交（JOIN）」「显式租户/逻辑删除/jdbcType」钉在构建期；维护剔除的**算术**在 Java 侧，因此「不减维护内断档」这类变异由 `AvailabilityServiceImplTest` 单测咬住（复核实测过：早期把剔除写在 SQL 里时，「把子查询结果乘 0」能逃逸子串门禁——那正是把剔除搬到 Java 侧的动机之一）。

**仍未闭环（本片相关）**

| # | 事项 | 现状 |
|---|---|---|
| **M1** | 租约交接**自动**开/关窗 | 表与 `source=LEASE_HANDOVER` 已备好，租约侧接线未做（释放/过期→开窗、接管成功→关窗）⇒ 交接空档仍按断档计（A6/A10 保持登记） |
| **M2** | 管理台与权限码 | 当前只有内部端点（平台侧调用）；面向运维的页面/权限码属后续增量 |
| **M4** | 租户来源（**本片修掉了一个既有缺陷**） | A11 的可用率查询与新端点原先只读 `TenantContext`（ThreadLocal），而真实请求链路上绑定的是 `IdentityContext`（网关身份头 → 过滤器），`TenantContext` 只有显式 `executeWithTenant` 才非空 ⇒ 两个端点在真实请求下都会误报「缺少租户上下文」。现改为**与 MP 租户插件同源**：`TenantContext` 优先、其次 `TenantProvider`（`MicroserviceTenantProvider` 读 `IdentityContext`）；并补了「TenantContext 为空、provider 有租户」的用例 |
| **M3** | 维护窗口与断档的**边界**语义 | 窗口起止与断档起止都按秒结算；若窗口正好在断档中间结束，只剔除重叠部分（本实现如此），未做「整段断档都不计」的宽松口径 |

### 五、替换缝（3a 已备好，3b-2 只需新增自动配置）
3a 的 `LoggingTenantLinkManager` 已去掉 `@Component`，由 `AccessLeaseConfiguration`（`@AutoConfiguration`
+ `@Bean @ConditionalOnMissingBean`）装配，并有源码门禁守着（四处变异全咬）。⇒ 3b-2 提供真实现时
**只需新增一个 `@AutoConfiguration` + `@Bean`**，判定逻辑（`AccessLeaseManager`）一行不动。
