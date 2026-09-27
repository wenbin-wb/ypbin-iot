# `deploy/emqx/` —— EMQX 5.8.9 中间件机独立部署资产

> 面向**中间件机**（`43.242.200.8:61260`，Ubuntu 24.04，8C/15991MB）的 EMQX 部署与验收资产。
> **设计依据**：`docs/EMQX-INGRESS-DESIGN.md`（§5.2 ACL、§8.1 compose、§8.2 暴露面、§8.3 资源、H1–H12）
> **部署文档（含真实实测数据与回滚）**：`docs/EMQX-DEPLOY.md`
>
> ⚠️ 与设计文档 §8.1 的**差异**：设计写的是把 `emqx` 服务加进**生产机**的 `deploy/docker-compose.yml`；
> 实际按用户指令改为在**中间件机**上做**独立 compose 项目**（`/opt/emqx`）。
> 原因见设计 §8.3 阶段③「另机部署」与本文档 §1 的取舍说明。

## 文件清单

| 文件 | 跑在哪 | 作用 |
|---|---|---|
| `docker-compose.yml` | 中间件机 `/opt/emqx/` | 独立 compose 项目 `emqx-edge`：pin 镜像、独立网络/卷、`mem_limit`、健康检查、只发布回环端口 |
| `emqx.conf` | 中间件机 `/opt/emqx/` | **只读真源**：`authorization.no_match=deny`（红线 H1）、内置库 sha256+salt 认证、`client_attrs_init`、监听器、retainer 上限、日志滚动 |
| `.env.example` | 仓库 | 环境变量**示例**（**不含任何真实凭据**） |
| `.gitignore` | 仓库 | 挡住 `.env` 与 `api-key/default_api_key.conf`，防真实凭据入库 |
| `gen-env.sh` | 中间件机 | 就地生成 `.env`（600）与 API Key 预置文件（400，属主给容器 uid）——**值不回显，只打印长度与指纹** |
| `emqx-init.sh` | 中间件机 | 配置红线**断言** + ACL 规则**幂等 upsert** + 调自检（失败即 exit 1） |
| `emqx-selfcheck.sh` | 中间件机 | 阶段① 验收自检（19 项，含匿名/越权/放行正负用例，用 EMQX 指标前后差值判定） |
| `emqx-tunnel.service` | 生产机 | `autossh` 隧道单元（`Restart=always`、开机自启、主机密钥 pin、算法 pin；以 `-N` 只做转发、**转发目标**由中间件机侧 `permitopen` 钉死在 `127.0.0.1:18093`。⚠️ 注意「目标钉死」≠「只能转发」——反向转发未被禁止，见 `docs/EMQX-DEPLOY.md` §6.1 的 R8 残留风险） |
| `emqx-tunnel-install.sh` | 生产机 | 幂等安装器：生成**专用受限**密钥、pin 主机密钥、装单元与告警 timer |
| `emqx-tunnel-watch.sh` | 生产机 | 「隧道停摆」判据（四级，**只告警不自愈**；不使用任何凭据） |
| `emqx-tunnel-watch.service` / `.timer` | 生产机 | 每 2 分钟触发一次判据；失败即单元 `failed` + journald 明确告警 |
| `phase2-loadtest.sh` | 中间件机 | 阶段② 限内存 OOM/存活实测（100/500 连接两档，判据 + 阈值对照 + 清理） |
| `emqx-ingress-tunnel.service` | 生产机 | **入站/出向双向隧道**（段 A 新增）：`-L 172.20.0.1:18093`（容器可达的管理面）+ `-R 127.0.0.1:18084`（EMQX 回平台）；与既有 `emqx-tunnel.service` **并存**、互不影响 |
| `emqx-ingress-relay.socket` / `.service` | 中间件机 | **入站中继**：`systemd-socket-proxyd` 把 `172.28.0.1:18084` 转到宿主回环（EMQX 容器到不了宿主回环） |
| `emqx-ingress-firewall.service` | 中间件机 | 幂等放行「emqx-edge 网桥 → 本机 18084」（宿主 INPUT 策略是 DROP，不放行则容器连不上） |
| `emqx-ingress-install.sh` | 运维机 | 幂等安装/核验上述三份单元 + 落 `ingress/internal-token`(600) |
| `emqx-ingress-init.sh` | 中间件机 | 幂等 upsert **规则 + Webhook 动作**（含 `max_buffer_bytes=16MB`）+ 动作 `connected` 自检 |
| `emqx-ingress-rollback.sh` | 中间件机 | 撤销入站（**先删规则再删动作**——EMQX 拒绝删除被引用的桥接） |
| `mqtt-device-probe.py` | 中间件机 | 模拟 MQTT 设备（用平台签发的凭据；只依赖 `/opt/emqx/venv` 里既有的 paho-mqtt） |
| `mqtt-external-probe.py` | **生产机**（从外部接入） | **零第三方依赖**（只用标准库 socket/ssl）的 MQTT 3.1.1 探针：从**别的机器**经公网连中间件机验收。生产机上没有 paho，也不该为一次验收污染它 —— 这是它存在的唯一理由。含 PUBACK/PINGREQ（不然 QoS1 下行会一直被重发、空闲连接会被 keepalive 断开） |
| `accept-emqx-external.sh` | 运维机 | **从外部网络**（生产机视角）经**公网 1883** 的端到端验收 ①–⑥（上行落库/曲线/幂等/越权/无凭据 + 下行 receipt→succeeded）。与 `accept-emqx-{ingress,downlink}.sh` 的差异见脚本头部 |
| `emqx-mqtt-expose.sh` | 中间件机 | MQTT 1883 **对外放行 / 撤销 / 巡检**（`open`/`close`/`status`；只碰 tcp/1883，不碰 18093/8883/8083/8084） |
| `emqx-mqtt-expose-firewall.service` | 中间件机 | 幂等维护 tcp/1883 的 INPUT 放行（`-C` 命中不重复插；`ExecStop` 即回滚）。⚠️ **对 Docker 发布端口这是纵深防御，不是有效闸门**——见 `docs/EMQX-DEPLOY.md` §3.1 |
| `emqx-mqtt-expose-watch.sh` + `.service` / `.timer` | 中间件机 | 1883 暴露面判据 A1–A5（匿名计数恒 0 / 管理面未对外 / 8883-8084 无监听 / 声明与现实一致），**只告警不自愈**，每 5 分钟 |
| `expose-evidence/` | 仓库 | 本轮暴露面与端到端验收的**原始输出**（`accept-external-run-pass.txt`、`reachability-from-prod.txt`、`diagnose-emqx-admin-h2c.txt`）。⚠️ 用 `.txt` 而不是 `.log`：根 `.gitignore` 有 `*.log`，否则这些证据会被静默排除在提交之外（实测踩过） |
| `diagnose-emqx-admin-h2c/` | 运维机 + 生产机 | 复跑判据：平台 `EmqxRestAdminClient` 在 **JDK HttpClient HTTP/2** 下 `POST /api/v5/publish` 必失败（h2c upgrade EOF），HTTP/1.1 或预热连接则成功。**平台侧缺陷，与暴露面无关** |
| `accept-emqx-ingress.sh` | 运维机 | 入站端到端验收 ①–⑤（可复跑；越权判据用 **EMQX 指标差值**，因为 `deny_action=ignore` 下客户端看不出被拒） |
| `latency-probe.py` | 中间件机 | 只读订阅端延迟观测器（逐条统计 p50/p95/p99；**不产生负载**） |

## 平台侧集成（段 A）

入站（设备 → EMQX → 平台）与出向（平台 → EMQX 管理面）**两条方向**、以及为什么必须用反向隧道 + 中继，
见 **`../../docs/EMQX-INTEGRATION.md` §2**（含拓扑图与逐条理由）。最短上手顺序：

```bash
# ① 通道（幂等）：三份单元 + 内部凭证落位 + 双向连通性核验
bash deploy/emqx/emqx-ingress-install.sh --mw-ssh "<mw ssh>" --prod-ssh "<prod ssh>"
# ② 规则/动作（幂等）：在中间件机跑
ssh <mw> 'bash /opt/emqx/emqx-ingress-init.sh'
# ③ 端到端验收 ①–⑤（可复跑）
bash deploy/emqx/accept-emqx-ingress.sh --mw-ssh "<mw ssh>" --prod-ssh "<prod ssh>"
# 回滚
ssh <mw> 'bash /opt/emqx/emqx-ingress-rollback.sh'
ssh <prod> 'systemctl disable --now emqx-ingress-tunnel.service'
```

## 凭据纪律

- 真实凭据**只存在于目标机** `/opt/emqx/.env`（600）与 `/opt/emqx/api-key/default_api_key.conf`（400，属主 = 容器内 `emqx` 用户 uid 1000）。
- 两者都在 `.gitignore` 内；仓库只有 `.env.example`（全空值）。
- 所有脚本**只回显长度与 sha256 指纹**，从不打印值；API Key 用 `curl -K <600 文件>` 传入（curl 的 argv 里只有路径）。
- ⚠️ **但 emqtt-bench 的 `-P <明文口令>` 会进 `docker run` 的 argv 与容器 `Config.Cmd`**（第三方工具硬限制，
  两个脚本的 S6–S11 / 压测端都用它；宿主 `/proc` 无 hidepid ⇒ 有可见窗口）——见下方专门一条。
- ⚠️ **一处必须说准的口径**：`phase2-loadtest.sh` 的**压测端 emqtt-bench 只支持 `-P <明文口令>`** ⇒
  压测**期间**该临时口令会出现在 `docker inspect <pub 容器>` 的 `Config.Cmd`（与 /proc 的 cmdline）里。
  对策：输出目录 700、`cleanup()` 立即删除 `*.inspect.json`、账号在压测结束即删（口令随之失效）。
  **不要**把它写成"口令从不落盘/只在进程内存"——独立复核实测证伪过这版表述。
- 排查凭据问题只比「长度 / 指纹 / 键名」。

## 最小使用顺序（中间件机）

```bash
# 0) 只读确认镜像源可用（不 pull）
#    见 docs/EMQX-DEPLOY.md 附录 A
# 1) 上传资产到 /opt/emqx/ 后
bash /opt/emqx/gen-env.sh            # 生成 .env(600) 与 api-key 文件(400)
cd /opt/emqx && docker compose up -d # 起容器（首次初始化 data 卷 ⇒ dashboard 口令生效）
bash /opt/emqx/emqx-init.sh          # 断言红线 + upsert ACL 规则 + 自检（全绿才 exit 0）
```

## 生产机（隧道）

```bash
# autossh 由离线 .deb 装入（生产机无法直连外网），见 docs/EMQX-DEPLOY.md 附录 B
bash emqx-tunnel-install.sh --dir /opt/ypbin/ypbin-iot/deploy/emqx \
     --mw-hostkey-file /tmp/mw_host_ed25519.pub
# 然后把脚本打印的 authorized_keys 行加到中间件机 root（带
# restrict,port-forwarding,permitopen="127.0.0.1:18093"），再 restart 隧道单元
```
