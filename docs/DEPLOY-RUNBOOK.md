# ypbin-iot 上线运行手册（DEPLOY-RUNBOOK）

> **单一入口**：上线前检查（Pre-flight）→ 升级步骤 → 上线后核验 → 回滚 → 故障速查。
> 面向「要执行一次升级 / 首装的人」，命令可照抄、判据可核对、动作可回滚。
>
> **本文的事实纪律**：所有指标名 / 配置键 / 阈值 / 命令都取自仓内既有文档或代码，逐条给出出处；
> 未在真机/真库验证的期望值显式标注。发现文档互相矛盾的地方**不在本文静默改**，见文末「文档一致性清单」。

**来源标记**（每条判据后标注其一）：

| 标记 | 含义 |
|---|---|
| `[文档 §]` | 直接取自仓内文档（给出文件与章节） |
| `[代码 file:line]` | 取自仓内代码常量 / 校验逻辑 |
| `[单测]` | 有单测/契约测试证据，但未真机验证 |
| `[生产实测]` | 文档中记录的现场实测 |
| `[未核实]` | 本机无真机/真库，或当前生产无对应设备/组件 ⇒ **不要当作已达成** |

**本文覆盖 / 不覆盖**：覆盖一次「升级」或「首装」的检查、步骤、核验、回滚、速查；
**不覆盖** EMQX 集群、Prometheus/Grafana 大盘、镜像制品化剩余、K8s/HA 等（详见 §F）。

---

## 0. 五分钟速览（先看这个）

| 阶段 | 一句话 | 跳转 |
|---|---|---|
| 上线前 | **`access` 与 `iot` 必须同一窗口成对升级、配置随 jar 同切**；EMQX 凭据是硬前置 | [§A](#a-上线前检查pre-flight) |
| 升级 | 备份回滚物 → **迁移登记/应用** → 停 access → 换 iot → 换 access → 起；禁止部署机跑 Maven / `down -v` / 整栈重启 | [§B](#b-升级步骤) |
| 上线后 | 按「制品 → 订阅 → 落列 → Modbus → 接入规范化 → 业务面」逐条核验，每条给命令+期望+说明 | [§C](#c-上线后核验清单) |
| 回滚 | 应用/配置可回滚；**落列口径不可回退** | [§D](#d-回滚) |
| 出事 | 先查 §E 速查表（现象 → 判据 → 原因 → 处置） | [§E](#e-故障速查) |

---

## A. 上线前检查（Pre-flight）

### A.1 服务必须成对升级：`ypbin-access` 与 `ypbin-iot` 同一窗口一起切

> 结论：`access` 与 `iot` **不能只升一个**。理由来自 2026-10-13 起的近期变更，逐条列后果。

| # | 变更 | 单独升/混用的后果 | 出处 |
|---|---|---|---|
| ① | 接入侧切 `ypbin.iot.protocol.tcp.payload-format=key-value`（jar 与配置必须同切） | **旧 jar（0.2.0）+ 新配置 `key-value`**：0.2.0 的 `TcpPayloadFormat` **没有 `KEY_VALUE` 枚举** ⇒ Spring 绑定失败 ⇒ access **启动/重绑失败**；**新 jar + 旧配置（SDK 默认 `binary`）**：SDK 交付整帧 `byte[]`，宿主只认 `String`、出口闸门丢弃 ⇒ **该设备读数全丢** | `docs/DEPLOY-BACKEND.md` §5.8；`docs/VALUE-DECODE-DESIGN.md` §5.1 |
| ② | 落列改为由物模型 `data_type` **唯一决定**（`ValueColumn.from(data_type)`，不再按值形态猜列） | **新 access + 旧 iot**：旧 iot 仍按词法猜列（`ReadingValueMapper.map(raw)`）⇒ 过渡期同一属性的值可能落进不同列，**按单列查询会静默漏数据** | `docs/VALUE-DECODE-DESIGN.md` §4.6；`CHANGELOG.md`「未发布/变更」 |
| ③ | `forceText` 字段已**整体删除**（`AccessReading`/`ReadingObservationDto`/`Outcome`/`emit` 全删） | **旧 access 发来的 `forceText` 是新 iot 的未知字段**，按 Spring 默认**忽略**——所以不会炸，但**也不能靠它做灰度缓冲**：混用期间 string 点的落列口径仍由 iot 侧新旧逻辑决定 | `docs/VALUE-DECODE-DESIGN.md` §4.5.6（历史归档）、§4.6.3；`CHANGELOG.md`「未发布/变更」 |

**可执行顺序（推荐）**：`备份 → 停 access → 换 iot → 换 access（同批导入新 ypbin-access.yaml）→ 起 access → 核验`。
理由：access 有状态（持有 TCP 会话）、先停它可避免「新 access 产出的读数被旧 iot 按旧逻辑落列」；
iot 先就绪后 access 才起，避免启动窗口内的写入失败。access 短暂停采可接受（比丢数据/起不来好）——
`docs/DEPLOY-BACKEND.md` §5.8。

### A.2 配置必须随 jar 同切（Nacos Data ID 会被 install.sh 整体覆盖）

`deploy/nacos/` 下 **7 份**模板（`ypbin-common / ypbin-gateway / ypbin-auth / ypbin-system / ypbin-ai / ypbin-iot / ypbin-access`）
会被 `install.sh` 逐份导入 Nacos（Data ID = 文件名，`DEFAULT_GROUP`）；**已上线环境必须重导或手工加键**，
否则会出现「jar 是新的、配置是旧的」。出处：`deploy/install.sh` 的 Nacos 导入段；`docs/DEPLOY-BACKEND.md` §5.8。

上线前逐项核对（**键名逐字**）：

| 配置键（Nacos） | 期望 | 为什么必须核 | 出处 |
|---|---|---|---|
| `ypbin.iot.protocol.tcp.payload-format`（`ypbin-access.yaml`） | `key-value` | 不配 ⇒ SDK 退回 `binary` ⇒ **读数全丢** | `deploy/nacos/ypbin-access.yaml`（`payload-format: key-value`）；`docs/DEPLOY-BACKEND.md` §5.8 |
| `ypbin.timeseries.password`（`ypbin-iot.yaml`） | 占位符 `${IOTDB_PASSWORD}` 已被 install.sh 渲染成**与 IoTDB 内一致**的口令 | 三处联动漏一处 ⇒ 801 | `deploy/nacos/ypbin-iot.yaml`；`docs/DEPLOY-TIMESERIES.md` §6.1 |
| `YPBIN_OPENAPI_SECRET_PEPPER`（**只在 `.env`/容器 env，不在 Nacos**） | 非空、≥32 字节、与上一次一致 | 换值 ⇒ 既有 OpenAPI Key 全部失效 | `deploy/.env.example`；`deploy/docker-compose.yml`（`ypbin-iot` environment） |
| `ypbin.security.internal-token.token`（`ypbin-common.yaml`，入站守卫）+ `ypbin.cloud.feign.internal-token`（`ypbin-common`/`ypbin-access` 等，出站携带） | 占位符 `${INTERNAL_TOKEN}` 已渲染、服务间同串 | 缺失/不一致 ⇒ `/internal/**` fail-closed 拒绝（租约/读数上报全挂） | `deploy/nacos/ypbin-common.yaml`；`deploy/nacos/ypbin-access.yaml`。⚠️ 旧键 `ypbin.internal.token` 自 starter 4.0.0 起**无任何消费方**（#297，已由 `tools/check-deprecated-config-keys.sh` 守住） |
| `ypbin.security.identity.trusted-source-token`（`ypbin-common.yaml`）+ `ypbin.cloud.feign.trusted-source-token`（`ypbin-access.yaml`/`ypbin-iot.yaml`）+ 网关侧 `ypbin.gateway.auth.trusted-source-token`（`ypbin-gateway.yaml`） | 四处**同串**（都渲染自 `${GATEWAY_SIGN_TOKEN}`） | identity 校验为 fail-fast：`enabled=true` 且该键空 ⇒ **启动即失败** | `deploy/nacos/ypbin-common.yaml`；`deploy/nacos/ypbin-access.yaml`；`deploy/nacos/ypbin-iot.yaml`；`deploy/nacos/ypbin-gateway.yaml` |

**核对命令（Nacos 回读，只读）**：

```bash
# 单 Data ID 回读（把 <dataId> 换成 ypbin-access.yaml 等；NACOS_ADDR 见 deploy/.env）
curl -s "http://127.0.0.1:8080/nacos/v1/cs/configs?dataId=ypbin-access.yaml&group=DEFAULT_GROUP" \
  | grep -E 'payload-format|trusted-source-token|internal'
```

> ⚠️ 注意口径：`curl` 直连 `8080` 的控制台 API 与安装脚本走的 `NACOS_CONSOLE_URL` 一致；
> 若你的 Nacos 地址不同，按 `deploy/.env` 的 `NACOS_ADDR` 调整（`docs/DEPLOY-BACKEND.md` §5.8 用的是 `$NACOS_ADDR`）。

### A.3 硬前置（缺了会以「compose 起不来 / 服务起不来」的形态暴露）

| 前置 | 判据 | 不满足的形态 | 出处 |
|---|---|---|---|
| **EMQX API 凭据** | `.env` 里 `EMQX_API_KEY` / `EMQX_API_SECRET` 非空（或安装前 `export`） | compose 用 `${EMQX_API_KEY:?}` / `${EMQX_API_SECRET:?}` 表达必填，**对整份文件插值** ⇒ **任何** compose 命令（含只起 nacos/redis/mysql）都会失败 | `README.md`「一键安装（推荐）」注；`docs/DEPLOY-BACKEND.md` §2.1 |
| **IoTDB 口令** | `.env` 的 `IOTDB_PASSWORD` 与 IoTDB 内实际口令一致 | `iotdb-init` 连续 801 后退出 1；`ypbin-iot` 时序写入失败 | `docs/DEPLOY-TIMESERIES.md` §2、§6.1 |
| **`ypbin-iot-ui` 静态产物** | `IOT_UI_DIST_DIR`（默认 `../iot-ui-dist`）里有 `index.html` | 前端打开 404/白屏 | `docs/DEPLOY-UI.md` §1/§4；`deploy/ui-up.sh` |
| **外部中间件机（EMQX/隧道）** | EMQX 单独部署并对平台可用 | 不在本 compose 内，compose 成功 ≠ MQTT 链路可用 | `README.md`「未做」；`deploy/emqx/*` |

### A.4 制品：优先用 Release 资产，并做机器可验证的校验

Release（`v*` tag）由 `.github/workflows/release-artifacts.yml` 自动产出：**6 个服务 jar** +
`jars.manifest.sha256`（首部含 `# git: <sha>` 与 `# built: <UTC>`）+ CycloneDX SBOM `bom-cyclonedx.json`。
出处：`docs/DEPLOY-BACKEND.md` §2.0；`.github/workflows/release-artifacts.yml`。

```bash
TAG=v1.0.0
gh release download "$TAG" --repo wenbin-wb/ypbin-iot \
  --pattern '*.jar' --pattern 'jars.manifest.sha256'
# 没有 gh：curl -L -O https://github.com/wenbin-wb/ypbin-iot/releases/download/$TAG/<jar 文件名>

# ① 逐文件核对 sha256（在下载目录执行；清单里是 basename，可直接 -c）
sha256sum -c <(grep '\.jar$' jars.manifest.sha256)

# ② 核对「制品来自哪个 commit」：清单首部的 # git: 必须 == 你要部署的提交
head -3 jars.manifest.sha256
git -C <repo> rev-parse HEAD

# 或一键完成①②（并断言恰好 6 个 jar）：
#   deploy/verify-release-artifacts.sh --dir . --git "$(git -C <repo> rev-parse HEAD)"
```

- ⚠️ **发版前提**：工作流只给「`project.version` == tag 名（去 `v`）且不含 `SNAPSHOT`」的提交产制品；
  本仓继承的 upstream `v1.0.0` tag（2026-08-28、`1.0.0-SNAPSHOT`）**不是**可部署制品（`docs/DEPLOY-BACKEND.md` §2.0）。
- ⚠️ SBOM 是**依赖清单**，**不是漏洞扫描**；依赖漏洞门禁已落地（`.github/workflows/dependency-vuln-gate.yml`，
  OWASP Dependency-Check，CVSS ≥ 7 判红）——见 `docs/OPS-DEPENDENCY-VULN-GATE.md` 与 `docs/DEPLOY-BACKEND.md` §2.0。

**镜像制品（2026-10-14 起，同一 Release）**：6 个 `ypbin-iot-<service>-<version>.tar.gz` +
`images.manifest.sha256`（tar 的 sha256）+ `images.digests.tsv`（GHCR digest + image id）。
无外网部署机用 `deploy/verify-release-images.sh --tag "$TAG" --dir . --load --jars-manifest ./jars.manifest.sha256`
校验并 load（会核对**镜像内 /app/app.jar == jar 清单**），再 `docker tag ypbin/<svc>:<ver> ypbin/<svc>:local`；
有外网可直接 `docker pull ghcr.io/wenbin-wb/ypbin-<svc>:$TAG`（私有包先 `docker login ghcr.io`）。
方案对比、权限/可见性与边界见 `docs/OPS-RELEASE-IMAGES.md`。**禁止部署浮动 `latest`**。

### A.5 先做一次备份（回滚物）

```bash
cd /opt/ypbin/ypbin-iot
deploy/backup.sh --dry-run     # 先看计划（不碰 docker、不落盘）
deploy/backup.sh               # 备份 MySQL + IoTDB，落 $ROOT/backups
```

边界见 §D。备份脚本本身的能力/局限：`deploy/backup.sh` 头注释、`deploy/PROD-OPS-NOTES.md` §13。

---

## B. 升级步骤

> 本节以「手工部署」为准（生产长期形态）；只用 Release 制品、只替换 jar、只重启该服务。
> **禁止**：在部署机跑 Maven、`docker compose down -v`、整栈重启（`docs/DEPLOY-BACKEND.md` §1/§2）。

### B.1 备份回滚物（升级前一次做完）

```bash
cd /opt/ypbin/ypbin-iot
TS=$(date +%Y%m%d-%H%M%S)

# ① 旧镜像 tag（回滚用；换服务时按需要重复）
docker tag ypbin/ypbin-iot:local    ypbin/ypbin-iot:rollback-$TS
docker tag ypbin/ypbin-access:local ypbin/ypbin-access:rollback-$TS

# ② 旧 jar 备份（Dockerfile 只 COPY target/*.jar，回滚时可直接覆盖回去）
#    ⚠️ <ver> 换成实际版本（如 1.0.0-SNAPSHOT）；只备份可执行 jar，排除 -sources/-javadoc/*.original
cp -a ypbin-service/ypbin-iot/target/ypbin-iot-<ver>.jar       /root/ypbin-iot-jar-rollback-$TS.jar
cp -a ypbin-service/ypbin-access/target/ypbin-access-<ver>.jar /root/ypbin-access-jar-rollback-$TS.jar

# ③ 配置回滚物：dump 当前 Nacos live（只读；口令/密钥不落 stdout，写 600 文件）
#    可参照 docs/DEPLOY-BACKEND.md §5.6.2 的登录 + dump 片段，或直接备份 .env(600)
cp -a deploy/.env /root/ypbin-env-rollback-$TS

# ④ 数据备份（MySQL + IoTDB）
deploy/backup.sh
```

> 记录：`TS`、两个 rollback 镜像 tag、两个 jar 备份路径、`.env` 备份、Nacos dump 路径、本次部署的 git sha。

### B.2 数据库迁移登记（换 jar 前必做；紧跟 §B.1 备份之后）

> 为什么是硬步骤：dev 升级实测暴露过「新 jar 需要新列，但迁移静默没应用」——
> 症状是 `Unknown column 'word_count'`、`/internal/device-specs/tenant` 500、access 拿不到设备规格。
> 迁移不在 jar 里，必须先用运行器确认并应用，再换 jar。

```bash
cd /opt/ypbin/ypbin-iot

# ① 先看：已应用 / 待应用 / 校验和不一致 + 计数（只读；有 pending/mismatch 时退出码非 0）
deploy/migrate.sh --status

# ② 有 pending ⇒ 先应用迁移，再换 jar（幂等、失败即停；校验和不一致会判红并中止）
deploy/migrate.sh

# ③ 复查：待应用=0 且 校验和不一致=0 才进 §B.3
deploy/migrate.sh --status
```

判据与纪律：
- **待应用=0 且 校验和不一致=0** 才继续 §B.3 换 jar；否则先按输出处置。
- **校验和不一致**：登记表记录的 sha256 与迁移文件不同，运行器**不自动重放**，按脚本打印的人工处置指引走。
- 迁移**失败即停**，不吞错；库可能半应用时先人工核对再重跑（登记表里该文件 `success=0`）。

> **分工**：`deploy/migrate.sh` = **升级**（已有库，按 `deploy/sql/migration/iot-migrations.list` 顺序应用
> 未登记迁移，登记到 `iot_schema_migration`，含 sha256 防重放）；`deploy/install.sh` = **全新安装/整栈**
> （导 001–007 并把全部迁移登记为已应用）。两者共用同一张登记表，可安全交替使用。

### B.3 协调切换（`access` + `iot` 成对）

```bash
cd /opt/ypbin/ypbin-iot
COMPOSE="docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml"

# ① 停 access（避免切换窗口内产生读数；access 是有状态服务）
$COMPOSE stop ypbin-access

# ② 换 iot：覆盖 jar → 只重建 iot 镜像 → 只起 iot
install -m 644 /path/to/new/ypbin-iot-<ver>.jar ypbin-service/ypbin-iot/target/ypbin-iot-<ver>.jar
$COMPOSE build ypbin-iot
$COMPOSE up -d --no-deps ypbin-iot

# ③ 换 access：覆盖 jar → 只重建 access 镜像 → 只起 access（配置已在 §A.2 核对/重导）
install -m 644 /path/to/new/ypbin-access-<ver>.jar ypbin-service/ypbin-access/target/ypbin-access-<ver>.jar
$COMPOSE build ypbin-access
$COMPOSE up -d --no-deps ypbin-access
```

- `--no-deps` + 只点名服务：**不动别人的容器**（`docs/DEPLOY-BACKEND.md` §2）。
- 前端（若同批）：用 CI 真产物覆盖 `IOT_UI_DIST_DIR`，`docker restart ypbin-iot-ui`
  （`docs/DEPLOY-BACKEND.md` §2；`docs/DEPLOY-UI.md` §5）。
- 若走一键安装（`install.sh`），它会拉代码 → 构建 → 起全栈，并把本仓检出到 `$ROOT/ypbin-admin`；
  首装/整栈场景用它，**单服务升级不要用整栈 `up -d`**（`README.md`「一键安装」；`docs/DEPLOY-BACKEND.md` §2.3）。

> **镜像路径（2026-10-14 起，可选）**：若本 Release 的镜像已按 §A.4 用 `deploy/verify-release-images.sh --load`
> 校验并 retag 成 `ypbin/<svc>:local`，则上面的「覆盖 jar → `$COMPOSE build <svc>`」两步可**省略**，
> 直接 `$COMPOSE up -d --no-deps <svc>`（compose 有本地镜像即不再 build）。仍必须先完成 §B.1 的旧镜像
> rollback tag 与 §B.2 的迁移登记。详见 `docs/OPS-RELEASE-IMAGES.md`。

### B.4 立即核对（进入 §C 之前的最低门槛）

```bash
# 容器内 jar 与制品逐字节一致（md5 或 sha256 二选一）
docker exec ypbin-iot    md5sum /app/app.jar
docker exec ypbin-access md5sum /app/app.jar

# 健康
curl -s http://127.0.0.1:18084/actuator/health
curl -s http://127.0.0.1:18086/actuator/health/liveness
curl -s http://127.0.0.1:18086/actuator/health/readiness

# 容器状态 / 重启计数
docker inspect -f '{{.State.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' ypbin-iot ypbin-access
```

### B.5 记录（部署回执，必须留）

- 部署的 git sha 与 Release tag；制品 sha256（`jars.manifest.sha256` 对应行）。
- 容器内 jar 的 md5/sha256（与制品一致）。
- rollback 镜像 tag 与 jar 备份路径、`.env` 备份路径、Nacos dump 路径。
- 切换开始/结束时刻、核验结果（§C 逐条结论）。

---

## C. 上线后核验清单

> **用法**：逐条执行命令、比对期望值、读「不满足说明什么」。任一「期望 0」的计数在增长，先按 §E 定位，不要直接放行。
> **先读 [§C.0](#c0-读法与判据约定先读这一节能避开三类误判)**：指标按服务分列、业务接口看 body 的 `R.code`、以及「指标 404」的两种正常情形。
> 两台服务都只绑回环（`docs/DEPLOY-BACKEND.md` §5.1/§5.7）。
>
> **期望值来源**：本清单的期望值来自 ① 仓内文档记录的**生产实测**（如 `docs/ACCESS-ENABLE.md` §4、`deploy/PROD-OPS-NOTES.md` §12）、
> ② **单测/契约测试**（如 `docs/VALUE-DECODE-DESIGN.md` §4.x 的落地清单与对应测试类）、或 ③ **代码常量/校验**（如 `MAX_FUTURE_SKEW_MS`、`2^53`）；
> 每行末/正文给出具体出处。**本机无真机/真库、或当前生产无对应设备/组件的，逐条标 `[未核实]`**（如 Modbus 三项、SDK `iot.tcp.*` 暴露路径）。

### C.0 读法与判据约定（先读这一节，能避开三类误判）

**① 指标按服务分列——写错服务会 404，且与「指标不存在」无法区分。**
2026-10-13 生产只读实测（同一时刻两台各查一次 `/actuator/metrics`）：

| 服务 | `/actuator/metrics` 条数 | 前缀归属（实测） | 交叉验证 |
|---|---|---|---|
| iot `127.0.0.1:18084` | **144** | `iot.timeseries.*` **9** / `iot.ingest.*` **7** / `iot.alert.*` **31** 等 | `iot.timeseries.db.rows`、`iot.timeseries.write.rows`、`iot.alert.notify.given_up` 均 **200** |
| access `127.0.0.1:18086` | **92** | `iot.access.*` **35** | `iot.timeseries.db.rows` 在 18086 上 **404**（这就是「查错服务」的形态） |

⇒ 本手册 C.1–C.7 每条都在「核验项」上标了服务归属：`【iot 18084】` / `【access 18086】` / `【网关 18080 + 登录态】` / `【MySQL】` / `【systemd】`。

**② 业务接口判真伪看 body 的 `R.code`，不要只看 HTTP 状态码。**
本仓业务响应是 `R` 信封，**业务错误一律 HTTP 200 + `code`**。2026-10-13 生产只读实测（直连 iot 18084，GET）：

```
/devices/9300012/series?limit=1        -> HTTP 200, body {"code":401,...}   ← 路径正确，只是缺登录态
/iot/devices/9300012/series?limit=1    -> HTTP 200, body {"code":404,...}   ← 路径错误（/iot 是网关路由前缀）
```

两者 **HTTP 状态码完全相同**（都是 200），只看状态码必然误判（这条也证实了：「直连 18084 去掉 `/iot` 前缀」才是对的）。可复制的判码命令：

```bash
# 判业务响应码（§C6 的业务面核验都用它；无登录态时以 401 表示"路径对、缺登录态"）
code() { curl -s "$1" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code"))'; }
code 'http://127.0.0.1:18084/devices/9300012/series?limit=1'       # 2026-10-13 实测：401（路径对、缺登录态）
code 'http://127.0.0.1:18084/iot/devices/9300012/series?limit=1'   # 2026-10-13 实测：404（路径错）

# 带登录态（经网关 18080；T 为登录返回的 accessToken）：
code_auth() { curl -s -H "Authorization: Bearer $T" "$1" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code"))'; }
```

> ⚠️ **例外**：`/actuator/**` 是**原生 JSON**（没有 `R.code` 信封，如 `{"status":"UP"}`、`{"measurements":[...]}`）
> ⇒ health/metrics 按各节写的字段判，**不要**套 `code()`。

**③ 「指标 404」不一定是异常：分「升级前不存在」与「惰性注册」两种。**
- **升级前不存在**：`iot.timeseries.value.type_missing` / `not_numeric` / `precision_lost`、`iot.access.modbus.pair.stale`、
  `iot.access.value.normalize.failure` 是 **2026-10-13 那批变更引入的新指标**。2026-10-13 生产只读实测：两个容器已运行
  **30 小时（旧 jar）**，上述指标在 18084/18086 上**全部 404** —— 这是「尚未升级到含这些指标的版本」，**不是故障**。
  **升级是否生效靠 [§B.4](#b4-立即核对进入-c-之前的最低门槛) 的 jar md5 与 [§C1.4](#c1-制品与进程) 的容器启动时间/重启计数判断，不要靠这些指标是否 404。**
- **惰性注册**：SDK 的 `iot.tcp.*` 计数器**首次递增才出现**；`/actuator/metrics` 里 0 条/404 **无法区分**「还没失败过」与「Micrometer 桥没接上」⇒ 见 C2.4 的探针判据。

### C.1 制品与进程

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C1.1 | 【两者】容器内 jar == 制品 | `docker exec ypbin-iot md5sum /app/app.jar`；`docker exec ypbin-access md5sum /app/app.jar`；<br>或（与 Release 清单 `jars.manifest.sha256` 比对）：`docker exec ypbin-iot sha256sum /app/app.jar`（容器内无该命令时用 `docker cp ypbin-iot:/app/app.jar /tmp/ && sha256sum /tmp/app.jar`） | 与构建机 md5 / Release sha256 **一致** | 部署替换的是别的 jar / 镜像没重建（`docs/DEPLOY-BACKEND.md` §2、§2.0） |
| C1.2 | 【iot 18084】健康 | `curl -s http://127.0.0.1:18084/actuator/health` | `{"groups":["liveness","readiness"],"status":"UP"}` | 未就绪/崩溃；看 `docker logs --tail 100 ypbin-iot`（`docs/DEPLOY-BACKEND.md` §5.4） |
| C1.3 | 【access 18086】判活 | `curl -s http://127.0.0.1:18086/actuator/health/liveness`、`.../readiness` | `{"status":"UP"}` | 用聚合 `/actuator/health` 会被 Redis 健康项挂死（≥120s 不返回，`deploy/PROD-OPS-NOTES.md` 陷阱 5）⇒ **判活只用 liveness/readiness** |
| C1.4 | 【两者】无异常重启/OOM | `docker inspect -f '{{.State.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' ypbin-iot ypbin-access` | `running`、`restarts=0`、`oom=false` | access 常驻 ~580–780 MiB；`oom=true` ⇒ 立即停回（`docs/ACCESS-ENABLE.md` §2） |

### C.2 订阅形态（key-value 一次多地址订阅）

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C2.1 | 【access 18086】订阅成功**每设备一次**（不再是 N 次） | `curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.subscribe.success` | 一次订阅动作后，**每设备增量 = 1**（按设备/按周期对齐） | 仍按 N 点位增长 ⇒ 配置没切到 `key-value`（走了逐点位单订阅）；`[文档] docs/VALUE-DECODE-DESIGN.md` §5.5 |
| C2.2 | 【access 18086】订阅失败 | `curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.subscribe.failure` | 期望 0（首启/变更后短时可容忍，随后不应持续增长） | 协议栈/设备侧问题；与 `.../iot.access.subscribe.backoff.skipped` 成对出现属预期（`docs/METRICS.md` §2.4） |
| C2.3 | 【access 18086】在途去重 | `curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.subscribe.inflight.skipped` | 少量正常；长期增长 = 订阅长时间不完成 | `docs/METRICS.md` §2.4 |
| C2.4 | 【access 18086】selector 未命中（SDK 指标，**不能单看 404/0 条**） | 首选**探针制造一次已知失败**再核：用 `deploy/access-tcp-simulator`（或一台**专用探针** TCP 设备）发一个**故意不合格**的帧（键值形态但去掉 `=`，或非 UTF-8 字节），然后 `curl -s "http://127.0.0.1:18086/actuator/metrics/ypbin.iot.errors?tag=type:iot.tcp.selector.not-found"`（⚠️ **SDK 指标不是独立指标名**：`MicrometerMetricsRecorder` 把 `iot.tcp.*` 记进 **`ypbin.iot.errors`** 的 **`type`** 标签，故 `/actuator/metrics/iot.tcp.selector.not-found` **恒 404**） | **该 tag 的计数出现且 +1** ⇒ SDK→Micrometer 桥接正常，该指标可继续用；**不出现** ⇒ 指标链路没接上（这才是真问题） | 2026-10-13 生产只读实测：18086 的 **92 条指标里 `iot.tcp.*` 为 0 条**（`selector.not-found` / `payload.not-key-value` / `payload.not-utf8` 全 404）⇒ 惰性注册下**无法区分**「还没失败过」与「桥没接上」。⚠️ **不要为探针去改生产设备配置**；没有可用探针设备时本条标 `[未核实]`，兜底用 access 日志 + 点位 BAD 质量 + `iot.access.egress.*`；指标名的文档出处 `docs/METRICS.md` §2.6 / `docs/VALUE-DECODE-DESIGN.md` §5.5 |

> ✅ **2026-10-14 实测更新（问题已修复，本条判据现在可用）**：
>
> - **指标桥已生效**：`ypbin-access` 启动打 `[ypbin-iot] iotMetricsRecorder bound to Micrometer (...)`，**不再**出现 no-op 回退 WARN。
>   前置：starter **≥ 0.3.2**（修复 MeterRegistry 自动配置锚错类导致的静默回退）；本仓 `ypbin-iot.version` 已升到 **0.3.3**。
> - **协议层错误指标真实上报**：前置 starter **≥ 0.3.3**（issue #27：TCP/MQTT 协议层此前**只打日志、从不调用** MetricsRecorder#recordError ⇒ 指标永远 404）。
> - **实测证据（dev，2026-10-14）**：把模拟器整帧的键**全部改错**后：
>   `GET /actuator/metrics/ypbin.iot.errors` → **200**；`availableTags.type = iot.tcp.selector.not-found`；`COUNT = 8`；
>   `GET .../ypbin.iot.errors?tag=type:iot.tcp.selector.not-found` → **8**（探针已还原）。
> - **判据（修正后）**：先 `curl -s http://127.0.0.1:18086/actuator/metrics | grep ypbin` —— 出现 `ypbin.iot.errors` 即「桥 + 上报链路」都正常；
>   **404 只说明还没发生过错误（指标惰性注册）**，应先用探针制造一次已知失败再判，不要据此认定桥没接上。
> - ⚠️ **触发语义（实测留痕）**：selector 只在**整帧的键全不匹配**时记 `iot.tcp.selector.not-found`；**只缺一个键**（其余仍匹配）**不记**错误指标。

### C.3 落列（物模型 `data_type` 唯一决定）

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C3.1 | 【iot 18084】类型缺失/冲突 | `curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.value.type_missing` | **0** | `iot_property.data_type` 缺失/未知或同标识类型冲突 ⇒ 读数被丢弃（`docs/METRICS.md` §2.9；`docs/VALUE-DECODE-DESIGN.md` §4.6.4） |
| C3.2 | 【iot 18084】声明数值但不可解析 | `curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.value.not_numeric` | **0** | 非数值送到 int/long/decimal 点（建模或帧内容错），该读数被丢弃、不落文本兜底（同上） |
| C3.3 | 【iot 18084】整数精度丢失 | `curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.value.precision_lost` | **0** | 整数型值 `\|v\| > 2^53` ⇒ 仍写 `value_double` + 计数（应把该属性建模为 `string`）（同上） |
| C3.4 | 【iot 落库】同一属性列恒定 `[未核实]` | 不变量核验（本手册未给单条可复制查询：IoTDB 表模型按属性聚合的精确语法未在仓内文档核到）：按属性统计 `value_double`/`value_text` 的非空行数，确认**只有一个列非空** | 同属性列恒定（不出现两列都有值） | 落列口径没切/混用了新旧 iot（`docs/VALUE-DECODE-DESIGN.md` §4.6.6 不变量）。⚠️ 本条查询语法 `[未核实]`，请按你环境里 IoTDB 表模型支持的聚合写法执行 |
| C3.5 | 【Modbus 点位】float 最短表示 | 需要一个 `register_format=float` 的 Modbus 点位，读其最新值/落库值 | `23.4` 而非 `23.399999618530273` | 用了 `Double.toString`/`BigDecimal.valueOf((double) f)` ⇒ 17 位噪声（`docs/VALUE-DECODE-DESIGN.md` §4.4.7）。⚠️ **当前生产唯一 Modbus 设备已停用**（`docs/VALUE-DECODE-DESIGN.md` §5.6）⇒ 本条 `[未核实]`，需启用 Modbus 点位后执行 |

> 取值方式补充：`type_missing`/`not_numeric`/`precision_lost` 是 **iot 侧** Counter（不带标签），`/actuator/metrics/<name>` 取 `measurements[0].value`；
> 期望 0 的依据是「这三类都是显式失败/建模告警」——`docs/METRICS.md` §2.9、`docs/VALUE-DECODE-DESIGN.md` §4.6.5。
>
> ⚠️ **升级前不存在**：C3.1–C3.3 这三个指标是 **2026-10-13 变更引入的**。2026-10-13 生产只读实测（两个容器旧 jar 已跑 **30 小时**）：
> 这三个指标在 18084 上**全部 404** —— **这不是异常**，是「尚未升级到含这些指标的版本」。**判断升级是否生效靠 §B.4 的 jar md5 + §C1.4 的启动时间/重启计数，不要靠这三个 404。**

### C.4 Modbus（多寄存器配对 / 字符串 / 无重复）

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C4.1 | 【access 18086】半字配对陈旧 | `curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.modbus.pair.stale` | **0** | 采集周期/寄存器地址配对异常或重复订阅 ⇒ 整组丢弃（`docs/METRICS.md` §2.6；`docs/VALUE-DECODE-DESIGN.md` §4.3.4/§4.5.3） |
| C4.2 | 【Modbus 点位】字符串点位 GOOD 且值正确 | 查该点位 latest/历史（见 §C6.1/C6.2） | 质量 `GOOD`、值等于设备上报文本（全 NUL ⇒ GOOD + 空串，不是 BAD） | 非法 UTF-8 ⇒ BAD + `iot.access.value.normalize.failure{reason=not-utf8}`（`docs/VALUE-DECODE-DESIGN.md` §4.5.2） |
| C4.3 | 【Modbus 点位】多寄存器**同周期只一条读数**（无重复） | 对 `word_count>1` 点位做同窗口查询/计数 | 每个周期**恰好 1 条** | 半字配对/去重异常（`docs/VALUE-DECODE-DESIGN.md` §4.3.4「不产生重复读数」、§4.5.3） |

> 注：`?tag=reason:<值>` 是 Spring Boot Actuator 的**标准 tag 过滤用法**（仓内文档只示范了 `/actuator/metrics/<name>` 这一级，见 `docs/DEPLOY-BACKEND.md` §5.2）；
> 若不生效，以第 ① 步的 `availableTags` 为准。
>
> ⚠️ **可执行性**：C4.1–C4.3 需要**真实/启用的 Modbus 点位**；生产当前唯一的 Modbus 设备已停用
> （`docs/VALUE-DECODE-DESIGN.md` §5.6）⇒ 这三条在生产**当前无法执行，标注 `[未核实]`**；
> 启用 Modbus 演示/真实设备后再按本表执行。
>
> ⚠️ **升级前不存在**：C4.1 的 `iot.access.modbus.pair.stale` 是 **2026-10-13 新增指标**；2026-10-13 生产只读实测
> （旧 jar 已跑 30 小时）在 18086 上 **404** —— 属「未升级」，不是「配对没失败」也不是故障。

### C.5 接入侧规范化（类型规范化失败 = 0）

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C5.1 | 【access 18086】全部原因码（`reason` 标签） | ① `curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.value.normalize.failure` 看 `availableTags`；② 再按 reason 逐个取值（Actuator 标准 tag 查询）：`curl -s "http://127.0.0.1:18086/actuator/metrics/iot.access.value.normalize.failure?tag=reason:not-numeric"`，另 4 个 reason：`not-boolean`、`unknown-data-type`、`non-finite`、`not-utf8` | **全 0** | 该 reason 对应「建模/帧内容/解码配置」问题（`docs/METRICS.md` §2.6；`docs/VALUE-DECODE-DESIGN.md` §6）。`non-finite`/`not-utf8` 仅 Modbus 形态，当前 Modbus 停用 ⇒ 这两条 `[未核实]` |
| C5.2 | 【access 18086】出口 byte[] 闸门 | `curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.egress.invalid` | **0 且不增长** | 有未解码 `byte[]` 交付到出口 ⇒ 配置没切到 `key-value` / 版本没对齐（`docs/DEPLOY-BACKEND.md` §5.8；`docs/METRICS.md` §2.5） |

> ⚠️ **升级前不存在**：C5.1 的 `iot.access.value.normalize.failure` 是 **2026-10-13 新增指标**；2026-10-13 生产只读实测
> （旧 jar 已跑 30 小时）在 18086 上 **404**。升级后它才出现；**升级前 404 不能判「升级没生效」**（判升级见 §B.4/§C1.4）。

### C.6 业务面（曲线 / 最新值 / 告警 / 菜单）

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C6.1 | 【网关 18080+登录态】演示设备曲线仍出点 | 用 §C.0 的判码函数（带 `-H "Authorization: Bearer $T"`，即 `code_auth`）核：`code_auth 'http://127.0.0.1:18080/iot/devices/9300012/series?propertyId=temperature&limit=5'`（权限 `iot:series:get`） | **`code==200`** 且 `data` 为**数值点**（非空、`propertyId=temperature`） | `code!=200`：401=登录态/权限、404=路径错，其余见 §E「曲线画不出」。⚠️ **不能只看 HTTP 状态码**：2026-10-13 生产只读实测，直连 18084 的 `/devices/...` = `HTTP 200 + code=401`（路径对、缺登录态），`/iot/devices/...` = `HTTP 200 + code=404`（路径错）——两者状态码相同 |
| C6.2 | 【网关 18080+登录态】最新值更新 | 同 §C.0 判码：`code_auth 'http://127.0.0.1:18080/iot/devices/9300012/latest'`（权限 `iot:device:latest`）；或 `REDISCLI_AUTH="$REDIS_PASSWORD" docker exec -e REDISCLI_AUTH ypbin-redis redis-cli HGETALL iot:latest:1:9300012` | **`code==200`** 且有值、`ts` 随采集前进 | 不更新 ⇒ 见 §E「最新值不更新」（`docs/ACCESS-ENABLE.md` §4-⑤ 的有效判据是 Redis 里 `temperature` 的 ts 前进） |
| C6.3 | 【网关 18080+登录态】断档/可用率不误报 | 同 §C.0 判码：`code_auth 'http://127.0.0.1:18080/iot/devices/9300012/availability'`；或看 `feeder-watch` | **`code==200`** 且无「喂数正常却报停摆」 | 见 §E「断档误报」（`deploy/PROD-OPS-NOTES.md` §5.2.1：日志 dropped 是误报源，对账才是真判据） |
| C6.4 | 【MySQL】平台告警规则不误报 | 平台表：<br>`MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD ypbin-mysql mysql -uroot -N -e "use ypbin_admin; select rule_code,state,observed_rounds from iot_platform_alert order by id desc limit 8"` | 无 `FIRING` 且 `observed_rounds` 不持续增长 | 见 §E「平台自告警 FIRING」；阈值/规则名见 `deploy/PROD-OPS-NOTES.md` §12 |
| C6.5 | 【MySQL】设备告警不误报 | `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD ypbin-mysql mysql -uroot -N -e "use ypbin_admin; select id,device_id,property_id,state,consecutive_count,start_ts from iot_alert_instance where is_deleted=0 order by id desc limit 10"` | 无异常 `FIRING`（演示环境不应出现） | 规则/阈值/数据质量见 `docs/ALERTING-DESIGN.md`；通知状态见 §E「GIVEN_UP」 |
| C6.6 | 【MySQL】菜单/权限（007 导入） | `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" docker exec -e MYSQL_PWD ypbin-mysql mysql -uroot -N -e "use ypbin_admin; select count(*) from sys_menu where auth_code like 'iot:%' and is_deleted=0; select count(*) from sys_role_menu rm join sys_menu m on m.id=rm.menu_id where rm.role_id=1 and m.auth_code like 'iot:%'"` | 两个计数都 > 0 | 菜单未导入/未授权 ⇒ 页面无 IoT 菜单、租户 403（`deploy/sql/007-iot-data.sql`；`docs/permission-rollout.md` §4.1 提醒：种子只给超管 role_id=1，租户角色需另配） |

> ⚠️ **凭据卫生（照做）**：上面 MySQL 命令用的是 `MYSQL_PWD`（宿主 env）+ `docker exec -e MYSQL_PWD`（**只写变量名、不写值**），
> Redis 用 `REDISCLI_AUTH`（宿主 env）+ `docker exec -e REDISCLI_AUTH`——口令都不进 argv/进程列表。
> 从 `.env`(600) 取值：`MYSQL_ROOT_PASSWORD=$(sed -n 's/^MYSQL_ROOT_PASSWORD=//p' deploy/.env | head -1)`、
> `REDIS_PASSWORD=$(sed -n 's/^REDIS_PASSWORD=//p' deploy/.env | head -1)`（口径见 `docs/DEPLOY-CREDENTIAL-HYGIENE.md`）。

### C.7 链路与平台自告警

| # | 核验项 | 命令 | 期望 | 不满足说明什么 |
|---|---|---|---|---|
| C7.1 | 【iot 18084】access→iot 指标上报 | `curl -s http://127.0.0.1:18084/actuator/metrics/iot.platform_alert.access.report.lag` | 稳态 ≈ 15000ms（一个上报周期）；`-1` = 从未收到 | ≥45s ⇒ `PLATFORM_INSTANCE_UNREACHABLE`（`deploy/PROD-OPS-NOTES.md` §12）。✅ **已核实存在**（2026-10-13 生产只读实测补测）：旧 jar 的 18084 指标名里有 **`iot.platform_alert.*` 7 条**（`access.report.lag` / `firing` / `notify.send` / `notify.succeeded` / `notify.failed` …）与 **`iot.lease.*` 3 条**（`expired` / `revoked` / `takeover`）⇒ 该指标**升级前后都存在**，所以**升级后若在此仍是 404/取不到，才是真信号**（不要再用「旧 jar 可能还没有这个指标」来解释）。 |
| C7.2 | 【access 18086】上报链路失败 | access：`curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.metrics.report.failure` | 期望 0 | 增长 ⇒ 推送断、平台侧相关规则判「不可判定」（`docs/METRICS.md` §2.7） |
| C7.3 | 【systemd】喂数停摆看门狗 | `systemctl --failed`；`journalctl -u feeder-watch.service --since -30min --no-pager \| grep -E '判定: (ALERT\|无法求值)'` | 无输出 / 单元非 failed | 命中 ⇒ 停摆或判据不可求值（`deploy/PROD-OPS-NOTES.md` §5.1；注意实际周期 ≈17–20 分钟，§5.3） |

---

## D. 回滚

> 回滚是**集合动作**：应用（镜像/jar）、配置（Nacos）、数据三者分别处置；只回一半会立刻不一致。

### D.1 应用回滚（镜像 tag / jar 备份）

```bash
cd /opt/ypbin/ypbin-iot
COMPOSE="docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml"
TS=<升级时的 TS>

# 方式 A：镜像 rollback tag 打回 local，再只重建该服务
docker tag ypbin/ypbin-iot:rollback-$TS    ypbin/ypbin-iot:local
$COMPOSE up -d --no-deps ypbin-iot

docker tag ypbin/ypbin-access:rollback-$TS ypbin/ypbin-access:local
$COMPOSE up -d --no-deps ypbin-access

# 方式 B：用备份的 jar 覆盖 target/ 后重打镜像（路径见 §B.1）
#   install -m 644 /root/ypbin-iot-jar-rollback-$TS.jar ypbin-service/ypbin-iot/target/<原名>.jar
#   $COMPOSE build ypbin-iot && $COMPOSE up -d --no-deps ypbin-iot
```

出处：`docs/DEPLOY-BACKEND.md` §3。前端回滚 = 换回备份的 `IOT_UI_DIST_DIR` 再 `docker restart ypbin-iot-ui`（同节）。

### D.2 配置回滚（Nacos）

- **`payload-format` 是天然的订阅形态开关**：把 `ypbin.iot.protocol.tcp.payload-format` 改回非 `key-value`
  （`binary`/`text`/`number`）⇒ 订阅自动回到**逐点位单订阅**，无需第二个开关（`docs/VALUE-DECODE-DESIGN.md` §5.5）。
  改完**必须重启 access**（配置绑定只读一次，见 §E 第 8 类）。
- 其它键（trusted-source / internal / IoTDB 口令 / pepper）：用 §B.1 的 Nacos dump / `.env` 备份还原；
  **不要整份覆盖 live**——按既有口径只改值（`docs/DEPLOY-BACKEND.md` §5.6.1；`docs/ACCESS-ENABLE.md` §1）。

### D.3 ⚠️ 不可回退 / 回滚也改不回来的项（必须写进变更票）

| 项 | 事实 | 出处 |
|---|---|---|
| **落列口径** | 落列由 iot 侧 `ValueColumn.from(iot_property.data_type)` 决定。**已按类型写入的行不会被回滚改写**；回滚 iot 到旧版本会恢复「按值形态猜列」⇒ 同一属性又可能跨 `value_double`/`value_text` 两列。D0.9 下无历史数据负担，但**回滚后新数据仍按「当时运行的 iot 逻辑」落列**，不会回到升级前的形态 | `docs/VALUE-DECODE-DESIGN.md` §4.6；`CHANGELOG.md`「未发布/变更」 |
| **`forceText` 通道** | 已整体删除，回滚 access 也不会让它回来（除非回滚 iot 也一起） | `docs/VALUE-DECODE-DESIGN.md` §4.6.3 |
| **IoTDB 数据** | `backup.sh` 的 IoTDB 是**运行中热拷贝**：不保证崩溃一致性、不支持增量；恢复是**整卷替换**，不能只回滚某个点位 | `deploy/backup.sh` 头注释；`deploy/PROD-OPS-NOTES.md` §13.4 |
| **MySQL 数据** | 逻辑备份：并发 DDL 下不保证一致；**无 PITR**（需 binlog）；恢复会重放 `DROP/CREATE/INSERT` | `deploy/restore.sh` 头注释；`deploy/PROD-OPS-NOTES.md` §13.4 |

### D.4 数据回滚（备份/恢复，含边界）

```bash
cd /opt/ypbin/ypbin-iot
# 只看计划（默认）；确认后再加 --yes 执行（破坏性）
deploy/restore.sh --mysql /opt/ypbin/backups/ypbin-admin-<TS>.sql \
                  --iotdb /opt/ypbin/backups/iotdb-data-<TS>.tar
deploy/restore.sh --mysql ... --iotdb ... --yes
```

边界：`deploy/backup.sh` / `deploy/restore.sh` 头注释；`deploy/PROD-OPS-NOTES.md` §13.4。
**恢复后必做**：按 §C 重新核验（尤其 C1/C3/C6），并注意「跨组件时间点可能不同」（同 §13.4）。

---

## E. 故障速查

> 用法：先定位「现象」，用「首选判据」确认，再看「常见原因 → 处置」。指标命令默认：
> iot→`http://127.0.0.1:18084/actuator/metrics/<name>`，access→`http://127.0.0.1:18086/actuator/metrics/<name>`。

| 现象 | 首选判据（命令/指标/日志关键字/SQL） | 常见原因 | 处置 | 出处 |
|---|---|---|---|---|
| 设备/点位**完全不出数**：未领到租户 | access 日志 `租户领取完成：node=access-1 持有租户=[]`；`GET http://127.0.0.1:18080/iot/tenant-ledger` | `tenant_ledger` 里该租户 `assignable=false`，且配置 `ypbin.iot.lease.assignable-tenant-ids` 为空 | 用平台接口 `PUT /iot/tenant-ledger/{id}/assignable {"assignable":true}` 后回读 | `docs/ACCESS-ENABLE.md` §2.1 |
| 不出数：**订阅失败** | `iot.access.subscribe.failure`（+ `.../subscribe.backoff.skipped` 成对）；access 日志「订阅成功：deviceId=… 点位数=…」缺失 | 协议栈/设备侧问题；配置形态不对 | 先看协议栈/设备侧；成对出现退避属预期 | `docs/METRICS.md` §2.4；`docs/ACCESS-ENABLE.md` §4-③ |
| 不出数：**selector 未命中** | SDK `iot.tcp.selector.not-found`（BAD 质量）；点位值为空 | 点位映射 `raw_address` 与帧里的键不一致 | 把 `raw_address` 对齐成帧里的键（演示数据从 `holding:0` 改成 `TEMP`） | `docs/VALUE-DECODE-DESIGN.md` §6 |
| 不出数：**帧不是键值** | SDK `iot.tcp.payload.not-key-value`（BAD） | 帧是裸值/JSON/二进制 | 换协议/映射；`binary` 等形态需寄存器级解码 | `docs/VALUE-DECODE-DESIGN.md` §4.2、§6 |
| 不出数：**帧非 UTF-8** | SDK `iot.tcp.payload.not-utf8`（BAD）；Modbus 另见 `iot.access.value.normalize.failure{reason=not-utf8}` | 二进制寄存器帧 / 字符串寄存器非法 UTF-8 | 用寄存器级解码参数（`register_format`）；非法 UTF-8 处保持 BAD、不替换字符 | `docs/VALUE-DECODE-DESIGN.md` §4.5.2、§6 |
| 不出数：**数值不可解析** | `iot.timeseries.value.not_numeric`；access `...{reason=not-numeric}` | 非数值送到 int/long/decimal 点 | 修建模或帧内容；**系统明确丢弃、不落文本兜底** | `docs/METRICS.md` §2.9；`docs/VALUE-DECODE-DESIGN.md` §4.6.5 |
| 不出数：**类型缺失/冲突** | `iot.timeseries.value.type_missing` | `iot_property.data_type` 缺失/未知或同标识类型冲突 | 查 `iot_property.data_type`；补齐/统一类型 | `docs/METRICS.md` §2.9；`docs/VALUE-DECODE-DESIGN.md` §4.6.4 |
| 不出数：**多寄存器两半未配对** | `iot.access.modbus.pair.stale` 增长；该点位 BAD | 采集周期/寄存器地址配对异常、重复订阅 | 核对 `word_count`/`raw_address`/订阅去重；**绝不用陈旧一半或 0 拼值** | `docs/VALUE-DECODE-DESIGN.md` §4.3.4/§4.5.3；`docs/METRICS.md` §2.6 |
| 不出数：**时间戳越界** | `iot.ingest.latest.future_rejected` 增长；或可用率/事件里该点被跳过 | 设备时钟超前服务端 > **5 分钟**（`MAX_FUTURE_SKEW_MS`） | 校设备时钟；该上限只作用于**最新值**写入路径 | `ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/values/RedisLatestValueWriter.java`（`MAX_FUTURE_SKEW_MS`、`METRIC_FUTURE_REJECTED`） |
| 曲线画不出 / `/series` 空或报错 | `GET …/iot/devices/{id}/series?propertyId=<identifier>`（`propertyId` 是**点位标识**，不是主键）；`docker logs ypbin-iot \| grep 时序写入失败` | ① `propertyId` 传成主键/错值；② IoTDB 未启用/未初始化；③ 查询列不匹配 | ① 用点位 identifier；② 看 `iotdb-init` 退出码与 IoTDB 表/TTL；③ 错误码 614/616 对照 | `docs/DEPLOY-TIMESERIES.md` §3.4、§5；`docs/ACCESS-ENABLE.md` §6.1/§6.3 |
| IoTDB 写入持续失败 | `iot.timeseries.write.failed` 增长；ERROR 日志「时序写入失败」；`iot.timeseries.db.probe.failed` | 口令 801 / 未初始化 / `setNull` 旧驱动问题 / URL 缺库名 | 按 §E「IoTDB 口令」与 `docs/DEPLOY-TIMESERIES.md` §5 逐行对照；**不要**改回「一条语句 + setNull」 | `docs/DEPLOY-TIMESERIES.md` §5 |
| **最新值不更新** | `GET /iot/devices/{id}/latest` 为空；`redis-cli … HGETALL iot:latest:<tenant>:<device>`；`iot.ingest.latest.regressed` / `.failed` / `future_rejected` | 未来 ts 抑制 / 旧 ts 后到被抑制 / Redis 写失败 | 看三个 `iot.ingest.latest.*` 指标定位；校设备时钟 | `ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/values/RedisLatestValueWriter.java`；`docs/ACCESS-ENABLE.md` §4-⑤ |
| **断档误报**（喂数正常却报停摆） | `journalctl -u feeder-watch.service`；L1b 对账（`iot.access.egress.accepted` vs `iot.timeseries.write.rows` 增量差） | 日志 `inbound buffer overflow … dropped frames=N` 是**误报源**（辅助缓冲副本），不是真丢 | 用 L1b 对账判真丢；`feeder-watch` 阈值/边界见出处 | `deploy/PROD-OPS-NOTES.md` §5.2.1/§5.2/§5.3 |
| 告警只落 `GIVEN_UP`（用户没收到） | `iot.alert.notify.given_up` 增长；`iot_alert_notification.last_error` | 收件人解析不出 / 渠道码非法 / 重试耗尽（30s→2min→10min） | 用 `last_error` 分层：配置面（缺 host/username）× 凭据面（`Authentication failed`）；**不要手工催** | `deploy/PROD-OPS-NOTES.md` §10.4；`docs/ALERTING-DESIGN.md` §S6/M4 |
| 平台自告警 FIRING | `select rule_code,state,observed_rounds from iot_platform_alert`；`iot.platform_alert.*` | 规则名/阈值见 §12 表（含 `PLATFORM_RECONCILE_DEFERRED`/`PLATFORM_TICK_SLOW`/`PLATFORM_INSTANCE_UNREACHABLE`） | 按规则逐条定位；`access` 上报断 ⇒ 先修 access→iot 链路 | `deploy/PROD-OPS-NOTES.md` §12 |
| **EMQX 凭据失效** | access/iot 日志 401；`docker compose config` 报缺 `${EMQX_API_KEY:?}` | 平台与中间件机**双份持有**，轮换只改一侧 | 两侧同改（`/opt/ypbin/ypbin-iot/deploy/.env` 与 `/opt/emqx/.env`），各用 `GET /api/v5/status` 确认 | `deploy/PROD-OPS-NOTES.md`「EMQX REST Key/Secret 的双份持有」；`docs/DEPLOY-BACKEND.md` §2.1 |
| **IoTDB 口令不符** | `iotdb-init` 801；`iot.timeseries.write.failed` | IoTDB 内 / `.env` / Nacos 三处不一致 | 按 §6.1 的三处联动对齐；脚本已自动化的部分见 `docs/DEPLOY-TIMESERIES.md` §6.1 | `docs/DEPLOY-TIMESERIES.md` §5、§6.1 |
| **Nacos 配置未生效** | 回读 live 正常但行为不变 | ① 有的 Bean **不是 `@RefreshScope`**（如 `AccessProperties`）⇒ 改 `reconcile-budget-ms` 必须**重建容器**；② Data ID 没重导/jar 与配置没同切；③ 模板被 install.sh 整份覆盖，手工改的 live 键丢失 | ① `docker compose up -d --no-deps --force-recreate ypbin-access`；② 重导 Data ID 或手工加键；③ 不要把手工改动只留在 live | `deploy/PROD-OPS-NOTES.md` §12.1；`docs/DEPLOY-BACKEND.md` §5.8 |

---

## F. 本手册不覆盖什么（避免「照做就万事大吉」的错觉）

| 不覆盖项 | 现状 | 指向 |
|---|---|---|
| **EMQX 集群** | 生产仍单节点；**2 节点集群已在隔离实验床实测通过**（static 发现 + 跨节点路由），生产未落地 | `docs/OPS-EMQX-CLUSTER.md`（issue #219） |
| **Prometheus / Grafana 大盘** | **dev 已落地**（Prometheus+Grafana+Alertmanager+桥接器；8 条规则过 promtool）；当前 jar 无 `micrometer-registry-prometheus`，故用桥接器过渡 | `docs/OPS-MONITORING.md`（issue #222） |
| **镜像制品化（GHCR / tar）** | **已落地**（2026-10-14）：Release 产 6 个镜像 tar + `images.manifest.sha256` + `images.digests.tsv` 并推 GHCR；部署机 `docker load`/`docker pull` 后 retag 部署 | `docs/OPS-RELEASE-IMAGES.md`；`.github/workflows/release-artifacts.yml` 的 `images` 作业（issue #234） |
| **K8s / HA / 多副本** | 仓内只有 docker compose 编排，无 K8s manifests；多实例告警评估分布式锁未做 | `deploy/docker-compose.yml`；`README.md`「未做」（多实例告警锁） |
| **外部 EMQX / 隧道 / 入口中间件机** | 不在本 compose 内 | `deploy/emqx/*`；`docs/EMQX-INGRESS-DESIGN.md` |
| **SBOM = 漏洞扫描？** | **不是**：SBOM 只是依赖清单；依赖漏洞门禁已另立（OWASP Dependency-Check，CVSS ≥ 7 判红） | `docs/DEPLOY-BACKEND.md` §2.0；`docs/OPS-DEPENDENCY-VULN-GATE.md` |
| **明文 1883 收紧 / 限来源 / 限流** | **收紧能力已在隔离实验床实测**（access_rules 允许/拒绝、max_conn_rate、limiter.messages_rate、自签 TLS 8883）；生产未应用。⚠️ 生产 8883 **当前实际不可用**（容器读不到证书） | `docs/OPS-EMQX-HARDENING.md`（issue #218） |
| **协议/解码未支持面** | IEEE754 双精度、BADC/DCBA、有符号位域、字符串寄存器跨区等 | `docs/VALUE-DECODE-DESIGN.md` §4.2/§4.3.6/§4.4.9/§4.5.5 |
| **MySQL/IoTDB 恢复演练** | 脚本已具备，**真机恢复未验证** | `deploy/PROD-OPS-NOTES.md` §13.3/§13.4 |
| **数据库结构变更（006/007/迁移）** | 升级走 `deploy/migrate.sh`（§B.2 硬步骤）；全新安装走 `install.sh`；迁移与 006+007 的语句等价由门禁守 | `deploy/sql/`；`tools/check-iot-sql-equivalence.sh` |

---

## 附录 A：本文确认过的指标名（逐字，仓内代码/文档）

iot（18084）：
```
iot.timeseries.value.type_missing        iot.timeseries.value.not_numeric       iot.timeseries.value.precision_lost
iot.timeseries.write.rows                iot.timeseries.write.failed            iot.timeseries.write.rows.noinfo/unknown/nonstandard
iot.timeseries.db.rows                   iot.timeseries.db.probe.failed         iot.timeseries.points.collected
iot.ingest.latest.regressed              iot.ingest.latest.failed               iot.ingest.latest.future_rejected
iot.alert.notify.given_up / queued / sent / failed / throttled / silenced
iot.platform_alert.access.report.lag     iot.platform_alert.notify.send / succeeded / failed
iot.lease.insert_skipped
```
access（18086）：
```
iot.access.subscribe.success / failure / backoff.skipped / inflight.skipped
iot.access.value.normalize.failure   （reason = not-numeric / not-boolean / unknown-data-type / non-finite / not-utf8）
iot.access.modbus.pair.stale
iot.access.egress.sent / accepted / dropped / failed / invalid / pending
iot.access.metrics.report.failure
iot.access.config.reconcile.deferred / not_applied / forced / changed / check.failure
iot.access.lease.tick.duration / renew.success / renew.failure / revoked / self_fenced / node_fenced / acquired / clock_skew_seconds / clock_skew.deferred
iot.access.spec.empty / failure / load.duration / backoff.skipped / reconcile.applied
iot.access.connection.invalid
```
SDK 侧（本仓只登记名字，暴露路径未核实）：`iot.tcp.selector.not-found` / `iot.tcp.payload.not-key-value` / `iot.tcp.payload.not-utf8`。

## 附录 B：本文确认过的命令（仓内出处）

- 指标/健康：`curl -s http://127.0.0.1:18084|18086/actuator/...`（`docs/DEPLOY-BACKEND.md` §5.2/§5.7）。
- 只重建单服务：`docker compose -f ... -f ... up -d --no-deps <svc>`（`docs/DEPLOY-BACKEND.md` §2/§3）。
- 容器内 jar：`docker exec <c> md5sum /app/app.jar`（`docs/DEPLOY-BACKEND.md` §2）。
- 备份/恢复：`deploy/backup.sh [--dry-run]`、`deploy/restore.sh … [--yes]`（`deploy/backup.sh` / `restore.sh` 头注释；`deploy/PROD-OPS-NOTES.md` §13）。
- IoTDB CLI（轮换后带 `-h ypbin-iotdb` 且口令走 stdin）：`docs/DEPLOY-TIMESERIES.md` §3、§6.1。
- 租户可分配：`PUT /iot/tenant-ledger/{id}/assignable`（`docs/ACCESS-ENABLE.md` §2.1）。
- feeder-watch：`systemctl --failed`、`journalctl -u feeder-watch.service`（`deploy/PROD-OPS-NOTES.md` §5.1）。

## 附录 C：文档一致性清单（4 项均已由 PR #258 / commit `0bd024e9` 修正，保留记录）

> 下列 4 项由 #257 手册转交；Lead 已在 **PR #258（commit `0bd024e9`）** 修正。此处保留原始记录与判定依据，避免「提过就忘」。
> （本手册按 2026-10-13 生产只读实测收紧时复核：4 项当前**均已修正**。）

1. `docs/DEPLOY-BACKEND.md` §5.8 步骤 ④ 写 `curl http://127.0.0.1:18084/iot/devices/9300012/series?...`；
   直连 18084 **没有 `/iot` 前缀**（`IotTimeSeriesController` 映射是 `/devices/{deviceId}/series`，`/iot` 是网关路由前缀、`StripPrefix=1`）。
   疑为文档笔误，本文 C6.1 用网关路径 `/iot/devices/...` 并标注。
2. `docs/DEPLOY-BACKEND.md` §5.7 写 `iot.access.value.normalize.failure`「**3 个** reason 标签」「实测 3 个 reason 全为 0.0」；
   现状是 **5 个** reason（`not-numeric`/`not-boolean`/`unknown-data-type`/`non-finite`/`not-utf8`，见 `docs/METRICS.md` §2.6 与 `NormalizeFailure` 枚举）。
3. `ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/controller/IotTimeSeriesController.java` 类 Javadoc 仍写「当前后端存储（IoTDB）尚未接入：本端点会返回业务错误」；
   与其实际实现（走 `TimeSeriesQueryService` → IoTDB）及 `docs/DEPLOY-TIMESERIES.md` §3.4 不符。
4. `docs/METRICS.md` §2.9 的 `precision_lost` 文案写「值超出 `\|v\| ≤ 2^53`」，语义应是「`|v| > 2^53`」（代码 `NumericValues.MAX_EXACT_INTEGER_IN_DOUBLE` 判的是 `> 2^53`）。
