# 采集链路「喂数源」：TCP 模拟设备（容器外 · 宿主机 systemd 单元）

> 建立于 **2026-09-26**（承一轮事故：机器重启后采集链路静默变成"零写入"，排查见下）。
> 脚本本体是**仓库里的测试工具**：`ypbin-iot/docs/tools/access-tcp-simulator.py`
> （git-tracked，最后提交 `225de4a`，md5 `8f56983a52a36efc5c79491e5e8572e4`）。

---

## 0. 一句话

生产机没有物理设备；access 侧设备 `demo-dev-curve`（`deviceId=9300012`）的 endpoint 是
`tcp://172.20.0.1:19002` —— 由 **access 容器回连宿主机**取数。这个模拟器就是那台"设备"。
它曾经是**某次手工 nohup 的进程**，2026-09-26 的机器重启把它带走了，于是：

> 容器全 Up、Nacos 5/5 健康实例、19000=200、18084=UP —— **但 `iot.reading` 一行都不再增加**。

现在它由 **systemd 单元 `access-tcp-simulator.service`** 托管（`Restart=always` + 开机自启 + journald），
**不新起任何容器**。

---

## 1. 它确实是那个喂数源的确认证据（一手，2026-09-26）

| 维度 | 证据 |
|---|---|
| 端口 | 脚本默认 `--port 19002`；`--bind` 默认 `0.0.0.0`（**我们刻意改成 `172.20.0.1`**，见 §3） |
| 帧格式 | `payload = f"TEMP=23.5,SEQ={seq}\n"` —— 被动设备：**accept 后按周期单向发帧**，不响应下行 |
| 目标地址 | `172.20.0.1` 经 `docker network inspect deploy_ypbin-net` 确认 = `{"Subnet":"172.20.0.0/24","Gateway":"172.20.0.1"}` ⇒ **宿主机侧网桥网关** |
| access 侧行为 | 重启后 access 日志持续 `open failed for connection t1-d9300012` + `Connection refused: /172.20.0.1:19002` |
| **成功态对应** | access 日志 `[access] 订阅成功：deviceId=9300012 点位数=4` 只在模拟器在线时出现：**01:52:35 / 02:57:46 / 03:13:12（本地 CST）** —— 正是 access 三次 (重)启动且模拟器在跑的时刻 |
| 唯一性 | `open failed for connection t1-d*` 统计里，**只有 `d9300012` 曾成功**；`9300001/0002/0006~0011/0013` 全程只有 `open failed`（它们的 endpoint 是 `127.0.0.1:9`，本就不存在） |

⇒ **确认成立，未发现对不上的地方。**

---

## 2. 安装与运行

```bash
# 脚本（仓库 docs/tools/access-tcp-simulator.py）
install -o root -g root -m 0755 access-tcp-simulator.py      /usr/local/sbin/access-tcp-simulator.py
# 单元（仓库 deploy/access-tcp-simulator.service）
install -o root -g root -m 0644 access-tcp-simulator.service /etc/systemd/system/access-tcp-simulator.service
systemctl daemon-reload && systemctl enable --now access-tcp-simulator.service
```

关键参数：`--bind 172.20.0.1 --port 19002 --interval 2.0`（每 2 秒一帧）。

---

## 3. 安全：**只绑网桥网关，不绑 0.0.0.0、也不绑 127.0.0.1**

| 绑定 | 结论 |
|---|---|
| `127.0.0.1` | ✗ **不行** —— access 在容器内，容器回连宿主机 loopback 打不到宿主机的 127.0.0.1 |
| `0.0.0.0`（脚本默认） | ✗ **不必要且危险** —— 会把 19002 暴露到公网接口。本机对 IoTDB 6667/9091/9092 都坚持只绑回环，同理 |
| **`172.20.0.1`** | ✓ **本单元采用** —— 容器可达、公网不可达 |

实测（2026-09-26）：`ss -ltnp` 只有 `172.20.0.1:19002`；对 `172.16.1.11` / `192.168.0.1` / `192.168.16.1` 的 19002 连接全部 REFUSED/TIMEOUT。

其它加固：`DynamicUser=yes`、`NoNewPrivileges`、`ProtectSystem=strict`、`PrivateTmp/PrivateDevices`、
`RestrictAddressFamilies=AF_INET AF_INET6`、`MemoryMax=128M`、`CPUQuota=20%`。

---

## 4. 扛重启 / 扛崩溃

| 场景 | 机制 | 实测 |
|---|---|---|
| 进程崩溃 / 被 kill | `Restart=always` + `RestartSec=3` + **`StartLimitIntervalSec=0`**（关掉"5 次失败就不再拉起"的限流 —— 否则一次抖动就可能让喂数**永久**停摆） | **已实测**：`kill -9` MainPID ⇒ 3 秒内 systemd 拉起新进程（`NRestarts=1`）、19002 重新监听、access 自动重连、`write.rows` 继续增长（22 → 28） |
| 机器重启 | `WantedBy=multi-user.target` + `After=network-online.target docker.service`，`enabled` | 单元已 `enabled`；**但"整机重启后是否自动恢复"本轮未复测**（需再一次 L3 重启，见报告「仍未验证项」） |

停止后接入侧不再有新读数 —— 这**正是**验收「数据确由该链路产生」的反证手段（脚本 docstring 自己也这么写）。

---

## 5. ⚠️ 「喂数停摆」判据（本轮新增，必须接入监控）

这是本轮踩到的真问题：**没有现成告警会告诉你"采集已经静默变成零写入"**。
判据已固化为只读脚本 **`deploy/feeder-watch.sh`**（安装到 `/usr/local/sbin/feeder-watch.sh`）：

| 层 | 判据 | 窗口 | 说明 |
|---|---|---|---|
| **L1** | `iot.timeseries.write.rows` **无增长** | **120s** | 最快；但它是**进程内计数器**，ypbin-iot 重启会归零 ⇒ 必须配合 L2 |
| **L2** | `iot.timeseries.db.rows` **无增长**（对 `iot.reading` 的 `COUNT(*)` 真值探针） | **900s** | 真值；服务端 10 分钟一次，故窗口取 15 分钟 |
| **L3** | `access-tcp-simulator.service` 非 active / `172.20.0.1:19002` 无监听 | 即时 | 喂数源自身挂了（本轮故障就是这一条） |
| **L3** | `iot.timeseries.write.failed` 或 `iot.timeseries.db.probe.failed` 非 0 | 即时 | 写失败 / 对库对账失败 |

退出码：`0` OK / `2` ALERT / `3` 判据无法求值（本身也要人看）。

**手动验收同一判据的可复现方式**（无凭据）：

```bash
# ① 实时计数器
curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.write.rows
# ② 真值 COUNT(*)
curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.db.rows
# ③ 喂数源与监听
systemctl is-active access-tcp-simulator.service; ss -ltn | grep 172.20.0.1:19002
# ④ 当天新点（需要运维台令牌；路径已确认）
#    GET http://127.0.0.1:18080/iot/devices/9300012/series?propertyId=temperature&from=<ms>&to=<ms>&limit=200
#    ⚠️ 返回的是**窗口内升序的前 limit 条**；要看"最新点"请把 from 设成你要验证的起始时刻
```

**建议告警阈值（写成监控规则）**：L1 连续 2 个窗口（4 分钟）不增长 ⇒ 黄；L2 一个窗口（15 分钟）不增长 ⇒ 红；
L3 任一条命中 ⇒ 立即红。

> **接入监控本身**（systemd timer / 外部探针）**本轮未做，待批准**——本轮的硬约束是"不新起容器/中间件"，
> 而定时器属于新增监控组件，属于"只登记不实施"的范围。目前可人工 `feeder-watch.sh` 巡检。
