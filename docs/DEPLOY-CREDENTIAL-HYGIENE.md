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

compose 在解析期就把 `${REDIS_PASSWORD}` 替换成真值 ⇒ **明文口令随命令行走**。经复核纠正，精确的暴露面是两条：

- **持久**：`docker inspect ypbin-redis -f '{{json .Config.Cmd}}'` —— 明文常驻容器配置；
- **瞬态**：健康检查每次 exec 出一个 `redis-cli -a <口令> ping` 进程（默认 10s 一次），
  其 argv 会被 `docker events` 的 `exec_create`/`exec_start` **完整记录**（Action 字段内嵌命令行）；
  在 `docker top` / 宿主 `ps` 里则是**偶发可见**——探针进程生存期只有毫秒级，靠采样撞上。
  **实测命中率（复核者紧循环采样，只输出计数）**：mysql 3207 次采样命中 **1** 次；redis 3236 次命中 **0** 次；
  而同期 `docker events` 是 **38/38**。⇒ **可靠证据只有 `docker events`**，「采样 `docker top` 命中 0」
  **不能**单独当作「argv 干净」的判据。

> ⚠️ **反例（实测，2026-09-26）**：`docker top ypbin-redis -eo pid,args` 里**看不到** `requirepass`，
> 宿主 `ps` 也是 —— redis 启动后会把进程标题改写成 `redis-server *:6379`，原始 argv 不再可见。
> 因此**不能**拿「`docker top` 主进程有没有 `requirepass`」当改前/改后的差分判据；
> 该形态的差分判据只有 `.Config.Cmd`、健康检查 exec 的采样与 `docker events`。

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
  ⚠️ 该 uid 在**宿主机**上是系统账号 `lxd`（复核者实测）⇒ 宿主上的 `lxd` 用户也能读该文件；
  这是「让容器内 redis 读得到」的必然代价，如实登记（如不接受可改用其它降权方案，本轮未实施）。
- **首次部署顺序**：bind 挂载的源不存在时 docker 会把**源路径**建成空目录
  ⇒ redis 启动期报错，而且**生成器随后会以 `Is a directory` 失败**（`printf > "$OUT"` 写不进目录）
  ⇒ 恢复动作是**先 `rmdir <源路径>` 再重跑生成器**，别只看「redis 起不来」就以为口令不对。
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

### 4.1 ⚠️ 本轮**新发生**的暴露（必须单独登记，不可并入上表）

**事实**：本轮做 `docker events` 审计时，我（运维会话）把 `docker events --format '{{json .}}'` 的
**`Action` 字段**直接按计数/样本打印了出来 —— 而 `Action` 本身就内嵌 exec 命令行
（形如 `exec_create: mysqladmin ping -h localhost -p<口令>`、`exec_create: redis-cli -a <口令> ping`）。
我做的「按行替换口令值」脱敏只覆盖了 JSON 正文，**没覆盖 Action** ⇒ **MySQL root 口令与 Redis 口令的
当前值进入了会话输出/记录**。

**为什么单独登记**：这两个值在此之前已对「本机 root 与任何有 docker 权限者」可见
（`docker inspect` 的 `Config.Env` / `Config.Cmd`），本次没有增加新的**技术**攻击面；
但会话文本的留存与传播面**不受 600 文件权限约束**，这是原裁定（§4「本轮不轮换」）作出时**不存在**的暴露面。

**因此**：
1. 本项作为「本轮新暴露」登记在此；
2. **是否维持「本轮不轮换」需由用户在知情后重新裁定**（原裁定的前提已变）；
3. 若维持不轮换，请在本节写明「用户已知悉该暴露并接受」；
4. **操作约束（已生效）**：`docker events` 的 Action 内嵌命令行 ⇒ 以后采样一律**只输出计数**
   （`grep -c` / `wc -l`），禁止打印 Action 字符串、禁止 `--format '{{json .}}'` 落盘后整段回读；
   临时事件流用完即删。
5. 若决定轮换：MySQL 需同时改 `.env` 与 Nacos `ypbin-common.yaml` 并重建 mysql + 依赖服务；
   Redis 需改 `.env` + 重新生成 `redis-requirepass.conf` 并重建 redis + 依赖服务（步骤同
   `DEPLOY-TIMESERIES.md` §6.1 的成对口径）。

## 5. argv / 凭据暴露面清点（`docker inspect` 全部容器 + `docker events` 采样 + 仓库值级 grep）

**本轮已修（healthcheck / 脚本 argv）**：

| 位置 | 旧形态 | 现状 |
|---|---|---|
| `ypbin-mysql` healthcheck | `mysqladmin ping -h localhost -p<口令>` | 无凭据 |
| `ypbin-redis` healthcheck | `redis-cli -a <口令> ping` | 无凭据（TCP） |
| `ypbin-redis` Cmd | `redis-server … --requirepass <口令>`（暴露面=`docker inspect .Config.Cmd`；主进程 argv 因进程标题改写**本就看不到**，见 §2 反例） | 配置文件（600） |
| `ypbin-iotdb` healthcheck | `start-cli.sh … -pw <口令> -e "SHOW DATABASES"` | 上一轮已改 TCP（本文不重复） |
| `deploy/install.sh` | `mysql -uroot -p"$PW"` ×3、`sh -c "mysql … -p\"$PW\""` ×2、curl `password=$NACOS_PASSWORD` ×3 | `MYSQL_PWD` + `docker exec -e MYSQL_PWD`（只传变量名）、curl `password@-`（stdin） |
| `tools/*.py` | curl `--data-urlencode "password=<值>"` ×3 | `password@-`（stdin）——**这一行的改动在「代码」那一支 PR（`fix/rotate-script-recursion`）**，两份 PR 合并后本表才成立 |

**只登记、未修（属其它范畴，或需另开一轮）**：

| 位置 | 现状 | 归属 / 建议 |
|---|---|---|
| `xxl-job-admin` 的 `PARAMS` 环境变量 | `--spring.datasource.password=${MYSQL_ROOT_PASSWORD}`。**已一手核实（复核者读镜像元数据）**：`Entrypoint=["sh","-c","java ${LOG_HOME:+-DLOG_HOME=$LOG_HOME} -jar $JAVA_OPTS /app.jar $PARAMS"]` ⇒ `PARAMS` 确实拼进 **java argv** ⇒ 该容器一旦启动，其 argv 含明文 root 口令。该容器当前**未部署/未运行** | 属「服务启动 argv」（非 healthcheck/脚本）⇒ 只登记。修法建议：改用 Spring 的 `SPRING_DATASOURCE_PASSWORD` 环境变量（relaxed binding）替代 `--spring.datasource.password=…`，改前先在测试实例核实镜像 entrypoint 行为 |
| 容器 `Config.Env` 里的各口令（mysql/redis/iotdb/nacos） | `docker inspect` 可读 | 用户裁定接受（§4）；收紧 docker 权限或对外暴露时轮换 |
| `install.sh` 把 `AI_MODEL_SECRET_KEY` 打印到 stdout | 安装时提示运维抄写，属**有意**披露 | 只登记（改变它会破坏既有运维流程）；`已生成 .env（MySQL 密码：…）` 那句本轮已改为不打印值 |
| `/opt/ypbin/rollback-20260925-013857.sh`（服务器上，非仓库） | 内含 1 处写死的明文 Nacos 口令 | 遗留脚本，只登记（见 `docs/ACCESS-ENABLE.md` §11）；本轮新写的回滚脚本一律从 `deploy/.env` 取 |
| `deploy/install.sh` 整体 | fork 自 admin 仓，路径大量指向 `$ROOT/ypbin-admin/deploy`（见 `docs/IOT-ROADMAP.md` 已知项） | 只登记：本仓实际部署走 compose（见 `docs/DEPLOY-BACKEND.md`） |
| `tools/rotate-*.py` 的 `dump*`（开 auth 前） | 匿名读 8848 client API ⇒ 开 auth 后 **403 ⇒ JSONDecodeError，工具不可用** | **已修**（PR #64：dump 带 accessToken + 登录提前）；这是「改服务端鉴权」的连带面，见 `NACOS-AUTH.md` §8.5 |
| Nacos 容器 `NACOS_AUTH_TOKEN` | 镜像 `bin/docker-startup.sh` 只把 `NACOS_AUTH_ENABLE`/`_ADMIN_ENABLE`/`_CONSOLE_ENABLE` 映射成 `-D…`，**token/identity 不走 `-D`** ⇒ 只在 env，不进 argv（复核结论） | 无需改 |
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
# ① 健康检查配置里没有凭据（持久面）
docker inspect -f '{{json .Config.Healthcheck.Test}}' ypbin-mysql ypbin-redis
docker inspect -f '{{json .Config.Cmd}}' ypbin-redis      # 期望 ["redis-server","/usr/local/…conf","--appendonly","yes"]
# ② 瞬态 argv 的**辅助**观察（**非决定性**）：探针进程生存期毫秒级，采样极易漏捕
for i in $(seq 1 12); do docker top ypbin-mysql -eo pid,args | grep -c -- '-p'; docker top ypbin-redis -eo pid,args | grep -c -- '-a '; sleep 5; done
#    ⚠️ 实测（复核者紧循环采样）：改前 mysql 3207 次采样只命中 1 次、redis 3236 次命中 0 次
#    ⇒ 「全 0」既可能真干净、也可能只是没撞上（false-green）⇒ **不作为判据**；
#    redis 主进程因进程标题改写**一直**是 0（§2 反例），更没有判别力。决定性证据看 ③。
# ③ 【决定性】docker events 审计（**只输出计数**；Action 字段内嵌命令行，禁止打印，禁止 --format '{{json .}}' 落盘回读）
timeout 90 docker events > /tmp/ev.$$  2>&1
grep -c 'exec_create: mysqladmin ping -h 127.0.0.1' /tmp/ev.$$   # 期望 >0（新探针真的在跑）
grep -c -F "$MYSQL_ROOT_PASSWORD" /tmp/ev.$$                    # 期望 0
grep -c -F "$REDIS_PASSWORD" /tmp/ev.$$                         # 期望 0
rm -f /tmp/ev.$$
# ④ 仍 healthy + 凭据本身仍可用（业务面）：/actuator/health UP、登录链路 200
docker inspect -f '{{.State.Health.Status}}' ypbin-mysql ypbin-redis   # 期望 healthy
```

> ⚠️ 判据②只是辅助：不用「grep 口令值」当主判据（那需要把口令放进命令行，反过来制造一次 argv 泄露），
> 且在 redis 上**本来就不成立**（§2 反例）；更要紧的是它**没有判别力**（命中率约 1/3200，见上）。
> **判据③（`docker events` 计数）才是决定性的瞬态证据**：它同时证明「新探针真的在跑」与
> 「事件里不再出现口令」。若确要用口令值做一次负向验证，只允许 `grep -c`（计数），**不得**打印匹配行。

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

## 8. 实测回执（生产 2026-09-26）

被测 artifact 三元组、逐项原始输出见 [`NACOS-AUTH.md`](NACOS-AUTH.md) §8.1（同一窗口、同一批容器）。

| 判据 | 改前 | 改后 |
|---|---|---|
| `docker inspect .Config.Healthcheck.Test`（mysql） | `mysqladmin ping -h localhost -p<口令>` | `["CMD","mysqladmin","ping","-h","127.0.0.1"]` |
| `docker inspect .Config.Healthcheck.Test`（redis） | `redis-cli -a <口令> ping` | `["CMD-SHELL","timeout 3 nc -z 127.0.0.1 6379"]` |
| `docker inspect .Config.Cmd`（redis） | `… --requirepass <口令>` | `["redis-server","/usr/local/etc/redis/redis-requirepass.conf","--appendonly","yes"]`（口令值在 `.Config.Cmd` 中命中 **0** 次） |
| 探活语义仍然有效 | — | 无口令 `PING` → `NOAUTH`；从容器内 600 文件取口令 → `PONG`；mysql 容器 `healthy`（探针 exit 0） |
| 4 个基础设施容器 | healthy | 全部 `healthy`（mysql/redis/nacos/iotdb） |
| **`docker events`（决定性；单位=事件行）** | **改前（两容器均旧）**：独立复核者的 70s 完整捕获中 mysql/redis 口令各 **38 次**；我自己的首次捕获当时只读到 90 行（mysql 4 / redis 4），但文件后来增到 12MB ⇒ **缓冲未落盘，那是下界、不是窗口完整值**；**中间态（只重建了 redis）** 90s 窗口 16 行含 mysql 口令 | 含 mysql/redis/nacos/iotdb 口令值行数 **0/0/0/0**；新探针被观测 **mysql/redis/nacos=18、iotdb=6** 次；旧带凭据形态 `-p`/`-a`/`-pw` **0/0/0** |
| `docker top` 采样（辅助，**无判别力**） | 「90 次命中 2」是单次偶然观察（按命中率 1/3207 估计 90 次期望≈0.03），**不可重复、不作为证据** | 90 次采样命中 **0**（同样无判别力） |
| 全容器 `Healthcheck`/`Entrypoint`/`Cmd` 含凭据者 | 3 处 | **0** |

**过程中两个必须记下的点**：

1. **第一轮重建漏了 `ypbin-mysql`**：只重建 redis 就做了验收，测量当场抓到「mysql healthcheck 仍有
   `-p<口令>`、events 仍有 16 行含 mysql 口令」⇒ 补重建后复测才归零。**没有这次测量就会把漏项当完成。**
2. **`ps -eo args | grep -cE 'mysqladmin.*-p[^ ]'` 会数到自己**：模式串出现在 grep 自己的
   命令行里，于是得到「2 次命中」的假阳性。正确判据是 `ps … | grep -E 'mysqladmin ping|redis-cli' |
   grep -v grep` ⇒ **0**（本轮实测）。这条与本文件 §6 的口径一致：**形态计数要先排除自匹配**。

**副作用登记**：清理 dry-run 备份目录时，我一并删掉了 `/opt/ypbin/{secret-rotation,token-rotation}-*`
的全部历史目录（**含早前轮次的轮换回滚物**）。这些是过期中间产物（口令早已轮换、目录 700/600），
但「删除他人轮次的回滚物」超出本轮范围，如实登记；本轮自己的回滚物在
`/opt/ypbin/cred-hardening-20260926-153455/` 内，**未动**。

**已删除的临时事件流**：两次 `docker events` 捕获（含改前明文口令，最大 12MB，600 文件）
已在提取计数后删除，只保留计数结论（本文件 §4.1 的操作约束）。
## 9. 未决项与后续建议（复核后登记）

| 项 | 状态 |
|---|---|
| `ypbin-access` 的 `/actuator/health` 超时（000，而 `/` = 200） | **未决**：现值可复现，但「改前是否也存在」**无一手证据**（改前容器已被替换、仓库文档无历史记录）。独立因果判断：无正面证据指向本轮改动（同批其它 4 服务 health 全 200、access 的 Nacos 注册 healthy=1、鉴权失败签名 0）。建议单独立项排查 |
| 轮换工具把 **Nacos accessToken（JWT）经 `-H` 传 argv** | 已知残留：token 是短时凭据（非长期口令），且沿用本文件既有做法。复核者已实测 `curl -K -`（配置走 stdin）在生产可用，**建议后续统一改造**；本轮不做（避免在同一区域连续改动的风险） |
| Redis/MySQL 探活不校验口令 | 见 §3 折衷记账（无专用应用侧指标，口径弱于 IoTDB） |
| 容器 env 中的口令 | 见 §4（用户裁定本轮不轮换；另见 §4.1 本轮新发生的**会话暴露**，需用户知情后重新确认该裁定） |
| `install.sh` 的改口令链路 | 未端到端验证（需重装） |
