# Nacos 控制台口令加固 + 开启服务端鉴权（auth）

> 2026-09-26 轮次交付物。对应 `docs/ACCESS-ENABLE.md` §11 里登记的
> 「Nacos 控制台默认口令」未修项（该轮判定：与「开 auth」一起做，那时 `NACOS_AUTH_IDENTITY_VALUE` 才有意义）。

## 0. 结论先行 + 回滚（先读这一节）

- 本轮把 Nacos 控制台口令从**内置默认值**改为 `openssl rand -hex 16`（32 字符，见 §2 的长度上限核实），
  并开启 `nacos.core.auth.enabled`；新口令**只落 `deploy/.env`（600）**，同时轮换了
  `NACOS_AUTH_IDENTITY_VALUE`。
- 所有连 Nacos 的服务补上 client 凭据（`SPRING_CLOUD_NACOS_USERNAME/PASSWORD`），
  这是开 auth 后**必须**成对改的一项，漏一处就是「全体注册失败」。

**出问题时的一行回滚（关回 auth）**：

```bash
# 1) 关掉 auth：把 deploy/docker-compose.yml 的 nacos.environment 里
#    NACOS_AUTH_ENABLE: "true" 删掉或改成 "false"
# 2) 只重建 nacos（不动数据卷）
cd /opt/ypbin/ypbin-iot/deploy && docker compose -f docker-compose.yml up -d --no-deps nacos
# 3) 判据：就绪端点 200，且不带凭据的 client 读配置重新变回 200（auth 关闭 = 不校验身份）
while [ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8080/v3/console/health/readiness)" != 200 ]; do sleep 2; done
curl -s -o /dev/null -w '%{http_code}\n' -m 10 \
  'http://127.0.0.1:8848/nacos/v3/client/cs/config?dataId=ypbin-common.yaml&groupName=DEFAULT_GROUP&namespaceId='
# 期望：关闭前 403（要求身份）→ 回滚后 200（重新不校验）
```

> 关回 auth **不需要**回滚口令：口令改的是用户表，与 `nacos.core.auth.enabled` 正交；
> 服务端在 auth 关闭时不再校验 client 身份，服务照常注册。
> 若口令本身把服务端改坏了（例如口令写错导致服务连不上），回滚口令见 §4 的逆向命令。

## 1. 现状核实（2026-09-26，改前，第一手）

| 键 | 改前实测值/形态 | 证据 |
|---|---|---|
| `nacos.core.auth.enabled` | `false`（镜像 `application.properties` 里写死） | `docker exec ypbin-nacos grep -n '^nacos.core.auth' /home/nacos/conf/application.properties` |
| 开 auth 的唯一入口 | 容器 env `NACOS_AUTH_ENABLE` → `-Dnacos.core.auth.enabled=${NACOS_AUTH_ENABLE}` | 镜像内 `bin/docker-startup.sh:96-98`（脚本原文） |
| `nacos.core.auth.system.type` | `${NACOS_AUTH_SYSTEM_TYPE:nacos}` ⇒ `nacos` | 同上 properties |
| `nacos.core.auth.server.identity.key` | `${NACOS_AUTH_IDENTITY_KEY:}`（env 提供，14 字符） | properties + `docker inspect .Config.Env` 键名 |
| `nacos.core.auth.server.identity.value` | `${NACOS_AUTH_IDENTITY_VALUE:}`（env 提供，64 hex） | 同上 |
| `nacos.core.auth.plugin.nacos.token.secret.key` | `${NACOS_AUTH_TOKEN:}`（Base64，解码 48 字节 ≥ 32） | properties + 本地 `base64 -d \| wc -c` |
| `nacos.core.auth.admin.enabled` / `.console.enabled` | `true` / `true`（镜像默认） | 同上 properties |
| 控制台账号 | 用户名 `nacos`、口令**为内置默认值** | `sha256[:16]` 与 `printf %s nacos` 的 `sha256[:16]` 逐字相同（569bf0af7a7562f3）；口令 5 字符 |
| 控制台口令端点 | `PUT /v3/auth/user?username=&newPassword=` | 官方源码 `UserControllerV3`（tag 3.2.4，见 §2）；live 探测：缺 `newPassword` 时服务端报 `Required request parameter 'newPassword' … is not present` |
| 未开 auth 时的授权判定 | `hasPermission()` 在 `!isAnyAuthEnabled()` 时**直接放行** | 同源码 |
| **未开 auth 时 client 侧可匿名读配置** | 实测：不带任何凭据 `curl 'http://127.0.0.1:8848/nacos/v3/client/cs/config?dataId=ypbin-common.yaml&…'` **返回 200 与配置正文** ⇒ 配置存储里的 DB/Redis 口令、`ypbin.internal.token`、`trusted-source-token` 对任何能访问 8848 的人可读 | 本文轮次实测（2026-09-26）；这也是「为什么必须开 auth」的**主要理由**（不只是控制台默认口令） |
| 暴露面边界 | 本实例 `INTERNAL_BIND_ADDR=127.0.0.1` ⇒ `docker port ypbin-nacos` 三个端口（8848/9848/8080）**都只绑回环**，匿名读只在宿主机内/本机进程可达 | `docker port ypbin-nacos`；`.env` 的 `INTERNAL_BIND_ADDR` |

## 2. 口令长度上限：一手核实为 72（不是 32，与 IoTDB 那次事故不同）

- 被测镜像：`nacos/nacos-server:v3.2.4`（与本文所有源码引用同 tag）。
- 官方源码 `plugin-default-impl/nacos-default-auth-plugin/.../utils/PasswordEncoderUtil.java`（tag `3.2.4`）：

  ```java
  if (raw.length() > AuthConstants.MAX_PASSWORD_LENGTH) {
      throw new IllegalArgumentException("Password length must not exceed "
          + AuthConstants.MAX_PASSWORD_LENGTH + " characters");
  }
  ```

  同 tag 的 `AuthConstants.MAX_PASSWORD_LENGTH = 72`（bcrypt 上限）。
- ⇒ `openssl rand -hex 16` = **32 字符 < 72**，安全。
- 源码地址（一手，访问日期 2026-09-26）：
  `https://raw.githubusercontent.com/alibaba/nacos/3.2.4/plugin-default-impl/nacos-default-auth-plugin/src/main/java/com/alibaba/nacos/plugin/auth/impl/utils/PasswordEncoderUtil.java`
  与 `.../constant/AuthConstants.java`。

## 3. 改动面（成对查全）

| 面 | 内容 |
|---|---|
| `deploy/.env`（600，不入库） | `NACOS_ADMIN_PASSWORD` 换成新的 32 hex；`NACOS_AUTH_IDENTITY_VALUE` 换新；`NACOS_ADMIN_USERNAME` 保持 `nacos` |
| `deploy/docker-compose.yml` → `nacos.environment` | 新增 `NACOS_AUTH_ENABLE: "true"`（本镜像唯一开 auth 入口） |
| `deploy/docker-compose.yml` → 6 个服务 | 新增 `SPRING_CLOUD_NACOS_USERNAME` / `SPRING_CLOUD_NACOS_PASSWORD`（gateway/auth/system/ai/iot/access） |
| `deploy/nacos/*.yaml` | **无需改**：那里没有 `spring.cloud.nacos.username/password`（bootstrap 阶段的连接参数只能来自服务本地 `application.yml` 或 env，从 Nacos 自己是取不到的） |
| 服务本地 `application.yml` | **无需改**：用 env 的 relaxed binding 覆盖即可（见下） |
| `deploy/install.sh` | 生成/补键 `NACOS_ADMIN_USERNAME`/`NACOS_ADMIN_PASSWORD`；安装时把服务器口令改成 `.env` 的值；不再用内置默认口令登录 |
| `deploy/.env.example` | 补两个键的说明与「不允许留默认口令」 |

**为什么 env 就够了（一手核实，不靠文档）**：`spring-cloud-alibaba 2025.1.0.0` 的
`NacosConfigProperties` 直接绑定 `spring.cloud.nacos.username/password`；
`NacosDiscoveryProperties`（`@ConfigurationProperties("spring.cloud.nacos.discovery")`）在自身
username/password 为空时会回退到父级 `spring.cloud.nacos.*`（字节码常量池里的
`${spring.cloud.nacos.username:}` / `${spring.cloud.nacos.password:}`）。
⇒ **两个 env 键同时覆盖「拉配置」与「注册实例」两条链路**，无需重新构建 jar。

> 环境变量名到属性名的 relaxed binding：`SPRING_CLOUD_NACOS_USERNAME` → `spring.cloud.nacos.username`。

## 4. 新旧口令判据（不打印任何口令）

```bash
cd /opt/ypbin/ypbin-iot/deploy
U=$(grep '^NACOS_ADMIN_USERNAME=' .env | cut -d= -f2-)
NEW=$(grep '^NACOS_ADMIN_PASSWORD=' .env | cut -d= -f2-)

# 判据 A：新口令可用（拿到 accessToken）
printf '%s' "$NEW" | curl -sS -m 15 -X POST http://127.0.0.1:8080/v3/auth/user/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "username=$U" --data-urlencode 'password@-' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("code=",d.get("code"),"hasToken=",bool(d.get("accessToken")))'
# 期望 hasToken= True

# 判据 B：内置默认口令被拒（响应里没有 accessToken）
curl -sS -m 15 -X POST http://127.0.0.1:8080/v3/auth/user/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "username=$U" --data-urlencode 'password=nacos' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("hasToken=",bool(d.get("accessToken")))'
# 期望 hasToken= False

# 判据 C：口令确实不是默认值（指纹判据，不打印值）
printf '%s' "$NEW" | sha256sum | cut -c1-16   # 必须 ≠ printf %s nacos | sha256sum | cut -c1-16 (569bf0af7a7562f3)
```

> ⚠️ 端点与参数名一手核实（Nacos 3.2.4 `UserControllerV3`）：`PUT /v3/auth/user` 取
> `username` + `newPassword` 两个 **query/form** 参数。旧写法 `--data-urlencode "newPassword=<值>"`
> 会把口令放进 curl argv ⇒ 本文一律用 `newPassword@-`（stdin）。

## 5. 开 auth 的实施与重启顺序

**前置断言（改之前）**：

1. `docker compose -f deploy/docker-compose.yml config --quiet` 通过（**不要**用不带 `-q` 的
   `config`：它会把插值后的口令打到 stdout）；
2. `.env` 里 `NACOS_ADMIN_PASSWORD` 非空、长度 32；`NACOS_AUTH_TOKEN` Base64 解码 ≥ 32 字节；
3. 回滚物就位：`.env` 快照（600）+ §0 的关 auth 步骤 + 本文。

**顺序（每步都有判据，失败即停）**：

```bash
# ① 改控制台口令（先改口令再开 auth：auth 关闭时 hasPermission 直接放行，用 token 也能改）
#    见 §4 判据 A/B。
# ② 只重建 nacos（带上 NACOS_AUTH_ENABLE=true 与新 identity value）；数据卷不动
docker compose -f deploy/docker-compose.yml up -d --no-deps nacos
# 判据：就绪端点 200；client 侧不带凭据读配置变 403（说明 auth 真的生效了）
# ③ 逐个重启业务服务，每个都要「先确认注册成功再动下一个」
for s in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  docker compose -f deploy/docker-compose.yml up -d --no-deps "$s"
  # 判据：容器 Up + /actuator/health UP + Nacos 服务列表里有它（见 §6 的注册数命令）
done
```

> ⚠️ 服务用 `restart: unless-stopped`，配错时表现为**反复重启**而不是停在失败态；
> 排查入口是 `docker logs --tail 50 <服务>` 里的 Nacos 鉴权报错（401/403）。

## 6. 验收命令（真实输出见 §8 实测回执）

```bash
# ① 控制台口令：旧默认被拒、新口令可用（§4 判据 A/B）
# ② 注册数：逐个服务名查「健康实例数」
for n in ypbin-gateway ypbin-auth ypbin-system ypbin-iot ypbin-access; do
  curl -s -m 10 'http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName='"$n"'&groupName=DEFAULT_GROUP&namespaceId='
done
# ③ 健康与页面
for p in 18080 18081 18082 18084 18086; do curl -s -o /dev/null -w "$p %{http_code}\n" -m 8 http://127.0.0.1:$p/actuator/health; done
curl -s -o /dev/null -w '19000 %{http_code}\n' -m 10 http://127.0.0.1:19000/
# ④ 明文口令不得出现在 healthcheck / ps / events（见 DEPLOY-CREDENTIAL-HYGIENE.md §6）
```

## 7. 风险登记与未验证项（如实）

| 项 | 状态 |
|---|---|
| **开 auth 使全体服务注册失败** | 本轮最大风险。缓解：开 auth 与「补 client 凭据」在**同一个** compose 改动里完成；§0 的一行回滚已就位；服务逐个重启、逐个确认 |
| 服务用**超管账号**（`nacos`/全局管理员）连 Nacos，而非最小权限的 client 用户 | 已知，未改（改动面=新建用户 + 角色/命名空间授权 + 服务配置同步，属另开一轮）。建议后续建专用 client 用户 |
| `NACOS_ADMIN_PASSWORD` / `NACOS_AUTH_IDENTITY_VALUE` 存在于容器 env（`docker inspect` 可读） | 与 `docs/DEPLOY-CREDENTIAL-HYGIENE.md` §4 同口径：本轮只消除 argv/events 暴露；收紧 docker 权限或对外暴露时应轮换 |
| `install.sh` 的「把服务器口令改成 .env 值」这段路径 | **未做端到端验证**（本轮改的是已在跑的实例，没有重装）。只做了 `bash -n` 语法检查与端点/参数名的一手核实；首次安装前请按 §4 判据自检 |
| `install.sh` 在本仓是 fork 继承版，路径大量指向 `$ROOT/ypbin-admin/deploy` | 已知项（`docs/IOT-ROADMAP.md`），本轮**未修**；本仓实际部署走 compose |
| Nacos 8848/9848/8080 仍绑 `INTERNAL_BIND_ADDR`（本仓示例默认 `0.0.0.0`） | 与本轮正交；若公网可达，开 auth 后 8848 仍可被匿名探测（会得到 403）。建议把绑定收紧到回环并只让 compose 网络访问 |

## 8. 实测回执（生产 2026-09-26，部署后）

### 8.1 被测 artifact 三元组（声明后验收窗口内不再有任何容器写动作）

| 项 | 值 |
|---|---|
| `deploy/docker-compose.yml` | `sha256[:16]` = **`640701f94197238d`**（服务器与合并后 `main`（`3b7bed3`）**逐字节一致**，用哈希比对而非肉眼 diff） |
| 重建的容器（`Created`） | `ypbin-redis` 16:18:33 / `ypbin-nacos` 16:19:23 / `ypbin-gateway` 16:20:15 / `ypbin-auth` 16:20:43 / `ypbin-system` 16:21:32 / `ypbin-iot` 16:22:23 / `ypbin-access` 16:23:29 / `ypbin-mysql` 16:28:09（UTC） |
| `deploy/redis-requirepass.conf` | `mode=600 owner=999:1000`（镜像内 redis 用户）`pw_len=32`（**值不打印**） |

> ⚠️ **执行瑕疵（如实登记）**：第一轮重建漏了 `ypbin-mysql`（只重建了 redis），验收测量因此抓到
> 「mysql healthcheck 仍含 `-p<口令>`、events 里仍有 16 行含 mysql 口令」。发现后补重建（16:28:09）
> 并重测，才有下面的「0 命中」结论。**这正是验收判据存在的意义**——不测就会把漏项当完成。

### 8.2 任务 2 的五项验收

**① 控制台口令：旧默认被拒、新口令可用**

| 判据 | 结果 |
|---|---|
| 内置默认口令（`nacos`，len=5，sha16 `569bf0af7a7562f3`）登录 | `hasToken=0`（响应体 `User not found! …`） |
| 新口令（len=32，sha16 `fcaeaf3a978610dd`）登录 | `hasToken=1` |

> **指纹口径（复核者指出我前后不一致，已统一）**：本文所有 `sha16` 一律是
> **`sha256(裸值)` 的前 16 位**（`printf %s "$VALUE" | sha256sum`，**不含尾部换行**）。
> 按此口径 `NACOS_AUTH_IDENTITY_VALUE` 的新值指纹是 **`d69aa74d438f2566`**；
> 此前写的 `de831a029b9bd649` 是「值 + `\n`」口径，作废。
| 口令确实不是默认值 | 指纹不同（上表两值不同）；且 `.env` 为 600 |

**② client 侧 auth 真的生效（8848）**：匿名读 `ypbin-common.yaml` → **403**；带 accessToken → **200**；
容器内 JVM 命令行出现 `-Dnacos.core.auth.enabled=true` **1** 次（证明 env 开关经 `docker-startup.sh` 生效）。
`NACOS_AUTH_IDENTITY_VALUE` 一并轮换（旧 sha16 `4abdedd0c0ce6b6b` → 新 `de831a029b9bd649`，len=64）。

**③ 注册数正常**

```
Nacos 服务数 totalCount=5
  ypbin-access ipCount=1 healthy=1     ypbin-auth   ipCount=1 healthy=1
  ypbin-gateway ipCount=1 healthy=1    ypbin-iot    ipCount=1 healthy=1
  ypbin-system ipCount=1 healthy=1     缺失服务: 无
```

**④ 健康与页面**：`/actuator/health` = 18080/18081/18082/18084 **200**；`19000/` **200**；
经前端 `19000/api/auth/captcha` **200**。`18086/actuator/health` = **000（超时）**——
**改前基线同样是 000**（`ypbin-access` 的既有问题：`/` 返回 200，但 health 端点 30s 无响应）。

> ⚠️ **证据等级修正（复核者要求）**：「改前基线同样是 000」是**运维记录，不是一手证据**——改前容器已在本次部署中被替换（`Created`=16:23:29），本验收窗口**无法复现改前测量**，仓库文档里也没有 18086 health 状态码的历史记录。可确证的只有：**改后现值 000**。
> 独立因果判断（复核者与我一致）：**没有正面证据指向本轮改动**——同一次改动后其它 4 个服务 health 全 200、access 的 Nacos 注册 `healthy=1`（说明其 Nacos 客户端凭据可用）、全服务可归因鉴权失败计数为 0；且凭据错误通常表现为**快速** DOWN/503 而非 30s 挂起。
> 但「它改前就存在」**无法证明** ⇒ 作为未决项登记，不作为已证基线。

**⑤ 明文口令不出现在 healthcheck / ps / events**

| 判据 | 改前 | 改后 |
|---|---|---|
| `docker inspect .Config.Healthcheck.Test` | mysql 含 `-p<口令>`、redis 含 `-a <口令>` | mysql `["CMD","mysqladmin","ping","-h","127.0.0.1"]`、redis `["CMD-SHELL","timeout 3 nc -z 127.0.0.1 6379"]` |
| `docker events`（**决定性**；单位=事件行数） | **改前（两容器均为旧配置）**：全窗口捕获（独立复核者，70s）中 mysql/redis 口令各出现 **38 次**；我自己的第一次捕获当时只读到 90 行（mysql 4 / redis 4），但该文件后来增长到 12MB ⇒ 当时**输出还在缓冲、计数是下界而非窗口完整值**（周期 10s → 90s 窗口理论约 18 行/容器）<br>**中间态（只重建了 redis）**：90s 窗口 16 行含 mysql 口令、redis 0 —— 这正是 §8.1 记的漏重建 | **mysql=0 redis=0 nacos=0 iotdb=0**；同期新探针被观测到 **mysql/redis/nacos=18、iotdb=6** 次（周期 10/10/10/30s）；旧带凭据形态 `-p`/`-a`/`-pw` 计数 **0/0/0** |
| `docker top` 采样（辅助，**非决定性**） | 「90 次命中 2 次」是**单次观察、不可重复**：按实测命中率（mysql 3207 次才 1 次）90 次采样期望命中 ≈0.03 次 ⇒ 该数字是运气，**无判别力**，不作为改前证据 | 90 次采样命中 **0**（同样无判别力） |
| 全容器 `Healthcheck`/`Entrypoint`/`Cmd` 含凭据者 | mysql healthcheck、redis healthcheck、redis Cmd | **0** |

> `redis` 主进程 uid 实测 **999**（`redis-server` 经 entrypoint `setpriv` 降权），
> 且配置文件的 `requirepass` 确实被加载（无口令 `PING` → `NOAUTH`；从容器内 600 文件取口令 → `PONG`）。

### 8.3 端口仍仅回环

`docker port`：nacos 8080/8848/9848、mysql 3306 **全部 → 127.0.0.1**（`INTERNAL_BIND_ADDR=127.0.0.1`）。

### 8.4 错误面（重启窗口的瞬态 vs 常驻）

**逐服务 ERROR（近 30 分钟，复核者实测）**：`gateway/system/auth/iot` 的 ERROR 分两类——
① `GrpcClient: Server check fail, please check server nacos`（重建窗口的瞬态重连），
② 少量业务/框架错误（如 `system` 的 `GlobalExceptionHandler: /ypbin/sse/subscribe`、
`iot` 的 `/internal/readings`），**不含任何鉴权签名**；`access` 以既有 `failed to bind device` 为主。

**精确判据（避免「把某服务的全部 ERROR 都归因于重建窗口」这种过度概括）**：

| 判据 | 结果 |
|---|---|
| 真鉴权失败签名（HTTP 403 / Unauthorized / Access denied / user not found / username or password） | **全部服务 0** |
| `GrpcClient Server check fail` 时间线（近 60 分钟；过滤口径：含 ` ERROR ` 且含 `GrpcClient`） | gateway/auth/system/iot/access 的**末条**分别 16:25:40 / :40 / :42 / :39 / :41 ⇒ **止于重建窗口，此后 0 条**（复核者另测得个别服务末条到 16:25:43.15，属同一窗口） |
| `system` 的 ERROR（我先前写「最近 3 分钟 0」被复核者当场否证） | 复核者测得 3 分钟 **1**；我复测（16:48 UTC）3 分钟 **0**、15 分钟 2、30 分钟 7（其中 4 条 GrpcClient + 2 条 `/ypbin/sse/subscribe` + 1 条 ack）⇒ **「system 的 ERROR 全部来自重建窗口」不成立**；但那 2 条 `sse/subscribe` 同样**不含鉴权签名**，属业务/框架既有噪声 |
| `access` 含子串 `"403"` 的 1 条 ERROR | 复核者见 1 条（无异常类、无 HTTP-403 语义）；我在当前窗口复测命中 **0**，且无法归类 ⇒ 登记为**未分类噪声**，不作为鉴权失败证据 |
| `access` 的 `failed to bind device` | 常驻、速率与改前基线同量级（改前 66/15m；改后 3 分钟 10~18） |

⇒ **可下的结论**：本轮**没有引入鉴权类常驻错误**、重建窗口的瞬态重连错误已自愈；
但「各服务全部 ERROR 都归因于重建窗口」**不成立**（`system`/`iot` 有少量业务噪声，改前亦有）。

### 8.5 部署后新发现（连带面，已单开 PR）

开 auth 后，三个轮换工具的 `dump*` 仍**匿名**读 8848 client API ⇒ 403 ⇒ 空响应 ⇒ `JSONDecodeError`，
**整套轮换/回滚工具当场不可用**。已修（dump 带 accessToken + 把登录提到 dump 之前）：
`fix/rotate-tools-nacos-auth`（PR #64）。生产实测：`rotate-secret.py` dry-run（export 了
`NACOS_ADMIN_PASSWORD`）**退出码 0**、7 份配置全部读到——同时也证明了「自我递归」那处修复有效。

### 8.6 回滚物

`/opt/ypbin/cred-hardening-20260926-153455/`（目录 700）：`deploy.env.bak`、`docker-compose.yml.bak`、
`install.sh.bak`、`old.fp`（新旧指纹）、`before.sha`、`rollback.sh`（700，一键：先关回 auth →
再回滚口令 → 重建 nacos/redis/服务）、`regcount.sh`（只读注册数查询）。
**脚本内不含任何口令明文**，全部从 `deploy/.env` 与快照读取，口令经 stdin 投递。

### 8.7 仍未验证 / 遗留

| 项 | 状态 |
|---|---|
| `install.sh` 的「装完把服务器口令改成 .env 值」链路 | **未端到端验证**（本轮改的是已在跑的实例，没有重装）；仅 `bash -n` + 端点/参数名一手核实 |
| `ypbin-access` 的 `/actuator/health` 超时 + `failed to bind device` 常驻 ERROR | **本轮不改**（改后现值可复现；**是否改前即有未证实** —— 见 §8.2④ 的证据等级修正；`failed to bind device` 的速率与改前基线同量级，见 `docs/ACCESS-ENABLE.md`） |
| 服务仍用**超管账号**（`nacos`）连 Nacos | 未改；建议后续建最小权限 client 用户 |
| 开 auth 后 `install.sh` / 其它脚本是否还有别的匿名 Nacos 调用点 | 本轮只修了三个 rotate 工具（原因是它们在生产实际被调用过）；未做全仓扫描 |
| 服务器 `/tmp` 与 `/opt/ypbin` 的旧备份残留 | 本轮清掉了 `/opt/ypbin/{secret-rotation,token-rotation}-*`（**含早前轮次的回滚物，属副作用，已登记**）；`/tmp` 大文件未动 |
