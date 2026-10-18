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
**本清单与 `tools/sync-whitelist-regex.sh` 保持一致**（2026-10-09 起白名单只有一个来源：
`sync-whitelist.yml` 的「改动的既有文件」检查与 `upstream-sync.yml` 的「干跑合并」检查**共用**它；
改了本清单就改那个脚本，不要再在 workflow 里内联第二份）。

> **判据（2026-10-09 更正）**：`Upstream Sync Check` 的干跑合并要求「**冲突必须 ⊆ 白名单**」，
> 而不是「必须无冲突」——白名单里的文件本仓**必然**有改动 ⇒ 它们与 upstream 冲突是**设计使然**；
> 旧判据因此在 main 上**恒红**（`b70cdd14` 起每个提交都红，2026-10-09 起更正），
> 只会被当噪音忽略。现在：白名单内冲突 = warning + summary 列出；白名单外冲突 = 判红（那才是
> 「分歧面失控」的信号）。

> **白名单膨胀要记账**：目前 **36** 个文件（权威清单 = `tools/sync-whitelist-regex.sh`；本表与它必须逐项一致）。
> 2026-10-18：**加第 25–34 项**（`ypbin-starter` 4.0.0 全量下沉，见下文「一揽子下沉」），本质是「删除 admin 既有文件」，与以往「改一行」的条目性质不同。
> 2026-10-12：**加第 21 项 `CHANGELOG.md`**（本批 deploy/IoT 修复需要写发布台账；无法用新文件表达，
> 与 upstream 冲突时整体取本仓版本，代价同 README.md）。
> 2026-10-09：**移除 2 个死条目**（`deploy/sql/007-iot-data.sql`、`deploy/sql/migration/2026-10-05-iot-platform-alert-schema.sql`）——
> 这两份文件在 upstream **不存在**，而两个门禁的判据都是「与 upstream 的差异」⇒ 它们永远不会被命中，留着只会让计数虚高、
> 并制造「007 既是既有文件又是新文件」的自相矛盾（下方"新文件"行才是它们的归属）。
> 每增加一个都是「以后同步时的潜在冲突点」；
> 加之前先问：能不能用新文件/新模块实现？只能改既有文件时才加，并在提交信息里写明理由。

| 文件 | 改动 | 说明 |
|---|---|---|
| `pom.xml`（根） | `dependencyManagement` 加 `ypbin-iot-api` 一行 | 根 pom 统一管理各 `-api` 模块版本 |
| `ypbin-service/pom.xml` | 加 `<module>ypbin-iot</module> + <module>ypbin-access</module>` | 业务域聚合 |
| `ypbin-service-api/pom.xml` | 加 `<module>ypbin-iot-api</module>` | 契约聚合 |
| `deploy/install.sh` | IoT/access 服务与 Nacos 模板清单 + 本仓加固（见 §三 的逐文件配方） | 部署脚本。2026-10-09 并入 upstream 后：Nacos 配置导入段改用 `SCRIPT_DIR` 解析 + 自动枚举，并保留本仓加固（期望清单缺失告警、零模板 `die`、口令不回显、`MYSQL_PWD` 写法） |
| `deploy/docker-compose.yml` | 新增 `ypbin-iot` 服务块 | 部署编排 |
| `deploy/nacos/ypbin-gateway.yaml` | routes 加 `iot` 一段（`Path=/iot/**` + `StripPrefix=1`） | 网关路由（IoT 路由也进仓，便于与其它服务同构） |
| `deploy/nacos/ypbin-system.yaml` | `xxl-job.access-token` 由**空值**改为**必填占位** | **2026-10-14 加**：xxl-job-core **3.5.0 成对升级**要求 AccessToken 改为**执行器维度且不可为空**（空值会让执行器启动即失败）。该键位于 admin 侧 Data ID 的 `xxl-job` 段，**无法用新文件表达**（Spring 按 Data ID 加载整份配置）。**代价**：与 upstream 同步时该文件冲突**整体取本仓版本**（同 CHANGELOG.md 的既有约定）。 |
| `deploy/.env.example` | 端口段注释加 18084 | 环境变量示例（纯注释） |
| `ypbin-architecture-tests/src/test/java/cn/ypbin/admin/arch/SourceConventionTest.java` | `LOOP_DB_EXEMPTIONS` **加一条误报豁免**（类名#接收者.方法 + 理由） | **唯一的上游测试类例外**，理由见下方专条 |
| `ypbin-service/ypbin-ai/src/main/java/cn/ypbin/admin/ai/service/impl/AiModelConfigServiceImpl.java` | `testConnection` 的 HTTP 客户端改为**复用单个实例 + 显式 `HTTP/1.1` + 具名超时常量** | **2026-09-27 加**：修 admin 既有代码里的**缺陷**——该方法**每次调用新建 `HttpClient`**（丢掉连接池、超时策略散落）且**未指定协议版本**（JDK 默认 HTTP/2）。而它的调用形态恰是「新连接 + 带体 POST」，`baseUrl` 又允许明文 `http://` ⇒ 会踩已在 `ypbin-iot` 实测到的 h2c 坑（带体 POST 作为新连接首个请求 ⇒ `EOF reached while reading`）。**为什么不能用新文件**：问题就在这一行上，改的必须是这个既有方法。⚠️ **更好的长期做法是改上游 admin 仓**（本仓下次同步自然继承、分歧面回到 9）——本轮受「改动落在 ypbin-iot」的范围约束才走白名单。 |
| `ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClient.java` | 新增两个**内部端点**契约：`POST /inbox-message-send`（普通站内信）、`POST /mail-send`（纯文本邮件） | **2026-09-28 加**（告警段 C2 通知投递，见 `ALERTING-DESIGN.md` §7.5-M2/M4）：告警投递必须复用 admin 的 `sys_message` 表与 system 侧既有 JavaMail 能力，新增端点只能声明在**既有 Feign 契约**上——Feign 接口是契约文件，无法用「新文件」表达「给既有契约加方法」 |
| `ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClientFallback.java` | 为上面两个新端点补熔断兜底 | 既有 fallback 必须覆盖契约的全部方法（否则熔断时降级空洞），只能改既有文件 |
| `ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/feign/SystemClientImpl.java` | 实现两个内部端点：站内信落库（校验收件人存在且属于声明的租户，§7.5-M4）、邮件发送（复用 `MailService`）；失败如实返回 `R` | 端点实现必须落在既有 `SystemClientImpl` 上（构造器同步注入 `SysMessageMapper`/`MailService`）；「给既有 Feign 实现加方法」同样无法用新文件表达 |
| `ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/service/impl/SysConfigServiceImpl.java` | 列表页脱敏改为调用 `ConfigValueMasker`（与 internal 端点同一判据同一形态）＋ 三条写路径加「掩码值不落库」守卫 | **第 35 项，2026-10-18 加（#87 脱敏口径统一）**：admin 原实现是「2 个精确键 + 置空 `""`」，而 internal 端点用「6 个后缀 + `****末4位`」⇒ **存在「internal 打码、列表页返回明文」的真实泄露面**（凡命中后缀但不属那 2 个精确键的配置项，管理面列表页会把密钥原文回显）。修复必须改这个既有方法的脱敏实现，无法用新文件表达；同时 `createConfig`/`updateConfig`/`updateGroup` 要识别「提交回来的是掩码」并保留库内真实值（否则管理员改其它字段保存时会把密钥静默覆盖成 `****abcd`）。`ConfigValueMasker` 与其 3 个测试是**新文件**，不在白名单内。**代价如实登记**：上游若再改脱敏逻辑，同步时按语义合并——正确解是「采纳上游演进 + 保留单一判据」，不要恢复旧的精确键清单（那会重新引入泄露面）。 |
| `ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/mapper/SysMessageMapper.java` | 增「按收件人查站内信」与落库辅助查询 | 站内信必须复用既有 `sys_message` 表与 Mapper（不新造站内信链路），查询方法只能加在既有 Mapper 上 |
| `ypbin-service/ypbin-system/src/test/java/cn/ypbin/admin/system/feign/SystemClientImplUserByIdTest.java`、`SystemClientImplPlatformUserTest.java`、`SystemClientImplLogIngestTest.java` | 构造参数随 `SystemClientImpl` 注入新增，同步补 `mock(SysMessageMapper.class)`/`mock(MailService.class)` | 既有 3 个单测的构造器签名随被测类变化，属**连带更新**（3 个文件共 17 行） |
| `docs/microservice-deployment.md` | 「初始口令」一句话更正 | **2026-09-26 加**：该句原写「Nacos 控制台默认 `nacos/nacos`」，而本仓已改为随机口令 + 开 auth（`NACOS-AUTH.md`）。留着一句**已不成立**的口令说明会误导运维，故只能改既有文件（无法用新文件表达「原句作废」） |
| `README.md` | **整体重写为 IoT 版** | **2026-09-29 加**（M-6）：原 README 是 admin 原版（标题、徽章、截图、功能清单全是基座的），**读起来像另一个项目**。这是「仓库门面」性质的内容，**只能用既有文件表达**——新增 `README-IOT.md` 之类只会让访客仍然先看到错误的那个（GitHub 默认渲染 `README.md`）。同时它是**仓库首屏可信度**问题：README 里承诺的能力与实际不符属对外陈述。**代价如实登记**：README 是上游高频改动文件（徽章/截图/功能表），下一次 `git merge upstream/main` 必然冲突，且**必须整体取本仓版本**（不是逐行合并）——这份代价由「门面必须说真话」换取 |
| `.github/workflows/ci.yml` | **两处改动（均针对同一段「starter 版本门禁」）**：① 校验那一步的 `curl` 加 `Authorization: Bearer ${{ github.token }}`（+ `Accept` 头）；② 其后「检出 ypbin-starter」由 `ref: master` 改为 **`ref: v<声明版本>`**（用上一步写进 `GITHUB_ENV` 的 tag） | **① 2026-10-13 加**：GitHub REST API 的**未认证**额度是 60 次/小时/IP，托管 runner 出口 IP 共享 ⇒ 实测 `curl: (22) ... 403` 让该步**随机转红**（同日 main 与 PR 各一次）。随机红的门禁会让人习惯性忽略红，比没有门禁更坏；加同仓库只读 token 后额度 5000/小时。**② 2026-10-13 加（同日）**：该步先断言「pom 声明的 starter 版本 == 最新 Release」，却检出 **master** 编译 ⇒ 「声明的」与「实际编译的」是两回事，master 上有而 Release 里没有的 API 会让 CI **假绿**（审计 issue #233）；改为按 `v<声明版本>` 检出后，CI 验证的就是「消费者真正拿到的那份 starter」。**为什么必须改既有文件**：这两处都在 `ci.yml` 里，另立新工作流无法消除「随机红」与「假绿」。**代价如实登记**：`ci.yml` 属 upstream 既有工作流 ⇒ 今后 `git merge upstream/main` 在该文件上可能出现冲突（预期内，冲突属「白名单内」）。后续若 upstream 自行补上这两处，本条可随之删除 
| `deploy/nacos/ypbin-common.yaml` | 在既有 `ypbin.security.identity` 节下**补一行** `trusted-source-token: ${GATEWAY_SIGN_TOKEN}`（+ 注释） | **2026-09-29 加**（#6b 部署阻塞面，P0）：starter **3.6.0 起**，`ypbin.security.identity.enabled: true`（**本文件既有行**）会强制要求同节的 `trusted-source-token`，缺失即**启动失败**（jar 内 `IdentityAutoConfiguration#identityHeaderFilterRegistration` 抛 `IllegalStateException`）。**为什么不能用新文件**：问题就在**这一行**——`enabled=true` 已经写在这个既有文件里，约束是「给这个既有键补一个兄弟键」；另立 `ypbin-common-iot.yaml` 之类**不会**让既有 Key 的缺配消失（`ypbin-common.yaml` 仍会被 `install.sh` 导入 Nacos，且这是**共享**配置，所有 Servlet 服务都吃它）。**为什么必须进仓而不是只改运行中的 Nacos**：`install.sh` 会把 `deploy/nacos/*.yaml` **整体覆盖**到 Nacos ⇒ 只在实例上手工补键必然在下次重跑时退化（这正是上一轮的临时处置留下的风险）。**代价如实登记**：多一个 merge 冲突点（该文件上游改动频率低，且本次是纯加法一行，代价可接受）。⚠️ **更好的长期做法是改上游 admin 仓**（本仓下次同步自然继承、分歧面回到 18）——本轮受「改动落在 ypbin-iot」的范围约束才走白名单。回归由 `tools/check-identity-config.sh` 在 `Sync Whitelist` 门禁里守住（5 条变异均已实测转红） |
| `ypbin-gateway/src/main/resources/application.yml` | 路由/清洗/免登录段并入本地配置 | **2026-10-01 加**（#11 第 1 批网关侧）：nacos 3.x 服务端脚本化读写 API 已移除（一手实测 GET/POST 全 404）⇒ 网关配置只能随 jar 发布，routes/exclude 必须落在网关既有 application.yml。可审计：若未来 nacos 支持 API，可回退到 nacos 配置（SYNC 分歧面 +1）。长期应反哺 admin 仓（网关工程属 upstream）。<br>**⚠️ 2026-10-08 更正**：本条当时的理由"nacos 3.x 服务端脚本化读写 API 已移除（全 404）"**不成立**——v1 确实全 404，但 **v3 admin API 读写均可用**（`/nacos/v3/auth/user/login` + `/nacos/v3/admin/cs/config` GET/POST，2026-10-08 实测；工具 `tools/set-nacos-flag.py`）⇒ 触发条件"若未来 nacos 支持 API"**已满足**，"网关配置回退到 Nacos"从"不可行"变成"**可行性已成立、决策未做**"。本白名单条目**暂不变**（未回退前仍需改该 application.yml），待另立评估 | 
| `CHANGELOG.md` | 在 `## [未发布]` 段追加本批 deploy/IoT 修复条目 | **2026-10-12 加**：发布台账/对外门面，GitHub 与发布流程只认根 `CHANGELOG.md`，另写一份不会被看到（同 README.md 的理由）。**代价如实登记**：与 upstream 该文件冲突时**整体取本仓版本**（不是逐行合并）。改的是「允许改这个既有文件」，不改变任何门禁判据。 |
| `deploy/nginx-microservice.conf` | 在 `/api/` location 加 `proxy_http_version 1.1` / `Connection ''` / `proxy_buffering off` / `proxy_cache off` | **2026-10-15 加**：SSE 实时流必须关闭代理缓冲，否则事件被攒在 proxy buffer 里、用户看到「实时流不动」。问题就在既有的 `/api/` location 上（无缓冲只能改这个文件），无法用新文件表达。**代价如实登记**：与 upstream 该文件冲突时按语义合并（保留 SSE 三行 + 采纳上游其它改动）。 |
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

### 2026-10-18 一揽子下沉（issue #289/#290）

> 用户明确要求「**不用考虑兼容旧的服务，全部按新版重构**」⇒ 本仓一次性把自建胶水全部换成
> `ypbin-starter` 4.0.0 / `ypbin-iot-starter` 0.4.0 的官方能力，**不作双轨过渡**（旧实例不兼容，需整批发布）。

| 原自建 | 替代（starter） | 相关配置键迁移 |
|---|---|---|
| `system/config/InternalTokenGuardInterceptor` + `InternalTokenGuardWebConfig`（admin 既有） | `ypbin-starter-security` 的 `InternalTokenGuardAutoConfiguration`（ENVELOPE + RAW_401 两种语义） | `ypbin.internal.token` → **`ypbin.security.internal-token.token`**；新增 `guard-enabled`、`raw401-path-patterns: [/internal/mqtt/*]` |
| `iot/config/InternalTokenGuard*` + `InternalMqttTokenFilter` + `InternalMqttGuardWebConfig`（本仓自建） | 同上（RAW_401 语义承接 `/internal/mqtt/*`） | 同上 |
| `InternalTokenFeignConfiguration`（system-api，admin 既有）+ iot-api 的 3 个 `*FeignConfiguration` 里的 token 拦截器 | `ypbin-starter-cloud-core` 的 `FeignHeaderInterceptor`（全局，非「每客户端配置」） | 新增 `ypbin.cloud.feign.internal-token` |
| `gateway/security/InternalEndpointBlockFilter` | `ypbin-starter-cloud-gateway` 的 `InternalPathBlockGlobalFilter`（**默认开启**，大小写 + `%2F`/`%5C` 加固） | 无需新键（如要关：`ypbin.gateway.internal-path-block.enabled=false`） |
| `common/provider/MicroserviceTenant*` + 其在 `AutoConfiguration.imports` 的登记（admin 既有） | `ypbin-starter-extension-tenant` 的 `IdentityTenantProvider` | 无（沿用 `ypbin.tenant.enabled`） |
| `iot/support/IotCsvExportUtils` | `ypbin-starter-tools` 的 `CsvUtils` | 无；**业务配额** `MAX_EXPORT_ROWS` 留在本仓 `IotExportLimits` |
| `iot/realtime/IotSseFanoutCodec` | `ypbin-starter-messaging` 的 `JacksonSseFanoutCodec`（协议 id `json-v1`） | 无 |
| `access/decode/*`（自建 `BytePayloadDecoder` SPI + 注册机 + `DecodeContext`/`DecodeOutcome`/`DecodeFailure`） | `ypbin-iot-core:0.4.0` 的 `cn.ypbin.iot.core.spi.*` + `ypbin-iot-runtime` 的 `DefaultBytePayloadDecoderRegistry` | 失败指标口径改为 `ypbin.iot.errors{type=iot.common.payload.decode-failed.<detail>}` |
| `access/link/ModbusRegisterDecoder` | `ypbin-iot-protocol-modbus:0.4.0` 的同名工具（`decodePair` → `decodeInt32`） | 无 |
| `access/egress/HttpAccessReadingSink` 内手写队列 + `@Scheduled` 微批 + 停机排空 | `ypbin-iot-runtime:0.4.0` 的 `BoundedBatchEgressQueue`（本类只保留业务闸门与指标） | `flush-interval-ms` 语义不变（作为基座的 flush 窗口） |
| `iot/availability/AvailabilityCalculator` + `OutageDetector` | `ypbin-starter-iot:4.0.0` 的**同名类**（`cn.ypbin.starter.iot.availability.*`；归一化后仅版权头/包名/`@Nullable` 标注差异，无计算逻辑差异） | 无（沿用 `AvailabilityRules` 的常量口径） |
| `access/link/ModbusRegisterDecoder` 的**测试侧假绿**（`ModbusStringDecodeTest` 无 import、靠同包解析到旧副本） | 测试显式 import starter 版；旧类已删 | 无 |

> **2026-10-18 收尾（issue #295/#296/#297）**：上表的下沉条目里，`AvailabilityCalculator` / `OutageDetector` /
> `ModbusRegisterDecoder` 这三个「已由 starter 取代」的**业务仓副本**本轮才真正删除（#291 只切了生产路径，
> 副本与测试仍留着）。为防止「下次下沉后又忘记删副本」复现，新增架构门禁
> `DuplicateClassNameGateTest`：**禁止 `cn.ypbin.admin.*` 下出现与 `cn.ypbin.iot.*` / `cn.ypbin.starter.*`
> 同名的顶层类**。该门禁按本仓 pom 声明的版本扫本地仓库制品并带「防空跑」自证，另有一条已知同名豁免
> （`RedisNonceStore`：开放 API 专用 fail-closed 实现，非旧副本）。
>
> **同批次配置键收尾**：`ypbin.internal.token` 在本仓已**彻底清零**（含 `ypbin-access` 本地 `application.yml`
> 与全部文档/模板注释），并新增 `tools/check-deprecated-config-keys.sh` 挂到 fork 自有的
> `sync-whitelist.yml`（**未改 admin 拥有的 `ci.yml`**）。门禁按 YAML 解析后的**键路径**判定，
> 注释里的键名（本仓多处用注释记录「旧键已废弃」的教训）不算命中。

> **2026-10-18 追加（#87 脱敏口径统一）**：白名单加第 35 项 `SysConfigServiceImpl.java`。
> 起因是**真实泄露面**：admin 的列表页脱敏用「2 个精确键 + 置空 `""`」，internal 端点用「6 个后缀 +
> `****末4位`」，两处判据不同 ⇒ 命中后缀但不属那 2 个精确键的配置项（如 `ALIYUN_SECRET_KEY`、
> `DB_PASSWD`）在**管理面列表页明文回显**。本仓 fork 自 admin，此缺陷**照原样继承**（实测本仓
> `SysConfigServiceImpl` 与 admin@origin/main 逐字节相同）。
> 修复分两部分：① 新增**新文件** `service/support/ConfigValueMasker.java`（判据+形态单一来源）及其 3 个测试
> ——新文件不受白名单约束；② 改**既有文件** `SysConfigServiceImpl.java` 让 `toResp` 与三条写路径改调它
> ——这一处只能进白名单。`SystemClientImpl.java` 原本已在白名单（第 15 项），本次同期切换为同一实现。
> **不是「为了少冲突而放过泄露面」**：只有「改既有文件」能消除该泄露面（另建新文件无法替换既有
> `toResp` 的脱敏实现，管理面仍会读到明文）。代价已逐项登记在上表第 35 项。

**整批发布的硬约束（不做双轨）**：
1. **SSE codec id** 由 `iot-json-v1` 变为 `json-v1` ⇒ 混版本滚动期内跨实例扇出双向丢弃；
2. **内部凭证配置键**变了 ⇒ 新旧版本不能共用同一份 Nacos 配置；
3. `ypbin-access` 的 `ypbin-iot.version` 与 `ypbin-starter.version` 必须同批发布（0.4.0 建立在 3.8.0 父链上，业务仓用 4.0.0）。

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

## 三、冲突处置（2026-10-09 实测配方：upstream 5 个提交 / 4 个文件冲突）

> **通则：逐块判断「保留本仓加固」还是「采纳上游改进」，禁止整体 `--ours` / `--theirs`。**
> 整体取 ours 会丢掉上游的部署凭据卫生修复；整体取 theirs 会回退本仓红线（禁静默降级）与凭据卫生口径。

| 冲突文件 | 上游这次改了什么 | 本次处置 |
|---|---|---|
| `pom.xml` | starter 版本 `3.4.0 → 3.7.0` | **取本仓**（本仓已 `3.8.0`，上游那侧更旧） |
| `docs/microservice-deployment.md` | 同一句「初始口令」被改写（补 XXL-JOB 随机口令） | **语义合并**：保留本仓「Nacos 不再有默认口令」口径 + 采纳上游 XXL-JOB 事实 |
| `deploy/docker-compose.yml` | Redis 探活加 `\|\| exit 1`；XXL-JOB 控制台默认只绑回环 | **采纳**（`\|\| exit 1` 是真改进：超时应判失败）；保留本仓更详的探活注释 |
| `deploy/install.sh` | ① Nacos 导入改「`SCRIPT_DIR` 解析 + 自动枚举」；② XXL-JOB 口令随机化 + `sha256` 占位符 + `import_xxl_job_sql` | ① **取上游**（比本仓「目录名候选 + 硬编码 7 服务」更稳）；② **采纳（必需）**——上游已把 `005-xxl-job.sql` 的 admin 口令改成 `${XXL_JOB_ADMIN_PASSWORD_HASH}` 占位符，不采纳则 XXL-JOB 登录不了。**本仓相对上游此文件的偏离（逐条，不是"唯一一处"）**：㈠ 期望的 7 份模板逐份缺失告警（自动枚举发现不了"本该有却缺"）；㈡ 一份模板都没有时 `die`（上游只 warn 后继续）；㈢ 两处口令**不回显**（上游原版会打印明文）；㈣ 函数体 `mysql -p<口令>`（进 argv）改回 `MYSQL_PWD`；㈤ `XXL_JOB_ADMIN_PASSWORD` 键存在但值为空时就地改写（上游只判键存在 ⇒ 新口令落不了盘、库内哈希无从对应明文） |

> ⚠️ 两处理由已被复核纠正（2026-10-09）：① `docker-compose.yml` 的 `|| exit 1` **语义上是 no-op**（`CMD-SHELL` 的退出码本就是 `timeout`/`nc` 的退出码）；采纳它只是与上游保持一致，不要当成"修了漏判"；② `docker-compose.yml` 把 XXL-JOB 控制台默认绑回环，但 `deploy/.env.example` 的 `INTERNAL_BIND_ADDR=0.0.0.0` 会把它覆盖回去⇒「默认只绑回环」仅在不设该变量时成立（要用回环需同时改 `.env`）。

其他条目（沿用）：

1. `deploy/sql/*` 冲突：admin 的 `00X` 编号是**顺序占用**的——若 admin 新增 `006-*`，本仓文件**改名顺延**，
   并同步更新本文件、迁移脚本名与等价校验脚本里的路径。
2. 共享文件的代码冲突（例如都改了 `ypbin-common`）：**优先接受 admin 的版本**，
   把本仓需求改到 IoT 自己的模块里实现——这样下一轮同步不会再冲突。
3. 提交信息写清「同步 admin@`<sha>` + 逐文件处置」，便于下次追溯（本次：admin@`922d0d50`）。

## 三·五、fork 运维须知（不写在代码里会踩的）

1. **Code Scanning 的「结案」状态不随 fork 复制**：admin 仓按误报结案的告警，在本仓首次扫描时会
   重新以 open 出现（本仓建立当天就有 2 条，位于 admin 自己的 `ypbin-system` 里）。
   处置：**镜像 admin 的结论**（同 reason + 同理由引用），不要各判各的——否则两边会漂移出两套结论。
2. **依赖机器人（Dependabot）默认会改 admin 拥有的 workflow 文件**，合入即破坏白名单；
   `Sync Whitelist` 会把这类 PR 判红，正确做法是去 admin 仓升级、本仓靠同步获得。
3. **部署目录名 / admin 检出源**：`deploy/install.sh` 多数路径仍按 `ypbin-admin/` 目录名拼
   （R5：不改这些行）；但 **2026-10-12 起 admin 检出源默认是 fork（ypbin-iot）**：curl 一键安装
   （`bash <(curl ...)`）时脚本把本仓 clone 到 `$ROOT/ypbin-admin`（`YPBIN_IOT_REPO`，GitHub→Gitee
   降级，分支跟 `BRANCH`），与人工执行 `git clone <this-repo> ypbin-admin` **等价** ⇒ 一键安装能装出
   ypbin-iot 全栈（ypbin-iot/access/iotdb 服务 + fork nacos 模板 + 006/007 表/菜单）。纯 admin 基座用
   `YPBIN_ADMIN_REPO=<upstream>` 显式退回。**已有 `$ROOT/ypbin-admin` 若非 fork 检出**且要装含 IoT
   服务的 compose ⇒ 脚本**直接 die** 并给迁移指引（不静默将就半个系统）。
   fork 专属 deploy 资源（`redis-auth-conf.sh` / `iotdb-init.*` / `nacos/ypbin-iot.yaml` / `006+007+迁移`）
   由 `resolve_deploy_src()` 统一解析：脚本自身位置 / `$ROOT/ypbin-admin/deploy` / 已克隆的
   `$ROOT/ypbin-iot/deploy` 优先，否则浅克隆 fork；两路都失败即 die。**纯 admin compose**
   （不含 `ypbin-iot`/`ypbin-access`/`iotdb` 服务）会跳过 fork 资源解析与校验，不误伤该语义。

   > **分歧面记账（2026-10-12）**：admin 检出源默认 fork + `YPBIN_ADMIN_REPO` + `resolve_deploy_src()`
   > 是 `deploy/install.sh` 对 upstream 的又一处**语义级**偏离（不是纯加法）：upstream 的 install.sh
   > 克隆 upstream admin，本仓默认克隆 fork。**代价如实登记**：与 upstream 该文件的 diff 更大、下次
   > `git merge upstream/main` 更可能冲突（该文件本就在白名单内，冲突属预期）；收益是 curl 一键安装
   > 不再是「半个系统」。纯 admin 用户可用 `YPBIN_ADMIN_REPO` 恢复 upstream 语义。
4. **同步后要重扫 CodeQL**：同步会把 admin 的新代码带进来，其新增/结案状态同样要按第 1 条镜像处置。

### 三·六、本仓的发布 tag 政策（2026-10-13 定）

- **只打本仓自己的发布 tag**：`vX.Y.Z` 必须打在**本仓的 release 提交**上（该提交把 `pom.xml` 的 `<revision>` 定成 `X.Y.Z`）。
  制品工作流（`release-artifacts.yml`）有守卫：**`project.version` 必须等于 tag 名且不含 `SNAPSHOT`**，否则拒绝产出制品。
- **不继承 upstream 的 tag**：本仓是从 `ypbin-admin` fork 的，`git tag` 里的 **`v1.0.0` 曾是继承来的 upstream tag**
  （`git log -1 v1.0.0` = 2026-08-28、落后 main **479** 个提交、其 pom 声明 `1.0.0-SNAPSHOT`），与本仓发版无关。
  它已于 **2026-10-13 删除** —— 理由：① 它占着 `v1.0.0` 这个名字，会**直接挡住本仓首个真正的 1.0.0 发版**；
  ② 「Release 资产」这种形态天然被当成可部署产物，挂在 479 个提交之前的代码上是**误导**。
- **upstream 的 tag 同步进来时**：不要沿用，按上一条处理（删除或改名）；`upstream-sync` 只处理分支合并，不处理 tag。
- 已发布/待发布版本的唯一事实源：本仓 `CHANGELOG.md` + GitHub Release（不是 tag 列表）。

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