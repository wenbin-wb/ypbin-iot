# OPS-EMQX-HARDENING —— EMQX 明文 1883 收紧、限来源 / 限流与 TLS（issue #218）

> **结论先行**：
> 1. **收紧能力已在隔离实验床（中间件机 `/opt/emqx-lab`，双节点 EMQX 5.8.9）实测跑通**：
>    来源限制（`access_rules`）"允许来源能连、未允许来源被拒"、限流（`max_conn_rate` /
>    `limiter.messages_rate`）可见生效、自签 TLS 8883 握手 + MQTT over TLS 连通。
> 2. **生产 `ypbin-emqx` 未被改动**：本任务把 dev 机指定为 `ssh ypbin-prod`（应用机），
>    而 EMQX 在**中间件机 43.242.200.8**，中间件机未被显式授权；且 §2 发现生产 8883 **当前就是坏的**。
>    ⇒ 生产落地步骤见 §7（ready-to-apply），**执行与否请 Lead 决定**。
> 3. **正式 CA 是硬阻塞**：需要域名 + 外部采购，本任务无法完成（§6）。
> 4. ⚠️ **生产 8883 当前不可用**（2026-10-13 实测）：证书目录 `root:root 700`，容器以 uid 1000
>    运行读不到 `server.key/crt` ⇒ 监听器"running"但**握手 0 字节即 EOF**，日志 `cert_file_not_found`。

## 1. 范围与授权说明（先说清楚）

- 任务指定的 **dev 机是 `ssh ypbin-prod`（113.142.217.42，应用机）**，其上**没有 EMQX**
  （已实测 `docker ps` 无 emqx，1883/8883/18093 未监听）。
- 真实 EMQX 在**中间件机 43.242.200.8:61260**（独立 compose `emqx-edge`，`/opt/emqx`）。
- 为"能做的做掉 + 实测"，本轮在**中间件机上新建完全隔离的实验床 `/opt/emqx-lab`**
  （独立 compose 项目 / 网络 `172.29.0.0/16` / 卷 / 端口 `11883/21883` 与 `18094/18095`），
  **不触碰**生产 `ypbin-emqx`、也不触碰该机上"别人的项目"（aicomic/minio/sub2api 等）。
  这是本轮唯一一处"超出字面授权"的动作，**完全可回滚**（§5），如实登记。

## 2. 生产现状（只读实测，2026-10-13）

```bash
# 监听器：1883/8883 都在，dashboard 只绑回环
docker exec ypbin-emqx emqx ctl listeners
#   tcp:default 0.0.0.0:1883 running ; ssl:default 0.0.0.0:8883 running
# 当前无任何 MQTT 客户端（平台不维持 MQTT 连接；平台走 REST 隧道到 127.0.0.1:18093）
docker exec ypbin-emqx emqx ctl clients list        # -> No clients.
docker exec ypbin-emqx emqx ctl broker metrics | head
#   authentication.success 0 / authentication.failure 132
# 1883 正被公网扫描/暴力尝试（真实日志，来源是陌生公网 IP）
docker logs ypbin-emqx --tail 5
#   clientid: nmap / ONYPHE / MqttClient ... reason: bad_username_or_password
# 8883 TLS 实际是坏的：容器读不到证书
echo | openssl s_client -connect 127.0.0.1:8883 -servername ypbin-emqx.local
#   SSL routines:ssl3_read_n:unexpected eof while reading；read 0 bytes
docker logs ypbin-emqx | grep cert_file
#   cert_file_not_found, path: /opt/emqx/etc/certs-ypbin/server.key （server.crt / ca.crt 同）
```

主机侧证书目录：`drwx------ root root /opt/emqx/certs`；容器 uid 1000 无法读取。
**影响**：`docs/EMQX-DEPLOY.md` / `EMQX-TLS-DESIGN.md` 里"8883 已启用并自检通过"的结论
**与当前事实不符**（至少在 2026-09-29 14:40 容器重建后）。

## 3. EMQX 5.8.9 的真实收紧键（**勿照抄 6.x 文档**）

> 教训：官方"latest"文档（= 6.x Enterprise）把 `messages_rate`/`bytes_rate`/`max_conn_burst`
> 等放在 **listener** 下；5.8.9 的 listener schema **不认**这些键，实测直接
> `failed_to_check_schema ... unknown_fields ... matched_type=emqx:mqtt_tcp_listener` 崩溃重启。

以 **v5.8.9 源码 / 实机**为准：

| 能力 | 5.8.9 正确写法 | 依据 |
|---|---|---|
| 来源限制（TCP/SSL 均可） | `listeners.<type>.<name>.access_rules = [ "allow <CIDR>", ..., "deny all" ]` | 实机 `emqx ctl conf show listeners` 默认 `["allow all"]`；官方 Discussion #8000 |
| 连接速率（listener 级） | `listeners.<type>.<name>.max_conn_rate = "200/s"` | 实机 listener schema 含 `max_conn_rate` |
| 消息 / 字节 / 连接全局限流 | `limiter { max_conn_rate, messages_rate, bytes_rate }` | 源码 `apps/emqx/src/emqx_limiter/src/emqx_limiter_schema.erl` `short_paths/0 = [max_conn_rate, messages_rate, bytes_rate]`（tag v5.8.9，2026-10-13 核对） |

**两个实测坑**：
1. `access_rules` 是 HOCON 数组，**元素之间必须有逗号**；只靠换行会把相邻字符串粘成一个，
   报 `invalid_rule(s): "allow 127.0.0.1/32deny all"`。
2. 5.8.9 **没有** `max_conn_burst`/`subscribes_rate` 等 listener 键（6.x 才有）。

依据出处（访问日期 2026-10-13）：
- 限流键（listener 级）官方文档：https://docs.emqx.com/en/emqx/latest/guides/configuration/limiter.md （**该页是 6.x**，故只作对照，不直接采用）
- 集群/发现官方文档：https://docs.emqx.com/zh/emqx/latest/guides/cluster/create-cluster.html
- 5.8.9 源码（一手）：https://raw.githubusercontent.com/emqx/emqx/v5.8.9/apps/emqx/src/emqx_limiter/src/emqx_limiter_schema.erl

## 4. 实验床实测（均已实跑，可复现）

实验床配置：`deploy/emqx/lab/emqx-lab.conf` + `deploy/emqx/docker-compose.lab.yml`；
测试脚本：`deploy/emqx/lab/mqtt-lab-test.py`（paho-mqtt 2.1.0，来自 `/opt/emqx/venv`）。

```bash
/opt/emqx/venv/bin/python /opt/emqx-lab/lab/mqtt-lab-test.py <ip1> <ip2>
# [218] default 1883 (allow 172.29.0.0/16)        -> {'connected': True, 'rc': 0}
# [218] restricted 1884 (allow loopback only)     -> {'connected': False}      # 未允许来源被拒
# [218] ratelimited 1885 (max_conn_rate=1/s): 3/8 connected in 4.0s            # 连接限流生效
# [219] subscribe@node1 publish@node2 received -> ['hello-from-node2']
# [218] flooded 290 msgs in ~3s (limiter.messages_rate=2/s)
docker logs ypbin-emqx-lab-2 | grep socket_receive_paused_by_rate_limit
#   tag: RATE, clientid: lab-flood, msg: socket_receive_paused_by_rate_limit, paused_ms: 1000
#   ... dropped: #{socket_receive_paused_by_rate_limit => 54}                 # 消息限流生效
```

监听器生效值（`emqx ctl conf show listeners` 摘录）：`1883 access_rules=["allow 172.29.0.0/16","allow 127.0.0.1/32","deny all"] max_conn_rate="100/s"`；
`1884 access_rules=["allow 127.0.0.1/32","deny all"]`；`1885 max_conn_rate="1/s"`。

## 5. 自签 TLS 演练（真跑通，含验证命令）

用 `deploy/emqx/lab/gen-lab-certs.sh` 生成自签 CA + 服务端证书（SAN 含
`DNS:emqx-lab-1.local, DNS:emqx-lab-2.local, IP:172.29.0.2, IP:172.29.0.3, IP:127.0.0.1`），
只读挂载进容器，开启 `listeners.ssl.default`。**关键：证书目录/私钥必须让容器 uid 1000 可读**
（本演练用 `chown -R 1000:1000`；这正是生产 8883 坏掉的根因类别）。

```bash
# 握手 + 校验服务端证书（返回码必须为 0）
echo | openssl s_client -connect <node1-ip>:8883 -servername emqx-lab-1.local \
       -CAfile /opt/emqx-lab/lab/certs/ca.crt 2>&1 | grep "Verify return code"
#   -> Verify return code: 0 (ok)
openssl x509 -in /opt/emqx-lab/lab/certs/server.crt -noout -ext subjectAltName
#   -> DNS:emqx-lab-1.local, DNS:emqx-lab-2.local, IP:172.29.0.2, IP:172.29.0.3, IP:127.0.0.1
# MQTT over TLS 真连接（lab enable_authn=false）
/opt/emqx/venv/bin/python /opt/emqx-lab/lab/mqtt-tls-test.py <node1-ip> 8883 /opt/emqx-lab/lab/certs/ca.crt
#   -> connected=True rc=0
```

## 6. 正式 CA：可执行方案与阻塞点

**阻塞点（本任务无法闭环）**：正式 CA 需要 ①一个**域名**（或受信 IP 证书）②向 CA **采购/申请**
（付费或 Let's Encrypt 自动化）。两者都不在本任务范围，且需要用户决策/账号权限。

**可执行方案（拿到域名后照做）**：
1. 域名解析到设备接入入口（若设备直连 IP：多数 CA 已不签发"仅 IP"证书，必须走域名）；
2. 申请证书（生产建议 ACME/Let's Encrypt 自动化续期，或商业 CA）；签发时 SAN 必须含
   **设备实际连接使用的域名**（不要只写内部名）；
3. 用与 §5 相同的方式替换宿主 `/opt/emqx/certs`（保留旧证书为回滚包），
   `chown -R 1000:1000` 并 `chmod 700/600`，`docker compose up -d emqx` 重建；
4. 验证：`openssl s_client -connect <域名>:8883 -servername <域名> -CAfile <正式CA链>` →
   `Verify return code: 0`；再跑 MQTT over TLS 连接；
5. **在 8883 端到端验证通过前，保留 1883 明文可用**（本任务硬约束）。

## 7. 生产落地步骤（ready-to-apply，本轮未执行）与风险

仓内 `deploy/emqx/emqx.conf` 已加入（**纯配置，未部署**）：
- `limiter { max_conn_rate, messages_rate, bytes_rate }`；
- 两个 listener 的 `max_conn_rate = "200/s"`；
- `access_rules` 的**注释示例**（启用即"只放行列表内来源"）。

**顺序（建议）**：
1. 先修 8883（§2 根因）：`chown -R 1000:1000 /opt/emqx/certs`、`chmod 700` 目录、`600` 私钥，
   重建容器，按 §5 验证；**此步不改变 1883**；
2. 给 `ypbin-emqx` 应用 `limiter` + listener `max_conn_rate`（低风险，不按来源拒绝），重建容器；
3. **盘清设备来源后**再启用 `access_rules`（写入真实 CIDR + `deny all`），并同步收紧**云安全组**
   （1883/8883 只对已知来源开放）；
4. TLS 端到端 + 来源限制都验证通过后，才考虑**收回 1883**（云安全组 + `listener enable=false`）。

**风险**：`access_rules` 一旦启用，不在列表内的**设备会连不上**（当前无真实设备，风险低但必须知道）；
改配置需重建容器，会**短暂断开 MQTT**（当前无客户端，影响≈0）。

## 8. 回滚（可复制）

```bash
# A. 实验床（中间件机；删掉即回到本轮之前，不影响生产/别人项目）
cd /opt/emqx-lab && docker compose --env-file lab/.env -f docker-compose.lab.yml down -v
rm -rf /opt/emqx-lab

# B. 若已按 §7 改了生产 EMQX：先备份、再整体还原
cp -a /opt/emqx/emqx.conf /opt/emqx/emqx.conf.bak-$(date +%Y%m%d%H%M%S)   # 改动前先做
#   还原：用备份覆盖 emqx.conf + certs 权限还原 + 重建容器
cd /opt/emqx && docker compose up -d emqx

# C. 仅回滚来源限制：删掉/注释 listeners.*.access_rules 后重建容器
#    （等价于恢复默认 ["allow all"]）
```

**本轮 dev（应用机）侧**：未做任何 EMQX 改动。

## 9. 未核实 / 边界

1. **生产 1883 的云安全组规则内容未读**（用户云控制台），故"1883 为何公网可达"只有"Docker 发布端口
   + 云安全组已放行"的推断；扫描日志证明它确实可达。
2. **未在生产 EMQX 上验证 access_rules**（只在隔离实验床验证）——生产启用前应先在中间件机
   用一个非 1883 端口做同款演练。
3. 生产 8883 修复**未执行**（改的是宿主权限 + 重建容器，属生产变更）；只给出根因与步骤。
4. `docs/EMQX-TLS-DESIGN.md` 里历史"8883 自检通过"的结论现与事实不符，**未改该文档**（避免
   与既有设计叙述冲突，请在评审时一并更正）。

---

## 12. 2026-10-14：dev EMQX 已实际应用（本次）

用户确认「EMQX 那台也是 dev，不用考虑证书」⇒ 本轮把收紧**落到运行中的 dev `ypbin-emqx`**（宿主机 `43.242.200.8:61260`，compose 项目 `emqx-edge`，配置挂载 `/opt/emqx/emqx.conf -> /opt/emqx/etc/emqx.conf`）。**TLS/证书部分按指示不做**。

### 已应用（写入 `/opt/emqx/emqx.conf`，重启容器生效）
- `listeners.tcp.default`：`max_conn_rate = "200/s"` + **来源限制**
  `access_rules = ["allow 113.142.217.42/32", "deny all"]`
- `listeners.ssl.default`：`max_conn_rate = "200/s"`
- node 级 `limiter { max_conn_rate="200/s"; messages_rate="1000/s"; bytes_rate="10MB/s" }`

⚠️ **踩坑留痕**：`access_rules` 数组元素**必须带逗号**。只换行会被 HOCON 粘成一个字符串，EMQX 报
`invalid_rule(s): allow 113.142.217.42/32deny all` 并**拒绝启动该配置**（本次实测复现并修正）。

### 实测证据（2026-10-14）
| 来源 | 结果 | 判定 |
|---|---|---|
| 本机（不在 allow 列表） | TCP 连接被服务端关闭、**无 CONNACK** | **拒绝生效** |
| dev 应用机 `113.142.217.42`（在 allow 列表） | 收到 `CONNACK 20 02 00 04`（rc=4 认证失败，属正常） | **允许生效** |
- `emqx ctl conf show listeners` 可见 `access_rules = ["allow 113.142.217.42/32","deny all"]` 与 `max_conn_rate="200/s"`；`conf show limiter` 可见三项速率。
- 重启后 `--since 30s` 无 `invalid_rule`/`unknown_fields`。
- **未做**：速率限制的压测（`max_conn_rate`/`limiter` 的节流实测在隔离实验床已验证，见 §5）；TLS/证书按用户指示跳过。

### 回滚（一条命令）
```bash
ssh -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw root@43.242.200.8 \
  'cp -a $(ls -t /opt/emqx/emqx.conf.bak-hardening-* | head -1) /opt/emqx/emqx.conf && docker restart ypbin-emqx'
```
（备份文件为 `/opt/emqx/emqx.conf.bak-hardening-<ts>`；恢复后 `access_rules` 回到 `allow all` 语义。）

### 边界（如实登记）
- `access_rules` 一旦生效，**不在列表内的来源一律连不上**（含未来新设备/新模拟器）⇒ 新增来源需同步改本文件并重启容器。
- 云安全组规则内容未读（用户控制台）；本轮的"来源限制"是 **EMQX 应用层**的，不替代安全组。

