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
| `emqx-tunnel.service` | 生产机 | `autossh` 隧道单元（`Restart=always`、开机自启、主机密钥 pin、算法 pin、只转发 18093） |
| `emqx-tunnel-install.sh` | 生产机 | 幂等安装器：生成**专用受限**密钥、pin 主机密钥、装单元与告警 timer |
| `emqx-tunnel-watch.sh` | 生产机 | 「隧道停摆」判据（四级，**只告警不自愈**；不使用任何凭据） |
| `emqx-tunnel-watch.service` / `.timer` | 生产机 | 每 2 分钟触发一次判据；失败即单元 `failed` + journald 明确告警 |
| `phase2-loadtest.sh` | 中间件机 | 阶段② 限内存 OOM/存活实测（100/500 连接两档，判据 + 阈值对照 + 清理） |
| `latency-probe.py` | 中间件机 | 只读订阅端延迟观测器（逐条统计 p50/p95/p99；**不产生负载**） |

## 凭据纪律

- 真实凭据**只存在于目标机** `/opt/emqx/.env`（600）与 `/opt/emqx/api-key/default_api_key.conf`（400，属主 = 容器内 `emqx` 用户 uid 1000）。
- 两者都在 `.gitignore` 内；仓库只有 `.env.example`（全空值）。
- 所有脚本**只回显长度与 sha256 指纹**，从不打印值；测试账号口令只存在于进程内存，退出即删账号。
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
