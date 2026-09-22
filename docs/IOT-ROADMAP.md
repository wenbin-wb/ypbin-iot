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
| **N-2** | **bind 失败后永不重发 ADD**：ADD 只在首次采集时发，而框架仅在 `bind()` **成功后**才挂重连监听（`IotLifecycle`） | 启动瞬间设备离线（建链失败） | 该设备**永久零数据**且不自动恢复，只有日志一次 ERROR | 对「已采集但无会话」的设备在后续对账中**重发 ADD**（或登记为显式限制）；当前仅在 planner 打 DEBUG「暂无会话」 |
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

- `ypbin-access` 单测 **60/0**（原有 37 → 本轮 +23：`ConfigEpochReconcilerTest` 10、`IotProtocolTenantLinkManagerTest` +6、
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
| **G1** | 订阅失败**无退避、无在途去重** | 失败设备每 15s 重发一次（可能打到远端），慢订阅 >15s 会重复发起 —— 未做 |
| **G6'** | 属性标识改名 / 新版本物模型发布**不**推进 `config_epoch` | 点位地址、类型、周期、字节序等变更已覆盖；`identifier` 仅影响读数标签，改名要等下一次变更才对账 |
| **G7'** | 设备「全部停用」路径 | 设备 `status` 没有写入口（`IotDeviceReq` 无该字段），故只能靠设备删除/映射删除触发；`status=1` 过滤仍在 `DeviceSpecServiceImpl` |
| **G8** | 单租户部署（未开 tenant 插件）与「台账无该租户」时**没有变更信号** | `bumpConfigEpochOfCurrentTenant` 无租户上下文即 no-op（设计取舍：不得顺手 insert 一行台账）。**已由周期安全网兜底**：超过 `config-refresh-interval-ms`（默认 5 分钟）未尝试过对账的持有租户会被强制对账、**每 tick 至多 1 个** ⇒ 全量轮转时间 ≈ `max(租户数 × tick 周期, 5 分钟)`（3 租户实测 3 个 tick 全覆盖）。⚠️ **代价如实说明**：安全网按「距上次尝试的时长」触发 ⇒ **信号正常、版本号长期不变的租户同样会被周期强制全量对账**（**不是**「只在信号缺失时才触发」），换来的是「任何漏信号/信号缺失场景最坏一个周期即收敛」；确定信号链路可靠时可调大间隔或设 0 关闭 |
| **R8-2** | **对账可能拖长调度 tick**：对账是串行远端调用（首次观测对全部持有租户各拉一次），单 tick 超过续约 TTL（30s）会让下一轮 `selfFenceExpiredLocally` **批量自我 fence** 并重领，形成抖动；`AccessStartupValidator` 只按「一次租约调用」建模。**安全网既限流又加剧**：它把新增调用限到每 tick 至多 1 次（缓解），但又恒定 +1 次全量 `loadByTenant`（最坏 +6s：Feign connect1+read5）⇒ 越界阈值从约 4 个租户下压到约 3 个（12+18+6=36s > 30s） | 未做（需要 tick 级时间预算/并发上限；或把安全网收敛为「仅对无台账/epoch 恒 0 的租户强制」/调大默认间隔） |
| **R8-3** | `iot.access.config.reconcile.not_applied` 对零设备/持续失败租户**永久递增** | **已修**：同一版本号只计一次；零设备租户仍每 tick 走一次**廉价**重试（`reconcile` 在 `collected == null` 时直接返回 false，不打远端） |
| **R8-4** | `/internal/lease/epochs` **不按节点过滤**：每节点每 10s 拉全平台 assignment × ledger（O(节点数 × 全平台租户)） | 未做（可加 `?accessNode=` 只返回本节点持有租户，或分页） |
| **R8-5** | `fence()` **不清理订阅跟踪**（与 `removeAllDevices` 不对称），`SubscriptionPlanner.forget` 默认空实现无门禁 | 未做；重领后是否漏订阅取决于框架 REMOVE→ADD 是否复用同一 `DeviceSession` 实例（**需真 socket e2e 核实**） |
| **R8-6** | **规格变化路径只重发 ADD**，依赖「框架先解绑再绑定 ⇒ 新会话实例 ⇒ 重新订阅」 | 未做（同上：若框架复用会话实例，新点位永不订阅 ⇒ 静默零数据；需 e2e 核实并登记依赖） |
| **R8-7** | P5 的**平台级不变量无门禁**：`platform_only=1` 与「不进 `sys_template_menu`」只靠人眼（`IotPermissionCodeGateTest` 只校验权限码存在性） | 未做（建议加一条 SQL/源码级门禁，防后续补授把跨租户越权面授给租户管理员） |
| **R8-8** | 「设备/点位变更与版本号**同一事务**」**无自动守卫**：单测用 mock 只能证明「被调用 3 次」；且台账表故障会**阻断设备/点位写入**（可用性耦合） | 未做（可加「bump 失败 ⇒ 业务写入回滚」的真库用例与事务边界门禁） |
| **R8-9** | **安全网的成本与可观测性**：健康系统也每租户每周期多一次全量 `loadByTenant`（见 G8 行的如实说明）；新指标 `iot.access.config.reconcile.forced`/`...not_applied`/`...changed` 已注册但**未进任何文档/大盘**，且 `deploy/nacos/ypbin-iot.yaml` **没有指标暴露配置**（`/actuator/**` 白名单只在 `ypbin-system.yaml`）⇒ 「可观测」目前只是潜在 | 未做（登记指标 + 补 iot 服务指标暴露配置；如需降低安全网成本，可收敛触发条件或调大间隔） |
| **P6/P7/P8/P10** | 台账全置 false 静默回落配置／容量不回收存量／`status` 未参与过滤／`renew/release/markExpired` 取节点行锁的残余死锁面 | 与「四点十」登记一致，本轮未动 |
| **M0b-4 残留** | 接入侧 `AccessLeaseManager` 仍用**本机时钟**做本地过期自停采 | 需租约契约带「服务端时间」，未做 |

### 五、替换缝（3a 已备好，3b-2 只需新增自动配置）
3a 的 `LoggingTenantLinkManager` 已去掉 `@Component`，由 `AccessLeaseConfiguration`（`@AutoConfiguration`
+ `@Bean @ConditionalOnMissingBean`）装配，并有源码门禁守着（四处变异全咬）。⇒ 3b-2 提供真实现时
**只需新增一个 `@AutoConfiguration` + `@Bean`**，判定逻辑（`AccessLeaseManager`）一行不动。
