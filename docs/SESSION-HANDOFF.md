# SESSION-HANDOFF · ypbin-iot 断点交接（2026-10-10 更新）

> 用途：新会话读本文 + `docs/TASK-BOARD.md` 即可无缝接续，无需翻历史对话。
> 更新：2026-10-10（R8 技术债：立项 → R8-7 → R8-4 → R8-9 → R8-5+R8-6 → **R8-2 方案 A+E**；另含 site 依赖策略与配置参考页修复）。

## 0. 新会话第一句话（可直接粘）

> 继续 ypbin-iot 任务：先读 `docs/SESSION-HANDOFF.md` 与 `docs/TASK-BOARD.md`，按「下一步」推进；
> 命令围栏：compose 必须在 `deploy/` 目录运行（`.env` 按 cwd 查找）；凭据一律经 `deploy/.env` shell 变量读取，不打印值。

## 1. 环境与拓扑（开发/测试环境，非生产）

- 主机：`113.142.217.42`（ssh 别名 `ypbin-prod`，key `~/.ssh/id_ed25519_iot_test`）；用户已确认是开发测试环境，可放心调试/清理；
- 部署根 `/opt/ypbin/ypbin-iot`；容器 ypbin-iot（18084）、ypbin-gateway（18080）、ypbin-mysql（库 `ypbin_admin`）、
  ypbin-iotdb、ypbin-nacos（3.2.4；**v1 配置 API 已移除（全 404），但 v3 admin API 可用**——`/nacos/v3/auth/user/login` +
  `/nacos/v3/admin/cs/config` 的 GET/POST，2026-10-08 实测读写均通，工具见 `tools/set-nacos-flag.py`）、ypbin-redis（有密码）、ypbin-iot-ui、EMQX（外部 43.242.200.8）；
- 凭据 `deploy/.env`（600）：GATEWAY_SIGN_TOKEN/INTERNAL_TOKEN/REDIS_PASSWORD/YPBIN_OPENAPI_SECRET_PEPPER 等；真值不入库不入日志。
- 踩过的坑：① compose 必须在 deploy 目录跑（.env 按 cwd）；② 容器 `${GATEWAY_SIGN_TOKEN}` 等须 compose environment 注入（已补键）；
  ③ 网关 GlobalFilter 顺序 sanitize(+1)<签发(+2)<OpenApiKeyAuth(+3)<限流(+4)，WebFilter 在 sanitize 前会丢转签头；
  ④ BaseEntity create_user/update_user 为 BIGINT（Long 用户 ID，fill 注入），裸 SQL 塞字符串会炸；
  ⑤ **改 Nacos live 单键必须用 `tools/set-nacos-flag.py`**（按行号定位 + 发布前断言"恰好 1 行变化" + 发布后回读 + 改前逐字节备份）：
     2026-10-08 用 `content.replace(…,1)` 的临时脚本改 `alert.enabled`，命中全文**首个**匹配 ⇒ 实际关掉的是
     `ypbin.tenant.enabled`（租户隔离总开关）；靠"改后立即回读"发现、2 分钟内还原（评估=无运行时影响，详见 PROD-OPS-NOTES 第 16 行）；
  ⑥ **Nacos live 刷新对 `@ConfigurationProperties` 即时生效（无需重启）**：实测 `Refresh keys changed: [ypbin.alert.enabled]`
     → 下一 tick 评估器即停/即恢复；但 `@Scheduled` 的**周期**仍只在启动绑定，`@ConditionalOnProperty` 的装配也不会因 live 改而增删 Bean（PROD-OPS-NOTES 第 17 行）。

## 2. 看板状态（详见 docs/TASK-BOARD.md）

- ✅：#7 盘点、#8 一批、#10 一批+前端列表+通知投递代码（默认关）、#11 第 1 批（Key+网关鉴权+e2e）与第 2 批（限流 429）
  + 第 3 批 Key 管理前端页（前端 #46）+ 门面/whoami/F-3/F-4（后端 #148）、#12、#13、#14；
- ✅ #11 整项完成（含 O-7 #150）+ 加固批（签名 #160/IP 白名单 #167/配额可见 #168/验签补洞 #169/运维质量 #170/#171/#175）；
- ⏸ #8 二批（立项完成 PR #152，只立项不实施，触发条件见 PHASE2 §1）；
- ➖ #9（用户 2026-10-01 拍板：基本是内网项目，TLS 先不用管——维持自签 8883 与 1883 并存，不换正式 CA、不收回 1883、不做限来源）。
- ⚠️ #10 两个真缺陷已定位并修复（2026-10-08，**PR #184 已合并 → main `b32e6c34`**）：
  ① **通知开关双源打架**——服务器 compose override 里的 `YPBIN_PLATFORM_ALERT_NOTIFY_ENABLED=false`
  （OS 环境变量，优先级高于 config data）覆盖了 Nacos live 的 `notify-enabled: true` ⇒ #157 的 flip **实际没生效**；
  已删除该 env 键（通知开关单一来源 = Nacos），dev 容器 env 已实证 `NOTIFY_ENV=<unset>`。
  ② **写路径整条不通**——`iot_platform_alert.tenant_id` 为 NOT NULL，而开单在 `TenantContext.runIgnore` 下
  租户拦截器不补值 ⇒ 每次 FIRING 都被库拒绝（`Column 'tenant_id' cannot be null`），
  「观察期 firing 恒 0」把这条彻底掩盖了（该表此前**一行都没有**）。
- ✅ #10 dev 端到端实证（2026-10-08，修复后部署）：造真实入站丢弃 → 判定 PENDING→FIRING→RESOLVED 全链落库
  （`iot_platform_alert` 1 行、`tenant_id=1`、`observed_rounds=2`、13:55:40 开单 / 13:56:41 收口），
  容器启动后 tenant_id 报错 **0 条**；FIRING/RESOLVED 两次通知**无失败日志**（iot 侧 WARN / system 侧 ERROR 均无），
  且**邮件到达已由用户确认**（163 收件箱**两封都收到**，FIRING + RESOLVED）⇒ **端到端闭环**。
- ✅ **#10 已结项（2026-10-08）**：机制闭环、**无遗留阈值校准项**——三条计数规则（`round.failed`/`notify.failed`/入站丢弃）
  在健康平台结构上恒 0，"增长即告警"即正确口径；唯一需实测的「评估器停摆」有 45s=3×周期的实测依据。
  累计观察：修复前约 13h（1605 轮、firing=0）+ 修复后 111 轮（firing=0）≈ **1,716 轮无自发 FIRING**（四指标现值 `lag=7166ms`、其余 0）。
  ⚠️ 强度如实：窗口内**无真实故障注入** ⇒ 只证"正常运行期未误报"，不证"故障态已确证"；**保留运维动作**：
  首次自然 FIRING 出现时人工确认一次投递与观感（非待办）。
- ✅ **#10 失败态验收（2026-10-08 补）**：4 条规则里 **2 条真实注入通过**——
  `PLATFORM_EVALUATOR_STALLED`（`tools/set-nacos-flag.py` live 置 `alert.enabled=false`、**无需重启**：rounds 冻结 303 → lag 24s→45s+ →
  23:36:05 FIRING（observed_rounds 续期到 8）→ 置回 true → lag 回 ~8s → 23:40:06 RESOLVED）与
  `PLATFORM_INGEST_DROPPING`（13:55–13:56）。两次通知均无失败日志；**FIRING/RESOLVED 两封邮件已由用户确认收到**（2026-10-08：「两封都收到」）。
  **未注入**：`ROUND_FAILED`（需不健康轮次，唯一低风险手段动共享 Redis ⇒ 不做）、`NOTIFY_FAILING`（需设备告警投递失败，属功能性写路径，另立批）。
- ✅ **并入 upstream/main（2026-10-09，PR #189，真 merge 提交 `7a8be780`）**：纳入 upstream UP-6~UP-9（Nacos 配置导入 `SCRIPT_DIR`+自动枚举、
  XXL-JOB 口令加固、凭据卫生），保留本仓逐项加固；**`dry-run-merge` 判据修正为「冲突 ⊆ 白名单」**（旧判据与白名单策略自相矛盾，
  `b70cdd14`(10-07) 起每个 main 提交恒红、被当噪音）⇒ 此后 main 上该工作流恒绿；与 upstream/main 差 **0 提交**；
  CI 三绿（Upstream Sync Check / CI / CodeQL）。白名单抽成单一来源 `tools/sync-whitelist-regex.sh`，条目 22 → **20**（删 2 条死条目）。
- ✅ **分支与工作区收敛（2026-10-09）**：8 仓远端只剩 `main`/`master`（`ypbin-iot` 另留 1 条有意保留的分支）；本地会话分支 **33 条已删**
  （先做完整 bundle 归档：`temp/local-branch-archive-20261009-062750/*.bundle` + `manifest.json`，恢复命令 `git fetch <bundle> 'refs/heads/*:refs/heads/*'`）；
  10 个 worktree 全部清理（4 个已注册 + 6 个失效残留，游离 HEAD 已打 `archive/*-20261009` tag 保命）；`remote.origin.fetch` 恢复为全分支 refspec。
- ✅ **#15 访问层技术债 R8 系列逐条立项（2026-10-09，纯文档）**：`docs/ACCESS-TECHDEBT-R8.md`（R8-2/R8-4~R8-9，每条含一手证据/触发前提/方案/验收标准/建议顺序）。
  **只立项不实施**，排期仍为「扩展前」。**两处判定更正**：R8-7「无门禁」已过时（反向门禁自 PR #48/2026-09-26 已存在，真实缺口是覆盖面）；
  R8-9「iot 无指标暴露配置」已过时（#53 已补）。**两个「需 e2e 核实」推进为源码判定**：框架 0.2.0 每次 ADD 都换新会话实例
  ⇒ R8-5/R8-6 的静默零数据不成立，残余是「引用只增不减 + 隐式契约零测试」。**未跑 Maven** ⇒ 门禁实际通过状态未核实。
- ✅ **#16 R8-7 门禁覆盖面补齐（2026-10-09，PR #191 → main `55e3de3f`）**：扩展 `IotMaintenanceAdminGateTest`——扫描面加 `deploy/sql/migration/*.sql`（菜单属性另读 `002-data.sql`）、新增平台级菜单**显式清单**（`3206`/`320014`/`320015`/`3320`）、把「`INSERT … SELECT` 常量目标」的补授按**可达性**判定（子项全平台级 ⇒ 恒命中 0 行 = 潜在授权；任一子项非平台级 ⇒ 违规）、解析器看不见的目标形态**显式报出**（豁免陈旧也红）。门禁 5/0/0/0、iot 878/0/0/0、**变异 4/4 转红**。
- ✅ **#17 R8-4 epoch 对账按节点过滤（2026-10-09，PR #192 → main `773c6508`）**：`GET /internal/lease/epochs` 从「无入参全量」改为 `?accessNode=`（必填，空/空白 ⇒ `code=400`），过滤下沉 SQL（`WHERE a.access_node = ?` 走 `idx_tenant_node_assignment_node`、`ORDER BY tenant_id`、手写两条 `is_deleted = 0`）；客户端 `ConfigEpochReconciler` 构造期校验 nodeId 并按其做一次批量调用。**分页方案被 CI 架构门禁拦下后主动放弃**（分页把一次批量调用变成每页一次串行 RPC，反而增加 tick 开销）——见 `docs/ACCESS-TECHDEBT-R8.md` §2.2。真库 IT `LeaseEpochNodeFilterIT` 4/0/0/0、HTTP 层 `InternalLeaseControllerTest` 4/0/0/0、iot 883/0/0/0、**变异 4/4 转红**、架构门禁 20/0/0/0；**L2 独立复核无阻断项**（复核独立复现了「分页版红、收敛版绿」），据其建议整改 5 处（错误码语义/客户端 fail-fast/去读侧 trim/清死导入/IT 区间与双重 purge）。
- ✅ **顺手批（2026-10-09/10，其它仓）**：`ypbin-site#29` 关闭（根因：`typescript@7.0.2` 的 `exports` 不再导出 `./lib/tsc`，而 `vue-tsc` latest 3.3.12 仍按该子路径加载 ⇒ 上游无解）→ PR **#31** 并回 4 个兼容升级 + `dependabot.yml` 改为「组只收 minor/patch + typescript 主版本 ignore」（跟踪 issue **#30**）；**#34** 重生成站点配置参考页（373 → **397** 项，修 #28 只更元数据未重生成页面的漂移）；`ypbin-iot-ui#62` / `ypbin-admin-ui#68` 同款 dependabot 预防（两仓 catalog 也是 `typescript ^6.0.3` + `vue-tsc ^3.3.8`，Dependabot 确实会改 catalog 条目 ⇒ 风险同型）。
- ✅ **#18 R8-9 指标登记 + tick/对账耗时（2026-10-09，PR #194 → main `48afaa1b`）**：新增 `docs/METRICS.md`（`ypbin-access` **33 条**指标逐条登记：口径/建议关注/注册文件）；新增 **3 条 `Timer`** —— `iot.access.lease.tick.duration`、`iot.access.config.reconcile.duration`、`iot.access.spec.load.duration`（把 R8-2 的「单次最坏 6s」假设变成实测分布）；**双向门禁** `MetricsRegistryGateTest`（清单→代码按全名或后缀匹配、代码→清单要求完整字面量都登记，两侧带非空自检）。access **119/0/0/0**，**变异 3/3 转红**。
- ✅ **#19 R8-5 对称清理 + R8-6 框架契约（2026-10-09，PR #195 → main `9a568c19`；框架仓 PR #21 → `584b394`）**：本仓 `fence()` 补 `planner.forget`（与下架路径逐条对称，access **119/0/0/0**，变异 1/1 转红）；**框架仓** `ypbin-iot-starter` 新增契约测试 `IotLifecycleTest#everyAddMustCloseOldSessionAndRebindNewInstance`（LIFE-13）——「规格变化只重发 ADD」与「REMOVE→ADD」两条路径都必须**先关旧会话、再产生新实例**，并写入 `CONTRACT.md` §四 行为承诺第 7 条 + `CHANGELOG.md`（框架侧 **148/0/0/0** 含 JaCoCo 覆盖率门禁，变异 2/2 转红）。**这条契约正是 R8-5/R8-6 残余风险的唯一支点**（本仓订阅对账用会话实例同一性判断是否重新订阅）。
- 🔄 **#20/#21 R8-2（2026-10-09，PR #197 → `b284b30f`、PR #198 → `71484d37`）**：**方案 A 对账时间预算**（`ypbin.access.reconcile-budget-ms` 默认 15000 = TTL 一半；超预算的候选**推迟到下一 tick**、不丢；预算用尽时跳过周期安全网；新指标 `iot.access.config.reconcile.deferred`）+ **方案 E 拆调度线程池**（`spring.task.scheduling.pool.size: 2`，1s egress 上报不再排在 10s 长 tick 之后，含配置门禁 `AccessSchedulingConfigTest`）。access **122/0/0/0**，变异 1/1 + 1/1 转红。**R8-2 剩余 B/C/D**：B（有界并发）需处理并发安全；**C/D 以覆盖换成本，不建议做**。

## 3. 开放 API 第 1/2 批速览

- 链路：X-Api-Key ak:sk → 网关 OpenApiKeyAuthFilter（+3，调 iot /internal/open-api-key/verify，转签虚拟主体）
  → starter `AttributeRateLimitGlobalFilter`（+4，Key 维度 QPS+日配额 429；自研
  `OpenApiRateLimitGlobalFilter` 已删去重，接线见 `deploy/nacos/ypbin-gateway.yaml`
  的 `ypbin.gateway.rate-limit`）→ RewritePath **保留 /open-api/v1 前缀落到 iot 门面**
  （#148 前为剥全前缀复用既有端点；未映射路径 404，不穿透管理面）；
- iot：iot_open_api_key 表（migration 2026-10-05 尾部+007 等价）、管理端点 /open-api-keys（权限码 key-*，菜单 3209）、
  门面 /open-api/v1/**（O-1~O-6 只读薄委托 + whoami；O-7/写/active-counts 刻意不映射）、api-doc 分组文档（仅 /open-api/v1/**）；
- 实测 dev（#150 时口径；#160/#166 后行为收紧）：有效 Key+签名 200 / 无 Key 401 / 错 Key 401 / 作用域隔离 403（R.code）/ qps=1 连打 200 后 429 / 未映射路径 404 / whoami 200；**现强制签名模式**：纯 Key 无签名被拒、IP 不在白名单 403；配额用量可见（whoami/列表 `usedToday`）；
- 造测试 Key：服务器 python（pepper=dev 值，HMAC-SHA256(full secret) 直接 INSERT；e2e 完吊销并删本地 Key 文件）。

## 4. 仓库与门禁

- 后端 main `b32e6c34`（#184：平台告警 tenant_id 修复 + 通知开关单一来源 + 文档回写，CI 6/6 全绿后 squash；上一版 `b70cdd14` = #183）；前端 main `a0c8766`；PR squash、CI 全绿才合；
- starter master `449a395`（v3.8.0 已发版，开发版 3.8.1-SNAPSHOT）；
- ✅ **已并入 upstream/main（admin@`922d0d50`，真 merge 提交，非 squash）**：采纳 UP-6~UP-9 部署凭据卫生 + XXL-JOB 口令加固；冲突配方与逐文件处置见 `SYNC.md` 第三节（下次同步照抄即可）。
  ⚠️ 同步必须用 **merge 提交**并入（保留 upstream 祖先），否则干跑合并会再次报冲突；
- 🛠 **`dry-run-merge` 判据已修正**（2026-10-09）：从「必须无冲突」改为「**冲突 ⊆ 白名单**」；两处**假通过洞**已判死（白名单正则取空、非冲突原因失败）；白名单移除 2 个死条目后为 **20** 项（权威清单 = `tools/sync-whitelist-regex.sh`）；白名单单一来源 = `tools/sync-whitelist-regex.sh`（两个 workflow 共用，勿再内联第二份）。
- ✅ **dev 镜像与容器 jar 已收敛（2026-10-08，A 项完成）**：镜像源仍不可用（`docker pull alpine` FAIL），故**未**走 `compose build`，
  改为「FROM 旧镜像 + COPY 已实证 jar」重建 `ypbin/ypbin-iot:local`（新 ID `e1d414ca`，构建于 14:17Z），
  并把该 jar 放回服务器 `ypbin-service/ypbin-iot/target/`（旧 jar 已备份）⇒ 三方 md5 一致：
  镜像内 = 容器内 = 服务器 target = 本地构建制品 = **`2472d8faba1b…`**（health UP、`NOTIFY_ENV=<unset>`、tenant_id 报错 0）。
  回滚：旧镜像保留为 `ypbin/ypbin-iot:pre-converge-20261008-141749`（`cbe4c20782e9`，2026-10-05）；
  回滚资产 `/opt/ypbin/ypbin-iot/backup/app-*.jar`。
  ⚠️ **为什么没走"真正的 `compose build`"（2026-10-08 查清，不是"镜像源偶发抖动"）**：dev 应用机**当前无出网**——
  `registry-1.docker.io/v2/`、`repo1.maven.org/maven2/`、`github.com` 三个端点 20s 全部超时（curl exit 124），
  `docker pull alpine` FAIL（本机侧 git/PR 正常）。⇒ 该机上既拉不到 `eclipse-temurin:21-jre` 基础镜像，也无法用 Maven 自行构建 jar
  ⇒ **构建机出制品 + 传 jar + 刷新镜像/recreate 是该环境的唯一可行路径**。另注：镜像 lineage 现来自旧镜像（`FROM <旧镜像> + COPY jar`）；
  且服务器检出停在 `ad80e3f`（`deploy/*` 有运维本地改动）⇒ **不要在那台机上盲目 `compose build`**（会把陈旧源码打进镜像）；
- 门禁：check-iot-sql-equivalence.sh（007 与 migration 等价、顺序敏感）、arch 48（禁内联 FQCN）、
  iot 全量单测 ~818+、gateway 单测 4、Sync Whitelist（既有 admin 文件改动须白名单+SYNC 登记，**现 20 项**，白名单单一来源
  = `tools/sync-whitelist-regex.sh`）、starter 版本最新 Release 检查。
  L2 对外契约/安全链改动须独立复核（#148 经 A–H 独立复核 + 2 变异转红）。

## 5. 下一步（建议顺序）

1. ~~**合并本次修复**~~ **已合并**（#184 → main；随后 #185 文档、#186 镜像收敛+测试修复，CI 均 6/6）；dev 侧无需再动（容器已是该 jar）。
2. ~~**dev 镜像收敛**~~ **已完成**（2026-10-08，见 §4）。**临时禁手已解除**：recreate 不会再回退旧代码（镜像内已是修复版 jar）。
3. ~~**#10 平台自告警**~~ **已结项（2026-10-08，看板转 ✅）**：机制闭环、**无遗留阈值校准项**
   （三条计数规则恒 0 属预期、无需再定阈值；「评估器停摆」45s 有 3×周期实测依据；链路已端到端证明；
   累计 ~1,716 轮无自发 FIRING）。**保留的运维动作（非待办）**：首次自然 FIRING 出现时人工确认一次投递与观感。
4. **#8 二批**：触发条件仍未满足（`docs/MESSAGE-TRACE-PHASE2.md` §1）⇒ 维持只立项不实施；**#9** 已搁置。
5. 若要在 dev 上做"故障注入式验收"或"更长观察窗口"，另立验收批（不要把它当成 #10 的默认尾巴）。
6. **R8 技术债进度（2026-10-10）**：已立项 **#15**；已完成 **R8-7**（#16）、**R8-4**（#17）、**R8-9 A/B**（#18）、**R8-5 + R8-6**（#19 + 框架仓 #21）、**R8-2 方案 A + E**（#20/#21）。
   **仅剩 R8-8**（bump 与业务写入同事务）：**需先拍板「台账故障时宁可少采（不一致）还是宁可写不进去（不可用）」**，之后才谈守卫与真库用例；R8-2 的 B/C/D 已按「不值得」登记（见 `docs/ACCESS-TECHDEBT-R8.md` §2.1）。
7. 其他未开工方向（用户已提及、未选定）：**D**（`TASK-BOARD.md` §3 的 P2 增强项）；**访问控制技术债之外的**技术债批次。

## 6. 遗留风险（如实）

- 限流 Redis 异常 fail-open（仅记日志）——429 语义缺失风险已登记；
- 网关配置随源码 application.yml 发布，当时的理由是"nacos 3.x 无法脚本化更新"——**该理由 2026-10-08 已被证伪**
  （v1 全 404，但 v3 admin API 读写均通，见 §1）⇒ "网关配置可回退到 Nacos"这件事**证据已成立、决策未做**，属待评估项
  （SYNC 白名单里那条登记可据此复核）；
- 平台告警通知收件人只有外部邮箱、`recipient-user-ids` 为空（站内信通道空跑）；平台告警实例/收件人固定写主租户
  1（多租户部署需显式裁定，已在 `PlatformAlertProperties.PLATFORM_TENANT_ID` 与通知器注释登记）；
- dev `ypbin-iot` 镜像 lineage 非 `compose build` 产物（由旧镜像 + COPY jar 得到，见 §4）；
- **dev 应用机当前无外网出口**（registry / Maven Central / GitHub 三端点 20s 超时）⇒ 该机上不能 `docker pull`、不能 `mvn` 拉依赖、
  不能 `compose build`；部署只能走"本机构建 → 传 jar"（见 §4）。恢复出网前不要在该机尝试构建类操作；
- 服务器检出停在 `ad80e3f`（且 `deploy/*` 有运维本地改动）⇒ 直接在那台机 `compose build` 会打包陈旧源码，属陷阱（见 §4）。
