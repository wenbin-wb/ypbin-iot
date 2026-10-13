# OPS-EMQX-CLUSTER —— EMQX 集群化与多节点接入（issue #219）

> **结论先行**：
> 1. **docker-compose 多节点可行**，且已在中间件机的**隔离实验床**上真跑通：
>    `cluster.discovery_strategy = static` + `cluster.static.seeds` 让 2 个节点自动成簇，
>    `emqx ctl cluster status` 返回 2 个 running node；**跨节点路由实测通过**
>    （订阅在 node1、发布在 node2，消息送达）。
> 2. 对现有单节点配置的**改动面很小**：加 `cluster.discovery_strategy/static.seeds`、
>    保证节点名可解析、放通集群内通信端口（4370/5369）、cookie 一致；**数据卷按节点独立**。
> 3. **未在生产 `ypbin-emqx` 上落地**（中间件机未被显式授权 + 需重建容器）；分步方案见 §4。
> 4. **未实测**：跨节点**会话接管**（clean_start=false 重连到另一节点）、认证/ACL 数据在集群间的
>    复制（实验床 `enable_authn=false`）——这两条落地前必须补验。

## 1. 可行性评估（逐项）

| 维度 | 结论 | 依据 / 说明 |
|---|---|---|
| 版本 | EMQX **5.8.9 OSS** | 当前生产与实验床同版本；OSS 支持 `manual/static/dns/etcd/k8s/singleton` 发现策略 |
| 集群发现 | **`static` 可用**（推荐） | 实验床 `cluster.static.seeds = ["emqx@emqx-lab-1.local","emqx@emqx-lab-2.local"]` 实测成簇；官方文档《创建与管理集群》列出 static |
| 节点命名 | 必须是**带点的 FQDN/IP** 且可被彼此解析 | 本仓生产已用 `emqx@ypbin-emqx.local`（带点，符合严格口径）；compose 需给网络 alias 与 hostname 一致 |
| 集群内通信 | 需同 Erlang 分发网络 | Docker 中用 `4370`(dist) / `5369`(cluster RPC)；同 compose 网络内不需发布宿主端口；跨主机才要 |
| 路由 | 集群共享订阅/路由表（full-mesh） | 实测跨节点路由通过 |
| 会话 | MQTT 会话落在连接节点；路由是全集群的 | **跨节点会话接管未实测**（见 §6），落地前补验 |
| 认证/ACL 数据 | `built_in_database` 走 Mnesia，**随集群复制** | 官方设计如此；**未实测**（实验床关掉了 authn） |
| 数据卷 | **每节点独立 data/log 卷** | 卷内容与节点名绑定；两个节点**不能共享同一 data 卷** |
| 接入方式 | 客户端需能连到任一节点 | 要么每个节点都发布自己的端口（客户端自行选），要么前面放 LB（NGINX/HAProxy） |
| 对现有单节点改动面 | **小**：加 cluster.seeds / 网络 alias / 端口 / cookie 一致 | 生产现有 `emqx@ypbin-emqx.local` 可直接作为集群一员 |

## 2. 实验床落地（已实跑，可复现）

资产：`deploy/emqx/docker-compose.lab.yml`（2 节点 `ypbin-emqx-lab-1/2`）、
`deploy/emqx/lab/emqx-lab.conf`（static seeds）。网络 `emqx-lab 172.29.0.0/16`，
宿主端口只绑回环 `11883/21883`（MQTT）、`18094/18095`（Dashboard）。

```bash
docker exec ypbin-emqx-lab-1 emqx ctl cluster status
# Cluster status: #{running_nodes => ['emqx@emqx-lab-1.local','emqx@emqx-lab-2.local'],
#                   stopped_nodes => []}
# 跨节点路由：订阅在 node1、发布在 node2
/opt/emqx/venv/bin/python /opt/emqx-lab/lab/mqtt-lab-test.py <ip1> <ip2>
# [219] subscribe@node1 publish@node2 received -> ['hello-from-node2']
```

## 3. 生产分步方案（建议，本轮未执行）

> 前提：先完成 #218 §7 的 8883 修复与收紧决策；集群化会重建容器，需维护窗口。

**阶段 A（同机双节点，最小验证）**
1. 新增第二个节点容器（同镜像、独立 `container_name/hostname`、独立 data/log 卷、
   独立 Erlang 端口；MQTT/管理端口错开或只给 node1 发布）；
2. 两节点 `node.name` 分别 `emqx@node-a.local` / `emqx@node-b.local`，**cookie 相同**；
3. 给 compose 网络加 alias，使两个 FQDN 互相可解析；
4. 配置 `cluster { discovery_strategy = static, static { seeds = [两个节点名] } }`，
   或在 node2 上 `emqx ctl cluster join emqx@node-a.local` 手动加入；
5. 验证：`emqx ctl cluster status` 2 节点 running；跨节点订阅/发布；Dashboard 集群页。

**阶段 B（多机/生产接入）**
6. 若跨主机：放通 `4370`/`5369`（容器网络则 `5369`）与节点名解析（DNS/hosts）；
7. 接入层：在 EMQX 集群前放 **LB**（NGINX/HAProxy，按 4 层转发 1883/8883），
   客户端只连 LB；或让设备分别连不同节点（需设备侧配置多地址）；
8. 把认证/ACL 数据治理纳入集群视角（`built_in_database` 复制；写操作建议走单一入口）；
9. TLS：LB 终止或直通，证书 SAN 覆盖 LB/域名（与 #218 §6 一致）。

## 4. 对现有单节点配置的改动面（清单）

| 文件/对象 | 改动 |
|---|---|
| `deploy/emqx/emqx.conf` | `cluster.discovery_strategy` 由 `manual` 改 `static` + 加 `cluster.static.seeds`（或保持 manual + join 命令） |
| `deploy/emqx/docker-compose.yml` | 新增第二个 emqx 服务块（独立容器名/hostname/卷/端口/网络 alias）；跨主机时放通集群端口 |
| `/opt/emqx/.env` | 两节点 **cookie 必须相同**；node name 各自唯一 |
| 数据卷 | 新增第二套 named volume；**不得**复用 node1 的 data 卷 |
| 防火墙/安全组 | 集群内 4370/5369 互达；对外仍只放行 MQTT 端口 |

## 5. 风险与回滚

**风险**：① 集群脑裂（autoheal 默认开，需监控）；② 认证/ACL 复制未验证前可能出现"某节点上账号不可用"；
③ 会话接管未验证前，客户端重连到另一节点可能出现订阅/未确认消息语义差异；④ 重建容器会短暂断连。

**回滚（实验床）**：
```bash
cd /opt/emqx-lab && docker compose --env-file lab/.env -f docker-compose.lab.yml down -v
rm -rf /opt/emqx-lab
```
**回滚（生产，若已做阶段 A）**：把 `cluster.discovery_strategy` 改回 `manual`、移除第二节点服务块与
其卷，重建 node1；单节点行为与集群化前一致（data 卷未动）。

## 6. 未核实 / 阻塞

1. **跨节点会话接管未实测**：需 `clean_start=false` 客户端在 node1 建会话后重连到 node2，
   验证订阅保留/离线消息/未确认 QoS≥1 消息的语义。实验床只验证了**路由**。
2. **认证/ACL 数据复制未实测**：实验床 `enable_authn=false` 是为隔离来源/限流判据；
   生产集群前必须补测"在 node1 建用户、node2 可鉴权"。
3. **生产未落地**：中间件机未显式授权、且需维护窗口；本任务只交付可复现实验 + 方案。
4. **性能/容量未测**：多节点吞吐、LB 连接分布、跨节点转发开销均未压测（既有 `phase2-loadtest.sh`
   是单节点口径）。
5. 集群模式官方提示"仅在有有效 License 时可用"——那是 **Enterprise** 的措辞；本实验用的是
   **开源 5.8.9**，static 成簇已实测可用，故该提示不构成 OSS 阻塞（**已实测**，非推断）。
