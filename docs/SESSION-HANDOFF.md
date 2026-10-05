# SESSION-HANDOFF · ypbin-iot 断点交接（2026-10-01 更新）

> 用途：新会话读本文 + `docs/TASK-BOARD.md` 即可无缝接续，无需翻历史对话。
> 更新：2026-10-01（门面/#148 落库版；上一版同日稍早；O-7 版）。

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
- ✅ #10 flip 已开通（#157：收件人 wenbin_hyp@163.com，firing=0 无刷屏；163 端到端送达待用户验证）；
- ✅ #11 整项完成（含 O-7 #150）+ 加固批（签名 #160/IP 白名单 #167/配额可见 #168/验签补洞 #169/运维质量 #170/#171/#175）；
- ⏸ #8 二批（立项完成 PR #152，只立项不实施，触发条件见 PHASE2 §1）；
- ➖ #9（用户 2026-10-01 拍板：基本是内网项目，TLS 先不用管——维持自签 8883 与 1883 并存，不换正式 CA、不收回 1883、不做限来源）。
- 🔄 #10 投递观察中（163 送达待验证；firing 持续 0）。

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

- 后端 main `ee39bdaa`（#175）；前端 main `a0c8766`（#49 vxe-grid，之后仅 dependabot）；PR squash、CI 全绿才合；
- 门禁：check-iot-sql-equivalence.sh（007 与 migration 等价、顺序敏感）、arch 48（禁内联 FQCN）、
  iot 全量单测 ~818+、gateway 单测 4、Sync Whitelist（既有 admin 文件改动须白名单+SYNC 登记，现 22 项）、starter 版本最新 Release 检查。
  L2 对外契约/安全链改动须独立复核（#148 经 A–H 独立复核 + 2 变异转红）。

## 5. 下一步（建议顺序）

1. #8 二批立项设计 + 外委独立复核；
2. #10 送达验证（用户查 163 邮件）+ 持续观察 firing；
3. ~~#9~~ 已搁置（见 §2 用户拍板）。
   （#11 整项已完成并加固：O-1~O-7 + Key 页 + 限流 + 门面 + whoami + 签名/IP/配额可见；dev 部署中（容器 1h 前重建，健康 200/nokey-401  smoke 通过）。）

## 6. 遗留风险（如实）

- 限流 Redis 异常 fail-open（仅记日志）——429 语义缺失风险已登记；
- 网关配置随源码 application.yml 发布（nacos 3.x 无法脚本化更新），nacos 恢复 API 后可回退（SYNC 登记）；
- 平台告警通知收件人默认空、notify 默认关——开通前必须配齐。
