# 更新日志

> **维护纪律（2026-10-13 确立）**
> 1. **行为变更与破坏性变更必须随 PR 写进 `## [未发布]`**（这是本文件的唯一用途：让升级的人不必读全部提交）。
> 2. **发版时**把 `[未发布]` 折叠为 `[X.Y.Z] - 日期`，并在 GitHub Release 里引用该段。
> 3. **历史积欠不回写**（本段此前长期未按批更新，导致与 git log 脱节）；更早的历史**以 `git log` 为准**，
>    需要追溯能力引入时间时查提交，不要指望本文件覆盖全部历史。
> 4. 条目要写清**动机与边界**（为什么这么做、不做什么），不只写"改了什么"。

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 变更（2026-10-18 批次四）· 消费 starter 4.1.0 / iot-starter 0.4.1（#100/#101/#103 + #37/#38/#39）

> 依赖仍是 `ypbin-starter 4.0.0` / `ypbin-iot 0.4.0`（**未改版本号**）；本批是**代码侧提前消费**，
> 构建与验证用 `-Dypbin-starter.version=4.1.0-SNAPSHOT -Dypbin-iot.version=0.4.1-SNAPSHOT` 覆盖。
> 整批切版本时这些改动即生效。

#### starter #100 · 日志/埋点跨服务上报上收到基座
- **删除 4 个自建类**：`RemoteLogAutoConfiguration` / `RemoteLogDao` / `RemoteTrackAutoConfiguration` /
  `RemoteTrackEventSink`（已由 starter 4.1.0 的同名实现 + `@ConditionalOnBean(..IngestClient)` 承担）。
- **新增 2 个 SPI 实现**：`SystemRemoteLogIngestClient` / `SystemRemoteTrackIngestClient`
  （只做「怎么调 system」）；新增 `RemoteIngestClientAutoConfiguration` 注册它们与两个 provider。
- **行为不变**：失败语义逐字保留——传输异常记完整堆栈后原样上抛；业务码失败显式构造异常后上抛并记 ERROR。

#### starter #101 · 权限数据源继承基类
- `IotPermissionProvider` 由 `implements PermissionProvider` 改为 `extends AbstractPermissionProvider`，
  删本类的 `resolveUserId`（已上收）。**虚拟主体分流原样保留在 `permissionsOf/rolesOf` 覆写里**
  （`OpenApiPrincipal.isVirtualPrincipal` ⇒ 取 scopes 不查库；虚拟主体一律无角色）。
- 新增边界用例：`loginId` 为 null/非数字/空白 ⇒ 空权限且**不触碰 SysCache**。

#### starter #103 · OpenApi 配置迁 `@ConfigurationProperties`
- 新增 `OpenApiProperties`（`ypbin.openapi`，含 `PREFIX` 常量），`OpenApiKeyService` 的 4 处 `@Value` 归零。
- 🔴 `secret-pepper` 是真实凭据：属性类**不覆写 `toString()`**、不进任何日志；默认值给空串（fail-closed 不变）。

#### iot-starter #38 · revision 由框架分配 + 删宿主 bind 重试
- `AccessDeviceRegistry` 的 `Map<String, AtomicLong>` 换成框架 `DeviceRevisionAllocator`；
  7 处 `new DeviceChange(...)` 改为 `registry.dispatch(type, device)`（一步分配 + 构造）。
- **删除**宿主自建的 bind 失败重试（`REBIND_BACKOFF_BASE/MAX`、`REBIND_MAX_PER_CYCLE`、`rebindBackoff`、
  `retryDevicesWithoutSession()`、`backoffDelay()`）——框架 `DeviceBindRetrier` 已覆盖**含同一个饿死修复**。
- `iot.access.device.rebind` / `.rebind.deferred` 指标随实现删除，`docs/METRICS.md` 标明「已移除 + 改看框架侧」。

#### iot-starter #39 · 清理 + 口径
- **删 `MqttBinaryNumberDecoder`（235 行）+ `AccessDecodeProperties` + `AccessDecodeConfiguration`**：
  该解码器在本仓**双重不可能生效**——① `mqtt-binary.enabled` 默认 false；② access 模块**不依赖**
  `ypbin-iot-protocol-mqtt`（只有 tcp/modbus）⇒ MQTT 设备根本连不上，235 行纯死代码。
  将来接入 MQTT 时按 SPI 声明 `@Bean BytePayloadDecoder` 即可。
- **删重复的 `BytePayloadDecoderRegistry` `@Bean`**：框架 `IotAutoConfiguration#iotBytePayloadDecoderRegistry`
  已自动装配同一个（收集宿主 `@Bean` 解码器、保持 `@Order`）。
- 两处手写 `1L << Math.min(...)` 指数退避改调框架 `ExponentialBackoff`（算法逐字等价，已 javap 核对）。
- `docs/METRICS.md` 写死 `iot.access.egress.dropped` 与 `ypbin.iot.errors{type=iot.common.egress.dropped}`
  的关系：**同一事件的两个视角**（框架侧统一口径 + 宿主侧大盘口径），双记是刻意的，判据是「同值同步增长」。

#### iot-starter #37 · 订阅编排下沉（**含 4 处语义差异，详见** `docs/SUBSCRIPTION-DOWNSTREAM.md`）
- `AccessSubscriptionPlanner`：**639 → 495 行**（其中约 200 行 javadoc）。分流、地址展开、在途去重、
  部分成功补订、按设备退避全部交给框架 `SubscriptionOrchestrator`；宿主只留
  「哪些设备归我 + 点位→地址映射 + 读数出口接线」。
  ⚠️ **未达 issue 估计的 <150 行**（lead 已确认接受：不为压行数牺牲注释）。
- **行为变化清单（逐条）**：
  1. 退避窗口内 `subscribe()` 返回值仍为 0、`session.subscribe` 仍零调用（**行为不变**，已加用例钉住）；
  2. **地址扩展失败**（如 `word_count=2` + 传统 5 位编号）：迁移前「整设备跳过、返回 0」；
     现在「该点位记 failure、设备级算发起、返回 1」。**采集结果一致**（都不订该点位、也不订基址）；
     宿主在 `PARTIAL` 分支记 WARN + 按地址级累加 `subscribe.failure` 保证可观测。
  3. `subscribe.failure` **保持地址级口径**（未改设备级，避免看板弱化故障）。
  4. ~~**key-value 形态的 TCP 仍由宿主退避**（技术债）~~ → **已闭环（2026-10-19）**：
     iot-starter `release/0.5.0` 反哺 `SubscriptionAddressingPolicy`（协议级默认 + 按形态覆盖表 +
     形态解析器），`TcpAdapter` 现在按 `payload-format` 细分声明（`key-value ⇒ BATCH`、
     其余 ⇒ `PER_ADDRESS`）。宿主据此**删除**了 key-value 退避表、`subscribeKeyValueOnce()`、
     `tcpKeyValuePayloadFormat` 分流字段：`AccessSubscriptionPlanner` **495 → 383 行**，
     且 `grep "session.subscribe" ypbin-service/ypbin-access/src/main` = **0**（宿主不再直调协议层）。
     key-value 仍一次多地址（框架 BATCH）、binary 仍逐点位（框架 PER_ADDRESS）；退避改由框架
     `SubscriptionBackoffPolicy` 承担。详见 `docs/SUBSCRIPTION-DOWNSTREAM.md` §三·④。
### 变更（2026-10-18 批次）· 全量切到 starter 4.0.0 / iot-starter 0.4.0（#289/#290）

> 用户决策：「**不用考虑兼容旧的服务，全部按新版重构**」⇒ 一次性删除全部自建胶水，**不做双轨过渡**。
> 逐项映射与整批发布约束见 `SYNC.md`「2026-10-18 一揽子下沉」。

- **内部调用凭证守卫改为 starter 官方装配**：删除 `ypbin-system` 的自建 `InternalTokenGuardInterceptor/WebConfig`
  （admin 既有）与 iot 的 4 个自建类（含 MQTT 入站的原始 401 过滤器），改由
  `ypbin.security.internal-token.guard-enabled=true` 装配（ENVELOPE `/internal/**` + RAW_401 `/internal/mqtt/*`）。
  **配置键迁移**：`ypbin.internal.token` → `ypbin.security.internal-token.token`；出站携带改由
  `ypbin.cloud.feign.internal-token` 全局拦截器完成（删除 system-api 与 iot-api 的 4 处手写拦截器）。
  ⚠️ 旧键不再被读取，**新旧版本不能共用同一份 Nacos 配置**。
- **网关内部路径阻断改为 starter 官方过滤器**：删除 `InternalEndpointBlockFilter`，用默认开启的
  `InternalPathBlockGlobalFilter`。**安全增强**：自建实现是大小写敏感的前缀匹配，`/iot/Internal/**`、
  `/iot/internal%5C…`、`/iot/internal%2F…` 三类变体可绕过；starter 全部拦截并在响应里返回统一 404 信封
  （自建实现只写状态码、无响应体）。
- **租户来源改为 starter 的 `IdentityTenantProvider`**：删除 `ypbin-common` 的 `MicroserviceTenantProvider` /
  `MicroserviceTenantAutoConfiguration` 及其 `AutoConfiguration.imports` 登记（只删该行，RemoteLog/RemoteTrack 保留）。
- **CSV 导出改为 `ypbin-starter-tools` 的 `CsvUtils`**：删除 `IotCsvExportUtils`；BOM / RFC 4180 / 公式注入中和
  逐项等价（已实测），**业务配额**（单次 10000 条）移至 `IotExportLimits`。
- **SSE 扇出 codec 改为 starter 的 `JacksonSseFanoutCodec`**：删除 `IotSseFanoutCodec`。
  ⚠️ **协议 id 由 `iot-json-v1` 变为 `json-v1`**：混版本滚动时跨实例扇出双向丢帧 ⇒ 必须整批发布。
- **接入层解码 SPI 改为 `ypbin-iot-core:0.4.0` 官方契约**：删除自建的 `BytePayloadDecoder` / 注册机 /
  `DecodeContext` / `DecodeOutcome` / `DecodeFailure`（`DecodeContext` 形态变为 `(deviceId, ProtocolCode, rawAddress, connectionId)`）；
  失败指标口径随之对齐：`iot.access.payload.decode.failure{reason=<短码>}` → **`ypbin.iot.errors{type=iot.common.payload.decode-failed.<detail>}`**。
  MQTT binary 参考实现保留并改用官方 SPI。
- **Modbus 寄存器解码改用 `ypbin-iot-protocol-modbus:0.4.0`**：删除自建 `ModbusRegisterDecoder`（`decodePair` → `decodeInt32`），
  45 条宿主侧矩阵用例改为直接对官方实现断言（**证明迁移后字序/符号/UTF-8/位域/NaN 口径不变**）。
- **读数出口队列改用 `BoundedBatchEgressQueue`**：`HttpAccessReadingSink` 的手写 `ArrayBlockingQueue` + `@Scheduled` 微批 +
  `@PreDestroy` 循环排空全部删除，改由基座承担（非阻塞 offer / 单消费线程 / 停机循环排空 / 关闭后不再静默接受）；
  业务闸门（原始字节、缺时刻质量、设备号非数字）与 `iot.access.egress.*` 指标口径**逐字保留**。
- **依赖升级**：`ypbin-iot.version` 0.3.3 → **0.4.0**（新增 `ypbin-iot-protocol-modbus` 依赖）。

### 修复（2026-10-18 批次二）

- **`HttpAccessReadingSinkTest` 断言时序竞争（issue #292，本仓 #291 引入的 flaky）**：出口换成
  `BoundedBatchEgressQueue` 后投递是**异步单消费线程**，`pendingCount()==0` 只表示「队列已取空」，
  不表示「本批已投递完成」——消费线程此刻还在 `deliver(batch)` 里，指标尚未累加。测试改为
  `awaitMetric(...)` 等指标到位再断言（实测：只等队列空时 200 轮有 8 轮读到 0；加等待后 0 轮）。
- **架构门禁 SAX 断言依赖系统 Locale（issue #293 缺陷 1）**：`IotMapperAnnotationXmlGateTest` 原断言硬编码
  英文 `XML document structures must start and end within the same entity`，而 JDK 的 Xerces 异常文案**按 Locale 本地化**
  （zh_CN 下为「XML 文档结构必须从头至尾包含在同一个实体内。」）⇒ 中文机器构建必红。改为匹配「类名 + 结构未闭合特征」，
  中英文两种文案都命中，不依赖 Locale。
- **子 JVM 用例在 Windows 触发 `CreateProcess error=206`（issue #293 缺陷 2）**：`IotDbDriverRegistrarTest`
  原本把整条 `java.class.path` 拼在命令行上，Windows 单条命令行上限 8191 字符 ⇒ 长类路径直接起不来。
  改为写 Java 9+ 的 `@argfile`（JVM 自己读取，无长度上限），并在 `finally` 中清理临时文件。

### 修复（2026-10-18 批次三 · issue #295/#296/#297）

- **删除下沉后遗留的业务仓副本 + 新增同名类门禁（issue #295/#296）**：
  - `ypbin-access` 的 `cn.ypbin.admin.access.link.ModbusRegisterDecoder` 删除（生产路径 #291 起已 import
    `ypbin-iot-protocol-modbus:0.4.0`，本仓该文件**零引用、只剩测试在测**）。同时修掉一处**假绿**：
    `ModbusStringDecodeTest` 原来**无 import**、靠同包解析到本仓旧副本 ⇒ 它绿了不代表生产路径（starter 版）
    正确。现已显式 import starter 版并核对断言（starter 版含 `decodeStringUtf8`，冻结口径一致）。
  - `ypbin-iot` 的 `cn.ypbin.admin.iot.availability.AvailabilityCalculator` / `OutageDetector` 删除
    （与 `ypbin-starter-iot:4.0.0` 同名，归一化后仅版权头/包名/`@Nullable` 标注差异，**无计算逻辑差异**）；
    `AvailabilityServiceImpl` 改 import `cn.ypbin.starter.iot.availability.*`，两个口径测试类同步迁移。
  - **新增架构门禁** `DuplicateClassNameGateTest`（`ypbin-architecture-tests`）：禁止 `cn.ypbin.admin.*` 下出现与
    `cn.ypbin.iot.*` / `cn.ypbin.starter.*` **同名**的顶层类，按本仓 pom **声明版本**扫本地仓库制品（不扫历史版本）；
    带三处防「空跑绿」自证（类数下界、三个锚点制品、豁免清单消耗检测）。已知同名豁免仅 `RedisNonceStore`
    一条（开放 API 专用 fail-closed 实现，非旧副本，理由写在代码里）。
  - **新增的口径冻结用例**：`AvailabilityCalculatorTest#availabilityBetweenNinetyNineAndTargetMustFail`
    钉住「可用率 0.992 ⇒ 不达标」。这条用例来自变异验证发现的**真实测试缺口**（见下）：
    原用例的期望要么引用 `AvailabilityRules.TARGET_AVAILABILITY`（跟随常量）、要么离目标值很远，
    把 starter 的 `TARGET_AVAILABILITY` 由 0.995 改成 0.99 后业务侧**全部用例仍然绿**。
- **清理已废弃配置键并加门禁（issue #297）**：删除 `ypbin-access` 本地 `application.yml` 的
  `ypbin.internal.token` 两行（starter 4.0.0 起**无任何消费方**）。留着它的真实危害是误导运维——
  以为配在这里就能出站携带凭证，实际必须配 `ypbin.cloud.feign.internal-token`，漏配的后果是 iot 的
  `/internal/**` 守卫 **fail-closed 全拒**（租约与读数上报全挂）。
  新增 `tools/check-deprecated-config-keys.sh`（**按 YAML 解析后的键路径**判定，注释里的键名不算数）
  并挂到 fork 自有的 `sync-whitelist.yml`（**不动 admin 拥有的 `ci.yml`**，零同步冲突面）；
  仓内残留的旧键说明（`docs/*.md`、`deploy/nacos/*.yaml` 注释）一并更新为迁移后的真实键名。

### 破坏性变更

- **上述条目全部不向后兼容**（类被删、配置键改名、指标改口径、SSE codec id 变更）⇒ **必须整批发布**，
  不接受新旧实例混跑；回滚同样需整批回滚到上一个 Release。

### 变更（2026-10-15 批次）

- **告警支持真 AND 复合条件**：一条规则的多个点位条件构成**同一设备的合取条件组** —— 组内全部条件对同一轮最新值都越界才触发；任一条未越界即不触发；任一条不可判定（无最新值/陈旧/非数值/坏质量/条件非法）且无未越界时整组本轮不可判定（不触发也不恢复，不把「读不到」伪装成「未越界」）。恢复口径为**合取被证伪**：任一条可判定条件越过其恢复门槛（含回差）即收口，避免坏点位钉出僵尸告警。去重键由 `rule:device:property` 收紧为 `R:rule:device`（一条规则对一台设备至多一条活动实例；断档键 `OUTAGE:` 不变），实例新增 `condition_snapshot` 列承载逐条件「阈值 vs 读到多少 vs 判定」，通知正文多条件时逐条件列出（**不新增通知明细行**）。
  - ⚠️ **行为变更**：条件行数 > 1 的规则从「任一条越界即触发」变为「全部越界才触发」。升级前请按 `docs/ALERTING-DESIGN.md` §7.9.8 的自查 SQL 核对受影响规则。
  - 含一次性数据迁移（同 (规则,设备) 多条旧活动实例收口为一条，`reason=CONDITION_GROUP_MERGED`）与回滚脚本；**接口/权限码零改动，单条件规则行为逐字不变**。
- **批量命令下发**：新增 `POST /iot/devices/commands/batch`（一个命令模板 + `deviceIds`，上限 `ypbin.emqx.command-batch.max-devices` 默认 50，`total-budget-ms` 默认 30000 为「是否继续下发」的判定阈值），权限沿用 `iot:debug:send`，返回**逐设备结果**（部分失败逐条如实返回，不整体 4xx）；幂等键按设备派生 `<clientRequestId>:<deviceId>`，**单台 `send()` 契约与行为不变**（抽共用出口 `sendWithModel`）。
  - 语义提示：`accepted`=平台已受理（实例已落库、可按 requestId 重发）与 `ok`=设备确实收到是两个字段；停用设备在批量下**被拒**（与单台端点不同）；无新表、无新权限码。

### 变更

- **Release 离线镜像 tar 资产改为按需产出（默认关闭）**：常规 tag 发版只产 jar/SBOM/GHCR digest；离线 `*.tar.gz` + `images.manifest.sha256` 仅在手动触发并勾选 `build_image_tarballs=true` 时产出（避免每次 Release 多约 700MB）。见 `docs/OPS-RELEASE-IMAGES.md` §10。
### 新增（2026-10-14 批次）

- **消息跟踪二批（issue #221）**：`iot_mqtt_ingest_receipt` 增 6 列（`receipt_status`/`received_count`/`persisted_rows`/`persisted_at`/`discard_reason`/`discard_detail`）+ `(tenant_id, device_id, create_time)` 索引；**整批被拒改为写 `rejected` 行**（此前零痕迹），幂等预查只认 `accepted`、命中 `rejected` 物理删除后按新批次继续；追踪接口新增阶段 `up-persisted`/`up-discarded` 与条目 6 字段。
- **命令点位映射（issue #224）**：`ref_type=command` 可在创建/校验/查询被接纳，`service_call` 下发按唯一启用映射携带 `point{rawAddress,addressType,rw}`；属性读数/设备规格/孤儿巡检显式排除命令点位。
- **影子 desired 下发（issue #225）**：`property_set + writeDesired=true` 且命令**投递成功**后合并写 `iot_shadow.desired`（`desired_ts` 只前进）；离线不写（不制造假期望），`delta` 读时计算、类型宽容。
- **后台任务分布式互斥锁（issue #220）**：新增 `cn.ypbin.admin.iot.lock`（Redis Lua 抢/续/删 + 应用层租约 + 看门狗），6 个单实例任务纳入互斥；保留清理的 JVM 闸门升级为**跨实例锁**（新增跳过原因码 `lock-held-by-other`）。
- **宿主可插字节载荷解码 SPI（issue #223）**：新增 `cn.ypbin.admin.access.decode`（`BytePayloadDecoder` + 注册表 SSOT + 失败原因码），未注册时行为与今天逐字一致（出口闸门 + `egress.invalid`），注册后解出值走既有规范化/落列；含 MQTT binary 参考实现（默认关闭）。
- **迁移登记表与升级运行器（防升级静默漏迁移）**：`iot_schema_migration` 登记表 + `deploy/migrate.sh`（`--status`/`--dry-run`/apply/`--baseline`；校验和不一致判红且不自动重放）；`install.sh` 双写账本。**背景**：dev 实测发现 4 个迁移静默未应用导致新 jar 直接 `Unknown column 'word_count'`。
- **Release 制品一键校验（issue #234）**：`deploy/verify-release-artifacts.sh`（sha256 校验 + 断言 6 jar + 可选 git sha 比对）。
- **镜像制品化（issue #234 剩余项）**：`release-artifacts.yml` 新增 `images` 作业——**复用已构建 jar（不重复编译）**，推 **GHCR**（`ghcr.io/<owner>/ypbin-<service>`，tag `<version>`/`<release-tag>`/`latest`）并读回 registry digest；同时 `docker save` 产 6 个 `*.tar.gz` + sha256 作为**无外网部署机的兜底**。Release 新增 `images.digests.tsv`（digest + image id）与 `images.manifest.sha256`；部署侧新增 `deploy/verify-release-images.sh`（tar sha256 + digest + `--load` 时核对**镜像内 /app/app.jar == jar 清单**）。失败**显式判红**（登录/推送/构建/读 digest 各有修复指引），不静默跳过；方案对比与权限/可见性见 `docs/OPS-RELEASE-IMAGES.md`。
- **依赖漏洞门禁（issue #237）**：新增 `dependency-vuln-gate.yml`（OWASP Dependency-Check，CVSS ≥ 7 判红；NVD 库按周缓存；空库/冷缓存不静默放行，且失败**自解释**）；`deploy/dependency-check-suppressions.xml`。
- **可观测栈与 EMQX 能力验证（issue #222/#218/#219）**：`deploy/observability/`（Prometheus + Grafana + Alertmanager + 只读 actuator 桥接器 + 8 条规则 + promtool 单测）；`deploy/emqx/` 收紧配置与隔离实验床、集群方案文档。
- **上线手册收紧（实测驱动）**：`C2.4` 更正 SDK 指标名（`iot.tcp.*` 实际记在 **`ypbin.iot.errors` 的 `type` 标签**，且宿主可能未启用桥接）；`B.2` 迁移硬步骤（换 jar 前 `migrate.sh --status`）。

### 修复

- **SDK 指标桥在真实宿主从未生效**（`ypbin-iot-starter` 仓）：Boot 4.1.1 中定义 `MeterRegistry` 的是 `CompositeMeterRegistryAutoConfiguration`，原 `afterName` 锚错类且 `@ConditionalOnBean` 在装配期求值 ⇒ 宿主有 actuator 也回退 Noop、**框架指标全部丢弃**（dev 实测每次启动 WARN）。已改为 `ObjectProvider` 实例化期解析（starter 仓修复；本仓需把 `ypbin-iot.version` 升到含该修复的版本后生效）。

### 运维/部署

- dev 环境完成**成对升级**（iot + access 新 jar + `payload-format=key-value` + 4 个缺失迁移），核验：健康 UP、`subscribe.success=1`（每设备一次）、`egress.accepted` 与 `write.rows` 同步增长、Redis 最新值新鲜、落列错误指标全 0、保留清理端点 `executed=true`。部署回执见 dev `/root/deploy-receipt-20261014.md`。

### 新增

- **数据保留清理：运维开关语义 + 手工触发内部端点（issue #227）**（`ypbin-service/ypbin-iot/**`、
  `deploy/nacos/ypbin-iot.yaml` 注释、`docs/DEPLOY-BACKEND.md` §6、`docs/IOT-PLATFORM-DESIGN.md` 端点清单）：
  新增 `POST /internal/retention/cleanup`（`/internal/**` 家族，沿用 `X-Internal-Token` 守卫，**不加权限码**）——
  **只跑一轮、同步返回真实结果**：`executed` / `skipReason`（`none`/`disabled`/`invalid-config`/`already-running`）/
  各表删除行数 / `durationMs`；删除阶段异常**直接上抛**（HTTP 200 + `R.code=500`，不吞）。
  服务层用 JVM 内 `AtomicBoolean` 防重入（定时任务与手工触发共用同一 `cleanupOnce()`；并发触发后到者返回
  `already-running`，**不并发删两轮**；失败也释放闸门）。该端点纳入既有 `ypbin.retention.enabled`：
  关闭时**不执行**并如实返回 `skipReason=disabled`（不静默成功）。`RetentionCleanupResult` 增 `skipReason`/`durationMs`，
  新增稳定原因码枚举 `RetentionSkipReason`。**边界如实登记**：防重入只覆盖单实例（多副本需分布式锁，本轮未做）；
  清理不可回滚、只删已闭合行（`end_ts IS NOT NULL`）、跨界行保守留到下一轮；IoTDB 原始时序保留由 TTL 承担，不在本端点范围。
- **Modbus 寄存器解码 v4：`register_format=double`（IEEE754 双精度，4 寄存器）**
  （`ypbin-service/ypbin-access/**`、`ypbin-service/ypbin-iot/**`、`ypbin-service-api/ypbin-iot-api/**`、
  `deploy/sql/**` 注释口径、`docs/VALUE-DECODE-DESIGN.md` §4.7）：
  声明用**新取值 `double`**，不靠 `float` + `word_count=4` 推断精度（声明与宽度一一对应 = 不猜）；
  `word_count` 必须 4、`signed` 必须 false、`bit_*` 必须 null、`data_type` 必须 `decimal`、
  仅 Modbus `holding`/`input` 且需显式 `type:offset` 地址。
  字序与 v1/v2 同口径：`big`=ABCD（高字在前）、`little`=DCBA（**整体翻转**）；不支持 BADC/CDAB 字内字节交换中间排列。
  有限值产出 **`BigDecimal.valueOf(d)`**（最短可回读，round-trip 恒等；不用 `new BigDecimal(double)` 的 17 位噪声）；
  `NaN`/`±Inf` ⇒ 点位 BAD + `iot.access.value.normalize.failure{reason=non-finite}`（与 float32 共用，不新增指标）；
  复用 v1/v2 的泛化配对暂存（任一寄存器 BAD/缺失 ⇒ BAD 并清空；同角色重复/超容差 ⇒ `pair.stale`；乱序按 `word_index` 收齐）；
  float32 与 double 独立分流、互不污染。**不动表结构**，仅把 `register_format`/`word_count` 的列注释口径补上 `double`
  （`007` 与迁移脚本逐字双写，SQL 等价门禁绿）。

### 修复

- **CI 门禁收口（issue #233 / #235）**：
  - `ci.yml`「检出 ypbin-starter」由 `ref: master` 改为 **按声明的 Release tag**（`v<ypbin-starter.version>`）——
    此前该步先断言「声明版本 == 最新 Release」却检出 master 编译，**声明与实际的代码是两回事**：master 上有而
    Release 里没有的 API 会让 CI **假绿**（任何干净机器/外部使用者都会编译失败）；
  - **真库 IT 纳入 main 推送触发**（`iot-integration-tests.yml` / `iot-iotdb-it.yml`）：此前只在 PR 与 `feat/**` 跑，
    合并之后的 main **没有真库回归** —— 两个 PR 各自绿、叠加后互相影响时无人拦（成对代价：每次 main 合并多跑两个分钟级作业）；
  - **`Central Resolution Build` 也纳入 main 推送**：合并后的 main 必须仍是「只用已发布制品就能构建」的状态。

### 修复

- **更正 4 处「文档/注释与代码事实相反」**（由上线手册的核对过程发现，逐处给证据）：
  - `docs/DEPLOY-BACKEND.md`：直连 `18084` 的 curl 多带了 `/iot` 前缀（`/iot/**` 是**网关**路由，`StripPrefix=1` 后服务侧无此前缀）；
    同文件另两处写 `iot.access.value.normalize.failure` 有「3 个 reason 标签」，实际自 v2/v3 起为 **5 个**
    （`not-numeric`/`not-boolean`/`unknown-data-type`/`non-finite`/`not-utf8`）；
  - `IotTimeSeriesController` 类 Javadoc 仍写「IoTDB 尚未接入、端点返回未启用」，与该类实际实现（走的正是 IoTDB）相反；
  - `docs/METRICS.md` 把 `iot.timeseries.value.precision_lost` 的判据写成 `|v| ≤ 2^53`，代码判的是 **`> 2^53`**。

### 变更

- **删除继承自 upstream 的 `v1.0.0` tag，并确立本仓的发布 tag 政策**（细节见 `SYNC.md` §三·六）：
  该 tag 指向 2026-08-28 的提交、落后 main **479** 个提交、其 pom 声明 `1.0.0-SNAPSHOT`，与本仓发版无关；
  它占着 `v1.0.0` 这个名字会**直接挡住本仓首个真正的 1.0.0 发版**，而「Release 资产」这种形态又天然被当成可部署产物。
  现在明确：**发布 tag 只打在本仓的 release 提交上**（制品工作流已有守卫：`project.version` 必须等于 tag 名且非 `SNAPSHOT`）；
  upstream 的 tag 同步进来时不沿用。

- **落列改由物模型 `data_type` 唯一决定，彻底删除 `forceText` 通道（task-23，行为变更）**
  （`ypbin-service/ypbin-iot/**`、`ypbin-service/ypbin-access/**`、`ypbin-service-api/ypbin-iot-api/**`、`docs/**`）：
  新增唯一映射表 `ValueColumn.from(data_type)`（穷举 switch、无 default：新增第 10 类物模型类型会**编译失败**）——
  `int`/`long`/`decimal` ⇒ `value_double`，`bool`/`string`/`enum`/`date_time`/`json_object`/`array` ⇒ `value_text`。
  `ReadingValueMapper` 只按给定列物化，`map(raw)`/`map(raw, forceText)` 旧签名与全部按值形态猜列
  （前导零、bool 文本、整数 > 2^53 转文本等）删除；`TimeSeriesPoint.column` 必填，`AccessReading`/
  `ReadingObservationDto`/`PointValueNormalizer.Outcome`/`PointMappingDataListener.emit` 的 `forceText` 全部删除。
  类型来源 = `iot_property.data_type`，由 `PointMappingIndex` 与坐标**同一批查询**取回（不在 access→iot wire
  上重复带类型，避免第二个不可信来源）。
  **显式失败口径**：类型缺失/未知或同标识类型冲突 ⇒ 丢弃 + `iot.timeseries.value.type_missing` + 按点位节流 WARN；
  声明数值但值不可解析 ⇒ 丢弃 + `iot.timeseries.value.not_numeric`（不落文本兜底）；整数绝对值 > 2^53 ⇒
  **仍写 `value_double`** + `iot.timeseries.value.precision_lost` + 按点位节流 WARN（带 `deviceId`/`propertyId`/
  原始值字符串；此类数据应把属性建模为 `string`；**不采用 BAD/丢弃**，避免把建模问题算成「设备看起来断了」）。
  **不变量**：同一属性的所有值必然落同一列（判据只依赖类型）。
  本条目**取代**下方「TCP `data_type=string` 一律落 `value_text`（issue #253）」的窄口径实现——其行为被
  「按声明类型落列」完整覆盖，`forceText` 补丁不再存在。
- **TCP `data_type=string` 一律落 `value_text`（issue #253，行为变更）**（`ypbin-service/ypbin-access/**`、
  `ypbin-service/ypbin-iot/**`、`ypbin-service-api/ypbin-iot-api/**`、`docs/**`）：
  `PointValueNormalizer` 的 `data_type=string` 分支显式置 `forceText=true` ⇒ 纯数字序列号（如 `SN=123456`）
  不再按词法落 `value_double`，与 Modbus `register_format=string` 统一为
  **「物模型声明为 string ⇒ 一律落 `value_text`（TCP 与 Modbus 一致），不再按词法猜」**；非 string 类型
  （int/long/decimal/bool/enum/date_time/json_object/array）行为**一字不变**。
  依据用户 2026-10-13 决策（开发阶段不做向后兼容权衡，见 `docs/IOT-PLATFORM-DESIGN.md` D0.9）。
- **`byte_order` 由「历史宽容」收紧为统一严格（行为变更）**：仅在 Modbus `int`+`word_count=2` 或 `float`
  允许非空；`int`+`word_count=1`、`bits`、`string`、非 Modbus、位类型上**非空即拒绝**（此前被静默忽略）。
  同步更新 `docs/VALUE-DECODE-DESIGN.md` §4.3/§4.4/§4.5 的旧不对称表述。同样依据 D0.9。

### 修复

- **制品化交付加守卫：只给默认分支上的提交产出制品**（`.github/workflows/release-artifacts.yml`）。
  验证时发现本仓**继承了 upstream admin 的 `v1.0.0` tag**（`git log -1 v1.0.0` = 2026-08-28，
  `git rev-list --count v1.0.0..main` = 463），而本仓版本是 `1.0.0-SNAPSHOT` ⇒ 将来真正的首个 1.0.0
  发版会撞 tag，且若有人误推这类陈旧 tag 触发发布，制品会被挂到陈旧代码上、并被当成可部署产物。
  现在工作流用两条判据显式拒绝：**项目版本必须等于 tag 名（去 `v`）且不含 `SNAPSHOT`**，外加"提交在 `origin/main` 上"。
  也就是说：发版前必须先做 release 提交把版本定成 `X.Y.Z`，再打 `vX.Y.Z` tag。
  ⚠️ 经验保留：`merge-base --is-ancestor` **单独用拦不住它**（该 tag 是 `main` 的祖先会放行）——
  这条已写进工作流注释，免得后人只留那一条判据。

### 性能

- **TCP key-value 形态改为「一次多地址订阅」**（`ypbin-service/ypbin-access/**`、`docs/VALUE-DECODE-DESIGN.md` §5.5）：
  `AccessSubscriptionPlanner` 按 `ypbin.iot.protocol.tcp.payload-format` 分流——`key-value` 时一个
  `SubscribeRequest` 携带全部（按 `raw` 去重）地址、一个共享 listener，N 点位从 N 次订阅降为 1 次、
  同一帧只解析/派发一次；其它形态（binary/text/number）或配置不可得时**保持逐点位单订阅**（UP-4 契约）。
  **回退手段**：把 `payload-format` 改回非 key-value 即回到旧订阅形态（无需第二个开关）。
  未改任何数据库/协议契约；SDK 多地址产出行为只有 starter 侧单测证据，真机端到端未验。

### 新增

- **新增上线运行手册 `docs/DEPLOY-RUNBOOK.md`（交付面单一入口）**：把散在 `DEPLOY-BACKEND` / `DEPLOY-TIMESERIES` /
  `DEPLOY-CREDENTIAL-HYGIENE` / `ACCESS-ENABLE` / `METRICS` / `VALUE-DECODE-DESIGN` / `deploy/PROD-OPS-NOTES` 等
  多份文档里的「上线前检查 → 升级步骤 → 上线后核验 → 回滚 → 故障速查」收拢成一份**可执行、可核对、可回滚**的手册。
  核验清单覆盖订阅形态（`iot.access.subscribe.success` 每设备一次）、落列（`iot.timeseries.value.{type_missing,not_numeric,precision_lost}` 均为 0）、
  Modbus（`iot.access.modbus.pair.stale`=0、字符串点位、无重复读数）、接入侧规范化（`iot.access.value.normalize.failure{reason=…}`=0）
  与业务面；每条给「命令 + 期望 + 不满足说明什么」并注明取值方式与期望值来源。所有指标名/配置键/阈值均取自仓内文档或代码并给出出处；
  **显式写清「不可回退项」**（落列口径、`forceText` 已删除、IoTDB 热拷贝边界）与**「本手册不覆盖什么」**
  （EMQX 集群、Prometheus/Grafana、镜像制品化剩余、K8s/HA、外部 EMQX/隧道等），避免「照做就万事大吉」的错觉。
  文档一致性核对中发现的 4 处不一致已列入手册附录 C，并已在 PR #258（commit `0bd024e9`）修正。
  **2026-10-13 按生产只读实测收紧核验判据**：业务接口判真伪改为看 body 的 `R.code`（实测 `/devices…`=HTTP 200+`code=401` 而 `/iot/devices…`=HTTP 200+`code=404`，状态码相同 ⇒ 只看状态码必误判）；
  核验清单逐条标注服务归属（同一时刻实测 18084=144 条 / 18086=92 条，`iot.timeseries.db.rows` 在 18086 上 404）；
  SDK `iot.tcp.*` 由「看指标」改为「探针制造一次已知失败再核」（18086 实测 0 条，惰性注册下无法区分「没失败过」与「桥没接上」）；
  明确落列/Modbus/规范化等指标为**升级后新增的指标**，升级前 404 属正常，升级是否生效靠 jar md5 + 启动时间/重启计数判定。
- **新增门禁「Central Resolution Build」**（`.github/workflows/central-resolution-build.yml`）：**只用 Maven Central 上已发布的制品**构建本仓（不 checkout starter 源码、不做本地 install）。
  为什么需要：admin 既有的 `ci.yml` 断言「pom 声明的 starter 版本 == 最新 Release」之后，却 checkout starter 的 **master** 源码本地 install 来编译 ⇒ 若 master 上已有而 Release 里还没有的 API 被用到，`ci.yml` 会**假绿**，而任何干净机器（外部使用者、我们的发布制品工作流）都会编译失败。
  为什么是独立新工作流：admin 的既有工作流按 `SYNC.md` 属「明确不动」；新增文件不产生同步冲突面。附带自检：恰好 6 个服务 jar 且每个都含 `BOOT-INF/`。
- **Modbus 寄存器解码 v1（16/32 位、有符号/无符号、字序）**（`ypbin-service/ypbin-iot/**`、
  `ypbin-service/ypbin-access/**`、`ypbin-service-api/ypbin-iot-api/**`、`deploy/sql/**`）：
  - `iot_point_mapping` 新增 `word_count INT NOT NULL DEFAULT 1`（1|2）与 `signed TINYINT NOT NULL DEFAULT 0`
    （Java 侧 `Boolean`，与 `enabled` 同约定）；复用 `byte_order` 表达**字序**（big=高字在前 ABCD /
    little=低字在前 CDAB），仅 `word_count=2` 生效；
  - 8 组解码矩阵 + 宿主侧**半字配对**（32 位点位两次逐值回调合成一条；任一寄存器 BAD/缺失 ⇒ 点位 BAD，
    **绝不用陈旧一半或 0 拼值**）；半字陈旧丢弃 + 计数 `iot.access.modbus.pair.stale`（已登记 METRICS.md）；
  - 订阅侧 `word_count=2` 扩 `base`/`base+1`（`raw_address` 仍是基址）并去重；
  - **向后兼容**：默认 `word_count=1/signed=false` ⇒ 16 位无符号原样透传，现有点位行为逐字节不变；
    `word_count=1` 的传统 5 位编号照常；`word_count=2` 只接受显式 `type:offset`（传统编号明确报错拒绝）；
  - `byte_order` 列注释由「字节序」更正为「字序」并写明不支持 BADC/DCBA（006 与 M1 迁移双写、SQL 等价门禁绿）；
  - 未支持：IEEE754 浮点、BADC/DCBA 字内字节交换、coil/discrete 宽度/符号、字符串寄存器、位域。

- **Modbus 寄存器解码 v2（`register_format` + IEEE754 单精度 + 位域）**（`ypbin-service/ypbin-iot/**`、
  `ypbin-service/ypbin-access/**`、`ypbin-service-api/ypbin-iot-api/**`、`deploy/sql/**`）：
  - 新增 `register_format VARCHAR(16) NOT NULL DEFAULT 'int'`（int|float|bits）、`bit_offset`/`bit_length`（仅 bits）；
  - **float**：IEEE754 单精度（2 寄存器，复用 v1 半字配对/扩地址）；`byte_order` 字序生效；有限值产出
    `new BigDecimal(Float.toString(f))`（float32 **最短可回读**表示，round-trip 恒等；不做位数四舍五入）；**NaN/±Inf ⇒ 点位 BAD + 计数**
    `iot.access.value.normalize.failure{reason=non-finite}`（不落库）；
  - **bits**：单寄存器位域；`bit_length=1` ⇒ Boolean（**不参与缩放**）、`>1` ⇒ 无符号整数（可走既有缩放）；
  - 校验（新字段严格）：float 强制 `word_count=2`/`signed=false`/`bit_*` 空/`data_type=decimal`；bits 强制
    `word_count=1`/`signed=false`/合法 bit 区间（0≤offset≤15、1≤length≤16-offset）/`data_type` 匹配（1 位 bool、多位 int|long）；
    **`int` 保持 v1 行为逐字节不变**；`byte_order` 在 bits 形态忽略；
  - 未支持：IEEE754 双精度、BADC/DCBA 字节交换、有符号位域、字符串寄存器（issue #248）。

- **Modbus 寄存器解码 v3（`register_format=string`：多寄存器拼 UTF-8）**（`ypbin-service/ypbin-iot/**`、
  `ypbin-service/ypbin-access/**`、`ypbin-service-api/ypbin-iot-api/**`、`deploy/sql/**`）：
  - `register_format` 扩第 4 个取值 `string`（**不新增 `encoding` 列**，固定 UTF-8）；`word_count` 对 string
    放宽为 **1..32**（≤64 字节，33+ 拒绝），int/float/bits 的既有范围与规则**逐字节不变**；
  - 填充/解码：按寄存器顺序、每寄存器高字节在前拼字节 → 去尾部连续 `0x00` → **严格 UTF-8**
    （`CharsetDecoder` REPORT，不替换字符）；不去空格、中间 NUL 保留；**全 NUL ⇒ GOOD + 空串**；
    **非法 UTF-8 ⇒ 点位 BAD + `iot.access.value.normalize.failure{reason=not-utf8}`**；`byte_order` 对 string 必须为空（非空即拒绝）；
  - 宿主配对从「恰好两个 half」泛化为 `PendingWords`（按 `word_index` 收齐 N 个；同角色重复/超容差整组丢弃 +
    `iot.access.modbus.pair.stale`；任一 BAD ⇒ 点位 BAD + 清空）；订阅扩地址 `base..base+N-1`
    （大 N 由 SDK 分块，宿主仍按同一逻辑拼接）；
  - **落列**：新增**可选** `forceText` 标记（缺省 = 既有词法落列，对非 string 类型零影响），
    **string 类型**置位（Modbus 与 TCP 一致）⇒ `ReadingValueMapper` 原样落 `value_text`（纯数字序列号不被数值化）；
    TCP `data_type=string` 已按 D0.9 完整口径纳入（见上方「变更」）；
  - 校验（新字段严格）：string ⇒ `signed=false`、`bit_*` 空、`byte_order` 空、`data_type=string`、`word_count` 1..32、
    `address_type ∈ {holding,input}`，`word_count>1` 时地址必须显式 `type:offset`；错误文案说清怎么改；
  - 未支持：BADC/DCBA、GBK/其它编码、IEEE754 双精度、有符号位域、跨寄存器区。

- **JaCoCo 覆盖率门禁接入 `ypbin-iot` / `ypbin-access`**（`mvn test`/`verify` 即生效，未达标直接失败）。
  阈值口径 = **2026-10-13 实测基线 − 安全边际**：
  `ypbin-iot` 指令 ≥ **0.78**（基线 0.8063）、分支 ≥ **0.68**（基线 0.7086）；
  `ypbin-access` 指令 ≥ **0.84**（基线 0.8623）、分支 ≥ **0.73**（基线 0.7588）。
  为什么"留边际而不是卡基线"：卡死基线会让正常迭代（加一个未测的小类）随机转红；留 2.5~3 个百分点的边际，
  既能拦住"新增大块无测试代码"这类真实回退，又不制造噪音。只统计单元测试（IT 由 CI 的 `-Pit` 作业单独跑）。
  **只在这两个模块的 pom 里声明**（fork 自有文件）：不动 admin 的根 pom，避免扩大与 upstream 的分歧面。
  **有效性已自测**：把阈值临时抬到 0.99 ⇒ 构建真实转红并报 `Rule violated for bundle ypbin-iot`。
  复算基线：`mvn clean verify` 后看 `<module>/target/site/jacoco/jacoco.csv` 汇总。

### 修复

- **控制器门禁测试改为「只扫业务方法」**（新增 `ControllerGateMethods` + 有效性自检）。
  接入 JaCoCo 后 `IotCommandControllerGateTest#browserEndpointsMustKeepTheEnvelope` 立刻转红：该用例遍历
  `getDeclaredMethods()` 要求「每个方法都返回 `R` 信封」，而 JaCoCo 的 `prepare-agent` 在**运行时**注入了合成方法
  `$jacocoInit()`（返回 `boolean`）⇒ 门禁把"插桩产物"当成了"端点"。
  修法：新增 `ControllerGateMethods.businessMethods*`（剔除 `isSynthetic()` 与 `isBridge()`），
  `IotCommandControllerGateTest`/`DeviceTraceControllerGateTest`/`OpenApiFacadeGateTest` 改用它；
  并补**自检**：合成/桥方法必须被剔除、普通方法必须保留、**扫不到任何方法时必须失败**（防门禁静默空跑）。
  ⚠️ 自检初版用方法名判断桥方法 ⇒ 与业务方法 `compareTo(Fixture)` 同名而误判；已改为按签名判断
  （这条也写进了代码注释）。


- **制品化交付：`v*` tag 自动产出并挂载可执行 jar**（`.github/workflows/release-artifacts.yml`）。
  此前 admin 既有的 `release.yml` 只发 Release 说明、**不产任何制品**，而部署纪律要求「只能在能访问 GitHub 的构建机编 jar、禁止部署机跑 Maven」⇒ 生产交付长期靠手工编 + scp，没有可下载的制品，也没有「jar 来自哪个 commit」的机器可验证链条。现于每个 `v*` tag 上构建 6 个服务 jar，并附 `jars.manifest.sha256`（含 git sha 与构建时刻）；生产按 `docs/DEPLOY-BACKEND.md` §2.0 下载 + 校验后替换。**为什么是独立新工作流**：admin 的既有工作流（ci/codeql/release）按 `SYNC.md` 属「明确不动」，新增文件不产生同步冲突面。每个 Release 同时附带 **CycloneDX SBOM**（`bom-cyclonedx.json`，聚合依赖清单；命令行调用插件 2.9.3、与母仓一致，**不往 pom 加配置**），并带「组件数/组件名」自检防挂空清单。边界：SBOM 是依赖清单、**不是**漏洞扫描（漏洞门禁见 issue #237）；镜像制品化属后续增量。

### 修复
- **生产帧形态只读普查（部署前置的证据）**：切 `payload-format=key-value` 前对生产库做了一次只读普查 —— 13 台设备全是演示夹具；**唯一在采的 TCP 设备 `demo-dev-curve`(9300012) 的 4 个点位与帧键逐字对应**（切换后行为不变）；其余 9 台 TCP 夹具的 `holding:*` 是 #217 之前的历史错位残留、早已离线且挂未闭合断档，新旧行为等价（`key-not-found` → `selector-not-found`，都是 BAD）。结论与复现 SQL 见 `docs/VALUE-DECODE-DESIGN.md` §5.6。

- **一键安装 IoT 断点：库表/菜单未装 + 迁移不幂等**（`deploy/install.sh`、`deploy/sql/migration/iot-migrations.list`）：
  - R1：`install.sh` 在 `$ROOT/ypbin-admin` 克隆的是 **upstream admin**，其 `deploy/sql` 只有 001–005 ⇒
    fork 的 `006-iot-schema.sql` / `007-iot-data.sql` 永远不会被导入，一键安装装出来**没有 IoT 表与菜单**。
    修复**不动** SYNC.md §三·五点名的 52 行 `ypbin-admin/` 路径，改为按序解析 SQL 来源：① 脚本自身位置的
    `deploy/sql`（与既有 UP-6/UP-7 的 `NACOS_DIR` 自定位同一套做法）；② 远端 `YPBIN_IOT_RAW_BASE`
    （默认 GitHub raw，带 Gitee 兜底与既有 `CURL_*` 硬超时）逐文件下载；两处都拿不到即 `die`（不静默跳过）。
  - R2：`deploy/sql/migration/` 的 DDL/DML **不幂等**（`CREATE TABLE` 无 `IF NOT EXISTS`、含 `ALTER`/`INSERT`），
    重复执行必失败。新增迁移账本 `ypbin_schema_migrations`（脚本 `CREATE TABLE IF NOT EXISTS` 自建，**刻意不放进
    006/007**，以免破坏 R3 的语句级等价门禁），只应用账本里没有的 `*-iot-*.sql`；已有库无账本时按哨兵表
    `iot_device` 探测：存在则 WARN 后预置基线（可见的假设），不存在则按名全量应用；提供
    `IOT_MIGRATE_BASELINE=1` / `IOT_MIGRATE_FORCE=1` 逃生开关。
  - 新增 `deploy/sql/migration/iot-migrations.list` 作为远端兜底的下载清单，并由
    `tools/check-iot-sql-equivalence.sh` 校验「清单 = 目录内实际文件」。
- **IoTDB 口令与 OpenAPI pepper 未贯通交付**（`deploy/install.sh`、`deploy/nacos/ypbin-iot.yaml`、`deploy/docker-compose.yml`）：
  - `install.sh` 首次部署随机生成 `IOTDB_PASSWORD`（24 位 hex）与 `YPBIN_OPENAPI_SECRET_PEPPER`（≥32 字节）写 `.env`(600)；
    新增 `ensure_iotdb_auth()` 在 Nacos 导入前把 IoTDB 内 root 口令对齐到 `.env` 值（口令只经 stdin、不进 argv，
    以「新口令能连」为判据）；无法对齐即 `die`，**不猜、不覆盖线上口令**；
  - `deploy/nacos/ypbin-iot.yaml` 的 `timeseries.password` 改为 `${IOTDB_PASSWORD}` 占位符（install.sh 的 sed
    渲染键由 4 个增至 5 个，只替换非注释行）；`docker-compose.yml` 给 `ypbin-iot` 注入 pepper。
- **compose 必填变量缺失导致「只起基础设施」也失败（EMQX 硬前置）**（`deploy/install.sh`、`deploy/.env.example`、
  `docs/DEPLOY-BACKEND.md`）：`docker compose` 对**整份文件**插值，任一 `${VAR:?}` 缺失都会让
  `up -d nacos redis mysql` 一起失败。新增 `ensure_compose_required_env()` 在**任何 compose 调用之前**机械抽出
  全部 `:?` 必填变量逐个处置：`EMQX_API_KEY`/`EMQX_API_SECRET` **绝不自动生成**、缺失时先于 compose fail-loud
  并给来源/用法；`AI_MODEL_SECRET_KEY` 复用旧 `.env` 缺键也补齐（env 优先）并校验 16/24/32 字节；
  `IOTDB_PASSWORD` 延后到 `ensure_iotdb_auth()` 处理。
- **curl 一键安装的 fork 专属 deploy 资源缺失**（`deploy/install.sh`、`docs/DEPLOY-BACKEND.md` §2.2）：
  `bash <(curl ...)` 时脚本落在 /tmp，install.sh 又只克隆 upstream admin，导致 `redis-auth-conf.sh`、
  `iotdb-init.*`、`nacos/ypbin-iot.yaml`、`sql/006+007+迁移` 全拿不到（此前会 `die 缺少 redis-auth-conf.sh`）。
  新增 `resolve_deploy_src()`：检出路径优先（`<repo>/deploy` / `$ROOT/ypbin-admin/deploy`），否则浅克隆
  fork 到 `$ROOT/ypbin-iot`（`YPBIN_IOT_REPO`，GitHub→Gitee 降级，分支跟 `BRANCH`），解析出统一资源根
  `DEPLOY_SRC` 供 redis-auth-conf/Nacos/SQL 使用；两路都失败则一次性 die 并列出缺项。SQL 远端 raw 下载
  降级为备选路径。纯 admin compose（不含 `ypbin-iot`/`ypbin-access`/`iotdb`）会跳过 fork 资源解析与校验，
  不误伤该语义。
- **curl 一键安装装不出 IoT 服务（门面级缺口，已修）**（`deploy/install.sh`、`SYNC.md` §三·五、
  `docs/DEPLOY-BACKEND.md` §2.3）：根因是 install.sh 把 **upstream admin** clone 到 `$ROOT/ypbin-admin`，
  而 `ypbin-iot`/`ypbin-access` 的 compose `build:` context、fork nacos 模板、`006+007` 都只在 fork 检出里。
  改为 [2/7] **默认把 fork（ypbin-iot）clone 到 `$ROOT/ypbin-admin`**（`YPBIN_IOT_REPO`，Gitee 降级，
  分支跟 `BRANCH`），与 SYNC §三·五 的推荐部署法等价 ⇒ [4/7] Maven 构建与 [6/7] compose 同用 fork 检出，
  一键安装能装出含 `ypbin-iot`/`ypbin-access`/`iotdb` 的全栈。`YPBIN_ADMIN_REPO=<upstream>` 可退回纯 admin
  语义。**已有的非 fork `$ROOT/ypbin-admin`** 且要装含 IoT 服务的 compose ⇒ 脚本 die + 迁移指引
  （不静默将就半个系统）。**代价如实登记**：这是 install.sh 对 upstream 的**语义级**偏离，下次
  `git merge upstream/main` 该文件冲突概率更高（该文件已在白名单内，冲突属预期）。
- **部署日志的密钥明文回显收敛**（`deploy/install.sh`）：`AI_MODEL_SECRET_KEY` 自动生成时不再把密钥值
  打进部署日志（改为只提示「已写入 .env 的哪个键 + 如何查看」）；Nacos 口令改写失败的 warn 不再回显
  `NACOS_CURRENT_PASSWORD` 的值。`XXL_JOB_ADMIN_PASSWORD` 保持既有的「不回显、只提示查看命令」语义。
  同步把 `.github/workflows/sync-whitelist.yml` 注释里的白名单计数 20 → 21。
- **部署凭据卫生**（`deploy/`，反馈 UP-8/UP-9）：
  - compose 健康检查不再携带口令：Redis 改 `nc -z` 探端口、MySQL 改 `mysqladmin ping -h 127.0.0.1`——
    原 `redis-cli -a <口令>` / `mysqladmin -p<口令>` 会把口令写进 exec argv，`docker top` / `docker events` 可见；
  - XXL-JOB 调度中心不再内置默认口令：`deploy/sql/005-xxl-job.sql` 的 `admin` 密码哈希改为
    部署期占位符 `${XXL_JOB_ADMIN_PASSWORD_HASH}`，`install.sh` 随机生成口令（不复用 `MYSQL_ROOT_PASSWORD`，
    写入 `.env` 的 `XXL_JOB_ADMIN_PASSWORD`，部署日志一次性提示）并在导入前替换（仅非注释行）；
  - XXL-JOB 控制台默认只绑回环（`INTERNAL_BIND_ADDR` 可覆盖）。
- **install.sh Nacos 配置导入两处缺陷**（反馈 UP-6/UP-7）：
  - 占位符替换限定非注释行：`/^[[:space:]]*#/!`，注释里的 `${...}` 保持原样，真实凭据不再进入配置存储的注释文本；
  - `NACOS_DIR` 按脚本自身位置解析（`SCRIPT_DIR/nacos`，与检出目录名解耦），fork（如 `ypbin-iot`）不再静默不导入
    自己的配置；模板缺失时输出明确的 warn（含期望路径）而非静默跳过；
  - 配置导入自动枚举 `deploy/nacos/*.yaml`（清单不再手写），渲染临时文件改 `mktemp`（600）+ 用后即删 + EXIT trap 兜底。
- **部署文档同步**（`docs/microservice-deployment.md`）：XXL-JOB 初始口令改为指向 `.env` 的
  `XXL_JOB_ADMIN_PASSWORD`，不再写"默认 admin/123456"。

### 变更（需协调升级）

- **接入侧切到 starter 0.3.0 `payload-format=key-value`（帧内字段选择 / valueSelector），并删除帧解析过渡层**
  （`ypbin-service/ypbin-access/**`、`deploy/nacos/ypbin-access.yaml`、`docs/VALUE-DECODE-DESIGN.md`）：
  - `ypbin-iot.version` 0.2.0 → **0.3.0**；两处配置（Nacos `ypbin-access.yaml` + access `application.yml`）新增
    `ypbin.iot.protocol.tcp.payload-format: key-value`；
  - **删除** `cn.ypbin.admin.access.decode`（`ValueDecoder` / `TextFrameValueDecoder` / `DecodeFailure` /
    `DecodeOutcome` / `DecodeFailureMetrics` + 2 个测试）与解码器 bean/注入；UTF-8、`KEY=VALUE` 拆分、按
    `raw_address`（= selector）取键现由 SDK `TcpValueSelector` 承担；
  - **保留**宿主「物模型类型规范化」（新 `cn.ypbin.admin.access.egress.PointValueNormalizer`，与删除前的
    `canonicalize` 逐字等价）：SDK 明确不做类型转换，而 `AccessReading.applyScale` 只吃 `Number`/`BigDecimal`、
    iot 侧 `ReadingValueMapper` 按词法落列 ⇒ 直接删会让 `scale/offset` 静默失效（读数错值）、bool `1/0` 落列改变。
    `raw_address` 作为帧键的约定不变；
  - **指标**：`iot.access.decode.failure` 下线（帧/选择失败由 SDK `iot.tcp.selector.not-found` /
    `iot.tcp.payload.not-key-value` / `iot.tcp.payload.not-utf8` 承接）；新增
    `iot.access.value.normalize.failure`（reason=`not-numeric`/`not-boolean`/`unknown-data-type`），已登记 `docs/METRICS.md`；
  - **⚠️ 升级前置（会丢读数或起不来）**：**jar 与 `payload-format=key-value` 必须一起切**——
    新 jar + 未配 key-value（SDK 默认 `binary`）⇒ 整帧 `byte[]` 被出口闸门丢弃 ⇒ **读数全丢**；
    旧 jar（0.2.0）+ `key-value` ⇒ 0.2.0 无 `KEY_VALUE` 枚举 ⇒ **绑定失败起不来**。
    协调切换顺序与核验见 `docs/DEPLOY-BACKEND.md` §5.8；
  - 订阅形态**保持逐点位单地址**（key-value 下仍合法），多地址单订阅（减少 N 倍帧解析）登记为文档待办；
  - **无 SQL 迁移**（无表结构变化）。

### 新增

- **数据备份/恢复工具链**（`deploy/backup.sh`、`deploy/restore.sh`、`deploy/backup.service`/`backup.timer`、
  `deploy/PROD-OPS-NOTES.md` §13）：MySQL（`mysqldump --single-transaction`，口令经 `MYSQL_PWD` 不进 argv）
  与 IoTDB（`docker cp /iotdb/data` 热拷贝）定时备份，产物带时间戳与 `.sha256`、默认保留最近 14 份轮转、
  支持 `--dry-run`；恢复脚本默认只打印计划、**必须显式 `--yes`** 才执行，恢复前核对 sha256。**边界如实登记**：
  IoTDB 热拷贝不保证崩溃一致性、不支持增量，无 PITR、无异地容灾，真机未验证（详见文档）。

- **埋点事件目录分层：新增 admin 的 project 层目录**（`ypbin-common/src/main/resources/META-INF/ypbin/tracking-events.json`）。
  承接从 starter 的 base 目录**迁出**的业务示例事件 `system.user.export`（starter 侧同批把它从 `docs/tracking-events.json` 移除）。
  运行时仍是「base + 宿主项目目录合并」：starter 的 `TrackingCatalogLoader` 用 `classpath*:` 取回类路径上
  **全部**同名资源、按所在归档区分两层后合并，同一事件码以 project 为准并在启动时以 WARN 逐字段打印覆盖差异
  （`TrackingEventCatalog#overriddenCodes()` 另给出可编程的覆盖标注）。
  - **为什么放 `ypbin-common` 而不是 `ypbin-system`**：目录必须落在**记录事件的那个服务**的类路径上——
    `TrackRecorder` 在录制入口就按目录校验事件码，而 auth 与 system 都会录制（`ypbin-common` 是两者共同的宿主 jar）。
    只放 `ypbin-system` 会让 auth 侧录制 admin 自有事件时被判「未登记」。
  - **事件码集合不变**：迁移前后「base ∪ project」的**联合视图逐码等价**（迁移前 base 10 码；
    迁移后 base 9 码 + project 1 码，并集仍是同样的 10 码，description / properties / type / maxLength 逐字段一致）。
    故前端生成物 `events.generated.ts` **零改动**、漂移门禁保持绿。
  - **该码目前无发送方**（全仓无 `system.user.export` 的上报点，也无按码分支的消费方，只有事件目录页的只读展示）——
    属**死事件**，本次按既定决策只做「迁位」不做删除；删码是破坏性动作，需单独决策。
- **架构门禁新增规则：`service/impl` 禁止依赖 `provider`**（`ypbin-architecture-tests` 的 `CodingRulesTest`）。
  `provider` 是宿主按 starter 端口契约给出的适配实现层、`service/impl` 是业务实现层，实现层直连适配实现类会把依赖方向
  倒置成硬耦合。这是一次**真实回归**的护栏：`SysUserServiceImpl` 曾直接持有 `provider.AdminDataScopeHandler` 复用
  「该部门是否在数据范围内」的判定，上一轮重构已抽为 `service/support` 的共享能力（`DataScopeResolver`），本规则把
  「不许倒回去」固化成构建失败。规则带**有效性自检**：合成违规（`service/impl` 持有真实 `provider.AdminDataScopeHandler`）
  必须转红，同时断言**反向依赖（provider → service/impl）与无关依赖不被误报**（证明方向性正确、非恒真也非恒假）。

- **AI 用量落库：宿主侧 `AiUsageListener` 实现（`ypbin-ai` 的 `AdminAiUsageListener`）**。
  此前 `ai_usage_log` **没有任何写入方**（全仓只有 Mapper 与统计读取），用量看板恒为空。
  starter 这一批补齐了回调触发点并改了契约（`AiUsageInfo` 的 token 由 `long` 改为可空 `Long`，
  新增 `outcome` 与 `errorMessage`），本仓按新契约实现宿主侧监听器：
  - **用户/租户维度由宿主补齐**（starter 回调不携带用户信息）：先取同线程
    `TenantContext`/`IdentityContext`，取不到时用 `conversationId`（即 `ai_chat_session` 主键）
    精确查一行补齐 tenantId/userId；匿名入口（`share-<kbId>`/`widget-<kbId>`/`kb-query-<kbId>`）
    没有用户与会话实体，`user_id`/`conversation_id` **如实落 NULL**，不伪造 ID；
    租户实在取不到则记 error 并放弃本次落库（禁止伪造成默认租户）。
  - **`ai_usage_log` 最小增量**（`deploy/sql/003-ai-schema.sql` + `deploy/sql/migration/2026-09-17-ai-usage-log-outcome.sql`）：
    `user_id` 与三个 token 列改可空（NULL＝上游未回报，**绝不折算成 0**）、新增
    `outcome`（success/failure/cancelled）与 `error_message`。已有库执行 migration 子目录脚本；
    全新安装无需额外操作（003 已是最终结构）。
  - **统计侧 NULL 口径**：聚合一律直写 `SUM(total_tokens)`，不在聚合参数上套 `COALESCE`
    （未知行绝不按 0 参与求和/均值）；每个聚合点额外返回未知条数
    （`unknownTokenCalls`/`unknownCalls`），用量总览另给出三类终局结果计数
    （`successCalls`/`failureCalls`/`cancelledCalls`）。
  - **异常隔离**：落库异常在监听器内捕获并 `log.error(..., ex)` 记完整堆栈与业务标识，
    不向对话主流程抛出；单条 INSERT（`AiUsageLogWriter`，`@Transactional(rollbackFor = Exception.class)`），
    无循环查询、无批量。
  - 测试：`AdminAiUsageListenerTest`（成功/失败/取消各自落库；token 为 null **不得写成 0**；
    落库失败不影响主流程且带堆栈留痕；会话补齐/同线程上下文/未知租户三种解析路径；超长原因按列宽截断）。
- **starter 依赖版本升至 `3.4.0`**（`pom.xml`）。新契约（可空 token + `AiUsageOutcome`）
  只存在于 starter 未发布的 3.4.0 线（3.3.0 的 `ypbin-starter-ai` 只有 `AiUsageInfo`/`AiUsageListener`
  两个类）。⚠️ **合并前须等 starter 3.4.0 正式发布**：本仓 CI 有「starter 版本必须等于最新 GitHub Release」
  校验，SNAPSHOT 会直接失败；发布后由 `sync-starter-version` 工作流改写为发布版号。
  本地开发需先 `mvn -Dmaven.test.skip=true install` 安装 starter 3.4.0。
- **数据范围处理器 `AdminDataScopeHandler`（starter 端口 `DataScopeHandler` 的宿主实现）**。
  此前宿主未实现该端口（实现数为 0），9 处 `@DataPermission` 全为空转——查询与写操作的 UPDATE/DELETE
  都不加任何数据范围条件。现按 `sys_role.data_scope` 计算 SQL 片段：平台超管（`PLATFORM` +
  `PLATFORM_SUPER` 角色）不受限；否则取各角色数据范围并集（1 全部⇒不加条件、2 本部门及以下⇒本部门+
  部门树后代、3 本部门、4 仅本人⇒`id = 当前用户`、5 自定义⇒角色绑定部门+其后代），部门条件与本人
  条件以 `OR` 组合；取不到身份头/无有效角色/解析结果为空时**拒绝全部**（`id = -1`）并记日志，
  不放行全量。只治理 `sys_user` 表（其余表无 `dept_id` 列，返回 null 放行）；租户隔离仍由 tenant
  拦截器独立施加，不拼 `tenant_id`；角色-部门关联走批量 IN（判空短路），解析自身的查询用线程内标记防递归。
- **敏感词词库提供者 `DbSensitiveWordProvider`（starter 端口 `SensitiveWordProvider` 的宿主实现）**。
  词库取自系统参数 `sys_config.SENSITIVE_WORDS`（逗号/分号/换行分隔），复用既有参数表与「系统参数」
  维护界面，不新建词表；词库为空时本类自行记 WARN（starter 只在「无 Provider 且配置项为空」时告警，
  宿主提供 Provider 后该告警不再触发，必须由实现把「过滤等同无操作」说出来）；读取失败带堆栈抛错，
  绝不降级为空词库。种子数据已补该参数行（`deploy/sql/002-data.sql`，默认留空待填）。
  **词库在启动期读取一次，改动后需重启生效**（starter 的契约如此，`SensitiveWordService#reload`
  本仓尚未接入）。
- **权限数据源（starter 端口 `PermissionProvider`）三处宿主实现，注解鉴权具备开启条件**
  （`ypbin-system` / `ypbin-ai` / `ypbin-auth`）。此前宿主均未实现该端口，框架装配的是「返回空列表」的
  （`ypbin-system` / `ypbin-ai` / `ypbin-auth`）。此前宿主均未实现该端口，框架装配的是「返回空列表」的
  默认实现，156 处 `@SaCheckPermission`（29 个 Controller、92 个去重权限码）即便拦截器打开也恒不通过。
  - `SystemPermissionProvider`：复用本地 `SysPermissionService`（权限码唯一来源 `sys_role_menu` →
    `sys_menu.auth_code`），**原样透传**平台超管的 `*:*:*` 短路结果，不做任何过滤/截断
    ——这是「超管不掉权限」的唯一保障，已由 `SysPermissionServiceImplTest` 与
    `SystemPermissionProviderTest` 双向锁定。
  - `AiPermissionProvider` / `AuthPermissionProvider`：本服务无该库表，经 `SysCache`
    （`sys:perm:user:<id>` 永久缓存，未命中回源 Feign）复用 system 的唯一一份判定，避免每请求一次 RPC。
    **fail-closed**：system 不可达时 `SysCache` 抛业务异常、Provider 不捕获 ⇒ 请求以
    `R.code=409`「系统服务暂不可用」失败——既不放行，也不把依赖故障伪装成「无权限 403」；
    响应成功但数据缺失、loginId 缺失/非法时一律返回空集合（拒绝）。Feign 超时沿用共享配置的
    显式钉死值（`deploy/nacos/ypbin-common.yaml:35-41`：连接 2000ms / 读取 5000ms）。

### 修复

- **`auth` 不再传递依赖 `ypbin-starter-data`（MyBatis-Plus）——把 SKILL 里「auth 不直连共享库」从口头约定变成编译期门禁**
  （`TRACKING-TAILS.md` 的既有偏差 #15）。根因不在 auth 自己的 pom，而在**跨服务契约把持久化实体当 DTO 用**：
  `ISystemClient`/`SysCache` 直接返回 `SysUser`/`SysUserSocial`，而两者继承 `BaseEntity`，
  于是无数据源的 auth 被迫跟着依赖 starter-data（连 Mockito 为 `ISystemClient` 生成 mock 都会因签名里的实体
  无法加载而失败——本轮的编译期实验正是这样暴露出来的）。
  - **契约收窄为只读视图**：新增 `SysUserDto`/`SysUserSocialDto`（字段与实体**逐一同名同类型**，禁改名映射；
    视图**结构上不含 `password`/`accessToken`**），`ISystemClient` 的 6 个方法
    （`getUserByUsername`/`getUserById`/`getUserByPhone`/`searchUsers`/`getSocialBinding`/`listSocialBindings`）
    与 `ISystemClientFallback`/`SystemClientImpl` 同步改签；实体→视图的投影集中在
    `ypbin-system` 的 `feign/support/UserViewConverter` 一处（**刻意不放 api 模块**：它必须引用实体，
    置于 api 会让已排除 `starter-data` 的 auth 拿到一个「能加载、一解析方法就 `NoClassDefFoundError`」的类）。
  - **auth 的 pom 显式排除 `ypbin-starter-data`**：一旦有人在 auth 里再引用实体类型，编译期即因缺类失败——
    这条约定从此由构建强制。`ai` **不加**该排除：它有自己独立的 `ai_*` 表与 Mapper，
    「不直连共享库」指的是不访问 system 的表，不是不许用 MyBatis。
  - **缓存 key 升版到 v2**（`sys:user:v2:*` / `sys:social:v2:*`）：用户/绑定快照是**永久缓存**，
    而 `CacheService#getOrLoad` 对命中值是「无类型校验的强转」——载荷由实体收窄为视图后若沿用旧 key，
    升级后会读到旧实体对象并在强转处抛 `ClassCastException`（登录直接不可用）。5 处 `@CacheEvict`
    注解已同步升版；旧的 v1 键不再被读取。
    **运维（可选）**：v1 用户/绑定键是永久键，如需释放内存可执行
    `DEL sys:user:username:* sys:user:id:* sys:user:phone:* sys:social:binding:* sys:social:bindings:*`
    （不执行也不影响正确性：新代码只读 v2 键）。
  - **新增门禁**：`SourceConventionTest` 增加「`@CacheEvict` 的 key 必须存在于 `SysCache` 的 key 常量中」，
    并带**规则有效性自检**与**变异验证**（把一处失效 key 改回 v1 → 精确转红；回滚 → 绿）。
    这类漂移此前完全静默：失效打在不再被读取的键上，改状态/改角色后登录仍用旧快照。
  - **验证**：受影响四模块 `mvn test` 全绿（`ypbin-system-api` 12 / `ypbin-common` 17 / `ypbin-auth` 30 /
    `ypbin-system` 159 / `ypbin-ai` 21），架构测试模块 35 项全绿（含新规则）。
- **用户名查重移出数据范围，跨部门重名改为友好业务错误**（`SysUserServiceImpl` / `UserAccountSupport` / `SysUserMapper`）：改用**语句级** `@InterceptorIgnore(dataPermission = "true")` 的全局计数语句（`countByUsernameGlobal`），不再受 `@DataPermission` 部门条件影响。

  `SysUserMapper`）。`uk_username` 是**不带 `tenant_id` 的全局唯一键**，而 `updateUser` 带
  `@DataPermission`（数据范围按部门过滤），原先的 `exists()` 查重落在该范围内 ⇒ **跨部门重名查不到** ⇒
  校验通过后由数据库唯一键抛原始 SQL 错误。现改走语句级跳过数据权限的
  `SysUserMapper#countByUsernameGlobal`（`@InterceptorIgnore(dataPermission = "true")`，已核实
  MyBatis-Plus 3.5.17 `DataPermissionInterceptor#beforeQuery/beforePrepare` 首行即判该标记）
  + `TenantContext.executeIgnore`（关租户过滤），两道过滤缺一不可：
  `TenantContext` 关不掉数据权限（`DataPermissionContext` 只有进入/退出、无挂起语义，
  外层已激活时内层 `@DataPermission(ignore = true)` 并不生效），故必须用语句级注解。
  测试：`UserAccountSupportTest`（同租户内跨部门重名同样报「用户名已存在」、执行时断言租户过滤已关且退出后复位、
  编辑排除自身）、`SysUserMapperInterceptorIgnoreTest`（走真实 Mapper 解析路径断言 MP 判据为真，
  并以未标注语句/不存在语句反向证明判据非永真）。
- **操作日志 `sys_log.clientId/clientType/authType` 不再恒为空**：新增 `SessionLogClientProvider`
  （`ypbin-common`，由 `RemoteLogAutoConfiguration` 装配，带 `@ConditionalOnMissingBean` 可被宿主覆盖），
  从登录会话中的 `LoginUser` 读这三个值——gateway 身份头只有 id/username/tenantId/deptId/roles，
  **不新增任何请求头契约**，而是复用 auth 登录时写入、三服务共享同一 Redis 会话存储的登录态。
  读会话失败时记 error 且让本条日志照常落库（仅三列为空），不因三个附加字段丢掉整条审计记录。
- **在线用户接口改为统一分页响应**：`GET /online-user/list` 由 `R<List<OnlineUserResp>>` 改为
  `R<PageResult<OnlineUserResp>>`（新增 `OnlineUserQuery extends PageQuery`，`keyword` 语义不变）。
  在线用户来自会话存储而非数据库，属**内存分页**：先枚举全部在线会话再切片，分页只减少传输量，
  不减少会话读取开销（O(在线会话数)）；页码越界时 `items` 为空而 `total` 仍为真实总数；
  非法页码/每页条数显式报错，不做静默纠正。
- **角色授权变更时的权限缓存清理由「逐用户一次缓存往返」改为批量一次删除**
  （`ypbin-system-api`：`SysCache.evictUserAuth(Collection<Long>)`）。用户权限缓存是永久缓存
  （TTL 传 `null`），一致性完全依赖写路径主动失效；受影响用户多时原实现按用户逐个 `DEL`，
  放大为 N 次缓存往返。现合并键后一次删除，空集合短路、`null` 元素跳过；
  `SysRoleServiceImpl`、`SysMenuServiceImpl` 的失效入口改用批量语义（仍为**精确失效**，
  只清受影响用户的 `sys:role:user:*` / `sys:perm:user:*`，不做整体清）。
  新增回归测试：`SysRoleServiceImplPermissionCacheTest`（改角色勾选菜单/改角色状态后必须清掉该角色下
  **全部**去重用户的缓存；角色下无人时不得发起删除）、`SysCacheTest` 的批量与短路用例。
- **内部端点 `GET /internal/user-by-id` 补齐租户忽略，第三方登录不再必然失败**
  （`SystemClientImpl` / `SysUserService` / `SysUserServiceImpl`）：`/auth/social/callback/**` 在网关
  白名单内、匿名链路没有网关签发的 `X-Tenant-Id`（`SaTokenGatewayAuthProvider` 仅在登录后签发），
  而 `sys_user` **不在** `ypbin.tenant.ignore-tables` 且 `fail-on-missing-tenant=true`（fail-closed）
  ⇒ 该端点原先调用继承自 `IService` 的 `getById`，必然抛「缺少租户上下文」，**第三方回调登录直接失败**。
  现新增 `SysUserService#getByIdGlobal`（内部 `TenantContext.executeIgnore`，与同链路 6 个兄弟方法
  `getByUsername`/`getByPhone`/`verifyPassword`/`countUsers`/`searchUsers`/`updateLastLoginTime` 同口径）
  并由端点委派；**刻意不覆写 `getById`**，否则所有既有调用者会悄然失去租户隔离。
  同文件其余端点已逐一核对：其余 6 个走已在 service 内忽略租户的方法，`sys_config`/`sys_log`/
  `sys_user_social`/`sys_track_event*` 相关端点命中的都是 `ignore-tables` 表，**无第二处漏项**。
  测试：`SystemClientImplUserByIdTest`（必须委派 `getByIdGlobal`，并反向禁止走 `getById`）、
  `SysUserServiceImplTenantIgnoreTest`（用真实 `DefaultTenantLineHandler` + 生产同口径配置断言
  「无上下文时 `getById` 必被拦、`getByIdGlobal` 必放行、退出后不泄漏」）。
- **手机号查重移出数据范围，跨部门重号改为友好业务错误**（`SysUserMapper` / `UserAccountSupport`）：
  与用户名查重同源——`updateUser` 带 `@DataPermission`，原先的 `exists()` 查重落在部门范围内 ⇒
  **跨部门重号查不到** ⇒ 校验通过后由唯一键抛原始 SQL 错误。现新增
  `SysUserMapper#countByPhoneGlobal`（语句级 `@InterceptorIgnore(dataPermission = "true")`）
  + `TenantContext.executeIgnore`（关租户过滤）并改用它，两道过滤缺一不可。
  测试：`UserAccountSupportTest` 补 6 个用例（同部门/跨部门重号均报「手机号已存在」、执行时断言租户过滤已关
  且退出后复位、编辑排除自身、手机号为空不查库、查重失败不掩盖异常）、
  `SysUserMapperInterceptorIgnoreTest` 补该语句命中 MP `willIgnoreDataPermission` 的断言。
- **数据范围报错不再教用户用错的机制**（`AdminDataScopeHandler`）：原文案建议
  「调用侧改用 `@DataPermission(ignore = true)`」，但该机制在**外层已激活数据权限时不生效**——
  `DataPermissionContext` 只有 `enter/exit/isActive`、**无挂起语义**，`DataPermissionAspect` 命中
  `ignore=true` 时只是自己不再 `enter()` 而直接 `point.proceed()`，处理器依旧会被回调，用户照做后问题仍在。
  现改为指向真正可行的机制（语句级 `@InterceptorIgnore(dataPermission = "true")` / 独立 Mapper 语句 /
  不经 Mapper 的通道），并把「为什么无效」及三条可行路径写进注释，避免后来者重踩。
  测试：`AdminDataScopeHandlerTest` 捕获日志文本，断言文案包含可行机制且不再出现旧文案。
- **在线用户姓名回填改为跨租户全局**（`SysUserServiceImpl#pageOnlineUsers`）：姓名批量回填原先直接走
  基类 `listByIds`，会被租户行拦截器追加 `tenant_id` 条件（`sys_user` 不在 `ignore-tables`）⇒
  平台管理员**只能回填到本租户姓名，他租户行 `realName` 静默为 null**。而在线会话本身是跨租户的
  （该接口挂在 `OnlineUserController` 的 `@PlatformAccess` 平台级语义下），故改为
  `TenantContext.executeIgnore(() -> listByIds(ids))`。此处**无需** `@InterceptorIgnore`：数据权限只在
  `@DataPermission` 作用域内才拼条件，本方法未标注、不产生额外查询。
  测试：`SysUserServiceImplOnlineUserTest` 断言他租户在线用户同样回填到姓名、查询时租户过滤已关且退出后复位、
  异常路径不吞异常也不泄漏忽略状态。
- **写路径补 `deptId` 数据范围校验**（`SysUserServiceImpl#createUser/updateUser` +
  `AdminDataScopeHandler#isDeptWithinScope`）：`@DataPermission` 只把范围条件拼进被标注方法内已发出的 SQL，
  而写方法的 `deptId` 是**请求入参**、不经过任何查询（`createUser` 更是完全没有 `@DataPermission`）⇒
  部门范围管理员可把用户建/改到任意部门。现两个写方法**先鉴权再校验/落库**，调用
  `isDeptWithinScope`（与读路径共用 `resolveScope` 与 `RESOLVING` 递归标记，**不新写第二套口径**）：
  平台超管或任一角色「全部数据」⇒ 不限；否则 `deptId` 必须在可见部门集合内（「本部门及以下」/「自定义」
  照常展开部门树后代）。越界、以及 `deptId` 为空（读路径 `dept_id IN (...)` 匹配不到 NULL 行）一律
  抛友好业务错误「目标部门不在你的数据范围内」，不静默忽略、不落到数据库层。
  **代价**：每次写操作新增 2～4 次查询（超管判定 + 角色 + 按需部门树/角色-部门），调用点不在任何循环内。
  **语义变化**：部门范围操作者现在**不能**把用户 `deptId` 置空或改到范围外（原先可静默做到，之后自己再也读不到）。
  测试：`AdminDataScopeHandlerTest`（部门范围可写本部门/拒他部门、超管不受限、部门树后代展开、四类 fail-closed）、
  `SysUserServiceImplDeptScopeTest`（越界必须友好报错且不落库/不查重、在范围内放行继续执行）。

### 文档

- 新增 [`docs/permission-rollout.md`](docs/permission-rollout.md)：`@SaCheckPermission` 灰度开启手册
  ——生效三条件与现状、一键回滚（`ypbin.security.interceptor` 置回 `false` 即整体不装配）、
  开启前两个硬前置（先给租户角色勾菜单；system 的 `interceptor: false` 注释已部分过期，须先在 auth
  验证登录校验链路再动 system）、auth → system → ai 的分步开启表、三个角色的验收清单与回滚演练、
  已知风险（鉴权依赖 system 可用、失效发生在事务提交前的残留竞态、前端 403 只弹 toast 不跳页）。

### 说明（本轮刻意未做的两件事）

- **未改动任何 `ypbin.security.interceptor` 开关**（system/ai 仍为 `false`）⇒ 对现网零行为影响。
- **未给 `/social/bind/{source}`、`/social/unbind/{source}` 加权限注解**。影响面分析建议补注解，但
  核实发现：auth 的 dataId **没有**该开关键 ⇒ 走 starter 默认 `true`，其 `SaInterceptor` 早已装配且
  `preHandle` 会先做方法注解校验（`sa-token-spring-boot-webmvc-v3v4-common:1.46.0` 字节码核实）⇒
  在 auth 上新加注解**立即生效**；而 `system:social:bind` / `system:social:unbind` 在 `sys_menu.auth_code`
  没有对应行、无法在界面授权 ⇒ 直接补注解会让**所有非超管用户部署即无法绑定/解绑第三方账号**，
  与「本 PR 对现网零影响」冲突。安全落地顺序（先补菜单行与授权、再加注解）与「维持仅需登录
  （与 `UserProfileController` 的自作用域既有约定一致）」两条路已写进上述文档，待明确选一条。

### 待决策与未处理（本轮盘点发现，未动手）

- **`dept_id IS NULL` 的用户对「部门范围」操作者不可见**：`dept_id IN (...)` 不匹配 NULL，
  这是数据范围语义的自然结果（与同类框架一致）。是否要 OR 出 `dept_id IS NULL`（放宽可见性）属产品决策。
  写路径已与读路径对齐（部门范围操作者不能把用户 `deptId` 置空，见上「写路径补 `deptId` 数据范围校验」）；
  若日后决定放宽读侧可见性，写侧的拒绝规则需同步评估。
- **`exportUsers` 双重注解**：`SysUserServiceImpl.exportUsers` 与 `UserExcelComponent.exportUsers`
  都标了 `@DataPermission`（跨 Bean 调用，切面各生效一次）。嵌套计数正确、无副作用，可择机去掉一处。
- **敏感词词库热更新未接**：改 `sys_config.SENSITIVE_WORDS` 需重启才生效（starter 装配期只取一次词）。
  若要即时生效，应在配置变更事件里调用 `SensitiveWordService#reload`（需处理 `enabled=false` 时 Bean 缺失）。
- **数据范围解析未加缓存**：每次回调 2～4 次查询（超管判定 + 角色 + 按需部门树/角色-部门）。
  缓存的失效点分散（角色、部门、用户角色变更），遗漏即越权，故本轮优先正确性；降本应作为独立改动评估。
- **在线用户分页仍是内存分页**：在线规模达到万级时应改为在会话侧维护可分页索引。
