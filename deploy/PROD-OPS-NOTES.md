# 生产机运维陷阱与约定（PROD-OPS-NOTES）

> 本文件收集**踩过一次就会再踩**的生产机陷阱。每条都注明"怎么发现的 / 会怎么咬人 / 怎么办"。
> 机器：`root@113.142.217.58`（8 vCPU / 7939MB，宿主 TZ=UTC）。
> 建立于 2026-09-26（内存调优第二轮收尾）。

---

## 陷阱 1 · `/tmp` 是**开机清空**语义 ⇒ 不要放任何需要存活的东西

**发现方式**：2026-09-26 的受控重启后，`/tmp` 从 72MB / 数十个文件变成只剩 68KB 的 systemd 私有目录。

**机制（一手）**：

```console
$ cat /usr/lib/tmpfiles.d/tmp.conf
D /tmp 1777 root root 30d
```

类型 **`D`** = `d` 的全部行为 **+** **`--remove`：开机时清空该目录的内容**（`systemd-tmpfiles-setup.service` 以 `--create --remove --boot` 运行）。

> ⚠️ **[2026-09-26 复核更正]** `30d` 这个年龄字段对 `D` **同样生效**（`--clean` 会清理所有配置了 age 的条目）
> —— 前一版写「`30d` 只对 `d`/`q` 这类类型是年龄」是**错的**。
> 但**「开机清空」与年龄无关**：`D` 无论配多大年龄，都会在开机时清空。
> 所以**光把 `30d` 改小并不能保住 `/tmp` 里的东西**，必须换类型（见下面「怎么办」）。

**会怎么咬人**：任何放在 `/tmp` 的"跨轮次产物"——SQL 快照、回滚脚本、构建产物、日志——都会在**下一次重启**时
**无声消失**，而且**没有任何告警**。

**本轮的实际损失（诚实登记）**：

| 文件 | 大小 | 现状 |
|---|---|---|
| `2026-09-19-iot-m2-event-log-schema.sql` | 3033B | ✅ **仓库有留档**：`ypbin-iot/deploy/sql/migration/`（git 跟踪，字节数一致） |
| `2026-09-19-iot-m2-event-log-schema-rollback.sql` | 660B | ✅ 仓库留档：`…/sql/rollback/` |
| `2026-09-29-iot-menu-onboarding-ledger.sql` | 2393B | ✅ 仓库留档：`…/sql/migration/` |
| `2026-09-29-iot-menu-onboarding-ledger-rollback.sql` | 981B | ✅ 仓库留档：`…/sql/rollback/` |
| `coord-DEPLOY-BACKEND.md` | 12,782B | ❌ **未找回**（本地 7 仓工作树 + 7 仓**全历史** + 目标机 `/root`/`/opt` + `find / -xdev` 均无） |
| `e2e-seed.sql` | 1,650B | ❌ **未找回**，且**不可再生**（7 仓 git 全历史从未出现过该文件；只在 `/tmp` 存在过） |
| 其余（build log / dist tgz / bundle / 探针产物） | ~70MB | ♻️ **可再生**，无影响 |

> ⚠️ 一个侥幸：**报告初版把这个损失判得过重**（写"4 个 SQL 已丢失"），是独立复核者抓出来的。
> 出错原因也已登记：我用 `find . -maxdepth 4` 找，而文件在第 5 层（`ypbin-iot/deploy/sql/migration/…`）。

**怎么办（约定）**：

1. **不要把需要存活的东西放 `/tmp`**。跨轮次产物放 `/root/<round>/`、`/opt/ypbin/`，或直接进仓库。
2. 需要长期保留的 SQL/配置快照，**一律进仓库**（`deploy/sql/{migration,rollback}/`）或 `/root`。
3. **清理策略的修改**：把 `/tmp` 从"开机清空"改成"按时间过期"**可以**做，但必须用
   **`/etc/tmpfiles.d/` 的 override**（例如 `/etc/tmpfiles.d/tmp.conf` 里写 `q /tmp 1777 root root 30d`
   覆盖发行版默认），**绝不要直接改 `/usr/lib/tmpfiles.d/tmp.conf`**（那是包管理的文件，升级会被覆盖/冲突）。
   > ⚠️ **本项本轮只登记、未实施**——等维护窗口决定。

---

## 陷阱 2 · IoTDB entrypoint 的 `env → conf` 注入**不幂等**（改 env 后 `restart` **不生效**）

**发现方式**：2026-09-26 启用 IoTDB 指标后，同一份 `iotdb-system.properties` 里的 metric 键**每键出现两次**
（`69/70` 都是 `cn_metric_reporter_list=PROMETHEUS`，`74/75` 都是 `dn_metric_reporter_list=PROMETHEUS`），
文件 `mtime` 每次开机都变。由两轮独立复核者分别复现。

**机制（一手：镜像内 `/iotdb/sbin/replace-conf-from-env.sh` 源码）**：

```bash
line=$(grep -ni "${key}=" ${filename})      # 模板里这两个键是**注释**形式：`# cn_metric_reporter_list=`
content=$(echo $line|cut -d : -f2)
if [[ "${content:0:1}" != "#" ]]; then sed -i "${line_no}d" ${filename}; fi
sed -i "${line_no}a${key_value}" ${filename}   # ← 注释行**不删**，只在其**后面插入**
```

- 镜像 `entrypoint.sh` 在**每次容器启动**时都会跑这段 ⇒ **每启动一次就多一份重复键**；
- 新注入的值插在**模板注释行之后、即旧重复键之上**，而 Java `Properties` 取**最后**一条
  ⇒ **旧值仍然生效**。

**会怎么咬人**：

1. **改了 compose 里的 env，`docker restart ypbin-iotdb` 不会生效**（重启只是再追加一份新值，旧值还在最后）。
   ⇒ 必须 **`docker compose up -d --no-deps --force-recreate iotdb`**（重建容器 = 重置该文件）。
2. `iotdb-system.properties` 随启动次数**单调变长**（conf 不在卷上，只有 `/iotdb/data` 与 `/iotdb/logs` 是卷）。
3. 目前两份值相同 ⇒ **无功能影响**；但不要"清理重复行"了事 —— 下次启动还会再加。

**怎么办（约定）**：

- 改 IoTDB 配置一律用 **`--force-recreate`**，不要用 `restart`；
- 巡检时 `docker exec ypbin-iotdb grep -nE '^(cn|dn)_' /iotdb/conf/iotdb-system.properties` 看有没有意外的重复键；
- 根治方案（待批准）：启动前用幂等改写替换追加逻辑，或改用只挂一个只读 conf 文件的方式。

---

## 陷阱 3 · 采集链路的喂数源是**容器外**进程 ⇒ 重启后会"静默零写入"

- 喂数源 = `access-tcp-simulator.service`（宿主机 systemd 单元，绑 `172.20.0.1:19002`）。
  详见 **`deploy/access-tcp-simulator.md`**。
- **判据与告警**：`deploy/feeder-watch.sh`（L1 `write.rows` 120s / L2 `db.rows` 900s / L3 单元+监听+失败计数）。
- **为什么必须专门告警**：这种故障下**所有常规健康检查都是绿的**，但数据一行都不写。

---

## 陷阱 4 · 重启**默认会启用新内核**（本机 2026-09-18 起一直待启用）

- 现状（2026-09-26 实测）：运行 `6.8.0-48-generic`，已安装 `6.8.0-139-generic`，
  `GRUB_DEFAULT=0` 的顶层 `menuentry 'Ubuntu'` 加载 `/boot/vmlinuz-6.8.0-139-generic`
  ⇒ **下一次普通 `reboot` 就会启用 139**。
- `libc6 2.39-0ubuntu8.9` **已随 2026-09-26 的重启生效**（`ldd --version` 确认），**只有内核仍待启用**。
- ⚠️ **信号已经消失**：`/var/run/reboot-required{,.pkgs}`（→ `/run`）在本次重启时被**清空且不会自动重建**
  （tmpfs + 重启后无 dpkg 活动）⇒ "有升级待重启"这件事**在机器上已经看不到了**，极易被遗忘。

**怎么办（短说明 · 交给有控制台的人）**：

```bash
# 1) 先确认当前是一次性钉回项（若此处为空，说明没有残留的 next_entry）
grub-editenv /boot/grub/grubenv list

# 2) 若你想"就启新内核"：什么都不用做，直接 reboot
systemctl reboot
#    （顶层默认项已指向 6.8.0-139）

# 3) 若你想"临时启回旧内核 6.8.0-48"（一次性）：
grub-reboot "Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-48-generic"
systemctl reboot
#    GRUB 消费后会自动清空 next_entry（重启后 grub-editenv list 应为空）

# 4) 若你想"永久钉住旧内核"（不推荐，只是保底手段）：
#    编辑 /etc/default/grub 的 GRUB_DEFAULT 指向子菜单项，然后 update-grub
```

**启用新内核时的注意事项**：

1. **必须有云控制台访问**再重启——若新内核起不来网络，远程 SSH 救不回来。
2. 重启后**预期现象**（2026-09-26 那次已实测）：5 个应用会因"所有容器同时启动、应用比 Nacos 先就绪"
   被 restart 策略**反复拉起 1–3 次，约 4 分钟后稳定**（`RestartCount`：access 3 / gateway 2 / auth 2 / iot 1 / system 1）。
   这是固有行为，不是故障。
3. 重启**会清空 `/tmp`**（见陷阱 1）。
4. 重启后逐项核对：`uname -r`、容器全 Up、`Started XxxApplication`、Nacos `totalCount=5` 且各 `healthyInstanceCount=1`、
   `19000=200`、`18084/actuator/health` = UP、`zramctl`/`swapon -s`/`swappiness=150`、
   `access-tcp-simulator.service` = active、`feeder-watch.sh` 判定 OK。
   一键：`/root/mem-round2-20260926/accept.sh`（或按本目录 `access-tcp-simulator.md` 的手工清单）。
5. **内核升级后请复验 zram**：zram 模块在 6.8.0-139 的模块树里**确认存在**
   （`/lib/modules/6.8.0-139-generic/kernel/drivers/block/zram/zram.ko.zst`，`modules.dep` 已生成），
   但没人在 139 上跑过 `zram-swap.service`；若单元失败，`journalctl -u zram-swap.service` 会直接说明。

---

## 陷阱 5 · access 的**聚合** `/actuator/health` 会被 Redis 健康项挂死（判活请用 liveness/readiness）

**发现方式**：2026-09-27 按批准的只读定位窗口实测（把 `management.endpoint.health.show-details`/`show-components`
**临时**设为 `always` → 重启 access → 逐个 `curl /actuator/health/<id>` → 立即改回 `never` 并重启）。

**现象**：`curl -m 120 http://127.0.0.1:18086/actuator/health` ⇒ `HTTP=000 time=120.0`（**≥120s 不返回**，
不是 30–60s 超时）；而 `/actuator/health/liveness`、`/actuator/health/readiness` 毫秒级 `{"status":"UP"}`。

**根因（本轮定位，逐项取证）**：**`redis` 健康项**。

| 组件 | 实测 |
|---|---|
| `diskSpace` / `ping` / `ssl` / `refreshScope` / `livenessState` / `readinessState` | 200，毫秒级 |
| `discoveryComposite` | 200，t=0.037s（**含 Nacos 服务清单**，内容正常） |
| `nacosDiscovery` / `nacosConfig` / `db` | 404（无此组件） |
| **`redis`** | **`HTTP=000 t=6.0s`（8s 超时截断）；长超时 `-m 30` 仍 `HTTP=000 t=30.0`** ⇒ 无限阻塞 |

⇒ 两个先前假设都被实测**证伪**：① **不是 Nacos**（`discoveryComposite` 毫秒级返回，且挂起期间 8s 内没有任何
新的 outbound 连接——连采 3 次 `/proc/net/tcp`+`tcp6`，对端集合与基线完全相同）；② **不是全局线程/事件循环
耗尽**（其余组件与 `/actuator/metrics` 全程正常）。**是 redis 这一项独有的**。

**它的性质（重要）**：`redis` 项**完全没有发起 TCP 连接**（挂起期间到 `6379` 的连接数为 0，容器内 `ypbin-redis`/`redis`
都能解析到 `172.20.0.11`）⇒ 阻塞发生在**获取/创建连接对象阶段**，不是「连不上某个地址」的网络超时。

**而 access 根本不用 Redis**：没有 `spring.data.redis.*` 配置、没有任何到 6379 的连接、代码里也没有 Redis 用法
⇒ 这是**依赖组合/自动装配给一个「不用 Redis 的服务」装配出了 Redis 连接工厂 + 健康项**，属装配面问题。

**怎么办**
1. **判活不要用聚合 health**：用 `/actuator/health/liveness` 与 `/actuator/health/readiness`（毫秒级、真 UP）。
2. **修症状（一行，待批准后做）**：`deploy/nacos/ypbin-access.yaml` 的 `management` 段加
   `health.redis.enabled: false` ⇒ 聚合 health 立即恢复可用。这是**关掉一个本服务不需要的健康项**，
   不是放宽安全策略。
3. **更彻底（需与 starter 侧一起定）**：确认 access 确实不需要 Redis 后，把 Redis 自动装配从 access 排除
   （`spring.autoconfigure.exclude`）或从依赖里去掉 `spring-data-redis`；**不要**在没确认前删依赖。
4. **闭环验证（2026-09-27 已完成，见下）**：加 `health.redis.enabled=false` 后 `/actuator/health`
   **确实**转 200 ⇒ 因果闭环成立。

**✅ 闭环证据（2026-09-27 06:01–06:04 窗口，两次配置 + 两次重启）**

```
# ① 临时打开组件访问 + 新增 health.redis.enabled=false → 重启
聚合 /actuator/health : {"components":{"discoveryComposite":{...},"diskSpace":{...},
   "livenessState":{"status":"UP"},"ping":{"status":"UP"},"readinessState":{"status":"UP"},
   "refreshScope":{"status":"UP"},"ssl":{...}},"groups":["liveness","readiness"],"status":"UP"}
   HTTP=200 t=0.400s          ← 修复前是 HTTP=000（≥120s 不返回）
组件逐个：ping 0.011s / diskSpace 0.008s / discoveryComposite 0.020s / refreshScope 0.007s / ssl 0.007s 全部 200
redis 组件路径：HTTP=404      ← 健康项已注销（不再出现在 components 里）
# ② 把 show-details/show-components 改回 never（保留 redis 修复）→ 重启
最终配置：/actuator/health → {"groups":["liveness","readiness"],"status":"UP"} HTTP=200 t=0.347s
          组件路径 → 404（细节仍隐藏）  liveness/readiness → UP   metrics → 200  decode.failure → 可读
```

**已实施的修复（live 配置）**：`deploy/nacos/ypbin-access.yaml` 等价内容 + `management.health.redis.enabled: false`
（live sha256 `e2f732251db7c4a7`）。**仓库模板里也补了同一行**（见 §5.2 之后）。
> 注意：这是**关掉一个本服务不需要的健康项**，不是放宽安全策略；`show-details/show-components` 仍是 `never`。

**更深一条（已登记，未做）**：既然 access **不使用 Redis**，比「关健康项」更干净的做法是**排除 Redis 自动配置**
（`spring.autoconfigure.exclude` 掉 `RedisAutoConfiguration` 与 `DataRedisHealthContributorAutoConfiguration`，
或从依赖里去掉 `spring-data-redis`）。**排除后是否有副作用的判据**（全部要过）：
① 服务正常启动（若 starter 某组件依赖 `RedisTemplate`/`RedisConnectionFactory` bean，会在**启动期**以
`NoSuchBeanDefinitionException` 暴露 —— fail-fast，不会静默）；② `/actuator/health` 200 且 `components` 里没有 `redis`；
③ 既有内部端点继续可用（`/internal/lease/**`、access→iot 的 `/internal/readings`）；④ 日志无 `Lettuce`/`Redis` 相关 ERROR；
⑤ `iot.access.*` 指标与喂数（`write.rows`）持续增长；⑥ **前提写进文档**：将来若 access 真要用 Redis（例如缓存租约），
必须撤销该排除 —— 否则会以「用了 Redis 却没有 bean」的形式失败（fail-fast，可发现）。
**Lettuce 连接对象创建为何阻塞：仍未查明**（需线程栈；本机 JRE 无 jcmd，按约定不换 JDK 镜像）。

**窗口纪律（本轮实际执行）**：改前声明 artifact 三元组（image `4bc3178ff0aa…` / jar md5
`74fb9ac1e8929658a572aaf18e115a26` / StartedAt `2026-09-27T03:48:48Z`）；`docker events` 审计（只计数）
显示窗口内**只有 access 的 kill/stop/die/start/restart**，其它容器零动作；测完把
`show-details`/`show-components` 改回 `never`——**live 配置 sha256 回到窗口前的 `4cfea417ea4efc2e`（逐字节还原）**。
工具：`tools/patch-nacos-yaml-value.py`（只改 live 里一个标量键、保留注释、带 600 备份与回读轮询）。

> 附：**Boot 4.1.1 里组件路径的开关是 `show-components`**，不是 `show-details`——本轮第一次只把
> `show-details` 设为 `always` 时 `/actuator/health/<id>` 仍是 404，把 `show-components` 也设为 `always` 后
> 才可逐组件访问。下次做同类定位别再只改一个。

---

## 陷阱 6 · 两条常用命令会把 `.env` 里的**口令明文回显**（凭据卫生，通用）

排障时很容易顺手敲这两条，**它们都会把真实口令打到终端 / 日志 / 会话记录里**：

| 命令 | 回显什么 |
|---|---|
| `docker compose config`（等价 `--format json`） | **整个 `.env` 展开后的全部值**，含 `EMQX_DASHBOARD_PASSWORD` 等 |
| `emqx ctl conf show dashboard` | `dashboard.default_password = "<明文口令>"` |

**纪律**：

```bash
# 只想看结构 / 服务名（安全）
docker compose config --services
# 只看端口映射（安全；不经 config 展开 .env）
docker compose ps --format '{{.Ports}}'
# 只想确认容器内真实生效的 jar/镜像（安全）
docker image inspect -f '{{.Id}}' <image>; docker inspect -f '{{.Image}}' <container>
```

- 需要看 `.env` 的**键名与长度**时：`awk -F= '{printf "%s len=%d\n", $1, length($2)}' .env`
  （⚠️ 用 `. .env` 再 `echo` 会把值带进环境与输出，别这么干）。
- **一旦回显过 ⇒ 视为已暴露 ⇒ 轮换**（本轮实测踩过：原作者用 `emqx ctl conf show dashboard`、
  独立复核者用 `docker compose config`，各一次）⇒ 已按此纪律轮换 EMQX Dashboard 口令。
- 同类坑：`git credential fill`、`mysql -p<口令>`、`curl -u user:pw`、`java -Dxxx=口令` 都会进
  **argv**（宿主 `/proc` 无 hidepid ⇒ 同机其它用户可见）。一律改用 `-K <600 文件>` /
  `--data-binary @<600 文件>` / `-p"$(cat <600文件>)"` 之类不落 argv 的形态。

## 陷阱 7 · 文档里的**日期可能超前于真实日期** ⇒ 判断时间线别只看文档日期

本仓为 IoT 项目留下的一部分产物（例如 `deploy/sql/migration/2026-10-0{1,2}-*.sql`、部分测试的
`@since 2026-10-01`）用的是**比真实时间超前 1–6 天**的日期。2026-09-27 由独立复核用**两个外部权威源**
（`api.github.com` 与 Google 的 `Date` 响应头，均为 `Sun, 27 Sep 2026`）核对确认：**真实日期是 09-27**。

**约定**：

- 判断"某事发生在什么时候"，以 ① **外部权威源**（HTTP `Date` 头 / NTP 校时后的 `date -u`）或
  ② **文件 mtime / 容器 `StartedAt` / journald 时间戳**为准；**不要**用文档标题或文件名里的日期。
- 新写的文档/证据**一律用真实日期**；发现旧产物日期超前时**只登记、不擅自改别人的文件**
  （全仓纠正不在本轮范围）。

---

## EMQX REST Key/Secret 的**双份持有**（刻意保留，轮换必须两侧同改）

平台（生产机 `/opt/ypbin/ypbin-iot/deploy/.env`）与中间件机（`/opt/emqx/.env`）
**各持有一份、且是同一对** EMQX REST API Key/Secret（本轮核对指纹一致）。这是**刻意的**：
平台要调 EMQX 管理面（发下行、同步设备账号）就必须有；中间件机的部署脚本/自检也需要。

- ⚠️ **轮换时必须两侧同改**，否则平台或中间件机的脚本会立刻 401（表现为"平台不可达"或自检失败）。
  改完用两侧各自的 `GET /api/v5/status`（或 `emqx-init.sh` 的 A00）确认。
- ⚠️ 不要把它当成"只在一处"的凭据来评估影响面；也不要因为"生产机不该有 EMQX 凭据"而误删它
  （`emqx-tunnel-watch.sh` 头部那句"不落任何凭据"**只描述那个脚本自身不用凭据**，不是"生产机没有"）。

---

## 5. 「喂数停摆」定时告警：怎么开、怎么看

**两件套**（仓库 `deploy/`，已装 `/etc/systemd/system/`）：

| 文件 | 作用 |
|---|---|
| `feeder-watch.service` | `Type=oneshot`，跑 `/usr/local/sbin/feeder-watch.sh`；stdout/stderr 进 journald（`SyslogIdentifier=feeder-watch`） |
| `feeder-watch.timer` | `OnCalendar=*:0/5` + `Persistent=true`（错过的触发开机补跑）+ `RandomizedDelaySec=15` |

```bash
install -m 0644 deploy/feeder-watch.service /etc/systemd/system/
install -m 0644 deploy/feeder-watch.timer   /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now feeder-watch.timer
systemctl list-timers feeder-watch.timer          # 应能看到 NEXT/LAST
```

### 5.1 怎么看"停摆告警"（三条途径，任选）

1. **最快**：`systemctl --failed` —— 一旦判据报警，`feeder-watch.service` 会进入 **failed**。
   这是**刻意设计**：脚本退出码 `0=OK / 2=ALERT / 3=无 ALERT 但判据无法求值`，**2 与 3 都不算成功**，
   所以"停摆"在 systemd 里是**红的**，而不是埋在日志里。下一次跑出 OK 时会自动回到 inactive（告警自愈）。
2. **看细节**：`journalctl -u feeder-watch.service -n 40 --no-pager`
   输出形如：
   ```
   feeder-watch @ 2026-09-26T21:42:09Z
     · L1 write.rows: 394 -> 454（窗口 120s）
     · L2 db.rows: 18245 -> 18545（窗口 900s）
     · L3 access-tcp-simulator.service: active
     · L3 监听 172.20.0.1:19002: 在
     · L3 iot.timeseries.write.failed = 0
     · L3 iot.timeseries.db.probe.failed = 0
   判定: OK（喂数正常）
   ```
3. **一行筛**（值班用）：
   ```bash
   journalctl -u feeder-watch.service --since -30min --no-pager | grep -E '判定: (ALERT|无法求值)'
   ```
   命中即说明最近 30 分钟报过停摆或判据不可求值。

### 5.2 判据与阈值（与本轮实测默认一致，**未改动**）

| 层 | 判据 | 窗口 |
|---|---|---|
| L1 | `iot.timeseries.write.rows` 无增长（**进程内计数器，ypbin-iot 重启会归零 ⇒ 必须配 L2**） | 120s |
| **L1b** | **对账**：`iot.access.egress.accepted` 与 `iot.timeseries.write.rows` 的**增量差**超容差（默认 `>10%` 或 `>20` 条，**故意放宽**）⇒ 疑似**真丢数据**。计数器归零（access/iot 重启）时判据**不可求值**而不是告警 | 同 L1 |
| L2 | `iot.timeseries.db.rows` 无增长（`COUNT(*)` 真值探针，服务端约 10 分钟一次） | 900s |
| L3 | `access-tcp-simulator.service` 非 active / `172.20.0.1:19002` 无监听 | 即时 |
| L3 | `iot.timeseries.write.failed` / `iot.timeseries.db.probe.failed` 非 0 | 即时 |

### 5.2.1 为什么需要 L1b（对账）而不是「看日志里有没有 dropped」

2026-09-27 实测：access 日志里 `[ypbin-iot] inbound buffer overflow on connection t1-d9300012; dropped frames=N`
每 ~2s 一条、N 单调增长，**但数据一条都没丢**——同一 61s 窗口内
`egress.accepted +31`、`egress.sent +31`、`write.rows +31`、`points.collected +31`，与设备 2s/帧 完全一致。
被淘汰的是 `NettyChannelConnection.drainFrames()` **无人消费的辅助缓冲副本**（字节码证据见
`docs/STARTER-FEEDBACK.md` UP-12；已作为反哺条目提给 starter：issue #17）。
⇒ 「日志里有 dropped」是**误报源**；**对账**才是真丢数据的判据。两者互补：L1b 报警时再回看日志找原因。

### 5.3 ⚠️ 两个必须知道的边界

1. **实际告警周期 ≈ 17–20 分钟，不是 5 分钟**：单次运行 = L1 120s + L2 900s ≈ 17 分钟；
   systemd **不会并发启动同一个 unit** ⇒ 落在"上一次还在跑"里的触发被**跳过**。
   若要严格 5 分钟粒度：把 timer 改成 `OnCalendar=*:0/20`（与真实耗时对齐，语义诚实），
   或给脚本加 `--quick`（只跑 L1+L3，约 1–2 分钟）另配一个 20 分钟的完整 timer。**本轮未做，登记待定。**
2. **只告警、不自愈**：本单元**不做任何写操作**、**不重启喂数源**。
   喂数源的崩溃恢复由它自己的 `Restart=always` + `StartLimitIntervalSec=0` 负责
   —— 「告警」与「自愈」分离，避免看门狗与单元互相打架。

---

## 5.4 ⚠️ 改 `/usr/local/sbin` 下脚本的前后纪律（并发会话踩出来的）

**发现方式**：2026-09-27 本机同时有**两条线**在动（本轮的运维线 + EMQX 部署线的
`emqx-tunnel-watch.timer`/worktree），`feeder-watch.sh` 被替换的瞬间恰好被 timer 触发 ⇒ journal 里出现
三条**行号对不上任何现存副本**的错误（`line 76: *：小流量下: command not found`、
`line 104: ACT_ACCESS: unbound variable`、`line 122: RECONCILE: unbound variable`），
而当时文件里该文本在第 50 行、`ACT_ACCESS` 在第 47 行且已定义，直接执行该文件完全正常。
**不是数据面问题，是并发替换窗口的瞬态**（后一次运行 `判定: OK`、单元自愈、`systemctl --failed` 为空）。

**约定（改系统路径下脚本时按序做）**：
1. **改前**跑一次 `systemctl --failed` 与 `sha256sum /usr/local/sbin/<脚本>`（记下旧哈希）；
2. 用 `install -m 0755 <仓库文件> /usr/local/sbin/<脚本>`（**原子替换**，不要 `cp` 到运行中的文件上）；
3. **改后**立刻**原地执行一次**该脚本（小窗口，如 `--window 5 --db-window 5`）确认可用，并再跑一次
   `systemctl --failed`；
4. 若同一时刻别的线也在动这台机器，**先对齐「谁什么时候改哪个文件」**，避免把「文件被替换瞬间被执行」
   误判成脚本缺陷（本轮就差点误判）。
5. 真出现「行号对不上任何现存副本」的错误时，**先比对 `sha256sum` 与 `git show` 的历史版本**再下结论 ——
   这比读 journal 猜要快，也不会把并发瞬态写成脚本 bug。

---

## 6. 启用新内核那次重启：预检 → 重启 → 核对 → 回退

> ⚠️ **前置原则：不要再单独做一次 L3 重启**。把"验证 zram/喂数源/mem 参数"这次机会
> **与用户启用 6.8.0-139 的那次重启合并**（本轮已用 `grub-reboot` 钉回 6.8.0-48 做过一次验证性重启；
> 那次**没有**启用新内核）。

### 6.1 现状（截至 2026-09-26）

| 项 | 值 |
|---|---|
| 运行内核 | `6.8.0-48-generic` |
| 已安装未启用 | `6.8.0-139-generic`（`GRUB_DEFAULT=0` 的顶层 `menuentry 'Ubuntu'` 加载 `/boot/vmlinuz-6.8.0-139-generic`） |
| `libc6` | `2.39-0ubuntu8.9` —— **已随 2026-09-26 那次重启生效**（`ldd --version` 可验）；**只有内核待启用** |
| `reboot-required` 信号 | **已消失**：`/var/run/reboot-required{,.pkgs}` 在 tmpfs 上、重启即清空且不会自动重建 ⇒ **"有升级待重启"在机器上已看不到了，极易遗忘** |
| 新内核上的 zram | 139 的模块树里 `zram.ko.zst` 与 `modules.dep` **都存在**（已核实），但**没人在 139 上启动过 `zram-swap.service`** |

### 6.2 重启**前**预检（逐项都要过；有一条不过就先别重启）

```bash
# ① 容器 restart policy：除一次性 init 外必须都是 always / unless-stopped
for c in $(docker ps -a --format '{{.Names}}'); do
  printf '%-24s %s\n' "$c" "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$c")"
done

# ② 关键单元都 enabled（否则重启后不会自起）
for u in docker docker.socket containerd 1panel-core 1panel-agent zram-swap \
         access-tcp-simulator feeder-watch.timer; do
  printf '%-26s enabled=%s active=%s\n' "$u" "$(systemctl is-enabled $u 2>&1)" "$(systemctl is-active $u 2>&1)"
done

# ③ 磁盘/内存余量（重启不额外写盘，但要留余量）
df -h /; free -m

# ④ 无未完成的 dpkg/apt 事务、fstab 无误
ls /var/lib/dpkg/updates/ ; findmnt --verify --verbose | tail -3

# ⑤ 确认没有正在进行的验收/压测（本目录的 *_log 与 docker events 都应为静默）
# ⑥ 备份（配置类）：override 与 .env 各留一份带时间戳的副本
cd /opt/ypbin/ypbin-iot/deploy
cp -a docker-compose.override.yml "docker-compose.override.yml.bak-$(date -u +%Y%m%d-%H%M%S)"
cp -a .env ".env.bak-$(date -u +%Y%m%d-%H%M%S)"
```

**已知的重启后预期现象**（不是故障，别误判）：

- **5 个应用会因"所有容器同时启动、应用比 Nacos 先就绪"被 restart 策略反复拉起 1–3 次，约 4 分钟后稳定**
  （2026-09-26 实测 `RestartCount`：access 3 / gateway 2 / auth 2 / iot 1 / system 1）。
  `iotdb/mysql/redis/iot-ui/openresty` 应为 0。
- **`/tmp` 会被清空**（见陷阱 1）。
- `ypbin-iotdb-init` 是 `restart=no` 的一次性 init ⇒ 重启后**保持 Exited(0)**，这是**预期**（DDL 幂等，数据在卷里）。

### 6.3 重启**后**核对：一条命令

```bash
bash /usr/local/sbin/post-reboot-check.sh --wait
```

它按 10 组判据逐项打 OK/FAIL 并给出退出码（`0` 全通过 / `1` 有 FAIL / `2` 有判据无法求值）：

| # | 判据 | 为什么是这条 |
|---|---|---|
| 0 | `uname -r`、`grub next_entry` 为空、`reboot-required` | 确认真的启用了目标内核、且没有残留的一次性钉回 |
| 1 | 容器全 Up（只允许 `iotdb-init` Exited(0)）、无 `OOMKilled` | — |
| 2 | 5 个应用日志都有 `Started XxxApplication` | **比 health=200 可靠**（见下） |
| 3 | Nacos `totalCount=5` 且各 `healthyInstanceCount=1` | 注册面 |
| 4 | `19000/`=200、`18084/actuator/health` 含 UP | 19000 是 iot-ui→gateway 的真链路；18084 是**唯一**真健康端点 |
| 5 | `POST /internal/readings` 带 `X-Internal-Token` + 空 body → **`code=400`**（不是 401） | **不写入任何数据**就能证明内部凭证链路可用；无/错 token 才是 401 |
| 6 | `iot.timeseries.write.rows` 与 `db.rows` 在增长 | 采集链路活性（本轮踩过"静默零写入"） |
| 7 | `swappiness=150`、`/dev/zram0` 是活动 swap、`zram-swap.service` active+enabled、算法 `lz4` | zram 成对持久化是否恢复 |
| 8 | **`access-tcp-simulator.service` active+enabled**、`172.20.0.1:19002` 在听、**未绑 `0.0.0.0`** | 喂数源自启（本轮的坑） |
| 9 | 5 个应用 `MALLOC_ARENA_MAX=2` | compose override 是持久化载体 |
| 10 | `df`、`free`、本次启动内核 OOM 计数 | 收尾 |

> ⚠️ **不要用 `18080/18081/18082` 的 `/actuator/health` 当健康证据**：它们返回**业务 404 包在 HTTP 200 里**；
> `18086`（access）的 `/actuator/health` 会**挂起**（>40s）。只有 **18084** 有真 UP 文档。

### 6.4 出问题怎么回到 `6.8.0-48`

```bash
# ① 临时启回旧内核**一次**（一次性，GRUB 消费后自动清空 next_entry）
grub-reboot "Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-48-generic"
grub-editenv /boot/grub/grubenv list      # 读回断言：next_entry=Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-48-generic
systemctl reboot
# 重启后核验：uname -r 应为 6.8.0-48-generic；grub-editenv list 的 next_entry 应为空（已被 GRUB 消费）

# ② 若连 SSH 都进不去：只能在**云控制台**的 VNC/串口里选 "Advanced options for Ubuntu" → 6.8.0-48-generic

# ③ 若想**永久**钉住旧内核（保底手段，非首选）：
#    编辑 /etc/default/grub 的 GRUB_DEFAULT 指向子菜单项，然后 update-grub
#    （或把 GRUB_DEFAULT 设为 "1>2" 这类 submenu>entry 索引；改完务必 update-grub 并读回 /boot/grub/grub.cfg）
```

### 6.5 给用户的短说明（可直接转述）

> **启用新内核（6.8.0-139）怎么做**
> 1. **前提**：必须有**云控制台**访问权限——万一新内核起不来网络，远程 SSH 救不回来。
> 2. **先跑预检**：见 §6.2（容器 restart policy、关键单元 enabled、磁盘/内存余量、备份 override 与 `.env`）。
> 3. **启用新内核**：**什么都不用做**，直接 `reboot`。顶层默认启动项已指向 `vmlinuz-6.8.0-139-generic`。
> 4. **想临时留在旧内核**：`grub-reboot "Advanced options for Ubuntu>Ubuntu, with Linux 6.8.0-48-generic" && reboot`（只生效一次）。
> 5. **重启后**：跑 `bash /usr/local/sbin/post-reboot-check.sh --wait`，看到 `判定：全部通过` 即可。
> 6. **注意**：① `/tmp` 会被清空，别把要留的东西放那儿；② 开机后 5 个应用会被 restart 拉起 1–3 次、**约 4 分钟**后才稳，这是正常的；
>    ③ zram/喂数源/内存参数都靠 systemd 单元与 compose 自恢复，逐项核对脚本会替你验；
>    ④ **新内核上 zram 从没跑过**——若 `post-reboot-check.sh` 报 zram 相关 FAIL，先 `journalctl -u zram-swap.service` 看原因。
> 7. **回不去了怎么办**：见 §6.4（`grub-reboot` 回到 6.8.0-48；最坏情况用云控制台 VNC 选旧内核）。

---

## 7. 回滚资产保留策略（草案；**本轮只登记，一个都没删**）

**问题**：根盘 29G 长期在 85–90%，而 `docker system df` 报的「可回收 4.896GB」**绝大部分是回滚资产**
（14 个 `ypbin/ypbin-iot:rollback-*` 的唯一层）。靠"感觉"删会删掉真正要紧的那几个。
**目标**：把磁盘余量从"薄（≈150 MiB）"变成"**有序**"。

**策略草案**（需用户批准后才执行）：

| 类别 | 保留规则 | 现状 | 过期候选 |
|---|---|---|---|
| 镜像 rollback tag | **最新 5 个** + **当前运行镜像**（无论新旧） | 18 个 | 待清点脚本列出 |
| `/root` 下 rollback jar | **最新 2 个** | 4 个 | 待清点脚本列出 |
| 配置 / SQL / Nacos 快照 | **全部保留**（体积小、且是唯一的人工回滚手段） | ≈35 项 | 无 |

**🔴 清单本身有盲区 ⇒ 等于没有策略（2026-09-27 实测）**：原清点脚本的 jar 一节只 glob
`/root/ypbin-iot-jar-rollback-*.jar`，于是两类文件**永远不会出现在清单里**，既不被保留也不被列为过期候选：

| 漏掉的对象 | 为什么漏 | 量 |
|---|---|---|
| `/root/ypbin-access-jar-*.jar` | 另一个服务的 jar 备份，**整个服务都没被覆盖** | 3 个 / ~247MB |
| `/root/ypbin-iot-jar-rollback2-*.jar`、`rollback3-*.jar` | `rollback-` 后面不是连字符 ⇒ glob 不匹配 | 2 个 / ~274MB |

**已修**（2026-09-27）：jar 一节改为**按服务**分组（`ypbin-iot` 留最新 **2** 个、`ypbin-access` 留最新 **1** 个），
并加**盲区自检**——`/root` 下凡 `*jar*.jar` 未被任何服务 glob 命中的，一律显式打印
「⚠️ **未纳入策略**」（而不是静默漏掉）；清单末尾给出「另有 N 个未纳入策略」。
**规则**：新增任何「按服务保留」的资产类别时，**必须同时写一条自检**，证明「没被覆盖的对象会被报出来」。

**只读清点工具**：`bash /usr/local/sbin/rollback-asset-inventory.sh`
（按上述规则逐条给出 `保留` / `**过期候选**（等用户决定）`，并列出已知危险资产、`df`、`docker system df`；
**它不会删除任何东西**。）

> ⚠️ **危险资产已被 VOID**：`/opt/ypbin/cred-hardening-20260926-153455/rollback.sh` 曾被改名为
> `rollback.sh.VOID-DO-NOT-RUN`，同目录另有 `VOID.md` 说明为什么**禁止执行**（它会先把 `.env` 覆盖成
> **5 字符旧口令**、再在不校验响应的情况下 PUT，失败还会继续重建 ⇒ 可能把口令降级并让 5 个服务鉴权中断）。
> **保留原文件内容不改动，以维持可追溯**；取代它的是 `/root/mem-round2-20260926/nacos-rotate-*/rollback-nacos-password.sh`
> （fail-closed 守卫 + `bash -n` 自检）。

---

## 8. 「已知漂移」：生产机文件 ↔ `main`（**生产不从仓库部署**）

**事实（必须知道）**：生产机上的 `deploy/` 资产**不是从仓库拉取部署的**，而是**手工 `install`** 到
`/usr/local/sbin/`、`/etc/systemd/system/`、`/etc/sysctl.d/`、`/etc/modules-load.d/`。
因此"仓库里的版本"与"机器上跑的版本"**可能悄悄分叉，而没有任何东西会告诉你**。

**第二件事实**：生产机 **连不上 GitHub**（实测 `git -C /opt/ypbin/ypbin-iot ls-remote origin` 超时），
且它那份检出（`/opt/ypbin/ypbin-iot`，HEAD `ad80e3f`）的 `origin/main` 是**旧 ref**
⇒ **在机器上"跟 origin/main 比"必然误报**（所有文件都被判成不存在/漂移）。

### 8.1 对账方法（**离线可用，一条命令**）

```bash
bash /usr/local/sbin/reconcile-prod-vs-main.sh      # 退出码 0=全一致 / 1=有漂移 / 2=有无法判定
```

原理：**清单模式** —— 仓库在**提交时**用
`bash deploy/gen-prod-install-manifest.sh > deploy/prod-install-manifest.sha256`
生成一份 `<sha256>  <生产机安装路径>  <仓库内路径>` 清单（与交付物放在同一个提交里）；
机器侧只做 `sha256sum` 比对。脚本在清单缺失时才退回 git 模式，且**ref 里没有的文件一律报"无法判定"而不是 DRIFT**
（避免一片假红 —— 这正是第一版脚本踩到的坑）。

**对账覆盖 13 个文件**（脚本/单元/配置），当前结果 **SAME=13 / DRIFT=0 / 无法判定=0**（2026-09-26 实测）。
**未纳入对账**（属正常，生产专有）：`docker-compose.override.yml`（含生产键）、
生产版 `docker-compose.yml`（比仓库新）、`.env`（凭据）。

### 8.2 ⚠️ 漂移纪律

1. **改了这些文件就必须在仓库重跑 `gen-prod-install-manifest.sh` 并与改动同一次提交** ——
   否则清单过期，对账会把"清单旧"误报成"机器漂移"。
2. **生产机上直接改**这些文件（而不是走仓库）会产生**未登记的漂移** ——
   只能靠定期 `reconcile-prod-vs-main.sh` 发现。建议列进例行巡检。
3. 清单**不覆盖** `deploy/` 里的**文档**（`.md`）与 `mem-round2/` 下的**一次性测量脚本**：
   它们不被安装到系统路径，属于"仓库侧留档"，无需对账。

---

## 9. 已登记、**刻意未做**的项（含性质与影响面）

| # | 项 | 性质 | 影响面 / 为什么可以不做 |
|---|---|---|---|
| 1 | **Nacos 回滚脚本的 3 条非阻断改进建议**：① 重建循环加显式失败处理（`\|\| { echo …; exit 8; }`）；② 头部注释把"任一步失败 ⇒ 不改 .env"限定为"**口令/守卫**分支"（`exit 7` 的就绪超时发生在 `install` 之后，属设计内）；③ 两个只读探针加 `-f`（对 fail-closed 无实质影响） | 可运维性/文档措辞，**非功能缺陷**；复核判定为 **PASS** | **刻意不改**：该回滚脚本与其生成器已被一次窄口径独立复核**逐字节锁定**（脚本 `sha256=513a1134…`、生成器 `sha256=33c196f2…`），当前"**生效版本 == 被复核版本**"。**动它就会破坏这个等式**，而那是凭据回滚路径上更重要的性质。⇒ 若要采纳这 3 条，必须**连同一次新的独立复核**一起做。 |
| 2 | **4 个服务 `MALLOC_ARENA_MAX` 的"同历时"复测** | 测量强度问题 | 机制已由 `ypbin-iot` 的**严格对称 A/B**（两腿同 elapsed、每腿 10 样本）证实（非堆匿名 −23.8%、≥1MB 匿名映射数量 −38.5%）；4 服务推广的基线是运行 ~3h 的老容器、落地后仅 5–11min ⇒ **幅度只能当上界**，这一点已写进报告 §10.2 与 §10.6。 |
| 3 | **`/tmp` 清理策略改成"按时间"** | 需要写 `/etc/tmpfiles.d/` override | 本轮只登记做法（陷阱 1）：**必须用 `/etc/tmpfiles.d/tmp.conf` override，不要直改 `/usr/lib/tmpfiles.d/`**；等维护窗口。注意：**光把 `30d` 改小没用**，`D` 无论年龄都会在开机清空，必须换类型（`q`）。 |
| 4 | **`feeder-watch` timer 的"严格 5 分钟"粒度** | 与真实耗时冲突 | 单次运行 ≈17 分钟（L1 120s + L2 900s），systemd 不并发启动同一 unit ⇒ 实际是**近乎连续的 ~17 分钟周期**（实测 22:16:20 → 22:33 完成 → 22:33:22 再起），**不是 5 分钟**。两个可选改法写在 `feeder-watch.timer` 注释里（改 `*:0/20`；或加 `--quick` 只跑 L1+L3）。 |
| 5 | **`main` 资产与生产机的"手工同步"漂移** | 流程性风险 | 见 **§8**（含一条命令的对账方法与漂移纪律）。 |
| 6 | **`/series` 返回的 `value` 显示为 `[B@3f06a7ee`** | **协议侧解码缺陷**（IoTDB 读出的是 `byte[]`，序列化成了数组的 `toString`） | 与本轮内存/运维改动无关；**另立项**。影响面：任何按 `value` 解析的客户端都拿不到真值，但 `ts`/`quality` 正常，且采集与落库不受影响。 |
| 7 | **保留策略的"过期候选"是否继续清理** | 需业务判断 | 2026-09-26 已按批准删掉 13 个过期 rollback tag + 2 个 jar（`/` 85% → **75%**）；**仍未动**的最大可回收项是 `ypbin/ypbin-ai:local`（340.6MB unique，该服务当前未运行，且不属任何保留类别）—— 需你决定是否保留。 |
| 8 | **新内核（6.8.0-139）上 `zram-swap.service` 的首次运行** | 未验证 | 139 的模块树里 `zram.ko.zst` 与 `modules.dep` **都存在**（已核实），但**没人实跑过**。⇒ 与用户那次内核重启合并，由 `post-reboot-check.sh` 第 7 组判据自动核对；若 FAIL 先看 `journalctl -u zram-swap.service`。 |
| 9 | **喂数源在"整机重启"后的自启** | 未验证 | 只证明到"单元 `enabled` + `Restart=always` + `kill -9` 自动拉起"。⇒ 同上，合并到那次重启，由 `post-reboot-check.sh` 第 8 组判据自动核对。 |
| 10 | **平台自告警（看板 #10）的启用顺序**：**开开关前必须先建表** | 部署顺序约束（**已在代码中核实**） | 调度壳由 `ypbin.platform-alert.enabled` 控制且**默认 false** ⇒ **当前部署不影响启动**（`PlatformAlertService` 无 `@PostConstruct`，唯一调用方是调度壳）。但若把该键改 `true` 而 `iot_platform_alert` 表**尚未建**（应执行 `deploy/sql/migration/2026-10-05-iot-platform-alert-schema.sql`），调度壳会每 30s 失败一次——**不会拖垮服务**（`@Scheduled` 异常由调度器记堆栈、下一轮重试，与仓内其它 10 个扫描器同范式），但会无意义地刷错误日志。⇒ 顺序固定为 **先建表 → 再改开关**；详见 `docs/PLATFORM-ALERTING-DESIGN.md` §7.1。**注**：一期为"观察期只落库不通知"，本就**不急**着开。 |
| 11 | **平台告警通知开关必须"单一来源"**：只改 Nacos，禁止再写 compose env `YPBIN_PLATFORM_ALERT_NOTIFY_ENABLED` | 配置陷阱（2026-10-08 实测，**已处置**） | OS 环境变量优先级高于 config data（Spring Boot 外部化配置 5 > 3，`spring.config.import` 导入的 Nacos 属 config data）⇒ env 里写 `false` 会把 Nacos live 的 `notify-enabled: true` **静默盖掉**；而开关关闭只记 DEBUG 日志 ⇒ 表现为"flip 了却永远收不到告警"且**无法从日志看出来**（#157 的 flip 就这样空转）。处置：删除 override 里的该 env 键，并在 `deploy/nacos/ypbin-iot.yaml` 写上硬规矩；实证 `docker exec ypbin-iot printenv` 无该键。**要临时关闭通知：改 Nacos 的 `notify-enabled: false`**（live 生效、**无需重启**：2026-10-08 实测 Nacos refresh 会 rebind 该 `@ConfigurationProperties`；但 `@Scheduled` 的 `fixedDelayString` 仍只在启动时绑定 ⇒ 改**周期**才要重启）。 |
| 12 | **`iot_platform_alert` 写路径曾整条不通**（`Column 'tenant_id' cannot be null`） | 缺陷（2026-10-08 已修并合并：**PR #184** → main `b32e6c34`） | 表列 `tenant_id` 为 **NOT NULL** 且参与去重唯一键，而开单在 `TenantContext.runIgnore` 下执行（租户拦截器不补值）⇒ 每次 FIRING 的 INSERT 都被库拒绝，**告警一条也落不了库**；因观察期 `firing` 恒 0，此路径从未被执行过（表内此前零行）。修复：开单显式落 `PLATFORM_TENANT_ID=1`；**不可**改为允许 NULL（唯一键含 tenant_id，NULL 行逃出去重）。运维判据：`docker logs ypbin-iot | grep "tenant_id' cannot be null"` 必须为 0。 |
| 13 | **dev `ypbin-iot` 容器 jar ≠ 镜像内 jar**（热换） | ✅ **已收敛（2026-10-08）**，附 lineage 遗留 | 原状：镜像 `ypbin/ypbin-iot:local`（2026-10-05 构建）内是旧 jar（`92121364…`），容器跑的是 `docker cp` 热换的 `2472d8fa…` ⇒ 任何 recreate 都会静默回退旧代码（health 仍 200）。**收敛做法**：镜像源当时仍不可用（`docker pull alpine` FAIL），故未走 `compose build`，改为「`FROM ypbin/ypbin-iot:pre-converge-<ts>`（旧镜像）+ `COPY app.jar`」重建 `:local`（新 ID `e1d414ca`，14:17Z），并把该 jar 放回服务器 `ypbin-service/ypbin-iot/target/`（旧 jar 已备份到 `backup/target-jar-backup/`）⇒ 镜像内 = 容器内 = 服务器 target = 本地制品 四方 md5 均为 `2472d8fa…`；recreate 实测 health UP、`NOTIFY_ENV=<unset>`、tenant_id 报错 0。回滚镜像：`ypbin/ypbin-iot:pre-converge-20261008-141749`。⚠️ **遗留**：镜像 lineage 不是 `compose build` 产物；**该机当前无出网**（见第 15 行）⇒ `compose build`（需拉 `eclipse-temurin:21-jre`）当前不可行；且**服务器检出停在 `ad80e3f`（deploy/* 有本地改动）⇒ 即使出网恢复，直接 `compose build` 也会把陈旧源码打进镜像**，要先用干净检出（或先同步源码）再 build。 |
| 14 | **平台告警实例与收件人固定主租户 1** | 多租户下需裁定 | `PLATFORM_TENANT_ID=1`（实例落库/唯一键）与通知收件租户同口径；本平台当前单租户（tenant 1）够用，多租户部署前需显式裁定"平台级告警归谁"。 |
| 15 | **dev 应用机当前无外网出口** | 环境事实（2026-10-08 实测，**非抖动**） | `https://registry-1.docker.io/v2/`、`https://repo1.maven.org/maven2/`、`https://github.com` 三端点各 20s **全部超时**（curl exit 124）；`docker pull docker.m.daocloud.io/library/alpine:3.20` FAIL。⇒ 该机上：拉不到基础镜像、`mvn` 拉不到依赖、无法 `compose build`；而本机侧 `git ls-remote` / 推 PR 正常 ⇒ 用"镜像源偶发抖动"解释是不成立的。**部署流程因此固定为**：本机（构建机）出制品 → `scp` 传 jar → `docker cp` 热换 或 刷新镜像后 `compose recreate`；不要在该机上尝试构建类操作。 |
| 16 | **改 Nacos live 单键的正确姿势（含 2026-10-08 事故）** | 操作规范（**必读**） | **事故**：为做故障注入临时改 `alert.enabled`，用了 `content.replace('    enabled: true', …, 1)`——它命中的是全文**首个**匹配，实际改掉的是第 8 行 **`ypbin.tenant.enabled`**（租户隔离总开关），而目标第 93 行没动。**发现与处置**：改完立刻回读，live 显示第 8 行 `false` / alert 段仍 `true` ⇒ 2 分钟内用逐字节备份还原（Nacos 日志两次 `Refresh keys changed: [ypbin.tenant.enabled]` 可见推送时点）。**影响评估：无运行时影响**——`DefaultTenantLineHandler` 从不读 `properties.isEnabled()`（只读 `failOnMissingTenant` / `column` / 构造时快照的 `ignoreTables`），`TenantAutoConfiguration` 是 `@ConditionalOnProperty`（只决定启动期装配）⇒ 已建好的拦截器全程有效；期间 health UP、日志无 ERROR/Exception。**规范**：改任何 live 键一律用 `tools/set-nacos-flag.py`（按行号定位 + 发布前断言"恰好 1 行变化" + 发布后回读比对 + 改前逐字节备份），**禁止**用 `str.replace` 类全局替换；改完**必须回读**。 |
| 17 | **Nacos live 刷新对"配置类开关"即时生效（对 `@Scheduled` 周期不生效）** | 机制事实（2026-10-08 实测，**修正旧口径**） | 通过 `/nacos/v3/admin/cs/config` 发布后，客户端 `[server-push] config changed` → `Refresh keys changed: [ypbin.alert.enabled]` → **下一个 tick 即生效**（实测：评估器立刻停止/恢复、`iot.alert.evaluate.lag` 随之增长/复位），**无需重启**。⇒ 旧口径"动态配置变更需重启 iot"（看板 #10 早期登记）对这些 `@ConfigurationProperties` 键**不成立**；但 `@Scheduled(fixedDelayString="${...}")` 的**周期**只在启动时绑定，改周期仍需重启。例外：被 `@ConditionalOnProperty` 决定**是否装配 Bean** 的开关（如 `ypbin.tenant.enabled`）改 live 不会增删已装配的 Bean。 |

---

## 10. 邮件（SMTP）配置：存放位置、生效机制与风险登记

> 登记于 2026-09-30（第二批）。**本节不含任何口令/授权码明文**，只登记键名、机制与风险。

### 10.1 配置存在哪（**不是** `.env`，也不是 Nacos）

邮件参数**不在** `deploy/.env`、**不在** Nacos 活配置，而是存在**业务库表**里：

| 库 | 表 | 组 |
|---|---|---|
| `ypbin_admin` | `sys_config` | `config_group = 'mail'` |

**7 个键**（表内 `config_key` 字面量，全大写下划线）：

`MAIL_HOST`、`MAIL_PORT`、`MAIL_SSL_ENABLED`、`MAIL_USERNAME`、`MAIL_PASSWORD`、`MAIL_FROM`、`MAIL_FROM_NAME`

- 读取方：`ypbin-system` 的 `DbMailConfigProvider`（实现 starter 的 `MailConfigProvider`），逐键读
  `SysConfigService.getString/getInt/getBoolean`，键名带默认值兜底（`MAIL_PORT` 默认 `465`、
  `MAIL_SSL_ENABLED` 默认 `true`）。
- 消费方：`MailService`（`MailController` 的测试发送 → `system:mail:test`；以及 `SystemClientImpl`
  的 `/mail-send` 内部端点 —— **iot 告警邮件正是经这条 Feign 链路**）。
- ⚠️ `MAIL_PASSWORD` 属敏感键，`SysConfigServiceImpl.toResp()` 会把它的值回显成**空串**
  （列表/详情接口看不到真值，这是有意的）。

### 10.2 怎么改、怎么生效（**两种路径，机制完全不同**）

| 路径 | 操作 | 生效机制 | 是否需要重启 |
|---|---|---|---|
| **A. 平台 UI**（推荐） | `系统管理 → 系统参数` → 页签 `mail` → 改值 → 保存 | `SysConfigServiceImpl.update()` → `SysCache.evictConfig(key)`（逐键失效 Redis 缓存）→ `publishConfigChanged(group)` → `ConfigChangedEvent` → `ConfigChangedEventListener.onConfigChanged`（`@TransactionalEventListener(AFTER_COMMIT)`）→ `configService.refreshCache()` 重建本地快照 | **不需要**，保存即生效 |
| **B. 直连 SQL** | 直接 `UPDATE sys_config …` | **绕过了**上面的接口与事件 ⇒ 本地快照 `cache` 不会刷新 | **必须重启 `ypbin-system`** 才生效 |

> ⚠️ **踩坑点**：路径 B 改完不重启，`DbMailConfigProvider` 读到的仍是**旧快照**，
> 表现为「库里明明改了但发信仍用旧值」——很容易误判成"配置没写进去"。**改邮件参数优先用 UI。**

重启口径（只动一个服务，不要全量 `up -d`）：

```bash
cd /opt/ypbin/ypbin-iot/deploy
docker compose -p deploy -f docker-compose.yml -f docker-compose.override.yml up -d --no-deps ypbin-system
```

### 10.3 ⚠️ 风险登记：**当前无声明式来源、无自动备份**（R8 主动提示）

**现状（事实）**：

1. 这 7 个键**只存在于运行库**`ypbin_admin.sys_config` 里 —— 仓库、`.env`、Nacos、`install.sh`
   全都**没有**它们的声明式定义 ⇒ **生产库是该配置的唯一真值来源**。
2. **没有自动备份**：`sys_config` 不在任何定时 dump / 快照流程里（§7 的回滚资产是手工产物）。
   一旦库损坏或误 `DELETE`，配置**不可从仓库重建**（只能重新申请授权码）。
3. **不做对账**：`reconcile-prod-vs-main.sh` 只覆盖 13 个文件（脚本/单元/配置），**不含库表**。
4. **两个库表项与其它配置不同构**：`MAIL_PASSWORD` 是**真实可用凭据**（QQ 授权码），
   按全局 R 的"真实凭据零入库"精神，它现在**确实入库了**（落在业务库里）。
   这是平台既有设计（starter 的 `MailConfigProvider` 就是这样读的），**不是本轮引入的**，
   但必须登记：**库备份 = 凭据备份**，库导出物的保管级别应与凭据一致。

**建议（按性价比排序，均未实施）**：

| 优先级 | 建议 | 说明 |
|---|---|---|
| 高 | 改邮件参数**一律走 UI**，改完在页面点一次测试发送 | 即时生效 + 当场验证，避免路径 B 的"改了不生效"陷阱 |
| 中 | 把 `mail` 组纳入**变更前备份**例行流程（导出 600 文件到 `/root/sysconfig-backup/`） | 与 §7 回滚资产同口径；本轮已按此做法留下样本 |
| 中 | 为 `sys_config` 的 `mail` 组加**周期性快照**（cron dump，600 权限，落 `/root`） | 消除「唯一真值无备份」这个单点 |
| 低 | 长期考虑把邮件凭据移出业务库（如 `.env` + 环境变量注入，与 EMQX/Nacos 凭据同口径） | 属架构变更，需立项；**当前不改** |

### 10.4 排障速查（怎么判断"邮件到底是配置问题还是凭据问题"）

看 `iot_alert_notification` 的 `last_error` 文本即可分层定位：

| `last_error` 形态 | 定位 |
|---|---|
| `邮件未配置或配置不完整（缺少 host/username）` | **配置面**：`sys_config` 缺键/空值（或改了 SQL 没重启） |
| `系统服务返回失败：邮件发送失败：Authentication failed` | **凭据面**：已读到配置、已连到 SMTP，**卡在 AUTH** ⇒ 授权码/账号状态问题 |
| 投递行 `SENT` | 平台侧已交出 SMTP（**对方收件箱是否真收到，平台无法证明** ⇒ 需人自查） |

> ⚠️ QQ 邮箱返回 `535` 时**不要反复重试**（会触发风控、甚至锁账号）。平台自带退避
> （30s → 2min → 10min，`attempt` 上限后转 `GIVEN_UP`），**不要去手工催**。
