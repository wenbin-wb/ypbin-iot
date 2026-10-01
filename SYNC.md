# 与 admin 基座的同步纪律（YPBIN-IOT SYNC）

> 本仓是 **`ypbin-admin` 的 fork**：把 admin 当基座（admin 此后**只更新基础功能**），
> IoT 业务以**增量**方式加在上面。
> 目标：**任何时候都能把 admin 的新提交同步下来，且合并代价不随时间上涨。**

## 一、同步怎么做

```bash
git fetch --no-tags upstream                # upstream = https://github.com/wenbin-wb/ypbin-admin.git
git log --oneline HEAD..upstream/main       # 看落后了什么
git merge upstream/main                     # 合并（冲突处置见第三节）
mvn -B -ntp -fae clean verify               # 同步后必须重跑门禁
```

- **必须带 `--no-tags`**：本仓继承了 admin 的 `release.yml`（`v*` 标签触发建 Release）。
  若同步时把 admin 的标签拉进来，之后 `git push --tags` 会在本仓给 **admin 的提交**发 Release。
- 只跟踪 **`main`** 一条基线。admin 的 `boot`（单体）与 `feature/miniapp-backend` **不跟**——
  跟得越多，合并面越大（这是显式取舍，不是遗漏）。
- **Dependabot 的 `github-actions` 更新 PR 一律不合**：它改的是 admin 拥有的
  `ci.yml` / `codeql.yml` / `release.yml` / `sync-starter-version.yml`，合进去就破坏了白名单
  （`Sync Whitelist` 门禁会自动把这类 PR 判红）。要升 actions 版本，**去 admin 仓升**，本仓通过同步拿到。

## 二、允许改 admin 的文件（全清单，越少越好）

IoT 代码一律放**新模块/新文件**；下面这些是唯一的例外，改动要尽量是「加法一行」。
**本清单与 `.github/workflows/sync-whitelist.yml` 里的白名单必须保持一致**（改了这里就改那里）。

> **白名单膨胀要记账**：目前 **20** 个文件（2026-10-01 由 19 增至 20：新增 `ypbin-gateway/src/main/resources/application.yml`，理由见下表）。
> 每增加一个都是「以后同步时的潜在冲突点」；
> 加之前先问：能不能用新文件/新模块实现？只能改既有文件时才加，并在提交信息里写明理由。

| 文件 | 改动 | 说明 |
|---|---|---|
| `pom.xml`（根） | `dependencyManagement` 加 `ypbin-iot-api` 一行 | 根 pom 统一管理各 `-api` 模块版本 |
| `ypbin-service/pom.xml` | 加 `<module>ypbin-iot</module> + <module>ypbin-access</module>` | 业务域聚合 |
| `ypbin-service-api/pom.xml` | 加 `<module>ypbin-iot-api</module>` | 契约聚合 |
| `deploy/install.sh` | SERVICES 加一行 + Nacos cfg 清单加 `ypbin-iot` + 同步「共 N 个」计数注释 | 部署脚本的服务清单 |
| `deploy/docker-compose.yml` | 新增 `ypbin-iot` 服务块 | 部署编排 |
| `deploy/nacos/ypbin-gateway.yaml` | routes 加 `iot` 一段（`Path=/iot/**` + `StripPrefix=1`） | 网关路由（IoT 路由也进仓，便于与其它服务同构） |
| `deploy/.env.example` | 端口段注释加 18084 | 环境变量示例（纯注释） |
| `ypbin-architecture-tests/src/test/java/cn/ypbin/admin/arch/SourceConventionTest.java` | `LOOP_DB_EXEMPTIONS` **加一条误报豁免**（类名#接收者.方法 + 理由） | **唯一的上游测试类例外**，理由见下方专条 |
| `ypbin-service/ypbin-ai/src/main/java/cn/ypbin/admin/ai/service/impl/AiModelConfigServiceImpl.java` | `testConnection` 的 HTTP 客户端改为**复用单个实例 + 显式 `HTTP/1.1` + 具名超时常量** | **2026-09-27 加**：修 admin 既有代码里的**缺陷**——该方法**每次调用新建 `HttpClient`**（丢掉连接池、超时策略散落）且**未指定协议版本**（JDK 默认 HTTP/2）。而它的调用形态恰是「新连接 + 带体 POST」，`baseUrl` 又允许明文 `http://` ⇒ 会踩已在 `ypbin-iot` 实测到的 h2c 坑（带体 POST 作为新连接首个请求 ⇒ `EOF reached while reading`）。**为什么不能用新文件**：问题就在这一行上，改的必须是这个既有方法。⚠️ **更好的长期做法是改上游 admin 仓**（本仓下次同步自然继承、分歧面回到 9）——本轮受「改动落在 ypbin-iot」的范围约束才走白名单。 |
| `ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClient.java` | 新增两个**内部端点**契约：`POST /inbox-message-send`（普通站内信）、`POST /mail-send`（纯文本邮件） | **2026-09-28 加**（告警段 C2 通知投递，见 `ALERTING-DESIGN.md` §7.5-M2/M4）：告警投递必须复用 admin 的 `sys_message` 表与 system 侧既有 JavaMail 能力，新增端点只能声明在**既有 Feign 契约**上——Feign 接口是契约文件，无法用「新文件」表达「给既有契约加方法」 |
| `ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClientFallback.java` | 为上面两个新端点补熔断兜底 | 既有 fallback 必须覆盖契约的全部方法（否则熔断时降级空洞），只能改既有文件 |
| `ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/feign/SystemClientImpl.java` | 实现两个内部端点：站内信落库（校验收件人存在且属于声明的租户，§7.5-M4）、邮件发送（复用 `MailService`）；失败如实返回 `R` | 端点实现必须落在既有 `SystemClientImpl` 上（构造器同步注入 `SysMessageMapper`/`MailService`）；「给既有 Feign 实现加方法」同样无法用新文件表达 |
| `ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/mapper/SysMessageMapper.java` | 增「按收件人查站内信」与落库辅助查询 | 站内信必须复用既有 `sys_message` 表与 Mapper（不新造站内信链路），查询方法只能加在既有 Mapper 上 |
| `ypbin-service/ypbin-system/src/test/java/cn/ypbin/admin/system/feign/SystemClientImplUserByIdTest.java`、`SystemClientImplPlatformUserTest.java`、`SystemClientImplLogIngestTest.java` | 构造参数随 `SystemClientImpl` 注入新增，同步补 `mock(SysMessageMapper.class)`/`mock(MailService.class)` | 既有 3 个单测的构造器签名随被测类变化，属**连带更新**（3 个文件共 17 行） |
| `docs/microservice-deployment.md` | 「初始口令」一句话更正 | **2026-09-26 加**：该句原写「Nacos 控制台默认 `nacos/nacos`」，而本仓已改为随机口令 + 开 auth（`NACOS-AUTH.md`）。留着一句**已不成立**的口令说明会误导运维，故只能改既有文件（无法用新文件表达「原句作废」） |
| `README.md` | **整体重写为 IoT 版** | **2026-09-29 加**（M-6）：原 README 是 admin 原版（标题、徽章、截图、功能清单全是基座的），**读起来像另一个项目**。这是「仓库门面」性质的内容，**只能用既有文件表达**——新增 `README-IOT.md` 之类只会让访客仍然先看到错误的那个（GitHub 默认渲染 `README.md`）。同时它是**仓库首屏可信度**问题：README 里承诺的能力与实际不符属对外陈述。**代价如实登记**：README 是上游高频改动文件（徽章/截图/功能表），下一次 `git merge upstream/main` 必然冲突，且**必须整体取本仓版本**（不是逐行合并）——这份代价由「门面必须说真话」换取 |
| `deploy/nacos/ypbin-common.yaml` | 在既有 `ypbin.security.identity` 节下**补一行** `trusted-source-token: ${GATEWAY_SIGN_TOKEN}`（+ 注释） | **2026-09-29 加**（#6b 部署阻塞面，P0）：starter **3.6.0 起**，`ypbin.security.identity.enabled: true`（**本文件既有行**）会强制要求同节的 `trusted-source-token`，缺失即**启动失败**（jar 内 `IdentityAutoConfiguration#identityHeaderFilterRegistration` 抛 `IllegalStateException`）。**为什么不能用新文件**：问题就在**这一行**——`enabled=true` 已经写在这个既有文件里，约束是「给这个既有键补一个兄弟键」；另立 `ypbin-common-iot.yaml` 之类**不会**让既有 Key 的缺配消失（`ypbin-common.yaml` 仍会被 `install.sh` 导入 Nacos，且这是**共享**配置，所有 Servlet 服务都吃它）。**为什么必须进仓而不是只改运行中的 Nacos**：`install.sh` 会把 `deploy/nacos/*.yaml` **整体覆盖**到 Nacos ⇒ 只在实例上手工补键必然在下次重跑时退化（这正是上一轮的临时处置留下的风险）。**代价如实登记**：多一个 merge 冲突点（该文件上游改动频率低，且本次是纯加法一行，代价可接受）。⚠️ **更好的长期做法是改上游 admin 仓**（本仓下次同步自然继承、分歧面回到 18）——本轮受「改动落在 ypbin-iot」的范围约束才走白名单。回归由 `tools/check-identity-config.sh` 在 `Sync Whitelist` 门禁里守住（5 条变异均已实测转红） |
| `ypbin-gateway/src/main/resources/application.yml` | 路由/清洗/免登录段并入本地配置 | **2026-10-01 加**（#11 第 1 批网关侧）：nacos 3.x 服务端脚本化读写 API 已移除（一手实测 GET/POST 全 404）⇒ 网关配置只能随 jar 发布，routes/exclude 必须落在网关既有 application.yml。可审计：若未来 nacos 支持 API，可回退到 nacos 配置（SYNC 分歧面 +1）。长期应反哺 admin 仓（网关工程属 upstream） | 
| `deploy/sql/006-iot-schema.sql`、`007-iot-data.sql` | **新文件** | 全新安装用 |
| `deploy/sql/migration/*-iot-*.sql` | **新文件**（命名必须含 `-iot-`） | 已上线库用；按文件名排序拼接后与 `006+007` **语句等价**（有 CI 校验）。顺序即结构演进顺序：`device-schema` → `lease-schema` → `menu-data` |
| `admin-ui`（后续） | 路由/菜单注册 | 前端增量时再补清单 |

> **为什么必须改这个上游测试文件（唯一豁免来源，不可回避）**：该类的 `LOOP_DB_EXEMPTIONS` 是
> 「循环内 DB/RPC」**误报的唯一出口**——规则按「接收者名以 `Mapper`/`Dao`/`Client`… 结尾」判定，
> 而 IoT 的 `IotDbTimeSeriesWriter#ReadingValueMapper.map` 是**纯词法映射**（读数文本 → 目标列，
> 无任何 IO，见其类注释），被规则误命中。豁免只能写在这个类里，而它是本仓**继承自 admin 的既有文件**
> ⇒ 必然产生一条白名单条目。
>
> **代价可控性证据（本次只动白名单，未放宽任何规则）**：规则正则、`loopDbCallsInLoops(...)`、断言与
> 「豁免消耗检测」（每个豁免键必须真的命中一次循环内调用，否则测试失败）**一行未动**；
> 且该豁免经消耗检测证实**确实命中**（未被命中的失效条目会让测试转红）。
> 换言之，改的是「这个文件允许被改」，不是「这类调用不再报错」——任何**真正**的循环内 DB/RPC 调用
> 仍然照旧转红。要缩小这条特例，只能做真实重构（把映射前置成循环外的纯函数预处理），
> 不能用「改成 stream/换写法绕开正则」这类利用门禁盲区的做法。

**口径说明（两组数字别混）**：`git diff upstream/main --stat` 的字面数字**包含新增文件**
（IoT 业务文件 + SYNC.md + 门禁脚本等），而本纪律关心的只有「**改动的既有文件**」——
用 `git diff --name-only --diff-filter=MDR upstream/main` 看，应当只有上表那几个。

**明确不动**的（改了就会长期冲突）：`ypbin-common`、`ypbin-auth`、admin
的既有 SQL（`001`–`005`）、admin 的既有工作流。
`ypbin-system` 平时同样不动；**2026-09-28 起唯一例外**是上表的
`ISystemClient`/`ISystemClientFallback`/`SystemClientImpl`/`SysMessageMapper` 及其 3 个既有单测——
告警通知必须复用 sys_message 与既有 Feign 契约（条条已在白名单内登记理由），除此之外不许再动。

**部署时的目录名**：`deploy/install.sh` 里有 52 处按 `ypbin-admin/` 目录名拼路径（它原本服务 admin 仓）。
本仓**不改这些行**（改 52 行 = 每次同步都冲突）；部署时把本仓检出到名为 `ypbin-admin` 的目录即可
（`git clone <this-repo> ypbin-admin`），或等 admin 侧把目录名做成变量后再收敛。

## 三、冲突处置

1. `pom.xml` 的模块清单冲突：**两边都留**（admin 加了新域 + 我们的 iot 行）。
2. `deploy/sql/*` 冲突：admin 的 `00X` 编号是**顺序占用**的——若 admin 新增了 `006-*`，
   把我们的文件**改名顺延**（例如 `008`/`009`），并同步更新本文件、迁移脚本名与等价校验脚本里的路径。
3. `deploy/install.sh` / `docker-compose.yml` 冲突：同为「加法行」冲突，两边都留（服务块整体保留）。
4. 共享文件的代码冲突（例如都改了 `ypbin-common`）：**优先接受 admin 的版本**，
   把我们的需求改到 IoT 自己的模块里实现——这样下一轮同步不会再冲突。
5. 有任何冲突：修完后在提交信息里写清「同步 admin@`<sha>` + 冲突处置」，便于下次追溯。

## 三·五、fork 运维须知（不写在代码里会踩的）

1. **Code Scanning 的「结案」状态不随 fork 复制**：admin 仓按误报结案的告警，在本仓首次扫描时会
   重新以 open 出现（本仓建立当天就有 2 条，位于 admin 自己的 `ypbin-system` 里）。
   处置：**镜像 admin 的结论**（同 reason + 同理由引用），不要各判各的——否则两边会漂移出两套结论。
2. **依赖机器人（Dependabot）默认会改 admin 拥有的 workflow 文件**，合入即破坏白名单；
   `Sync Whitelist` 会把这类 PR 判红，正确做法是去 admin 仓升级、本仓靠同步获得。
3. **部署目录名**：`deploy/install.sh` 按 `ypbin-admin/` 目录名拼路径（52 处，不改）；
   部署时把本仓检出成 `ypbin-admin` 目录，或等 admin 侧把目录名做成变量。
4. **同步后要重扫 CodeQL**：同步会把 admin 的新代码带进来，其新增/结案状态同样要按第 1 条镜像处置。

## 四、门禁

- `mvn -B -ntp -fae clean verify`（架构约束 / 源码规范 / 覆盖率等；**admin 没有 spotless 插件**，
  别按别的仓的习惯去跑 `spotless:apply`）。
- admin 的 CI 有「starter 版本必须等于最新 Release」这类硬断言；同步后本仓会跟着它走，
  这是**期望行为**（基座只更新基础功能）。
- IoT 自己的门禁若要新增，**加新文件/新规则**，不要改 admin 已有规则的语义。

## 五、两条机器门禁（纪律的强制力）

| 工作流 | 触发 | 作用 |
|---|---|---|
| `Sync Whitelist` | PR → main、手动 | ① 改动的**既有 admin 文件**必须落在第二节白名单内；② IoT 迁移脚本与全新安装脚本**语句等价**（`tools/check-iot-sql-equivalence.sh`） |
| `Upstream Sync Check` | push main、每周一 03:00 UTC、手动 | **干跑** `git merge --no-commit --no-ff upstream/main`：有冲突即红，把漂移从「将来爆炸」变成「当次就报」；只报告、不推送 |