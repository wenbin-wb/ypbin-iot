# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 修复

- **CI 门禁收口（issue #233 / #235）**：
  - `ci.yml`「检出 ypbin-starter」由 `ref: master` 改为 **按声明的 Release tag**（`v<ypbin-starter.version>`）——
    此前该步先断言「声明版本 == 最新 Release」却检出 master 编译，**声明与实际的代码是两回事**：master 上有而
    Release 里没有的 API 会让 CI **假绿**（任何干净机器/外部使用者都会编译失败）；
  - **真库 IT 纳入 main 推送触发**（`iot-integration-tests.yml` / `iot-iotdb-it.yml`）：此前只在 PR 与 `feat/**` 跑，
    合并之后的 main **没有真库回归** —— 两个 PR 各自绿、叠加后互相影响时无人拦（成对代价：每次 main 合并多跑两个分钟级作业）；
  - **`Central Resolution Build` 也纳入 main 推送**：合并后的 main 必须仍是「只用已发布制品就能构建」的状态。

### 变更

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
  文档一致性核对中发现的 4 处不一致已列入手册附录 C（未在手册里静默改其它文档）。
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
