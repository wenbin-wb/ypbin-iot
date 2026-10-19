#!/usr/bin/env bash
# 同步白名单的**唯一来源**：打印「与 upstream（ypbin-admin）的分歧面」允许的文件正则。
#
# 谁在用：
#   1) `.github/workflows/sync-whitelist.yml`：校验 PR 里**改动的既有 admin 文件**都在白名单内；
#   2) `.github/workflows/upstream-sync.yml`：干跑 `merge upstream/main`，要求**冲突文件**都在白名单内
#      （2026-10-09 之前该工作流要求「必须无冲突」——但白名单策略本身就保证这些文件必然冲突，
#       于是这个检查在 main 上**恒红**、被当噪音；判据已改成"冲突 ⊆ 白名单"，见 SYNC.md 第二节）。
#
# 纪律：
#   * 本文件与 `SYNC.md` 第二节的清单必须保持一致（改了这里就改 SYNC.md，反之亦然）；
#   * 新增一项前先问「能不能用新文件/新模块实现」；只能改既有文件时才加，并在 SYNC.md 写明理由与代价；
#   * 逐项是**精确路径正则**（`^…$` 锚定，不是前缀匹配）。
set -euo pipefail

ALLOWED='^('
ALLOWED+='pom\.xml|'
ALLOWED+='ypbin-service/pom\.xml|'
ALLOWED+='ypbin-service-api/pom\.xml|'
ALLOWED+='deploy/install\.sh|'
ALLOWED+='\.github/workflows/ci\.yml|'
ALLOWED+='deploy/docker-compose\.yml|'
ALLOWED+='deploy/nacos/ypbin-gateway\.yaml|'
# 第 23 项（2026-10-14 加）：xxl-job 3.5.0 **成对升级**要求执行器 AccessToken 必填
# 且与 admin 中同名 AppName 一致；该键位于 admin 侧 Data ID ypbin-system.yaml 的 xxl-job 段，
# 无法用新文件表达（Spring 配置按 Data ID 加载）。理由与代价见 SYNC.md 第二节。
ALLOWED+='deploy/nacos/ypbin-system\.yaml|'
ALLOWED+='deploy/\.env\.example|'
ALLOWED+='docs/microservice-deployment\.md|'
ALLOWED+='ypbin-architecture-tests/src/test/java/cn/ypbin/admin/arch/SourceConventionTest\.java|'
# 第 10 项（2026-09-27 加）：修 admin 既有代码里的**缺陷**，无法用新文件实现
# （问题就在该方法「每次调用新建 HttpClient 且未指定协议版本」那一行上）。理由与代价见 SYNC.md 第二节。
ALLOWED+='ypbin-service/ypbin-ai/src/main/java/cn/ypbin/admin/ai/service/impl/AiModelConfigServiceImpl\.java|'
# 第 11–17 项（2026-09-28 加）：告警通知投递必须复用 admin 的 sys_message 表与
# system 侧既有 Feign 契约/JavaMail，新增端点只能落在既有契约/实现/兜底上（见 SYNC.md 第二节）。
ALLOWED+='ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClient\.java|'
ALLOWED+='ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClientFallback\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/feign/SystemClientImpl\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/mapper/SysMessageMapper\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/test/java/cn/ypbin/admin/system/feign/SystemClientImplUserByIdTest\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/test/java/cn/ypbin/admin/system/feign/SystemClientImplPlatformUserTest\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/test/java/cn/ypbin/admin/system/feign/SystemClientImplLogIngestTest\.java|'
# 第 37 项（2026-10-19 加，#87 脱敏口径统一同步）：本文件在 admin 里由 admin 新增（#87 修复），
# 本仓同步时因 `SystemClientImpl` 构造器比 admin 多两个依赖（`SysMessageMapper`/`MailService`，
# 本仓独有的站内信/邮件内部端点）而**必须不同** ⇒ 与 upstream 构成 add/add 冲突。
# 为什么不能用新文件表达：被测类构造器签名不同，测试的构造参数必须跟随本仓实现。
ALLOWED+='ypbin-service/ypbin-system/src/test/java/cn/ypbin/admin/system/feign/SystemClientImplConfigMaskTest\.java|'
# 第 18 项（2026-09-29 加）：README 整体重写为 IoT 版（M-6）。
# 无法用新文件实现：GitHub 默认渲染 README.md；而 README 的能力清单属**对外陈述**，
# 与实际不符不能用「另写一份」补救。代价：上游常改 README ⇒ 下次同步必然冲突，整体取本仓版本。
ALLOWED+='README\.md|'
# 第 19 项（2026-09-29 加）：补 identity 入口侧密钥（#6b 部署阻塞面）。
# 问题就**在这一行**——`ypbin.security.identity.enabled=true` 必须同时给出 trusted-source-token，
# 否则 starter 3.6.0 起启动失败；新增文件无法表达「给既有键补一个兄弟键」。
ALLOWED+='deploy/nacos/ypbin-common\.yaml|'
# 第 20 项（2026-10-01 加）：网关配置随源码发布（routes/exclude 等）。
# ⚠️ 2026-10-08 更正：当时"nacos 3.x 服务端脚本化读写 API 已移除（全 404）"的理由**不成立**——
# v1 全 404，但 v3 admin API 读写均可用（工具见 tools/set-nacos-flag.py）。是否回退到 Nacos 待评估。
ALLOWED+='ypbin-gateway/src/main/resources/application\.yml|'
# 2026-10-09 移除 2 个**死条目**（`deploy/sql/007-iot-data.sql` 与
# `deploy/sql/migration/2026-10-05-iot-platform-alert-schema.sql`）：这两份文件在
# upstream/main **不存在**（git ls-tree 922d0d50 deploy/sql/ 只有 001–005 + 2026-09-17 迁移），
# 而两个门禁的判据都是「与 upstream 的差异」⇒ 它们永远不会被命中，留着只会让白名单计数虚高。
# 第 21 项（2026-10-12 加）：CHANGELOG.md 要记录本批 deploy/IoT 改动（发布台账/对外门面），
# 无法用新文件表达——GitHub 渲染与发布流程都只认根 `CHANGELOG.md`，另写一份不会被看到。
# **代价如实登记**：与 upstream 该文件冲突时整体取本仓版本（同 README.md 的处理方式）。
ALLOWED+='CHANGELOG\.md|'
# 第 24 项（2026-10-15 加）：SSE 实时流必须关闭 nginx 代理缓冲，否则事件会被攒在
# proxy buffer 里、用户看到「实时流不动」。问题就在既有的 /api/ location 上（无缓冲 = 改这个文件），
# 无法用新文件表达。代价：与 upstream 该文件冲突时按语义合并（保留 SSE 三行 + 采纳上游其它改动）。
ALLOWED+='deploy/nginx-microservice\.conf|'
# ---------------------------------------------------------------------------
# 第 25–34 项（2026-10-18 加）：**全量切到 ypbin-starter 4.0.0 官方能力**（issue #289/#290，
# 用户明确要求「不用考虑兼容旧的服务，全部按新版重构」）。
# 这一批的共同点：被删/被改的都是**admin 既有文件**（fork 继承），下沉能力的替代物在 starter 里，
# 无法用「新建文件」表达；删掉它们正是「消除重复」的**唯一**做法（保留 = 与上游继续各写一份）。
# **代价如实登记**：这 10 个文件上游若再改动，同步时会以 modify/delete 或文本冲突暴露，
# 处置方式统一为「**采纳上游的演进，并把同样的下沉再删一次**」——因为本仓的删除理由（已下沉到
# starter）对上游同样成立，正确解是上游也删（见下方各条备注）。
# ---------------------------------------------------------------------------
# 内部凭证：自建拦截器/过滤器/常量/Feign 配置 → starter 的 InternalTokenGuardAutoConfiguration
# + CloudFeignAutoConfiguration 全局携带（配置键 ypbin.security.internal-token.* / ypbin.cloud.feign.*）。
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/config/InternalTokenGuardInterceptor\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/config/InternalTokenGuardWebConfig\.java|'
ALLOWED+='ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/constant/InternalTokenConstants\.java|'
ALLOWED+='ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/config/InternalTokenFeignConfiguration\.java|'
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/config/InternalProperties\.java|'
# 租户来源：自建 Provider/自动装配 → starter 的 IdentityTenantProvider（4.0.0 新增）。
# ⚠️ imports 文件是**部分编辑**（只删 MicroserviceTenantAutoConfiguration 一行，保留 RemoteLog/RemoteTrack）。
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/provider/MicroserviceTenantProvider\.java|'
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/provider/MicroserviceTenantAutoConfiguration\.java|'
ALLOWED+='ypbin-common/src/main/resources/META-INF/spring/org\.springframework\.boot\.autoconfigure\.AutoConfiguration\.imports|'
# 随上面删除而必须同步的**连带更新**（去掉 @EnableConfigurationProperties(InternalProperties)、
# 改一处引用注释、Feign 客户端去掉 configuration 属性）。
# 第 36 项（2026-10-18 加，#87 脱敏口径统一）：admin 的 SysConfigServiceImpl#toResp 用「2 个精确键 + 置空 ""」
# 实现列表页脱敏，与 internal 端点的「6 个后缀 + ****末4位」判据不同 ⇒ 存在「internal 打码、列表页返回明文」
# 的**真实泄露面**。修复必须改这个既有方法（脱敏判据与形态要从这里切到 ConfigValueMasker，
# 无法用新文件表达「换掉既有方法的脱敏实现」）；同时该类的 createConfig/updateConfig/updateGroup 三条
# 写路径要加「掩码值不落库」守卫（否则列表页回显掩码后保存会把真实密钥覆盖成 ****abcd）。
# ConfigValueMasker 与其 3 个测试是**新文件**，不在白名单内（新文件不受限）。
# **代价如实登记**：该文件上游若再改脱敏逻辑，同步时按语义合并——正确处理是「采纳上游演进 + 保留
# 指向 ConfigValueMasker 的单一判据」，而非恢复旧的精确键清单（那会重新引入泄露面）。
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/service/impl/SysConfigServiceImpl\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/SystemServiceApplication\.java|'
ALLOWED+='ypbin-service/ypbin-system/src/main/java/cn/ypbin/admin/system/provider/DbLogProviders\.java|'
ALLOWED+='ypbin-service-api/ypbin-system-api/src/main/java/cn/ypbin/admin/system/api/feign/ISystemClient\.java|'
# ---------------------------------------------------------------------------
# 第 38–46 项（2026-10-18 加）：消费 starter 4.1.0 的 **starter #100**
# （日志/埋点跨服务上报的自动配置与落库实现上收到基座）。
# 这一批被删/被改的都是 **admin 既有文件**：本仓原有一套自建的
# 「上报自动配置 + 上报用 LogDao/TrackEventSink」，starter 4.1.0 把它们上收到基座后
# 正确形态是「本仓只留 2 个 SPI 实现 + 1 个注册 SPI 的自动配置」。
# 无法用「新建文件」表达，因为要表达的是**删除既有实现**（保留 = 与上游继续各写一份重复实现）。
# **代价如实登记**：这 9 个文件上游若再改动，同步时会以 modify/delete 或文本冲突暴露；
# 处置方式统一为「采纳上游的演进，并把同样的上收再落一次」——本仓的删除理由（已上收到 starter）
# 对上游同样成立，正确解是上游也删。
# ---------------------------------------------------------------------------
# 删除的自建自动配置与实现（4 个主源码类）
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/log/RemoteLogAutoConfiguration\.java|'
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/log/RemoteLogDao\.java|'
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/tracking/RemoteTrackAutoConfiguration\.java|'
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/tracking/RemoteTrackEventSink\.java|'
# 连带更新：identity provider 的注释改指新装配；LoginEventTracker 注释；两个既有单测随实现迁移
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/log/IdentityHeaderLogUserProvider\.java|'
ALLOWED+='ypbin-auth/src/main/java/cn/ypbin/admin/auth/service/LoginEventTracker\.java|'
ALLOWED+='ypbin-auth/src/test/java/cn/ypbin/admin/auth/log/AuthLogIngestTest\.java|'
ALLOWED+='ypbin-common/src/test/java/cn/ypbin/admin/common/log/RemoteLogDaoTest\.java|'
ALLOWED+='ypbin-common/src/test/java/cn/ypbin/admin/common/log/RemoteLogWiringTest\.java|'
# 第 47 项（2026-10-19 加）：`RemoteLogDaoTest.java` 的**重命名**（R086）——
# 随 starter #100 把「上报实现」从自建 LogDao 换成 SPI 实现，该测试的被测对象同步改名
# （`SystemRemoteLogIngestClientTest`），断言口径逐条保留。Git 按重命名记录（R086），
# 但 Sync Whitelist 门禁比对的是**新路径**，故新名字必须单独登记（旧名字条目保留，
# 因为上游若删除/改动旧文件仍要命中白名单）。
ALLOWED+='ypbin-common/src/test/java/cn/ypbin/admin/common/log/SystemRemoteLogIngestClientTest\.java|'
# 第 48–52 项（2026-10-19 加）：补齐 admin #88 的 5 处漏同步（iot 是 admin fork）。
# 这些是「admin 已修、iot 未跟随」的真实缺口，不是刻意分歧；改动只能落在既有文件上：
#   - `AdminConstants`：删 3 个零引用死常量 + 通配权限码改引用 starter 的
#     `StpPermissionAdapter.SUPER_ADMIN`（同一契约两个定义点，漂移即静默越权/漏权）；
#   - `AiEmbeddingConfigResolverImpl`：`orElse(1L)` 静默回退默认租户改为显式失败
#     （与同模块 Chat resolver 同口径，属「禁静默降级」红线）；
#   - 4 个 auth 类：登录开关配置键此前各自 `private` 重复定义（改一处漏一处只会让某入口
#     静默失效），收敛到新文件 `LoginConfigKeys`（新文件不受白名单限制）。
# 为什么不能用新文件表达：问题就在这些既有类的方法体/常量定义上。
ALLOWED+='ypbin-auth/src/main/java/cn/ypbin/admin/auth/controller/CaptchaController\.java|'
ALLOWED+='ypbin-auth/src/main/java/cn/ypbin/admin/auth/controller/SmsLoginController\.java|'
ALLOWED+='ypbin-auth/src/main/java/cn/ypbin/admin/auth/service/AuthService\.java|'
ALLOWED+='ypbin-auth/src/main/java/cn/ypbin/admin/auth/service/PhoneLoginStrategy\.java|'
ALLOWED+='ypbin-common/src/main/java/cn/ypbin/admin/common/constant/AdminConstants\.java|'
ALLOWED+='ypbin-service/ypbin-ai/src/main/java/cn/ypbin/admin/ai/core/AiEmbeddingConfigResolverImpl\.java|'
ALLOWED+=')$'

printf '%s' "$ALLOWED"
