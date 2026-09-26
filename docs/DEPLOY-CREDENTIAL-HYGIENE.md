# 凭据卫生：容器 argv / 健康检查里的明文口令（MySQL、Redis 及同类）

> 本文是 2026-09-26 轮次的交付物之一，与 [`DEPLOY-TIMESERIES.md`](DEPLOY-TIMESERIES.md) §6.1
> （IoTDB 那一处同类问题的处置）配套阅读。所有「实测」结论都标注了被测对象与命令。

## 0. 结论先行

1. **MySQL / Redis 健康检查里的明文口令已消除**：口径与 IoTDB 一致 —— 改成**不带凭据的存活探活**，
   「凭据是否真的可用」交给应用侧（连库/连缓存失败的报错与健康降级）承担，折衷在 §3 明确记账。
2. **Redis 服务端启动参数里的 `--requirepass <口令>` 也一并消除**（这是同一类 argv 暴露：
   `docker top` / 宿主 `ps` / `docker events` 都能读到），改为**受管 600 配置文件**驱动。
   不修它，本轮的验收判据「`docker top` 与宿主 `ps` 不再出现明文口令」根本过不了。
3. **本轮不轮换 MySQL / Redis 口令**（用户裁定）。理由与**如实登记的风险**见 §4。
4. 仓库侧「脚本 argv 带凭据」已清掉（`deploy/install.sh` 的 6 处 `-p<口令>` / `password=<口令>`、
   `tools/*.py` 的 3 处 curl `password=<口令>`）；**只登记不修**的其它暴露面见 §5。

## 1. 改动面清单

| 文件 | 改动 |
|---|---|
| `deploy/docker-compose.yml` | `mysql.healthcheck` 去 `-p<口令>`；`redis.healthcheck` 去 `-a <口令>`（改 TCP 探活）；`redis.command` 去 `--requirepass <口令>`（改挂受管配置文件） |
| `deploy/redis-auth-conf.sh`（新增） | 从 `.env` 生成 `redis-requirepass.conf`（600、owner 对齐镜像内 redis 用户 999:1000），**只打印长度/权限** |
| `.gitignore` | 忽略 `deploy/redis-requirepass.conf`（含真实口令，绝不入库） |
| `deploy/install.sh` | 6 处 argv 口令改 env/stdin；up 之前生成 redis 受管配置；不再把 MySQL 口令打印到 stdout |
| `deploy/.env.example` | Redis/Nacos 口令注释与「不进 argv」口径对齐 |
| `tools/rotate-secret.py`、`rotate-internal-token.py`、`fix-access-trusted-source.py` | curl 口令经 stdin（详见 PR「代码」那一支 + `tools/test_rotate_nacos_password.py`） |

## 2. Redis：`requirepass` 为什么要改成受管配置文件

旧写法：

```yaml
command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASSWORD:?...}"]
```

compose 在解析期就把 `${REDIS_PASSWORD}` 替换成真值 ⇒ **明文口令进入容器 argv**，
`docker top ypbin-redis -eo pid,args`、宿主 `ps`、`docker events` 的 exec 属性都能直接读到。

新写法：

```yaml
command: ["redis-server", "/usr/local/etc/redis/redis-requirepass.conf", "--appendonly", "yes"]
volumes:
  - ./redis-requirepass.conf:/usr/local/etc/redis/redis-requirepass.conf:ro
```

- **为什么不是「环境变量 + 覆盖 entrypoint 写临时配置」**：官方 redis 镜像的 entrypoint
  （实测原文 `/usr/local/bin/docker-entrypoint.sh`）只在首个参数是 `redis-server` 时才做
  `find . ! -user redis -exec chown` + `setpriv --reuid redis` 降权；覆盖 entrypoint 就得自己复刻
  降权（漏了就变成 root 跑 redis），还会把口令再注入容器 env（`docker inspect` 可读）。
  现在的写法**保留官方启动路径与运行身份（镜像内 uid=999）不变**，只换配置来源。
- **文件权限**：`deploy/redis-auth-conf.sh` 生成 600 并把属主改成 `999:1000`
  （镜像内 `id redis` 实测 `uid=999(redis) gid=1000(redis)`）。redis 是**降权之后**才解析配置文件的，
  所以文件必须对 uid 999 可读；600 + 该属主是「除 root 与容器内 redis 外无人可读」。
- **首次部署顺序**：bind 挂载的源不存在时 docker 会把它建成**目录** ⇒ redis 启动期报错。
  故 `install.sh` 在 `up -d nacos redis mysql` **之前**调用生成器；手工部署见 §6。
- 生成器拒绝含空白/引号/反斜杠的口令（`requirepass` 是单行指令，这类字符会让解析歧义），
  失败即退出，**不静默写出坏配置**。

## 3. 探活口径与折衷记账（MySQL / Redis）

按用户口径**优选「不带凭据的存活探活」**。第一手证据（在本机 `ypbin-mysql` / `ypbin-redis` 容器内实测）：

| 服务 | 探针 | 活着 | 死了（端口关闭） | 结论 |
|---|---|---|---|---|
| MySQL | `mysqladmin ping -h 127.0.0.1`（无 `-p`） | `Access denied … (using password: NO)` 且 **exit 0** | `Can't connect … (111)` 且 **exit 1** | 服务端应答即 0 ⇒ 可用作存活探活 |
| Redis | `timeout 3 nc -z 127.0.0.1 6379` | **exit 0** | **exit 1** | 纯 TCP 连通性 |

**为什么 Redis 不用 `redis-cli ping`**：不带口令时它对 `NOAUTH Authentication required.` 也 **exit 0**
（实测），即「服务器在，但认证没通过」与「认证通过」在退出码上不可区分 ⇒ 它并不比 TCP 更有信息量，
却容易让人误以为校验了口令。

**折衷（明确记账，必须一起看）**：

- 这两个探针**只证明「端口可连/服务端应答」，不校验口令**。口令错、库不存在、账号被禁等
  都不会让容器变 unhealthy。
- 凭据维度的判据**改由应用侧承担**：服务连库/连缓存失败会打 ERROR 日志并让 `/actuator/health` 降级。
  ⚠️ **如实登记**：IoTDB 那一处有专用应用侧指标（`iot.timeseries.db.rows` / `probe.failed`）兜底，
  **Redis 与 MySQL 没有同类专用指标** —— 这一处口径比 IoTDB **弱**。
- 想要「真的校验口令」且仍不进 argv 的备选（本轮**未采用**，留作后续选项）：
  给 redis 服务注入 `REDIS_PASSWORD` 环境变量 + 探针写
  `REDISCLI_AUTH="$$REDIS_PASSWORD" redis-cli ping | grep -q PONG`
  （compose 的 `$$` 转义使口令在解析期不进 Test 字符串，运行期由容器内 shell 从 env 取值）。
  未采用的理由：它把口令**再注入一份容器 env**（`docker inspect` 可读），换取的是一个
  更严但当前无指标配套的探针；按本轮「优选无凭据」的口径，收益不成比例。

## 4. 裁定：本轮不轮换 MySQL / Redis 口令（含风险登记）

**裁定（用户）**：本轮不轮换，只消除 argv / events 暴露。

**理由**：容器 **env 本身对任何有 docker 权限的人可读**（`docker inspect` 即可，`MYSQL_ROOT_PASSWORD`
一直在 mysql 容器 `Config.Env` 里），所以「argv/events 暴露」的**边际**风险主要就是「同权限读取者」；
而轮换 MySQL/Redis 口令会牵动**全部连库/连缓存的服务**（blast radius 大、需要停写窗口），
收益与代价不成比例。

**风险登记（如实）**：

| 项 | 现状 | 后续可选动作 |
|---|---|---|
| MySQL root 口令长期存在于容器 `Config.Env`（`docker inspect` 可读） | 未变 | 若将来收紧 docker 权限边界（如引入 rootless / 只给特定人 docker 组）或对外暴露 3306，**应轮换**；轮换步骤同 IoTDB（`.env` + Nacos `ypbin-common.yaml` + 相关容器重建） |
| Redis 口令长期存在于容器 `Config.Env` 与受管 600 文件 | argv 已消除；env/文件仍在 | 同上；另外注意 `redis-requirepass.conf` 属主是 uid 999，`chown` 该 uid 的宿主用户可读 |
| 该口令**曾经**在 `docker events` 的 exec 属性与容器 argv 中出现过 | 已不再出现 | events 缓冲/审计日志若被留档，其中的旧记录仍含明文；如需彻底消除，只能轮换 |
| `MYSQL_ROOT_PASSWORD` 在 `pybin-iot` 组合里同时是 xxl-job 的 DB 口令 | 未变 | 见 §5 的 xxl-job 条目 |

## 5. argv / 凭据暴露面清点（`docker inspect` 全部容器 + `docker events` 采样 + 仓库值级 grep）

**本轮已修（healthcheck / 脚本 argv）**：

| 位置 | 旧形态 | 现状 |
|---|---|---|
| `ypbin-mysql` healthcheck | `mysqladmin ping -h localhost -p<口令>` | 无凭据 |
| `ypbin-redis` healthcheck | `redis-cli -a <口令> ping` | 无凭据（TCP） |
| `ypbin-redis` Cmd | `redis-server … --requirepass <口令>` | 配置文件（600） |
| `ypbin-iotdb` healthcheck | `start-cli.sh … -pw <口令> -e "SHOW DATABASES"` | 上一轮已改 TCP（本文不重复） |
| `deploy/install.sh` | `mysql -uroot -p"$PW"` ×3、`sh -c "mysql … -p\"$PW\""` ×2、curl `password=$NACOS_PASSWORD` ×3 | `MYSQL_PWD` + `docker exec -e MYSQL_PWD`（只传变量名）、curl `password@-`（stdin） |
| `tools/*.py` | curl `--data-urlencode "password=<值>"` ×3 | `password@-`（stdin） |

**只登记、未修（属其它范畴，或需另开一轮）**：

| 位置 | 现状 | 归属 / 建议 |
|---|---|---|
| `xxl-job-admin` 的 `PARAMS` 环境变量 | `--spring.datasource.password=${MYSQL_ROOT_PASSWORD}`：`PARAMS` 由镜像 entrypoint 拼进 **java 命令行** ⇒ xxl-job 启动后其 argv 会含明文 root 口令。**该容器当前未部署/未运行**，且镜像 entrypoint 未在本轮一手核实 | 属「服务启动 argv」（非 healthcheck/脚本）⇒ 只登记。修法建议：改用 Spring 的 `SPRING_DATASOURCE_PASSWORD` 环境变量（relaxed binding），先核实镜像 entrypoint 再改 |
| 容器 `Config.Env` 里的各口令（mysql/redis/iotdb/nacos） | `docker inspect` 可读 | 用户裁定接受（§4）；收紧 docker 权限或对外暴露时轮换 |
| `install.sh` 把 `AI_MODEL_SECRET_KEY` 打印到 stdout | 安装时提示运维抄写，属**有意**披露 | 只登记（改变它会破坏既有运维流程）；`已生成 .env（MySQL 密码：…）` 那句本轮已改为不打印值 |
| `/opt/ypbin/rollback-20260925-013857.sh`（服务器上，非仓库） | 内含 1 处写死的明文 Nacos 口令 | 遗留脚本，只登记（见 `docs/ACCESS-ENABLE.md` §11）；本轮新写的回滚脚本一律从 `deploy/.env` 取 |
| `deploy/install.sh` 整体 | fork 自 admin 仓，路径大量指向 `$ROOT/ypbin-admin/deploy`（见 `docs/IOT-ROADMAP.md` 已知项） | 只登记：本仓实际部署走 compose（见 `docs/DEPLOY-BACKEND.md`） |
| curl 的 `-H "accessToken: <JWT>"` | 短时（默认 18000s）会话 token 进 argv | 只登记：本轮范围是**长期口令/密钥**；如需消除，可改用 `curl -K`（header 配置文件） |
| 仓库 `deploy/**` 值级 grep | 见 §7 判据：口令轮换后用「赋值上下文」判据复扫 | — |

> ⚠️ **值级 grep 的陷阱（上一轮实测）**：`grep -F "<口令>"` 在口令是常见子串（如默认值 `nacos`）时
> 会虚高命中。正确判据是**赋值上下文**：`grep -c "password=<值>"` 应为 0。

## 6. 线上验收判据（被测 artifact 必须一并声明）

被测 artifact 三元组（本轮）：

1. `deploy/docker-compose.yml` 的 `sha256[:16]`（服务器与 `main` 逐字节一致，用哈希比对，不靠肉眼 diff）；
2. `ypbin-mysql` / `ypbin-redis` 容器的**重建时间与镜像 ID**（`docker inspect` 的 `Created` + `Image`）；
3. `deploy/redis-requirepass.conf` 的 `mode/owner/pw_len`（**不打印口令值**）。

判据：

```bash
# ① 健康检查配置里没有凭据
docker inspect -f '{{json .Config.Healthcheck.Test}}' ypbin-mysql ypbin-redis
# ② argv（容器内 + 宿主）不含明文口令 —— 用「赋值上下文/短横线开关」判据，且不打印口令
docker top ypbin-mysql -eo pid,args | grep -c -E '\-p[^ ]'      # 期望 0（注释见下）
docker top ypbin-redis -eo pid,args | grep -c -E 'requirepass [^/]'  # 期望 0
ps -eo args | grep -c -F "$MYSQL_ROOT_PASSWORD"                  # 期望 0
# ③ 仍 healthy
docker inspect -f '{{.State.Health.Status}}' ypbin-mysql ypbin-redis   # 期望 healthy
# ④ 凭据本身仍可用（业务面）：服务 /actuator/health UP + 登录链路 200
```

> ⚠️ 判据②刻意写成「按开关形态计数」而不是「grep 口令值」：口令值判据在**本机 .env 之外**没有意义，
> 且会把口令写进命令行（反过来制造一次 argv 泄露）。第 3 条 `ps … grep -F "$MYSQL_ROOT_PASSWORD"`
> 只在**确认该变量不会进 argv** 的场合使用；本轮用它做负向验证时，通过 `grep -c` 只输出计数，不打印匹配行。

## 7. 回滚

```bash
# Redis 回到旧形态（会重新把明文口令放回 argv —— 非必要不回滚）
# 1) 编辑 deploy/docker-compose.yml：redis.command 恢复 --requirepass、删掉 volumes 里的配置文件挂载、
#    healthcheck 恢复 -a <口令>
# 2) docker compose -f deploy/docker-compose.yml up -d --no-deps redis
# 3) 立即可判：docker inspect -f '{{json .Config.Healthcheck.Test}}' ypbin-redis 又出现 -a <口令>
# MySQL 健康检查回滚同理（恢复 -p${MYSQL_ROOT_PASSWORD}）。
```

回滚只影响这两个容器，**数据卷不动**。
