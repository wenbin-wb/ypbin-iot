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
curl -s -H "X-Internal-Token: $NEW_I" http://127.0.0.1:18084/internal/lease/epochs   # code 200
curl -s -H "X-Internal-Token: $OLD_I" http://127.0.0.1:18084/internal/lease/epochs   # code 401
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
TMP=$(mktemp); trap 'rm -f "$TMP"' EXIT          # mktemp 默认 600（见 §5.5）
sed -e "s|\${GATEWAY_SIGN_TOKEN}|$GATEWAY_SIGN_TOKEN|g" \
    -e "s|\${INTERNAL_TOKEN}|$INTERNAL_TOKEN|g" \
    /opt/ypbin/ypbin-iot/deploy/nacos/ypbin-access.yaml > "$TMP"
# ⚠️ 发之前先 diff live，别整份覆盖（会带丢 live-only 键，见 §5.6.1 条 1）
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
