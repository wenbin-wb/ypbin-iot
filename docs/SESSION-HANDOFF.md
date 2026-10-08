# SESSION-HANDOFF · ypbin-iot 断点交接（2026-10-08 更新）

> 用途：新会话读本文 + `docs/TASK-BOARD.md` 即可无缝接续，无需翻历史对话。
> 更新：2026-10-08（#10 平台告警写路径修复 + 通知开关单一来源；上一版 2026-10-01）。

## 0. 新会话第一句话（可直接粘）

> 继续 ypbin-iot 任务：先读 `docs/SESSION-HANDOFF.md` 与 `docs/TASK-BOARD.md`，按「下一步」推进；
> 命令围栏：compose 必须在 `deploy/` 目录运行（`.env` 按 cwd 查找）；凭据一律经 `deploy/.env` shell 变量读取，不打印值。

## 1. 环境与拓扑（开发/测试环境，非生产）

- 主机：`113.142.217.42`（ssh 别名 `ypbin-prod`，key `~/.ssh/id_ed25519_iot_test`）；用户已确认是开发测试环境，可放心调试/清理；
- 部署根 `/opt/ypbin/ypbin-iot`；容器 ypbin-iot（18084）、ypbin-gateway（18080）、ypbin-mysql（库 `ypbin_admin`）、
  ypbin-iotdb、ypbin-nacos（3.2.4，脚本化配置 API 已移除全 404）、ypbin-redis（有密码）、ypbin-iot-ui、EMQX（外部 43.242.200.8）；
- 凭据 `deploy/.env`（600）：GATEWAY_SIGN_TOKEN/INTERNAL_TOKEN/REDIS_PASSWORD/YPBIN_OPENAPI_SECRET_PEPPER 等；真值不入库不入日志。
- 踩过的坑：① compose 必须在 deploy 目录跑（.env 按 cwd）；② 容器 `${GATEWAY_SIGN_TOKEN}` 等须 compose environment 注入（已补键）；
  ③ 网关 GlobalFilter 顺序 sanitize(+1)<签发(+2)<OpenApiKeyAuth(+3)<限流(+4)，WebFilter 在 sanitize 前会丢转签头；
  ④ BaseEntity create_user/update_user 为 BIGINT（Long 用户 ID，fill 注入），裸 SQL 塞字符串会炸。

## 2. 看板状态（详见 docs/TASK-BOARD.md）

- ✅：#7 盘点、#8 一批、#10 一批+前端列表+通知投递代码（默认关）、#11 第 1 批（Key+网关鉴权+e2e）与第 2 批（限流 429）
  + 第 3 批 Key 管理前端页（前端 #46）+ 门面/whoami/F-3/F-4（后端 #148）、#12、#13、#14；
- ✅ #11 整项完成（含 O-7 #150）+ 加固批（签名 #160/IP 白名单 #167/配额可见 #168/验签补洞 #169/运维质量 #170/#171/#175）；
- ⏸ #8 二批（立项完成 PR #152，只立项不实施，触发条件见 PHASE2 §1）；
- ➖ #9（用户 2026-10-01 拍板：基本是内网项目，TLS 先不用管——维持自签 8883 与 1883 并存，不换正式 CA、不收回 1883、不做限来源）。
- ⚠️ #10 两个真缺陷已定位并修复（2026-10-08，分支 `eea5b858`，待 CI/合并）：
  ① **通知开关双源打架**——服务器 compose override 里的 `YPBIN_PLATFORM_ALERT_NOTIFY_ENABLED=false`
  （OS 环境变量，优先级高于 config data）覆盖了 Nacos live 的 `notify-enabled: true` ⇒ #157 的 flip **实际没生效**；
  已删除该 env 键（通知开关单一来源 = Nacos），dev 容器 env 已实证 `NOTIFY_ENV=<unset>`。
  ② **写路径整条不通**——`iot_platform_alert.tenant_id` 为 NOT NULL，而开单在 `TenantContext.runIgnore` 下
  租户拦截器不补值 ⇒ 每次 FIRING 都被库拒绝（`Column 'tenant_id' cannot be null`），
  「观察期 firing 恒 0」把这条彻底掩盖了（该表此前**一行都没有**）。
- ✅ #10 dev 端到端实证（2026-10-08，修复后部署）：造真实入站丢弃 → 判定 PENDING→FIRING→RESOLVED 全链落库
  （`iot_platform_alert` 1 行、`tenant_id=1`、`observed_rounds=2`、13:55:40 开单 / 13:56:41 收口），
  容器启动后 tenant_id 报错 **0 条**；FIRING/RESOLVED 两次通知**无失败日志**（iot 侧 WARN / system 侧 ERROR 均无）——
  **邮件是否到达 163 邮箱**已由用户确认：两封都收到**（FIRING + RESOLVED）⇒ 端到端闭环。

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

- 后端 main `b70cdd14`（#183 收尾回写）+ 本轮平台告警修复 **PR #184**（`eea5b858` 代码修复 + `0160464b`/`8b89fb10` 文档回写）；前端 main `a0c8766`；PR squash、CI 全绿才合；
- starter master `449a395`（v3.8.0 已发版，开发版 3.8.1-SNAPSHOT）；
- 🔴 **dev 镜像与容器 jar 已不一致（P0 运维债，已实测）**：镜像 `ypbin/ypbin-iot:local`（构建于 2026-10-05T12:54Z）
  内 `/app/app.jar` md5 **`9212136454d2…`**，而容器内实际运行的是 docker cp 热换进去的 **`2472d8faba1b…`**
  （2026-10-08 修复版）⇒ **任何 `compose up`（recreate）都会静默回退到 #182 之前的旧代码**，health 仍 200、无任何告警。
  收敛动作：镜像源恢复后 `docker compose build ypbin-iot && up -d --no-deps --force-recreate`，并核对
  「构建机 jar = 镜像内 jar = 容器内 jar」三方 md5 一致。回滚资产：`/opt/ypbin/ypbin-iot/backup/app-*.jar`；
- 门禁：check-iot-sql-equivalence.sh（007 与 migration 等价、顺序敏感）、arch 48（禁内联 FQCN）、
  iot 全量单测 ~818+、gateway 单测 4、Sync Whitelist（既有 admin 文件改动须白名单+SYNC 登记，现 22 项）、starter 版本最新 Release 检查。
  L2 对外契约/安全链改动须独立复核（#148 经 A–H 独立复核 + 2 变异转红）。

## 5. 下一步（建议顺序）

1. **合并本次修复**（分支 `eea5b858`：平台告警 tenant_id）——CI 绿后 squash；合后 dev 侧无需再动（容器已是该 jar）。
2. **dev 镜像收敛**（P0 运维债，见 §4）：镜像源可用后正规 `build` + `--force-recreate`，核对三方 md5；
   在收敛前**禁止**对 `ypbin-iot` 做 recreate/down-up（会静默回退旧代码）。
3. **#10 后续（数据驱动）**：写路径与通知链路已实证可用；下一步是让真实 FIRING 自然出现后校准阈值
   （现有 4 条规则里只有「评估器停摆」的 45s 是实测定的，其余三条是「增长即告警」，恒 0 未观测）
   与 163 送达确认（**已确认：两封都收到**）。
4. **#8 二批**：触发条件仍未满足（`docs/MESSAGE-TRACE-PHASE2.md` §1）⇒ 维持只立项不实施；**#9** 已搁置。

## 6. 遗留风险（如实）

- 限流 Redis 异常 fail-open（仅记日志）——429 语义缺失风险已登记；
- 网关配置随源码 application.yml 发布（nacos 3.x 无法脚本化更新），nacos 恢复 API 后可回退（SYNC 登记）；
- 平台告警通知收件人只有外部邮箱、`recipient-user-ids` 为空（站内信通道空跑）；平台告警实例/收件人固定写主租户
  1（多租户部署需显式裁定，已在 `PlatformAlertProperties.PLATFORM_TENANT_ID` 与通知器注释登记）；
- dev `ypbin-iot` 容器 jar ≠ 镜像 jar（热换未收敛），见 §4。
