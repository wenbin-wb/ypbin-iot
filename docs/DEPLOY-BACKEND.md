# IoT 后端部署纪律（构建机 / 部署机分工）

> 为什么单独一份：`docs/microservice-deployment.md` 是**继承自 admin 基座的既有文件**
> （不在 `SYNC.md` 第二节的 8 文件白名单内 ⇒ 改它会让 `Sync Whitelist` 门禁转红）。
> 本文件是**本仓新增**的文档，用来承载 IoT 自己的部署纪律与教训。

## 1. 铁律：不要在「无法访问 GitHub 的部署机」上重建 jar

**现象（2026-09-25 实测）**：生产部署机（`113.142.217.58`）**连不上 GitHub** ——
`git fetch` 会挂到超时（独立复核复测 `git ls-remote` ⇒ `exit=124`），
而该机仓库里的源码树**停留在较早的提交**；与此同时 `ypbin-service/ypbin-iot/target/*.jar`
是**从外部上传的新产物**（由构建机构建）。

**后果**：此时若在该机执行 `mvn package` / `mvn clean package`，
会从**陈旧源码**编译出一个**缺少新端点**的 jar（真实风险示例：丢掉
`GET /iot/devices/{deviceId}/latest`，或本轮之前的「物模型读路径放开」），
紧接着 `docker compose build ypbin-iot` 会把旧 jar 打进镜像并 `up -d` ⇒
**线上能力静默回退**，而日志里看不出任何异常。

> 换句话说：`docker compose build` 本身是安全的（Dockerfile **只 `COPY target/*.jar`，不编译源码**），
> **危险的是在部署机上跑 Maven**。两者不要混为一谈：
> 允许 `compose build`，禁止 `mvn`。

## 2. 正确流程（本轮实际执行）

```bash
# ① 构建机（能访问 GitHub / 有完整源码 + 依赖缓存）：构建 fat jar
cd <repo>
MAVEN_OPTS=-Xmx768m mvn -s <settings.xml> -Dmaven.repo.local=<m2repo> \
  -pl ypbin-service/ypbin-iot -am -DskipTests -Djacoco.skip=true package
md5sum ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar   # 记下 md5，部署后核对

# ② 上传（只传 jar，不传源码、不在服务器编译）
scp -i <key> -P 22 ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar root@<server>:/tmp/new.jar

# ③ 部署机：备份 → 替换 jar → 打镜像 → 只重启这一个服务
cd /opt/ypbin/ypbin-iot
TS=$(date +%Y%m%d-%H%M%S)
docker tag ypbin/ypbin-iot:local ypbin/ypbin-iot:rollback-$TS               # 旧镜像留 tag（回滚用）
cp -a ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar /root/ypbin-iot-jar-rollback-$TS.jar
install -m 644 /tmp/new.jar ypbin-service/ypbin-iot/target/ypbin-iot-1.0.0-SNAPSHOT.jar
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml build ypbin-iot
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml up -d --no-deps ypbin-iot
rm -f /tmp/new.jar

# ④ 核对：容器内的 jar 必须等于构建机上那个 md5，再验 health 与关键端点
docker exec ypbin-iot md5sum /app/app.jar
curl -s http://127.0.0.1:18084/actuator/health
```

- `--no-deps` + 只点名 `ypbin-iot`：**不动别人的容器**；`down -v` 一律禁止（会连数据卷一起删）。
- 前端产物同理：服务器**没有 node/pnpm**，`iot-ui-dist` 必须用 CI 真构建的产物覆盖
  （下载 artifact → 覆盖 `iot-ui-dist` → `docker restart ypbin-iot-ui`），并核对
  「CI 产物与服务器文件逐字节一致」的 md5。

## 2.0 优先路径：从 Release 制品部署（2026-10-13 起）

**为什么加这条**：上面那套「构建机手工编 → scp」有两个长期问题：① 制品只能靠人工 md5 对账，
没有「这个 jar 来自哪个 commit」的**机器可验证**链条；② 换一台构建机就得重建整套 Maven + 本地仓库环境。
现由 `.github/workflows/release-artifacts.yml` 在 `v*` tag 上**自动构建并挂载** 6 个服务 jar
+ 一份 `jars.manifest.sha256`（首部含 `# git: <sha>` 与 `# built: <UTC 时刻>`）。

```bash
# ① 取制品（任一台能访问 GitHub 的机器；部署机若能访问 GitHub 也可直接下载）
TAG=v1.0.0
gh release download "$TAG" --repo wenbin-wb/ypbin-iot --pattern '*.jar' --pattern 'jars.manifest.sha256'
# 没有 gh 也可直连：
#   curl -L -O https://github.com/wenbin-wb/ypbin-iot/releases/download/$TAG/<jar 文件名>

# ② 校验（**必做**）：逐文件核对 sha256，并确认清单里的 git sha 就是你要部署的那个提交
sha256sum -c <(grep '\.jar$' jars.manifest.sha256)
git -C <repo> rev-parse HEAD      # 应与清单首部的 `# git:` 行一致

# ③ 之后的「替换 → 只重启该服务 → 核对容器内 md5/health」与第 2 节的 ②③④ 完全相同
```

⚠️ **守卫（发版前提）**：工作流只给「**把版本号定为该 tag**」的提交产出制品 —— 判据是
`project.version` 必须等于 tag 名（去掉 `v`）**且不含 `SNAPSHOT`**。故发版前必须先做一次
**release 提交**（把 `<revision>` 定成 `X.Y.Z`、折叠 CHANGELOG），再打 `vX.Y.Z` tag。
本仓继承了 upstream admin 的 `v1.0.0` tag（`git log -1 v1.0.0` = 2026-08-28、落后 main 463 个提交、
其 pom 声明 `1.0.0-SNAPSHOT`）⇒ 会被这两条判据挡下，不会被挂上"可部署的正式产物"。
> ⚠️ 经验保留：`git merge-base --is-ancestor HEAD origin/main` **单独用拦不住它**（该 tag 是 `main`
> 的祖先、会被放行）——判据必须是"版本一致性"这条，不能只留"是否在 main 上"。

每个 Release 还附带一份 **CycloneDX SBOM（`bom-cyclonedx.json`，聚合全反应堆依赖）**：用于回答
「这个版本里到底有哪些依赖、各自什么版本」——排查某个 CVE 影响面、或回答审计/合规提问时用它，
不必再翻 pom。生成方式是**命令行调用插件**（版本 2.9.3，与母仓 `ypbin-starter` 的 `sbom` profile 一致），
**不往 pom 里加配置**，因此不扩大与 upstream 的分歧面。

⚠️ **边界（如实登记）**：SBOM 是**依赖清单**，**不是**漏洞扫描——「已知 CVE 门禁」是另一件事（见 issue #237）。
镜像仍由部署机 `docker compose build`（Dockerfile 只 `COPY target/*.jar`、不编译源码，故安全）；
镜像制品化属后续增量。

## 2.1 compose 必填变量（`${VAR:?}`）：EMQX 是**硬前置**，缺失时在「起任何容器之前」报错

**为什么单列**：`docker compose` 对**整份文件**做变量插值。任一 `${VAR:?}` 缺失，**与该服务无关**
的命令也会一起失败——实测（2026-10-12，只读 `config`，未起容器、未连生产）：

```
docker compose -f deploy/docker-compose.yml --env-file <缺 EMQX 的 .env> config nacos
→ rc=1: error while interpolating services.ypbin-iot.environment.EMQX_API_KEY:
        required variable EMQX_API_KEY is missing a value
```

也就是说「先只把 nacos/redis/mysql/iotdb 拉起来」也做不到，且错误信息指向的是 compose 插值，
而不是「哪个键为什么缺」。`install.sh` 因此新增 `ensure_compose_required_env()`：在**任何 compose 调用之前**
机械抽出将要执行的 `docker-compose.yml` 里全部 `${VAR:?...}`（正则只认 `:?`，不会把 `${VAR:-默认}` 算进来），
逐个校验并按语义处置：

| 必填变量 | install.sh 的处置 |
|---|---|
| `MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` / `NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` / `NACOS_ADMIN_PASSWORD` | 自动生成/补生成（写 `.env` 600）；前置校验只兜底断言非空 |
| `AI_MODEL_SECRET_KEY` | 全新部署自动生成；复用旧 `.env` 缺键也补齐（env 优先）；长度必须 16/24/32 字节，非法即 die |
| `IOTDB_PASSWORD` | 延后：IoTDB 容器就绪后由 `ensure_iotdb_auth()` 生成/对齐（见 DEPLOY-TIMESERIES.md §6.1） |
| `EMQX_API_KEY` / `EMQX_API_SECRET` | **绝不自动生成**（随机值＝格式对但不能用的假凭据）。缺失时**在 compose 之前**一次性 die，提示去 EMQX Dashboard → 访问控制/API Keys 创建，然后 `EMQX_API_KEY=... EMQX_API_SECRET=... bash deploy/install.sh`（脚本持久化进 `.env` 600） |
| 其它未知 `${VAR:?}` | die 并列出，人工确认（禁静默降级） |

> 该清单是**运行时**从 compose 文件抽出的（不是写死在脚本里），所以 compose 以后加/删 `:?` 不会被漏掉；
> `deploy/.env.example` 顶部另有一份静态清单便于阅读，两者以脚本抽出结果为准。
> ⚠️ EMQX 两项是**外部 EMQX broker 的真实凭据**，脚本无法替你「生成」；缺它就该失败，而不是静默换假值。

## 2.2 fork 专属 deploy 资源：curl 一键安装如何拿到（`resolve_deploy_src`）

**2026-10-12 起 `install.sh` 默认把 fork（ypbin-iot）clone 到 `$ROOT/ypbin-admin`**（见 §2.3），
所以正常情况下 `$ROOT/ypbin-admin/deploy` 自带全部 fork 资源。但为覆盖引用脚本时
(`bash <(curl ...)` 落 `/tmp`、`sudo` 后 `SCRIPT_DIR` 变化、已有检出、`YPBIN_ADMIN_REPO` 覆盖)，
`install.sh` 仍用 `resolve_deploy_src()` 统一解析**一个资源根** `DEPLOY_SRC`（不再逐个文件兜底）：

1. 脚本自身位置（`<repo>/deploy`）——README 推荐部署法（本仓检出运行），**不重复克隆**；
2. `$ROOT/ypbin-admin/deploy`——本仓检出到 ypbin-admin（sudo 后 `SCRIPT_DIR` 落到 /tmp 时也命中）；
3. `$ROOT/ypbin-iot/deploy`——已浅克隆的 fork，复用；
4. 都没有 ⇒ `git clone --depth 1 -b $BRANCH $YPBIN_IOT_REPO $ROOT/ypbin-iot`（GitHub→Gitee 降级）；
5. 仍失败 ⇒ **一次性 die 并列出缺哪些资源**（禁静默跳过）。

解析后：`redis-auth-conf.sh` 从 `$DEPLOY_SRC` 取；Nacos 模板目录 = `$DEPLOY_SRC/nacos`；
`import_iot_sql()` 的本地 SQL 源 = `$DEPLOY_SRC/sql`（远端 raw 下载降级为备选）。

**新增 deploy 资源时必须同步更新 `install.sh` 的 `FORK_DEPLOY_RESOURCES` 清单**——它是机器判据
（curl 路径下资源是否齐备），报告/本文的资源分类表是它的说明：

| 资源 | upstream/main | 用途 |
|---|---|---|
| `redis-auth-conf.sh` | **无** | fork 专属；install.sh [5.5] 生成 Redis `requirepass` 受管配置 |
| `iotdb-init.sh` / `iotdb-init.sql` | **无** | fork 专属；fork compose 的 `iotdb-init` 服务挂载 |
| `docker-compose.yml` | 有但不同 | fork 版含 `ypbin-iot`/`ypbin-access`/`iotdb`；现由 `$ROOT/ypbin-admin`（fork 检出）直接使用，见 §2.3 |
| `nacos/ypbin-iot.yaml` / `nacos/ypbin-access.yaml` | **无** | fork 专属服务配置 |
| `nacos/ypbin-common.yaml` / `nacos/ypbin-gateway.yaml` | 有但不同 | **fork 版为准**（identity token / iot 路由） |
| `sql/006-iot-schema.sql` / `sql/007-iot-data.sql` / `sql/migration/iot-migrations.list` | **无** | IoT 表/菜单与远端下载清单 |
| `nginx-microservice.conf`（compose 也挂 `./nginx-microservice.conf`） | 有且**相同** | 无需 fork 源 |

> ✅ **2026-10-12 起已修**：`install.sh` 的 [2/7] **默认把 fork（ypbin-iot）clone 到 `$ROOT/ypbin-admin`**
> （`YPBIN_IOT_REPO`，Gitee 降级，分支跟 `BRANCH`；`YPBIN_ADMIN_REPO` 可显式覆盖），与 SYNC §三·五 的
> 推荐部署法等价 ⇒ `[4/7]` 的 Maven 构建与 [6/7] 的 compose 都用同一份 fork 检出，compose 用的就是
> fork 的 `docker-compose.yml`（含 `ypbin-iot`/`ypbin-access`/`iotdb`/`iotdb-init`）。
> 纯 admin 基座部署显式 `YPBIN_ADMIN_REPO=<upstream>`；**已有非 fork 的 `$ROOT/ypbin-admin`** 且要装
> 含 IoT 服务的 compose ⇒ 脚本 **die + 迁移指引**（不静默将就半个系统）。

## 2.3 一键安装（curl 路径）现在装什么 / 怎么迁移

**桩测证明到的**（未真机起容器）：`resolve_admin_source()` 选中的是 **fork 仓库**；11 项 fork 专属
deploy 资源全部可解析；clone 出来的 `deploy/docker-compose.yml` 经 `docker compose config --quiet`
可解析出 **14 个服务**：`nacos / redis / mysql / iotdb / iotdb-init / ypbin-gateway / ypbin-auth /
ypbin-system / ypbin-ai / ypbin-iot / ypbin-access / xxl-job-admin / ypbin-admin-ui / ypbin-iot-ui`。

**因此语义上会构建并启动的范围**（构建/启动本身未在本轮真机验证）：admin 基座 5 服务 + `ypbin-iot`/
`ypbin-access` + IoTDB（含一次性 `iotdb-init` 建库建表）+ XXL-JOB + 前端两套；数据库落 admin 基座
001–005 + fork 的 006/007（IoT 表与菜单）+ 全部 `*-iot-*` 迁移记账。

**仍需运维外部提供 / 不在 compose 内**：
- `EMQX_API_KEY`/`EMQX_API_SECRET`：硬前置（compose 用 `:?`），必须先在 EMQX Dashboard 创建 API Key；
  外部 EMQX broker、EMQX 隧道/入口那一套是**独立的中间件机部署**（`deploy/emqx/*`），不在本 compose 内。
- IoT 前端静态产物：`install.sh` 只构建 admin-ui；`ypbin-iot-ui` 的 `iot-ui-dist` 需由
  `deploy/ui-up.sh` 或 CI 产物单独准备（否则该 nginx 容器是空目录）。
- 其它外部凭据（AI 模型 Key、邮件 SMTP 等）按各自文档配置。

**从「已有纯 admin 检出」迁移**：脚本会 die 并打印两条路——① `rm -rf $ROOT/ypbin-admin && git clone -b
$BRANCH <fork URL> $ROOT/ypbin-admin`（或直接在含 deploy/ 的本仓检出里跑脚本）；② 确实只装纯 admin
基座就显式 `YPBIN_ADMIN_REPO=https://github.com/wenbin-wb/ypbin-admin.git` 后重跑（此时 fork 资源校验
按 compose 是否含 IoT 服务自动降级，不会误拦）。

## 3. 回滚

```bash
# 后端：把 rollback tag 打回 local 再重建容器
docker tag ypbin/ypbin-iot:rollback-<TS> ypbin/ypbin-iot:local
cd /opt/ypbin/ypbin-iot && docker compose -f deploy/docker-compose.yml \
  -f deploy/docker-compose.override.yml up -d --no-deps ypbin-iot
# 或直接用备份的 jar 覆盖 target/ 后重打镜像（jar 备份路径见第 2 节）

# 前端：换回备份目录再重启
cd /opt/ypbin/ypbin-iot && rm -rf iot-ui-dist && cp -a iot-ui-dist.bak-<TS> iot-ui-dist
docker restart ypbin-iot-ui
```

> **前提**：每次部署前必须留下「旧镜像 tag + 旧 jar + 旧 dist 目录」三件套；
> 少了任何一件，回滚就只剩「在部署机上重建」这条**被第 1 节禁止**的路。

## 4. 为什么部署机源码树与运行产物会不一致（如实说明）

部署机不是源码事实源：它只有部署所需的最小检出，且**无法从 GitHub 更新**。
因此「服务器上的源码」**不代表**「正在运行的产物」——
判断线上到底跑的是哪一版，**只认容器内的 jar md5**（或前端 dist 的文件 md5），
不要用 `git log` 去推断。要追溯「哪个提交对应这个 jar」，看构建机上的构建记录与 `docs/` 里的部署回执。

## 5. 可观测：只看指标（`/actuator/metrics`）与**为什么不能用公网访问**

### 5.1 暴露了什么、为什么是这个集合

> 版本口径：本仓是 **Spring Boot 4.1.1**（线上 Tomcat 11.0.24）。下面用到的 `management.*` 键
> 在 Spring Boot 3.x / 4.x 同名同义；核实来源是 4.1.1 的构件元数据
> （`spring-boot-actuator-autoconfigure` 的 `include` 默认 `['health']`、`spring-boot-health` 的
> `show-details` 默认 `never`）与官方文档
> <https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html>（一手，访问 2026-09-26）。
> 任务书里写的是「Spring Boot 3」，本仓实际是 4.1.1——按实际写，避免文档说谎。

配置落在 `deploy/nacos/ypbin-iot.yaml`（Data ID `ypbin-iot.yaml`）的 `management:` 段：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info   # ⚠️ 最小集合，绝不用 '*'
  endpoint:
    health:
      show-details: never              # 只回 status，不回组件细节/异常
      show-components: never
    metrics:
      enabled: true
```

- **为什么必须补这一段**：**Spring Boot 4.1.1**（本仓 `pom.xml` 的 `spring-boot.version`）的 `management.endpoints.web.exposure.include` 默认**只有 `health`**，
  所以生产此前根本打不到 `/actuator/metrics`，本轮新增的入站指标
  （`iot.ingest.latest.regressed` / `…future_rejected` / `…latest.failed` /
  `…propertyid.rejected` / `…propertyid.unmapped` / `…propertyid.orphan`、
  `iot.pointmapping.orphan`、`iot.pointmapping.coordinate_collision`、`iot.latest.coordinate.legacy` 等）
  **只能靠日志观测**。
- **为什么不用 `*`**：`*` 会连带 `env`、`configprops`、`heapdump`、`threaddump`、`beans`、`loggers`、`mappings`。
  其中 `heapdump` 能把整个堆下载下来（**含数据库/Redis 口令与内部 token**），`env`/`configprops`
  会回显全部配置。**这些端点一旦暴露等于把凭据交出去**，所以只开 `health`（探活）、`metrics`（指标）、
  `info`（版本信息）三个只读端点。
- **health 不泄露细节**：`show-details: never` + `show-components: never` ⇒ 只返回 `{"status":"UP"}`，
  不会带出数据库/Redis/IoTDB 的地址、状态与异常堆栈。

### 5.2 怎么只看指标（两条路径）

**① 宿主机上（推荐，回环直连，不经网关、无需登录态）**

```bash
# 存活/就绪
curl -s http://127.0.0.1:18084/actuator/health
# 单个指标（返回真实数值，含 measurements[0].value）
curl -s http://127.0.0.1:18084/actuator/metrics/iot.ingest.latest.regressed
# 指标清单（看有哪些名字可用）
curl -s http://127.0.0.1:18084/actuator/metrics | head -c 2000
```

**② 容器内（宿主机上连不到、或只想确认「服务自己看到的数」时）**

```bash
docker exec ypbin-iot sh -c 'curl -s http://127.0.0.1:18084/actuator/metrics/iot.pointmapping.orphan'
```

> 注意：**不要**通过网关 `http://<域名>/iot/actuator/metrics` 走——那条路径要带登录态
> （`ypbin-gateway.yaml` 的 `exclude-paths` 没有放行 `/iot/actuator/**`），且会经过 `StripPrefix`，
> 排障时多一层不确定；直接打 18084 才是「只看指标」的本意。

### 5.3 为什么**不能**用公网访问（安全边界）

1. **Actuator 默认没有认证**：`ypbin-iot` 关闭了本地 Sa-Token 拦截器
   （`ypbin.security.interceptor: false`，身份由网关校验），因此直连 18084 的 `/actuator/**`
   **不需要任何凭据**。虽然只开了三个只读端点，但指标会暴露业务规模（设备数、上报速率、失败计数），
   健康端点会暴露存活状态——都不该公开。
2. **实际边界靠「只绑回环」**：`deploy/docker-compose.yml` 里 18084 的映射是
   `"${IOT_BIND_ADDR:-127.0.0.1}:18084:18084"`，**刻意不跟 `INTERNAL_BIND_ADDR` 共用**
   （同 IoTDB 的 `IOTDB_BIND_ADDR` 先例）。不设 `IOT_BIND_ADDR` 时只监听宿主机回环 ⇒
   公网与服务网段都打不通；**把 `IOT_BIND_ADDR` 设成 `0.0.0.0` 等于把这套无认证端点放到公网**，
   除非同时加了反代白名单 + 认证，否则不要这么做。
3. **不要用「网关转发」当保护**：网关鉴权只覆盖 `/iot/**` 的白名单外路径，一旦有人把
   `/iot/actuator/**` 加进 `exclude-paths`（图省事免登录），保护就没了。收窄 18084 的绑定地址
   才是**结构性**保证，不依赖「没人改白名单」。
   **「与 `INTERNAL_BIND_ADDR` 解耦」怎么复现证明**（不必碰生产：生产该变量恰好已是 `127.0.0.1`，
   新旧表达式结果相同、无法动态区分）：

   ```bash
   # 在一个只放 deploy/ 的临时目录里，故意把 INTERNAL_BIND_ADDR 设成 0.0.0.0，看 iot 是否仍回环
   env INTERNAL_BIND_ADDR=0.0.0.0 MYSQL_ROOT_PASSWORD=x NACOS_AUTH_TOKEN=eA== \
       NACOS_AUTH_IDENTITY_KEY=k NACOS_AUTH_IDENTITY_VALUE=v REDIS_PASSWORD=x \
       INTERNAL_TOKEN=x GATEWAY_SIGN_TOKEN=x AI_MODEL_SECRET_KEY=0123456789abcdef \
     docker compose -f deploy/docker-compose.yml config \
     | python3 -c 'import sys,yaml; s=yaml.safe_load(sys.stdin)["services"]; \
         print({n: s[n].get("ports") for n in ["ypbin-iot","ypbin-system","mysql"]})'
   # 期望（2026-09-26 实测）：ypbin-iot = host_ip 127.0.0.1；ypbin-system/mysql = host_ip 0.0.0.0
   # 即「18084 的回环**只**由 IOT_BIND_ADDR 决定」，不再跟着 INTERNAL_BIND_ADDR 走。
   ```
4. **若将来要接 Prometheus 集中抓取**：正确做法是**抓取方与目标在同一内网**（抓容器网络地址
   `ypbin-iot:18084`，或经带认证的反代），**不要**为了省事把 18084 绑到公网；确需跨网段时，
   应在网络层限制来源 IP（安全组/防火墙），并轮换本文件 §1 提到的内部 token。
5. **经网关可达、但没有权限码（残余边界，已登记）**：`deploy/nacos/ypbin-gateway.yaml` 的
   `exclude-paths` **没有**放行 `/iot/actuator/**` ⇒ 经网关必须登录；但 actuator 端点**不带权限码**，
   而 `ypbin-iot` 的 `ypbin.security.interceptor: false` 只关掉登录态校验（身份由网关注入）
   ⇒ **任何已登录用户**都能按 `/iot/actuator/metrics` 读到**平台级**指标（含跨租户存量，
   如 `iot.pointmapping.orphan`）。它不等于公网暴露（前面三条边界仍在），但属**信息暴露面**。
   按角色收口需要 starter 侧给 actuator 路径挂权限码（本仓做不到），或把 actuator 从网关摘掉。
   **本轮未做**，登记在此。

### 5.4 判据与运维注意（本轮实测口径）

> ⚠️ **判据必须看响应体，不能只看 HTTP 状态码**（生产实测，2026-09-26）：本仓的全局异常约定是
> 「**未知路径也返回 HTTP 200**，靠 `R.code` 区分」，所以**未暴露**的 actuator 端点回的是
> `{"code":404,"data":null,"message":"接口不存在","success":false,...}` 而 **HTTP 状态码仍是 200**。
> 只 `-w '%{http_code}'` 会把「没暴露」误读成「暴露了」。**已暴露**的 actuator 端点回的是 actuator
> 自己的原生 JSON（如 `{"name":"...","measurements":[...],"availableTags":[]}`），两者形态完全不同。

```bash
# ✅ 回环可达且返回真实数值（原生 actuator JSON，含 measurements[0].value）
curl -s http://127.0.0.1:18084/actuator/metrics/iot.ingest.latest.regressed
# ✅ health 仍是 UP 且不含组件细节（期望形如 {"groups":["liveness","readiness"],"status":"UP"}）
curl -s http://127.0.0.1:18084/actuator/health
# ✅ 敏感端点必须**没有暴露**：看响应体里是不是那个 404 业务信封（而不是看 HTTP 状态码）
for e in env heapdump threaddump beans configprops loggers; do
  body="$(curl -s http://127.0.0.1:18084/actuator/$e)"
  case "$body" in
    *'"code":404'*) printf '%s -> 未暴露（OK）\n' "$e" ;;
    *)              printf '%s -> ⚠️ 疑似已暴露：%s\n' "$e" "${body:0:200}" ;;
  esac
done
# ✅ 公网不可达（在**非服务器的外部主机**上打服务器公网 IP）
curl -s -m 5 -o /dev/null -w '%{http_code}\n' http://113.142.217.58:18084/actuator/metrics   # 期望连不上/超时
```

### 5.7 `ypbin-access` 的对齐（2026-09-27）

**结论**：access 与 iot 用**同一份最小暴露集合**（`health,metrics,info` + 关掉 health 细节/组件），
并把它**只绑回环**。差别只有端口（18086）与绑定变量名（`ACCESS_BIND_ADDR`）。

**为什么必须补**：access 本来就带了一整套指标（`iot.access.egress.*` / `iot.access.lease.*` /
`iot.access.config.*` / `iot.access.subscribe.*` / `iot.access.spec.*` / `iot.access.connection.*` /
`iot.access.value.normalize.failure`），但 `management.endpoints.web.exposure.include` 默认只有 `health`
⇒ 生产里它们**只能靠 WARN 日志看**。`iot.access.value.normalize.failure`（2026-10-13 由 `iot.access.decode.failure` 更名而来）正是这个缺口的实例。

配置落在 `deploy/nacos/ypbin-access.yaml`（Data ID `ypbin-access.yaml`），键与 §5.1 逐字相同：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info   # ⚠️ 最小集合，绝不用 '*'
  endpoint:
    health:
      show-details: never
      show-components: never
    metrics:
      enabled: true
```

**绑定地址必须与 `INTERNAL_BIND_ADDR` 解耦**：`deploy/docker-compose.yml` 里 access 的映射是
`"${ACCESS_BIND_ADDR:-127.0.0.1}:18086:18086"`（同 `IOT_BIND_ADDR` / `IOTDB_BIND_ADDR` 先例）。
不设它时只监听宿主机回环 ⇒ 公网与服务网段都打不通；把它设成 `0.0.0.0` 等于把这套无认证端点放到公网。

**「解耦」怎么复现证明**（生产机上 `INTERNAL_BIND_ADDR` 恰好已是 `127.0.0.1`，新旧表达式结果相同、
无法动态区分——所以要在**渲染层**做差分）：

```bash
cd <只放 deploy/ 的临时目录或部署机 deploy 目录>
env INTERNAL_BIND_ADDR=0.0.0.0 <其余必填键...> \
  docker compose -f docker-compose.yml -f docker-compose.override.yml config \
  | python3 -c 'import sys,yaml; s=yaml.safe_load(sys.stdin)["services"]; \
      print({n: s[n].get("ports") for n in ["ypbin-access","ypbin-iot","mysql"]})'
# 期望（2026-09-27 实测）：ypbin-access = host_ip 127.0.0.1；ypbin-iot = 127.0.0.1；mysql = 0.0.0.0
# 即「18086 的回环只由 ACCESS_BIND_ADDR 决定」，不再跟着 INTERNAL_BIND_ADDR 走。
```

**指标零值也要可观测（本轮新增的代码改动）**：Micrometer 计数器是**惰性**创建的，若把建 meter 留在
失败路径里，「一条都没失败过」时 `/actuator/metrics/iot.access.value.normalize.failure` 会回 **404 而不是 0**，
值班无法区分「没有规范化失败」与「指标没上报」。因此 access 启动期会把 `iot.access.value.normalize.failure`
按全部原因码**预注册为 0**（`AccessMetricsConfiguration` + `PointValueNormalizeMetrics`），失败路径只自增。

**判据（与 §5.4 完全一致，判据必须看响应体）**：

```bash
# ✅ 指标清单可读，且含 iot.access.value.normalize.failure（3 个 reason 标签）
curl -s http://127.0.0.1:18086/actuator/metrics | python3 -c 'import sys,json;print([n for n in json.load(sys.stdin)["names"] if n.startswith("iot.access")])'
# ✅ 零值可观测（实测：3 个 reason 全为 0.0）
curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.value.normalize.failure
# ✅ 敏感端点必须**没有暴露**（看响应体是不是 404 业务信封，别看状态码）
for e in env configprops heapdump threaddump beans loggers mappings; do
  body="$(curl -s http://127.0.0.1:18086/actuator/$e)"
  case "$body" in *'"code":404'*) echo "$e 未暴露（OK）";; *) echo "$e ⚠️ 疑似已暴露";; esac
done
# ✅ 公网不可达（在**非服务器的外部主机**上）：18086/18084 期望 000 / curl exit 7；19000/18080 期望 200
```

> ⚠️ **live 配置里的这段注释是发布当时的版本**（脚本 `tools/patch-nacos-access-actuator.py` 的 `BLOCK` 与仓库 yaml 已按复核意见补上 health 挂起的事实；live 的注释差异不影响行为）。

> ⚠️ **access 的 `/actuator/health` 会挂起**（2026-09-27 部署前后各测一次，均 >6s 无响应；`PROD-OPS-NOTES.md`
> §6.3 早已登记同类现象）。**不是本次改动引入**（改前改后同样挂起），且与 metrics 暴露无关。
> 需要判活时用 `/actuator/health/liveness` 与 `/actuator/health/readiness`（均为 `{"status":"UP"}`，毫秒级）。
> 根因**未查明**（请求跑在虚拟线程上，JRE 镜像里没有 jcmd/虚拟线程 dump 手段）⇒ 登记为未决项。
> 线上对应的另外两条已知问题（同一窗口观测到，非本次引入）：`NettyChannelConnection: inbound buffer overflow
> on connection t1-d9300012` 持续增长、`IotLifecycle: failed to bind device` 360 次 —— 已在本轮回执里登记。

### 5.8 接入侧 `payload-format=key-value` 升级前置（2026-10-13，必读）

**背景**：接入侧已切到 starter 0.3.0 的 `ypbin.iot.protocol.tcp.payload-format=key-value`，
并**删除了帧解析过渡层**（`cn.ypbin.admin.access.decode`）。现在：
- 帧解析（UTF-8 + `KEY=VALUE` + 按 `raw_address` 取键）由 SDK 承接；
- 宿主只保留**物模型类型规范化**（`PointValueNormalizer`），它只认 SDK 交付的 `String`；
- 出口仍有一道「值仍是 `byte[]` 就丢弃」的兜底闸门（`iot.access.egress.invalid`）。

**🔴 两条致命组合（升级顺序错了会丢读数或起不来）**：

| 组合 | 结果 |
|---|---|
| **新 jar（0.3.0）+ 旧配置（未配 key-value ⇒ SDK 默认 `binary`）** | SDK 交付整帧 `byte[]` ⇒ 宿主规范化只认 String、原样透传 ⇒ 出口闸门丢弃 ⇒ **该设备读数全丢** |
| **旧 jar（0.2.0）+ 新配置（`key-value`）** | 0.2.0 的 `TcpPayloadFormat` **没有 `KEY_VALUE` 枚举** ⇒ Spring 绑定失败 ⇒ **access 启动失败/重绑失败** |

⇒ **jar 与配置必须一起切，不能先动任何一个**。推荐**维护窗口内的一次性切换**（access 有状态、单/少副本，
短暂停采比丢数据/起不来可接受）：

**升级顺序（协调切换）**
1. 备份：记录当前 access 镜像/ jar 版本与 Nacos `ypbin-access.yaml` 原文（回滚要用）；
2. **停 access**（`docker compose stop ypbin-access`，或缩容到 0）——避免滚动期出现「新 jar 读旧配置」；
3. 部署新 jar（0.3.0）+ 导入新 `ypbin-access.yaml`（含 `ypbin.iot.protocol.tcp.payload-format: key-value`）：
   - 仓库侧文件已含该键；已上线环境需**重导该 Data ID**（install.sh 路径）或**手工在 Nacos 加键**；
4. 启动 access，按下节核验；
5. 回滚：恢复旧 jar + 移除/恢复 `payload-format` 键（两者同时），再启动。

**核验（启动后 1~2 个采集周期内）**
```bash
# ① 配置真的生效（Nacos 回读；键必须在，值为 key-value）
curl -s "$NACOS_ADDR/nacos/v1/cs/configs?dataId=ypbin-access.yaml&group=DEFAULT_GROUP" \
  | grep -E 'payload-format|key-value'
# ② access 起来了且无绑定错误
docker logs --since 5m ypbin-access 2>&1 | grep -iE 'ConfigurationProperties|BindException|payload-format' || echo "无绑定错误（OK）"
# ③ 指标：规范化失败期望 0；出口 byte[] 闸门期望 0（两者任一增长 = 配置/版本没对齐）
curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.value.normalize.failure
curl -s http://127.0.0.1:18086/actuator/metrics/iot.access.egress.invalid
# ④ 端到端：某已知键值帧设备的点位仍 GOOD 且有新行（iot 侧；替换 deviceId/propertyId）
curl -s 'http://127.0.0.1:18084/iot/devices/9300012/series?propertyId=temperature&limit=5'
```
**判据**：`iot.access.egress.invalid` 不增长、`iot.access.value.normalize.failure` 为 0、
`/series` 有新数值点（曲线可画）；若 `iot.access.egress.invalid` 增长 ⇒ 配置没切到 key-value（SDK 仍 binary）。

### 5.5 部署脚本的「渲染后配置」不得留在盘上（凭据卫生）

`deploy/install.sh` 导入 Nacos 前的 sed 渲染产物**含真实口令与网关签名标记**。已改为
`mktemp`（默认 600 权限）+ 用后即删 + `EXIT` trap 兜底（只删自己创建的文件，不用通配符误删并发进程的文件）。

运维自查（**计数式判定，不要把文件内容打出来**；用 `find` 以覆盖**隐藏文件**与 /tmp 之外的位置）：

```bash
sudo bash -c 'cd /opt/ypbin/ypbin-iot/deploy; set -a; . ./.env; set +a
find /tmp /var/tmp /root /opt/ypbin -maxdepth 2 -type f \
     ! -name "*.jar" ! -name "*.sql" ! -name "*.log" -print0 2>/dev/null \
| while IFS= read -r -d "" f; do
    g=$(grep -cF "$GATEWAY_SIGN_TOKEN" "$f" 2>/dev/null); g=${g:-0}
    i=$(grep -cF "$INTERNAL_TOKEN"     "$f" 2>/dev/null); i=${i:-0}
    r=$(grep -cF "$REDIS_PASSWORD"     "$f" 2>/dev/null); r=${r:-0}
    m=$(grep -cF "$MYSQL_ROOT_PASSWORD" "$f" 2>/dev/null); m=${m:-0}
    [ "$g$i$r$m" = "0000" ] || echo "含凭据: $f (gw=$g it=$i redis=$r mysql=$m)"
  done'
```

判据说明（**不要夸大**）：两个 64 位随机 token 的命中是硬证据；`MYSQL_ROOT_PASSWORD`/`REDIS_PASSWORD`
长度只有十几到三十几位，命中只能当**线索**（可能偶合），需人工看一眼文件用途再处置。

**「期望输出」= 下面这份受管/遗留清单，且其中不得出现 `/tmp` 路径**（2026-09-26 实测，全部 `600`、
root-only；`deploy/.env` 是**刻意的凭据源**，已由 `! -name ".env"` 排除在扫描外）：

| 文件 | 命中 |
|---|---|
| `/opt/ypbin/nacos-ypbin-iot.yaml.bak-20260925-013857` | gateway ×2（**值行 + 注释行**——正是「全局 sed 把凭据写进注释」那次的物证） |
| `/opt/ypbin/nacos-ypbin-gateway.yaml.bak-20260925-013857` | gateway ×1 |
| `/opt/ypbin/nacos-ypbin-system.yaml.bak-20260925-013857` | gateway ×1 |
| `/opt/ypbin/nacos-ypbin-ai.yaml.bak-20260925-013857` | gateway ×1 |
| `/opt/ypbin/nacos-ypbin-common.yaml.bak-20260925-013857` | redis ×1、mysql ×1（**不含**两个 token） |
| `/opt/ypbin/_rollback-20260925-003947/admin-deploy.env.bak` | gateway ×1、redis ×1、mysql ×1（近似完整凭据文件） |

这 6 个文件是**上一轮部署的回滚材料**（未被任何 rollback 脚本引用），本轮**刻意未删**；
它们与 `deploy/.env` 一同属**轮换范围**（见下）。**若在 `/tmp`、`/var/tmp` 或其它位置扫出**额外文件，
那就是要立刻删掉的渲染残留（本轮已清掉 22 个）。

> 历史轮次的部署曾在服务器 `/tmp` 留下若干 **644** 的渲染配置（含当时的网关签名标记与内部 token）。
> 2026-09-26 已清理干净（删除 **22** 个文件，复核残留 **0**）。
> **这些标记曾以明文出现在本机 `/tmp`**——轮换是跨服务联动
> （`.env` 改值 → 重导 `ypbin-common.yaml` 与各服务配置 → 全量重启并复验登录/内部调用），
> 属需要维护窗口的独立动作。**已于 2026-09-26 执行完毕，处置面/命令/判据/回滚见 §5.6。**

### 5.6 凭据轮换实测：`GATEWAY_SIGN_TOKEN` / `INTERNAL_TOKEN`（2026-09-26 已执行）

> §5.5 末尾记的「**这些标记曾以明文出现在 `/tmp`，建议择期轮换**」于 **2026-09-26 执行完毕**，
> 本节是它的落地记录（处置面、命令、判据、回滚）。
> **命令里一律写占位符；真值只允许存在于 `deploy/.env`、Nacos 活配置，以及 700 目录 + 600 文件的回滚目录。**

#### 5.6.1 完整性：这两枚值一共有几处（漏一处就是 401/403）

> 下表是**服务器 live 现状**（**不是模板全集**：`ypbin-access.yaml` 的模板另有签名键，见 §5.6.6）。
> live 现状的布尔复核：7 份全部「旧值 0 命中、新值命中」。

| 键 | 位置 | 角色 |
|---|---|---|
| `GATEWAY_SIGN_TOKEN` | Nacos `ypbin-gateway.yaml` → `ypbin.gateway.auth.trusted-source-token` | **签发侧**：网关在写身份头的同时写 `X-Gateway-Signed` |
| 同上 | Nacos `ypbin-common.yaml` / `ypbin-auth.yaml` / `ypbin-system.yaml` / `ypbin-ai.yaml` / `ypbin-iot.yaml` → `ypbin.cloud.feign.trusted-source-token` | **消费侧**：入站请求带对标记才允许把身份头经 Feign 二次透传 |
| 同上 | `deploy/.env` → `GATEWAY_SIGN_TOKEN` | 渲染源（`install.sh` 替换模板里的 `${GATEWAY_SIGN_TOKEN}`） |
| `INTERNAL_TOKEN` | Nacos `ypbin-common.yaml` → `ypbin.internal.token` | 共享值：auth/system/iot 的携带侧 + system 的 `/internal/**` 守卫侧 |
| 同上 | Nacos `ypbin-access.yaml` → `ypbin.internal.token` | access 出站凭证（**access 没在跑也要改**） |
| 同上 | `deploy/.env` → `INTERNAL_TOKEN` | 渲染源 + compose 直接注入 `ypbin-access` |

四条口径（2026-09-26 逐条实测/核对，不是推断）：

1. **必须「先 dump live 再在 live 内容上改」，不能拿仓库副本整份覆盖。**
   - `ypbin-common.yaml` 的 `ypbin.cloud.feign.trusted-source-token` 块与
     `ypbin-auth.yaml` 的 `ypbin.security.identity.enabled: false` **只在 live 有**，
     模板里没有（已与 `origin/main` 的 `deploy/nacos/` 逐份核对）——整份覆盖会**抹掉它们**。
   - 反向的 drift 见 §5.6.6。
2. 仓库 `deploy/nacos/*.yaml` 一律用 `${...}` 占位符 ⇒ **不用改仓库模板**。但**别指望
   「重跑 `install.sh` 就自动生效」**：`install.sh:1280` 写的是
   `NACOS_DIR="$ROOT/ypbin-admin/deploy/nacos"`，它假设**本仓被检出为名为 `ypbin-admin` 的目录**
   （`SYNC.md` 第二节的部署约定）；本部署目录是 `/opt/ypbin/ypbin-iot` ⇒ 该路径不存在，
   脚本只会拿**旧 admin 仓**的模板渲染。要补 access 的模板键，走 §5.6.6 的显式渲染 + POST。
3. Java 侧只读配置（`InternalProperties` / `FeignProperties` / `GatewayProperties`），**无硬编码**；
   4 个运行中容器 jar 内 `BOOT-INF/classes` 配置经解包逐份核对**不含真值**。
4. 改了 live 之后**除 token 值以外逐字未变**（把两代值都归一化再 diff，7 份全部一致）⇒
   没有顺带改坏别的键，也没有把 live-only 键带丢。

#### 5.6.2 处置步骤（顺序不可换）

```bash
# ---- ⓪ 工作目录（700）+ 只读备份：先 dump 再改 ----
cd /opt/ypbin/ypbin-iot
TS=$(date +%Y%m%d-%H%M%S); W=/opt/ypbin/token-rotation-$TS
install -d -m 700 "$W/before/nacos" "$W/after/nacos"

# 活配置 dump（Nacos 3 Console API；accessToken 只进 600 的 curl 配置文件，不进命令行）
NACOS=http://127.0.0.1:8080
NACOS_USERNAME="${NACOS_USERNAME:?Nacos 控制台用户名}"
NACOS_PASSWORD="${NACOS_PASSWORD:?Nacos 控制台口令——显式提供，不要写进文档/命令行}"
T=$(curl -fsS -X POST "$NACOS/v3/auth/user/login" \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode "username=$NACOS_USERNAME" --data-urlencode "password=$NACOS_PASSWORD" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
printf 'header = "accessToken: %s"\n' "$T" > "$W/.hdr"; chmod 600 "$W/.hdr"; unset T
for c in ypbin-common ypbin-gateway ypbin-auth ypbin-system ypbin-ai ypbin-iot ypbin-access; do
  curl -sS -K "$W/.hdr" -o "$W/live-$c.json" \
    "$NACOS/v3/console/cs/config?dataId=$c.yaml&groupName=DEFAULT_GROUP&namespaceId="
  python3 -c 'import json,sys;open(sys.argv[2],"w").write(json.load(open(sys.argv[1]))["data"]["content"])' \
    "$W/live-$c.json" "$W/before/nacos/$c.yaml"
  chmod 600 "$W/live-$c.json" "$W/before/nacos/$c.yaml"
done
cp -a deploy/.env "$W/before/deploy.env.bak-$TS"     # 600

# ---- ① 生成新值（只落 600 文件；输出只给 长度+md5）----
openssl rand -hex 32 > "$W/.new-gateway"; openssl rand -hex 32 > "$W/.new-internal"
chmod 600 "$W/.new-gateway" "$W/.new-internal"

# ---- ② 在 live 内容上只替换「值」（保留 live-only 键），再发布 ----
#      OLD_G/OLD_I/NEW_G/NEW_I 从文件读入环境变量（不进程命令行）
for c in ypbin-common ypbin-gateway ypbin-auth ypbin-system ypbin-ai ypbin-iot ypbin-access; do
  python3 - "$W/before/nacos/$c.yaml" "$W/after/nacos/$c.yaml" <<'PY'
import os, sys
src = open(sys.argv[1]).read()
for old, new in ((os.environ["OLD_G"], os.environ["NEW_G"]), (os.environ["OLD_I"], os.environ["NEW_I"])):
    src = src.replace(old, new)          # 值级替换：不碰任何键名与结构
open(sys.argv[2], "w").write(src)
PY
  curl -sS -K "$W/.hdr" -X POST "$NACOS/v3/console/cs/config" \
    --data-urlencode "dataId=$c.yaml" --data-urlencode groupName=DEFAULT_GROUP \
    --data-urlencode type=yaml --data-urlencode namespaceId= \
    --data-urlencode "content@$W/after/nacos/$c.yaml"
done

# ---- ③ 改 deploy/.env 的两行（用 python 改，值不进 argv）----
# ---- ④ 逐个重启（不可并行；每步确认起来再看内存）----
docker restart ypbin-nacos && \
  until curl -fsS -m5 "$NACOS/v3/console/health/readiness" >/dev/null; do sleep 3; done
for c in ypbin-gateway:18080 ypbin-auth:18081 ypbin-system:18082 ypbin-iot:18084; do
  docker restart "${c%%:*}"; sleep 20
  curl -s "http://127.0.0.1:${c##*:}/actuator/health"; free -m | sed -n 2p
done
```

> - **禁止在服务器上 `mvn`**（源码树停滞，见第 1 节）；本轮**只改配置**，故无需重建镜像。
> - 重启用 `docker restart` 即可（配置从 Nacos 重新拉取）；**绝不** `down -v`。
> - `.env` 里这两枚对 gateway/auth/system/iot 只作渲染源（它们从 Nacos 取值）；只有
>   `ypbin-access` 由 compose 直接注入 `INTERNAL_TOKEN`，改 `.env` 后**需要重建**该容器才生效
>   （本轮 access 未运行，故未做）。

#### 5.6.3 联动验证（每条都要真实输出；**不得打印任何 token**）

```bash
# ① health：只有 iot 暴露 actuator（gateway/auth/system 回的是 404 业务信封，HTTP 仍是 200）
curl -s http://127.0.0.1:18084/actuator/health        # 期望 {"groups":["liveness","readiness"],"status":"UP"}
# ② 前端 + 反代：19000 是 nginx（/api/ → 网关，剥 /api）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19000/          # 200
# ③ 登录链路（错口令；不要用正确口令、也别连试以免触发锁定）
curl -s -X POST http://127.0.0.1:19000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"__no_such_user__","password":"__wrong__"}'             # 期望 code 409（不是 403/401）
# ④ 内部凭证链路：新值 200、旧值必须 401
# ⚠️ R8-4 起 /internal/lease/epochs 的 accessNode **必填**（不按节点过滤会退化成全平台扫描）：
#    不带参数会得到 code 400（请求参数有误），别把它误判成「内部凭证轮换失败」
curl -s -H "X-Internal-Token: $NEW_I" "http://127.0.0.1:18084/internal/lease/epochs?accessNode=token-check"  # code 200
curl -s -H "X-Internal-Token: $OLD_I" "http://127.0.0.1:18084/internal/lease/epochs?accessNode=token-check"  # code 401
curl -s -H "X-Internal-Token: $NEW_I" http://127.0.0.1:18082/internal/user-count      # code 200
curl -s -H "X-Internal-Token: $OLD_I" http://127.0.0.1:18082/internal/user-count      # code 401
# ⑤ 下游信任链：直连 18084 带身份头
curl -s -H 'X-User-Id: 1' -H 'X-User-Name: admin' -H 'X-Tenant-Id: 1' \
     -H 'X-Dept-Id: 1' -H 'X-Roles: admin' http://127.0.0.1:18084/devices            # code 200
docker logs --tail=100 ypbin-auth ypbin-system ypbin-iot 2>&1 | grep -ci 'trusted-source\|签名'   # 期望 0
# ⑥ 真实 ERROR：**必须在重启窗口之外量**（重启 nacos 会刷出一批 Nacos gRPC 重连 ERROR，属噪声）
docker logs --since 90s ypbin-iot 2>&1 | grep -cE '(^|[^A-Za-z])ERROR([^A-Za-z]|$)'  # 期望 0
# ⑦ 端口仍只绑回环
ss -lntp | grep -E ':(18081|18082|18084|3306|8848)\b'
```

**2026-09-26 实测结果**：① `status:UP`；② `200`；③ `code 409`（`用户名或密码错误`，
说明网关→auth 仍通且鉴权模式可用）；④ iot 旧 `401`/新 `200`（`{"items":[],"readAt":...}`）、
system 旧 `401`/新 `200`（`data:"6"`）；⑤ `code 200`、信任链告警 `0`；
⑥ 重启窗口外四服务 `ERROR=0`（窗口内有 Nacos gRPC 重连噪声 48–53 行/服务，属重启副作用）；
⑦ 五个端口全部 `127.0.0.1`（`0.0.0.0` 只有 18080 网关与 19000 前端，属既有暴露面）。

#### 5.6.4 「旧值失效」怎么证（诚实口径，别写成「已失效」了事）

- **`INTERNAL_TOKEN`：有直接判据。** 旧值打 `/internal/**` ⇒ `code 401`
  （`内部调用凭证校验失败`），新值 ⇒ `code 200`；iot 与 system 各测一枚端点。
- **`GATEWAY_SIGN_TOKEN`：没有直接判据。** 实测（直连 18084 `/devices` 带身份头，标记头分别给
  正确值 / 伪造值 / 不带）**三者都 `code 200`** ⇒ 下游**没有入站校验**——该键当前只决定
  「身份头是否经 Feign 二次透传」（`FeignHeaderInterceptor#isIdentitySourceTrusted`；
  且入站无校验时 `isIdentitySourceTrusted` 在未配置 token 的情况下**恒为 true**）。
  因此只能用**等价证据链**：
  1. **取值路径唯一**：容器 env 无该键、jar 内 classpath 配置无该键/无真值、Java 只读 Environment
     ⇒ 运行时该值的唯一来源是 Nacos 活配置；
  2. **Nacos 活配置已不含旧值、且含新值**（重 dump 后逐份 boolean 复核：7 份全部 旧 `no`、新 `YES`）；
  3. **除 token 值外逐字未变**（见 §5.6.1 第 4 条）；
  4. auth/system/iot（及未运行的 ai）**在改后成功重启**，而 `ypbin.cloud.feign.require-trusted-source=true`
     是 fail-fast（token 缺失即拒绝启动）⇒ 这几个服务运行时确实读到了非空的新值。
     **gateway 不在此列**：它是 WebFlux，live `ypbin-gateway.yaml` 里 `require-trusted-source`
     实测 0 命中、`GatewayProperties$Auth` 也无 fail-fast ⇒ 它的**签发侧**新值只能由第 2 条佐证；
  5. 旧值在服务器上**除受控回滚目录（700/600）外已无任何落盘位置**（`grep -rl` 只列路径）。
  ⇒ 结论只能表述为「**旧值已不在任何生效路径上**」；待 starter 补上入站校验（SF-5）后，
  旧值才会变成「**可被直接证伪**」。
- **已知设计缺口（登记）**：标记头值经 `String.equals` 比较（非常量时间），且下游不校验入站标记头。

#### 5.6.5 轮换后的就地清理（清理账目，别只写「已清理」）

- **删除**（`600` 遗留备份，删除前逐份确认命中）：`nacos-ypbin-{iot,gateway,system,ai}.yaml.bak-20260925-013857`
  （含旧 `GATEWAY_SIGN_TOKEN`）、`nacos-ypbin-common.yaml.bak-20260925-013857`（不含两枚 token，
  但含 `redis`/`mysql` 口令）、`deploy/.env.bak-20260925-013857`（含两枚 token + 库/缓存口令）。
- **改值而非删除**：`_rollback-20260925-003947/admin-deploy.env.bak` 与
  `/opt/ypbin/main/ypbin-admin/deploy/.env`（不运行的旧 admin 栈）——按「保持新值一致、
  但不删别人的回滚材料」处置，两枚键都改成新值（**改前各自 600 备份**）。
- **保留**：`$W/before/`（回滚原料，也是旧值**唯一**受控留存点）、
  `nacos-ypbin-{auth,access}.yaml.bak-*`（实测不含两枚旧值）、`rollback-20260925-013857.sh`。
- **清理后复核**：`grep -rl` 旧值只命中 `$W/` 之内；新值在 `/tmp` **零命中**。

#### 5.6.6 本轮新发现的 drift（登记，本轮**未**改）

`origin/main` 的 `deploy/nacos/ypbin-access.yaml`（commit `1af9f4c`，#38）**有**
`ypbin.cloud.feign.trusted-source-token` + `require-trusted-source: true`，理由写着
「access 不引 `ypbin-common.yaml`，拿不到共享键 ⇒ 必须在本 Data ID 显式声明」；
但**服务器 live 的 `ypbin-access.yaml` 里没有这两键**（服务器源码树停在旧 commit，导入的是旧模板）。
后果：access 现在若被拉起，`require-trusted-source` 缺失 ⇒ 走「未配置即恒可信」的兼容默认，
**身份头透传没有来源门**。修法**不能靠「重跑 install.sh」**（见 §5.6.1 条 2 的 `NACOS_DIR` 问题），
要用本仓模板**显式渲染后单独发**：

```bash
set -a; . /opt/ypbin/ypbin-iot/deploy/.env; set +a
cd /opt/ypbin/ypbin-iot
TMP=$(mktemp); trap 'rm -f "$TMP" "$TMP.live"' EXIT      # mktemp 默认 600（见 §5.5）
sed -e "s|\${GATEWAY_SIGN_TOKEN}|$GATEWAY_SIGN_TOKEN|g" \
    -e "s|\${INTERNAL_TOKEN}|$INTERNAL_TOKEN|g" \
    deploy/nacos/ypbin-access.yaml > "$TMP"

# ① 先 dump live 并与渲染结果 diff（NACOS / NACOS_USERNAME / NACOS_PASSWORD / HDR 见 §5.6.2 登录片段）
curl -sS -K "$HDR" \
  "$NACOS/v3/console/cs/config?dataId=ypbin-access.yaml&groupName=DEFAULT_GROUP&namespaceId=" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["content"])' > "$TMP.live"
diff <(sed -E 's/(token|password|secret)([[:space:]]*:[[:space:]]*)[^[:space:]]+/\1\2<masked>/Ig' "$TMP.live") \
     <(sed -E 's/(token|password|secret)([[:space:]]*:[[:space:]]*)[^[:space:]]+/\1\2<masked>/Ig' "$TMP")
# ⚠️ diff 里除「新增 trusted-source 两键」外的每一处都要逐条判：模板与 live 还有其它 drift 时，
#    只把需要的键**并入 live 内容**，不要整份覆盖（§5.6.1 条 1 就是这么丢键的）

# ② 用 ① 判过之后的最终内容发布（下面是刚渲染出来的那份；若 ① 发现别的 drift，先按上面并入 live）
curl -sS -K "$HDR" -X POST "$NACOS/v3/console/cs/config" \
  --data-urlencode dataId=ypbin-access.yaml --data-urlencode groupName=DEFAULT_GROUP \
  --data-urlencode type=yaml --data-urlencode namespaceId= \
  --data-urlencode "content@$TMP"
```

属**独立动作**，本轮不动（不在轮换范围内，且 access 未运行）。

> ⚠️ 同批实测发现的**另一处加固项**（同样不在轮换范围）：Nacos 控制台仍是**内置默认口令**
> （`deploy/.env` 无覆盖键、`install.sh` 的默认值即默认口令，实测可用它登录 `127.0.0.1:8080`）。
> 8080/8848 只绑回环，但它握着全部服务配置 ⇒ 建议下一轮把它纳入加固清单（换口令 + 写入 `.env`）。

#### 5.6.7 回滚（一键）

```bash
bash /opt/ypbin/token-rotation-<TS>/rollback-<TS>.sh
```

脚本做四件事：① 还原 `deploy/.env`（轮换后版本留档）；② 还原两处**非运行 admin 旧栈**的 `.env`；
③ 把 7 份「轮换前」的 live 快照原样重发到 Nacos；④ 按 `nacos → gateway → auth → system → iot`
逐个重启并等就绪。**前提是 `$W/before/` 未被删除**——它就是回滚原料。
