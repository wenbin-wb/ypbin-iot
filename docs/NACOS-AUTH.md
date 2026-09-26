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

## 8. 实测回执（生产，被测 artifact 三元组见 §5）

> 本节由部署后实测回填；口令/密钥值一律不出现在任何输出里。
