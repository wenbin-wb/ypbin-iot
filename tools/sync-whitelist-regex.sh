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
ALLOWED+='deploy/docker-compose\.yml|'
ALLOWED+='deploy/nacos/ypbin-gateway\.yaml|'
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
ALLOWED+=')$'

printf '%s' "$ALLOWED"
