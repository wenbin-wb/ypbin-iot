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
> **这些标记曾以明文出现在本机 `/tmp`，建议择期轮换**——轮换是跨服务联动
> （`.env` 改值 → 重导 `ypbin-common.yaml` 与各服务配置 → 全量重启并复验登录/内部调用），
> 属需要维护窗口的独立动作，**本轮未做**。
