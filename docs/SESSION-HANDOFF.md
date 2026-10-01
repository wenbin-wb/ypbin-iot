# SESSION-HANDOFF · ypbin-iot 断点交接（2026-10-01 更新）

> 用途：新会话读本文 + `docs/TASK-BOARD.md` 即可无缝接续，无需翻历史对话。
> 更新：2026-10-01（上一版 2026-09-30 已过时）。

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

- ✅：#7 盘点、#8 一批、#10 一批+前端列表+通知投递代码（默认关）、#11 第 1 批（Key+网关鉴权+e2e）与第 2 批（限流 429）、#12、#13、#14；
- 🔄 #10：观察期已开（YPBIN_PLATFORM_ALERT_ENABLED=true 判定器运行），阈值校准+notify flip 待观察数据；
- ⏸ #11 第 3 批：Key 管理前端页（菜单 3209+title 键已备）、门面、F-3、F-4；
- ⏸ #8 二批（上行结构化+实时跟踪，需设计立项）；#9（收 1883/正式 CA/限来源，需用户定节奏）。

## 3. 开放 API 第 1/2 批速览

- 链路：X-Api-Key ak:sk → 网关 OpenApiKeyAuthFilter（+3，调 iot /internal/open-api-key/verify，转签虚拟主体）
  → OpenApiRateLimitGlobalFilter（+4，Key 维度 QPS+日配额 429）→ RewritePath 复用既有端点；
- iot：iot_open_api_key 表（migration 2026-10-05 尾部+007 等价）、管理端点 /open-api-keys（权限码 key-*，菜单 3209）；
- 实测 dev：有效 Key 200 / 无 Key 401 / 错 Key 401 / 作用域隔离 403 / qps=1 连打 200 200 429 429 429；
- 造测试 Key：服务器 python（pepper=dev 值，HMAC-SHA256(full secret) 直接 INSERT）。

## 4. 仓库与门禁

- 后端 main `ae400e7e`；前端 main `98a03f2d`；PR squash、CI 全绿才合；
- 门禁：check-iot-sql-equivalence.sh（007 与 migration 等价、顺序敏感）、arch 48（禁内联 FQCN）、
  iot 全量单测 ~811+、gateway 单测 4、Sync Whitelist（既有 admin 文件改动须白名单+SYNC 登记，现 22 项）、starter 版本最新 Release 检查。

## 5. 下一步（建议顺序）

1. #11 第 3 批：Key 管理前端页（ypbin-iot-ui views/iot/openApiKeys/index.vue：签发 Modal 明文一次展示、列表 prefix/appName/scopes/status/expireAt、吊销确认；i18n page.iot.openApiKey.*）；
2. #11 门面 + F-3/F-4（api-doc / prod profile）；
3. #8 二批立项设计 + 外委独立复核；
4. #10 观察数据后阈值校准 → 配置 flip notify-enabled 开通；
5. #9 安全门禁按用户节奏。

## 6. 遗留风险（如实）

- 限流 Redis 异常 fail-open（仅记日志）——429 语义缺失风险已登记；
- 网关配置随源码 application.yml 发布（nacos 3.x 无法脚本化更新），nacos 恢复 API 后可回退（SYNC 登记）；
- 平台告警通知收件人默认空、notify 默认关——开通前必须配齐。
