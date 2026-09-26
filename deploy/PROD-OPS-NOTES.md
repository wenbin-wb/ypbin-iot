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
| L2 | `iot.timeseries.db.rows` 无增长（`COUNT(*)` 真值探针，服务端约 10 分钟一次） | 900s |
| L3 | `access-tcp-simulator.service` 非 active / `172.20.0.1:19002` 无监听 | 即时 |
| L3 | `iot.timeseries.write.failed` / `iot.timeseries.db.probe.failed` 非 0 | 即时 |

### 5.3 ⚠️ 两个必须知道的边界

1. **实际告警周期 ≈ 17–20 分钟，不是 5 分钟**：单次运行 = L1 120s + L2 900s ≈ 17 分钟；
   systemd **不会并发启动同一个 unit** ⇒ 落在"上一次还在跑"里的触发被**跳过**。
   若要严格 5 分钟粒度：把 timer 改成 `OnCalendar=*:0/20`（与真实耗时对齐，语义诚实），
   或给脚本加 `--quick`（只跑 L1+L3，约 1–2 分钟）另配一个 20 分钟的完整 timer。**本轮未做，登记待定。**
2. **只告警、不自愈**：本单元**不做任何写操作**、**不重启喂数源**。
   喂数源的崩溃恢复由它自己的 `Restart=always` + `StartLimitIntervalSec=0` 负责
   —— 「告警」与「自愈」分离，避免看门狗与单元互相打架。

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

**只读清点工具**：`bash /usr/local/sbin/rollback-asset-inventory.sh`
（按上述规则逐条给出 `保留` / `**过期候选**（等用户决定）`，并列出已知危险资产、`df`、`docker system df`；
**它不会删除任何东西**。）

> ⚠️ **危险资产已被 VOID**：`/opt/ypbin/cred-hardening-20260926-153455/rollback.sh` 曾被改名为
> `rollback.sh.VOID-DO-NOT-RUN`，同目录另有 `VOID.md` 说明为什么**禁止执行**（它会先把 `.env` 覆盖成
> **5 字符旧口令**、再在不校验响应的情况下 PUT，失败还会继续重建 ⇒ 可能把口令降级并让 5 个服务鉴权中断）。
> **保留原文件内容不改动，以维持可追溯**；取代它的是 `/root/mem-round2-20260926/nacos-rotate-*/rollback-nacos-password.sh`
> （fail-closed 守卫 + `bash -n` 自检）。
