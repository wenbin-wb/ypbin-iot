# EMQX TLS 接入设计（MQTT over TLS / 8883）

> **状态**：✅ **已启用（2026-09-30）** —— MQTT over TLS 8883 **自签证书**、单向认证、
> **与明文 1883 并存**、宿主绑 `0.0.0.0`、listener `running=true`。
> 用户 **2026-09-30 决定：8883 保持开启**（"EMQX TLS 不用做" = **不再投入实施**，
> **≠ 关闭**；**关闭反而是有风险的状态变更** ✗）。本文既是**已生效状态的备案**，
> 也是后续正式化（正式 CA / 收回 1883 / 限来源）的**设计参照**。
> **建立日期**：2026-09-30（**2026-10-04 按实机复核更正为"已启用"事实口径**）
> **适用对象**：中间件机 `43.242.200.8:61260` 上的 EMQX 5.8.9（独立 compose 项目 `emqx-edge`，`/opt/emqx`）
> **上游依据**：`docs/EMQX-DEPLOY.md`、`docs/EMQX-INGRESS-DESIGN.md`、`docs/DEVICE-CREDENTIAL.md`
> **状态口径**：本文所有"现状"结论均标注证据出处；外部事实按 R2/R3 标注一手/二手与访问日期（见 §10）。

---

## 0. 结论先行

1. **8883 已启用并在跑（2026-09-30 实测，2026-10-04 复核）**：自签证书、单向认证、**与 1883 并存**、宿主绑 `0.0.0.0`。用户决定**保持开启**（"TLS 不用做" = **不再追加实施投入**，**不是关闭** ✗）。本文是**现状备案 + 后续正式化的设计参照**，**不是**待施工单；本轮**未对中间件机做任何状态变更**（判据见 §1.3）。
2. **明文 1883 仍在对外**（开发测试期，用户 2026-09-27 已授权，见 `docs/EMQX-DEPLOY.md:103` §3.1）⇒ **该窗口内用过的设备凭据一律视为已暴露**，必须轮换；"上 TLS / 收回端口"**只能阻止将来的嗅探，不能撤销已发生的暴露**（同 `docs/EMQX-DEPLOY.md:345-347`）。
3. **两条技术路线**：**A. EMQX 原生 TLS 8883**（容器内直接终止，改动最小、无新组件，见 §2.1）；**B. 前置代理/网关终止 TLS**（EMQX 侧零开销、可复用证书与限流，但多一跳与新组件，且本机前置面可能是**别人的宝塔**，改动需用户同意，见 §2.2）。**推荐 A**：与既有部署形态同构、可一行回滚。
4. **启用 8883 需要三层齐备**（缺一即"看起来开了、实际连不上"）：**① 容器内 listener 监听 + ② 宿主发布端口绑定 + ③ 云安全组放行**。其中 **②③ 才是真闸门** —— **该机 Docker 发布端口不经 `INPUT`/ufw**（实证见 §2.3），所以"加一条 ufw 规则"是**无效动作** ✗。
5. **证书路线**：开发测试期可用自签（**SAN 必须含公网 IP `43.242.200.8`**，否则客户端主机名校验失败）；**生产必须换正式 CA**（§3）。**首期建议只做单向认证（服务端证书 + 用户名口令），不做 mTLS** —— 设备侧装客户端证书成本高（§3.4）。
6. **迁移分四阶段、每阶段可回退**：双监听并存 → 设备逐步切换 → 观察期 → **收回 1883**（收回前**必须**先确认云安全组 8883 已放行，否则设备集体失联 ✗，§4）。
7. **本仓已备好且已投入使用的资产**：中间件机 `/opt/emqx/certs/` 与 `gen-certs.sh` / `emqx-tls-selfcheck.sh` / `emqx-tls-rollback.sh` 三脚本 ⇒ **整套启用套件已生效**（不是"预留/半成品"✗），见 §9。

---

## 1. 现状与风险

### 1.1 当前暴露面（一手证据）

| 端口 | 容器内 | 宿主侧 | 公网 | 说明 | 出处 |
|---|---|---|---|---|---|
| `1883` | `0.0.0.0:1883` | **`0.0.0.0`**（`EMQX_MQTT_BIND_ADDR`） | **可达**（从生产机实测） | **MQTT 明文**；开发测试期决策 | `docs/EMQX-DEPLOY.md:87`、§3.1（`:103`） |
| `18093` | `0.0.0.0` | `127.0.0.1`（回环） | 不可达（实测） | Dashboard + REST API；**管理面不对公网** | `docs/EMQX-DEPLOY.md:86` |
| `8883` | `0.0.0.0:8883` | **`0.0.0.0`**（`EMQX_MQTT_TLS_BIND_ADDR`） | **可达**（`ss` 实测宿主在听；公网侧见下注） | ✅ **MQTT over TLS（自签、单向认证）**；listener `running=true`，与 1883 并存 | §1.3（2026-10-04 复核） |
| `8083` / `8084` | `:8083` / `:8084` | 不发布 | 不可达 | WS / WSS，`enable=false`（**这两个才是未启用**） | §1.3（实测 `running=false`） |

> **vantage 声明（R1/R4）**：上表"公网可达"结论的探测视点是**生产机**（`ssh ypbin-prod`），
> 不是本工作机 —— 从本工作机探测该机**任何**端口都返回失败，方法本身不成立，
> 详见 `docs/EMQX-DEPLOY.md:539` 的 vantage 说明。

### 1.2 风险：明文下的口令与数据

- **链路窃听**：1883 无 TLS ⇒ **设备口令与业务数据以明文过公网**，链路上任何人可读取
  （`docs/EMQX-DEPLOY.md:141` 已登记为 🔴 风险）。
- 🔴 **用过的凭据视为已暴露**：这是本仓**既有的登记口径** —— `docs/EMQX-DEPLOY.md:345-347`
  明确写「1883 是**明文** MQTT ⇒ 验收期间用过的设备口令**视为已暴露**；"收回端口/上 TLS" 只能阻止
  **将来**的嗅探，**不能撤销已发生的暴露** ⇒ 必须轮换」。**该口径对本设计同样成立**：
  **凡在明文窗口内签发/使用过的设备凭据，在上 TLS 后仍必须轮换**，不能因为"现在加密了"就假定安全。
  - 既有处置：`docs/EMQX-DEPLOY.md:349-355` 记录了 2026-09-27 的轮换实测（旧版本被拒、新版本可用）。
  - 凭据本身的口径（一次性明文、库内只存 `sha256(password+salt)`）见 `docs/DEVICE-CREDENTIAL.md:14`（C2）。
- ⚠️ **降级风险（R8）**：TLS 只保护**传输层**。它**不**解决：设备凭据泄露后的重放（除轮换）、
  设备被物理攻破（可提取证书/口令）、以及**明文窗口内已泄露的凭据**（只能轮换）。

### 1.3 本文与"当前是否已启用"的关系（**以实机为准**）

> ⚠️ **本节是本文唯一需要随现网状态更新的地方。**
> 本文交付时的**实机判定**见下；后续任何人**启用/回滚** 8883 后，**必须回头更新本节**，
> 否则文档与实机将不一致（这正是上一批出现过的误导来源）。

**判定方法（层次从内到外，三层都要看）**：

```bash
# ⚠️ 注意：EMQX 在**中间件机**（43.242.200.8:61260，独立 compose 项目 emqx-edge），
#    不在应用机 113.142.217.58/113.142.217.42 上 —— 在应用机上跑这些命令只会得到"没有此容器"。
# ① 容器内 listener 真开关（**这一层才是真开关**）
docker exec ypbin-emqx emqx ctl listeners | grep -A6 'ssl:default'
# ② 宿主发布端口绑定（是否对外）
ss -lnt | grep 8883 ; docker inspect ypbin-emqx --format '{{json .NetworkSettings.Ports}}'
# ③ 容器内配置与 .env 声明是否一致
docker exec ypbin-emqx sh -c 'grep -A2 "^listeners.ssl.default" /opt/emqx/etc/emqx.conf'
grep '^EMQX_MQTT_TLS_BIND_ADDR' /opt/emqx/.env
```

> 🔴 **已知误判陷阱（务必避开，本文档的"前身"就栽在这里）**：
> `emqx ctl conf show listeners.ssl.default` 在 **5.8.9 上恒返回 `key_not_found`** ——
> **在用的 1883 用同样写法查 `listeners.tcp.default` 也返回 `key_not_found`**。
> ⇒ 该命令**不能**用来判定 listener 是否生效：**它对正常工作的 listener 一样报错**，
> 报错只说明"点号键路径在 5.8.9 不适用"，**与 listener 是否启用无关**。
> **正确写法是查父级组**：`docker exec ypbin-emqx emqx ctl conf show listeners`
> （一次列出 `ssl.default` 与 `tcp.default` 的完整生效值，含 `enable`/`bind`/`ssl_options`）；
> **首选判据是 `emqx ctl listeners` 的 `running` 字段**。
> ⚠️ **更隐蔽的是：两种写法退出码都是 0**（实测 `ssl_exit=0`、`tcp_exit=0`）⇒
> **既不能看输出文本、也不能看 `$?`**，只能换用正确的命令。
> 曾有人据 `key_not_found` 误判"8883 未启用"，**实际当时 8883 已在正常运行** ⇒
> 该误判差点导致对现网做多余改动（甚至误关 TLS）✗。

**实机判定（2026-09-30 首次实测，2026-10-04 复核确认）**：✅ **8883 已启用并在跑** ——
`emqx ctl listeners` 显示 `ssl:default running=true / enbale=true`（`listen_on 0.0.0.0:8883`）；
宿主 `ss -lnt` 有 `0.0.0.0:8883`；`docker inspect` 端口映射为 `{"8883/tcp":[{"HostIp":"0.0.0.0","HostPort":"8883"}]}`；
`/opt/emqx/.env` 含 `EMQX_MQTT_TLS_BIND_ADDR=0.0.0.0`；`emqx.conf` 的 `listeners.ssl.default.enable = true`；
`emqx ctl conf show listeners` 的生效值 `enable = true` / `bind = "0.0.0.0:8883"`。
**证书（2026-10-04 复核）**：自签，`CN=43.242.200.8`，`issuer=CN=ypbin-EMQX-SelfSigned-CA`，
SAN 含 `43.242.200.8 / 172.28.0.1 / ypbin-emqx / ypbin-emqx.local / localhost`，
有效期 `2026-09-29 → 2029-01-01`，`openssl verify -CAfile ca.crt server.crt` = **OK**；
私钥 600、目录 700，`.gitignore` 已排除 `certs/` ✔。
**对照**：同批实测 `ws:default` / `wss:default` 均为 `enable=false / running=false` ⇒ **未启用的只有 WS/WSS**。

> **公网可达性**：⚠️ **未从公网独立实测**（✗ 未验证项）。已实测的是"宿主在听"（`ss`）与
> "应用机到 `43.242.200.8:8883` 的 TCP 可连"（对端接受连接 ⇒ 至少非本机过滤）。
> 仓内 `deploy/emqx/docker-compose.yml:96` 登记"云安全组未放行 8883（从生产机实测 CLOSED）"，
> **与本批"应用机可连通"的实测不一致**，且**未读云控制台规则** ⇒ **该登记待复核，不作为结论**。

> ✅ **裁定已明确（2026-10-04 更新）**：用户决定 **8883 保持开启** ⇒ **不做任何变更**，
> 本文档仅备案现状。⚠️ **不要**因为"TLS 不用做"就去执行 §7 的回滚 ✗ ——
> **关闭 8883 是有风险的状态变更**（会中断任何已用 TLS 的客户端），且与用户决定相反。
> §7 的回滚步骤**仅在**将来确实需要停用 TLS 时使用（例如迁移到方案 B）。

---

## 2. 方案：两条路线与取舍

### 2.1 方案 A：EMQX 原生 TLS 8883（**推荐**）

**做法**：在容器内的 `listeners.ssl.default` 上开 TLS，**与 1883 并存**；证书以只读方式挂进容器。

**配置骨架**（键名依据：官方《Listener Configuration》§Configure SSL Listener 与
《Enable SSL/TLS Connections》§Enable via Configuration File，见 §10 一手来源）：

```hocon
listeners.ssl.default {
  enable = true                      # ← 容器内真开关
  bind = "0.0.0.0:8883"
  max_connections = 10240
  ssl_options {
    certfile = "/opt/emqx/etc/certs-ypbin/server.crt"   # 容器内路径
    keyfile  = "/opt/emqx/etc/certs-ypbin/server.key"
    cacertfile = "/opt/emqx/etc/certs-ypbin/ca.crt"
    verify = verify_none             # 单向认证：不校验客户端证书
    fail_if_no_peer_cert = false     # 单向认证配套取值
    versions = ["tlsv1.3", "tlsv1.2"]
  }
}
```

- 官方对 `verify` / `fail_if_no_peer_cert` 的确切语义：`verify_none` = **不**校验客户端证书（单向）；
  `verify_peer` + `fail_if_no_peer_cert = true` = 双向/mTLS。`false` 时**仅在客户端送了无效证书时才拒**，
  **不送证书视为合法**（§10 一手来源原文）。
- **与 1883 并存**：两个 listener 相互独立、各自监听，互不影响 ⇒ 便于**灰度**（§4）。

**优点**：无新增组件；与既有部署形态同构（同一 compose、同一 `.env` 开关模式）；可**一行回滚**。
**缺点**：TLS 握手开销落在 EMQX 自己身上（官方指出的取舍，见 §2.2 表）；证书**每个 listener 单独配**，
将来多节点/多 listener 要重复配置。

### 2.2 方案 B：由前置代理/网关终止 TLS

**做法**：TLS 在 EMQX **之前**终止（如 nginx `stream` 模块、云 LB、HAProxy），EMQX 侧仍收明文 MQTT。

| | 方案 A：EMQX 原生 8883 | 方案 B：前置代理终止 TLS |
|---|---|---|
| 新增组件 | 无 | 需要代理/LB（多一跳、多一个要运维的东西） |
| EMQX 负担 | TLS 握手在 EMQX（官方：连接数巨大时 CPU/内存上升） | EMQX 侧**无 TLS 开销**，且**自带负载均衡能力** |
| 官方评价 | 「易用，无需额外组件」 | 「对 EMQX 性能无影响，并提供负载均衡；但**只有少数云厂商的 LB 支持 TCP SSL/TLS 终止**，否则需自行部署 HAProxy」 |
| 证书与限流复用 | 仅此 listener | 可与既有 Web 证书/限流策略统一 |
| 客户端看到的源 IP | 直接 TCP peer | ⚠️ 需正确配置转发头/PROXY protocol，否则**源 IP 可被伪造**（官方就此有专门警告，见 §10） |
| 适用场景 | 单节点、连接规模不大、要最小改动 | 已有成熟网关/多节点、需统一入口与 LB |

> ⚠️ **本机方案 B 的特殊约束（R8）**：中间件机上确实有**宝塔**（nginx）面，但那是**别人的项目**
> （与 `aicomic-*`/`sub2api` 同级）⇒ **改宝塔 vhost/stream 需用户明确同意**，本项目**不得擅自动**
> （与 `docs/EMQX-DEPLOY.md` §3.1 的"一律不碰"口径一致）。若走 B，建议**独立部署**自己的代理，
> 不复用宝塔面。

**推荐 A** 的理由：与既有部署形态同构、blast radius 最小、可一行回滚；
B 的价值主要在**多节点/多协议统一入口**场景，当前单节点不构成需求。

### 2.3 🔴 启用 8883 的"三层齐备"（缺一即连不上）

| 层 | 动作 | 不做的后果 |
|---|---|---|
| ① **容器内** | `listeners.ssl.default.enable = true` + `bind` | listener 不启动（`running=false`），容器内根本没这个端口 |
| ② **宿主发布** | compose `ports:` 映射 + 绑定地址（`EMQX_MQTT_TLS_BIND_ADDR`） | 端口不进 `nat/DOCKER` DNAT ⇒ **外部连不上**（只有容器内可见） |
| ③ **云安全组** | 控制台放行 `tcp/8883`（来源按需收敛） | 宿主在听、DNAT 也在，但**云侧丢弃** ⇒ 外部仍连不上 |

**🔴 关键机制事实（本仓实证，不是推测）**：**该机 Docker 发布端口不经 `INPUT`/ufw**
—— 外部流量在 `nat/PREROUTING -j DOCKER` 被 DNAT、在 `filter/FORWARD -j DOCKER` 被 ACCEPT，
**根本不经过 `INPUT`** ⇒ 删/加宿主 `INPUT` 规则对可达性**没有影响**（实测证伪：
撤掉 1883 的 INPUT 规则后从外部**仍可达**，`docs/EMQX-DEPLOY.md:146`、`:154`）。
⇒ **真闸门 = ②绑定地址 + ③云安全组**；要按来源收敛必须写 **`DOCKER-USER`** 或**云安全组**，
**写在 ufw/`INPUT` 上是无效动作** ✗（`docs/EMQX-DEPLOY.md:178-190`）。
> ⚠️ 该结论**仅对 Docker 发布端口成立**；同机非 Docker 端口仍受 ufw 管辖。

**IPv6 提醒（R8）**：`DOCKER-USER` 在 v4/v6 是**两套表**；该机已有 5 个端口带 `[::]` 监听
（`docs/EMQX-DEPLOY.md:200-205`）⇒ 限源规则**必须同时覆盖 IPv4 与 IPv6**，否则 IPv6 侧不受保护。

---

## 3. 证书策略

### 3.1 自签（开发测试期可用）vs 正式 CA（生产必须）

| | 自签 | 正式 CA（如 Let's Encrypt / 云厂商 DV） |
|---|---|---|
| 客户端是否默认信任 | **否** ⇒ 必须显式导入 `ca.crt`，否则握手失败 | 是（公开根 CA 已被系统信任库收录） |
| 私钥位置 | 自签 CA 私钥就在本机 ⇒ **一旦泄露可签发任意"合法"证书** | CA 私钥不在本机 |
| 吊销 / 轮换 | 无 CRL/OCSP、无自动轮换、有效期需人工看守 | 支持吊销与自动续期 |
| 适用 | **仅开发测试期** | **生产必须** |

> ⚠️ 用 `--insecure`（关校验）"绕过"信任问题 = **放弃服务端身份验证** ⇒ 可被中间人替换，
> **绝不可用于生产**（`deploy/emqx/gen-certs.sh` 头部已用同样措辞警示）。

### 3.2 SAN 必须包含实际连接用的名字

客户端会用**它连接时使用的地址**去校验证书 SAN，不匹配即报
`ERR_TLS_CERT_ALTNAME_INVALID`（官方文档给出了该错误原文，见 §10）。
⇒ SAN **必须至少包含**：

- **公网 IP `43.242.200.8`**（设备经公网直连时的实际连接目标 ⇒ **必须**，这是最容易漏的一条）；
- `ypbin-emqx` / `ypbin-emqx.local`（容器名/同网桥内互连）；
- `localhost` + `127.0.0.1`（本机自检）。

> **运维建议（R8）**：更稳的做法是**给 broker 一个域名**（如 `mqtt.example.com`）并让设备连域名，
> 这样换 IP/加节点时只改 DNS，**不必给每台设备换证书**。域名方案下 SAN 用 DNS 名即可，
> 但**若仍有设备直连 IP，SAN 里的 IP 项不能删**。

### 3.3 有效期与轮换

- **有效期**：自签产物当前为 **2029-01-01 到期**（本批实测，见 §9）；正式 CA 通常 90 天（LE）。
- **轮换时设备侧信任库怎么更新**（关键，最容易被忽略）：
  - **换服务端证书、CA 不变**（同一 CA 重签）⇒ 设备侧**无需任何改动** ✔（首选做法，代价最小）。
  - **换 CA** ⇒ **每台设备都必须更新信任的 `ca.crt`** ⇒ 面向大批设备是**高风险动作**，
    必须**先灰度**（新旧 CA 并行/双证书，或分批次），并预留回退 ⇒ 强烈建议**避免频繁换 CA**。
  - ⇒ **落地建议**：自签 CA 的**有效期给长**（如 10 年），**只轮换服务端证书**；
    这样设备侧只装一次 CA。
- **轮换窗口**：轮换动作本身会造成**短暂不可用**（新证书生效与设备重连之间）⇒ 安排在**设备侧可容忍的窗口**。

### 3.4 单/双向认证取舍（**首期建议：单向**）

| | 单向（服务端证书 + 用户名口令） | 双向 / mTLS（额外要求客户端证书） |
|---|---|---|
| 客户端证书 | 不需要 | **每台设备都要有**（签发、灌装、轮换、吊销全生命周期） |
| 能证明 | 服务器身份 + **通道加密** + 口令认证设备 | 额外再证明**设备持有私钥** |
| 设备侧成本 | 低（导入 `ca.crt` 即可） | **高**：产线灌装、安全存储私钥、轮换链路、丢失即需重签 |
| 适配现状 | 与**既有**平台签发口令链路（`docs/DEVICE-CREDENTIAL.md`）**直接兼容**，无需改设备 | 需重建设备侧凭据体系 |

**建议：首期单向** —— 理由：① 设备侧改造成本低（这是最大约束）；② 现有**平台签发一次性口令 + ACL 默认拒绝**
的鉴权/授权体系**已经生效且经生产验证**（`docs/EMQX-DEPLOY.md:126`：匿名被拒、越权被拒），
TLS 补的是**传输层加密**这一块，两者组合已覆盖主要风险；③ mTLS 的边际收益（防口令泄露重放）
可通过**轮换**缓解，而**成本是数量级的**。
> ⚠️ 但**须知**：单向认证下，**口令仍然是"持有的秘密"** —— 一旦泄露仍可被冒用。
> 若设备侧具备安全存储（TEE/安全芯片）且产线能灌装，**中期**可评估 mTLS。

### 3.5 私钥管理

- 宿主侧目录 `/opt/emqx/certs` 权限 **700**，私钥文件 **600**（本批实测）；
- **不入仓**：`deploy/emqx/.gitignore` 已排除 `certs/`（本批已核实 ✔，见 §9）；
- 容器内**只读**挂载（`ro`）；
- **备份**：私钥与证书需离线备份（丢失私钥 ⇒ 服务端无法启动 TLS；丢失 CA 私钥 ⇒ 无法重签同 CA 证书）；
- 🔴 **不得打印私钥内容**；协作中只报**路径 / 权限 / 指纹**（sha256 fingerprint）✔。

---

## 4. 迁移计划（分阶段、可回退）

> 原则：**每阶段都有判据与回退动作**；**任何阶段都不删数据卷**。

### 阶段① 双监听并存（1883 + 8883）

- **动作**：按 §2.1 配 `listeners.ssl.default`；compose 发布 8883；云安全组放行 `tcp/8883`（来源按需收敛）。
  **1883 保持不动** ⇒ 存量设备**零影响**。
- **判据**：① `emqx ctl listeners` 中 `ssl:default` 与 `tcp:default` **同时** `running=true`；
  ② 宿主 `ss -lnt` 同时有 1883 与 8883；③ `openssl s_client` 能从 **外部**（生产机视角）拿到证书链；
  ④ 1883 存量链路**指标不退化**（如 `iot.timeseries.write.rows` 持续增长）。
- **回退**：`bash /opt/emqx/emqx-tls-rollback.sh`（撤回 8883 发布 + 关 listener + 重建容器），1883 不受影响。

### 阶段② 设备/模拟器逐步切 8883

- **动作**：先切**模拟器**，再按批次切真设备；设备侧导入 `ca.crt`（自签场景）并改用 `mqtts://…:8883`。
- **判据**：8883 上**连接数上升**、1883 上**连接数下降**；经 8883 的**上行数据正常落库**
  （`iot.timeseries.write.rows` 增长）；**鉴权与 ACL 在 8883 上同样生效**（负向用例：无凭据/错凭据被拒）。
- **回退**：把该批设备改回 1883（**此时 1883 仍在** ⇒ 回退成本极低，这是本阶段刻意保留 1883 的原因）。

### 阶段③ 观察期

- **动作**：**观察期不缩短**（建议 ≥1 个完整业务周期）。保持双监听。
- **判据**：8883 侧**握手失败率**、**异常断连**、**证书告警**均无异常；1883 侧残留连接**仅剩待迁移设备**（逐一登记）。
- **回退**：同阶段②。

### 阶段④ 收回 1883

> 🔴🔴 **前置硬条件（未满足则设备集体失联，不可回退到位）**：
> **必须先确认云安全组已放行 `tcp/8883`**。理由：① 该机 Docker 端口**不经 INPUT**（§2.3），
> 收回 1883 后再发现 8883 不通，**没有任何本机层可补救**；② 收回动作后**旧通道立刻消失**。
> ⇒ **放行 8883 是"收回 1883"的入门券，顺序不可颠倒** ✗。

- **动作**：把 `EMQX_MQTT_BIND_ADDR` 改回 `127.0.0.1`（或 `listeners.tcp.default.enable=false`+不发布），重建容器。
- **判据**：① 从**外部**（生产机）探测 1883 ⇒ **CLOSED**；② 8883 侧连接数 ≈ 收回前 1883+8883 总和；
  ③ `iot.timeseries.write.rows` **不中断**；④ 旧客户端（仍按 1883 配的）**确实连不上**（§5 判据）。
- **回退**：把绑定改回 `0.0.0.0` + 重建容器（**分钟级**）⇒ 因此**必须在有回退窗口时执行**。

---

## 5. 验收判据（可测）

> 口径：**每条都要有真实命令输出**，不接受"没报错就算过"（与 `emqx-tls-selfcheck.sh` 同风格）。

| # | 判据 | 方法（示例） | 期望 |
|---|---|---|---|
| V1 | **TLS 握手成功、能取到证书链** | `openssl s_client -connect 43.242.200.8:8883 -servername ypbin-emqx.local -showcerts </dev/null` | 返回证书链；`Verify return code: 0 (ok)`（自签需 `-CAfile ca.crt`） |
| V2 | **看到的证书 = 我们装的那张** | 取 V1 输出证书的 sha256 指纹 ↔ `/opt/emqx/certs/server.crt` 指纹 | **一致** |
| V3 | **SAN 含实际连接地址** | `openssl x509 -in server.crt -noout -ext subjectAltName` | 含 `43.242.200.8`（及所用域名） |
| V4 | **鉴权仍生效** | 无凭据 / 错凭据经 8883 连接 | 被拒（CONNACK rc≠0 或断开）；EMQX 侧 `connack.auth_error` 增长 |
| V5 | **平台签发凭据可连接并受 ACL 约束** | 用平台签发的设备账号经 8883 连；发**自己**主题 / 发**别人**主题 | 自己主题放行；别人主题被拒（看 EMQX 侧指标，**不要只看客户端返回码**） |
| V6 | **旧客户端在 1883 收回后确实连不上** | 收回后按 1883 配置连接 | **失败**（连接被拒/超时） |
| V7 | **证书轮换后设备仍能连** | 同 CA 重签（`gen-certs.sh --force` 或正式 CA 续期）后，设备**不改配置**重连 | **成功**；若换了 CA，则需更新设备信任库后成功 |
| V8 | **1883 存量链路未退化**（阶段①②） | 采集指标 | `iot.timeseries.write.rows` 持续增长、`write.failed=0` |

> ⚠️ **两条已知判据陷阱（沿用既有登记，勿踩）**：
> ① **不要用 `emqx ctl conf show listeners.ssl.default` 判 listener 是否生效** —— 5.8.9 上恒 `key_not_found`，
> **且对正常工作的 1883 同写法也报错、退出码同样是 0** ⇒ 该命令与 listener 状态**无关**，
> 正确判据是 `emqx ctl listeners` 的 `running` 字段（§1.3）。
> ② **MQTT 3.1.1 客户端的越权 SUBSCRIBE/PUBLISH 看不到任何客户端侧错误**（SUBACK `Unspecified error`、
> `deny_action=ignore` 下 publish 静默）⇒ **验收判据必须看 EMQX 侧指标**（`authorization.nomatch`），
> 否则会把"其实拦住了"误判成"没拦住"或反之（`docs/EMQX-DEPLOY.md:493-496`、U-N）。

---

## 6. 可观测与巡检

| 观测项 | 手段 | 说明 |
|---|---|---|
| **8883 监听状态** | `emqx ctl listeners` 的 `ssl:default.running`；宿主 `ss -lnt` | 两层都看：容器内"在跑"≠"对外可达" |
| **声明 vs 现实一致性** | 比对 `.env` 的 `EMQX_MQTT_TLS_BIND_ADDR` ↔ 宿主监听 ↔ `nat/DOCKER` DNAT | 防"静默漂移成对外暴露"（与 `emqx-mqtt-expose-watch.sh` 的 A6 判据同构，见 §9） |
| **握手失败率** | EMQX 侧 TLS/连接失败指标；容器日志 `docker logs ypbin-emqx \| grep -i ssl` | 失败率突增通常指向证书过期/私钥不可读/版本不匹配 |
| **证书到期提醒** | `openssl x509 -in /opt/emqx/certs/server.crt -noout -enddate`；剩余天数 < 阈值告警 | **只告警，不自愈** ✗ —— 不自动重签（重签会换指纹，可能影响设备） |
| **鉴权拒绝计数** | `client.auth.anonymous` 恒 0；`connack.auth_error`；`authorization.nomatch` | 匿名必须恒 0 |

> **巡检原则（沿用既有口径）**：**只告警、不自愈**。理由：自愈（自动重签、自动改配置）在
> **信任根**上自动动作，风险高于收益；证书轮换必须**人工决策 + 可回退**。

---

## 7. 回滚

> **核心原则：只改配置、只重建 EMQX 容器，数据卷不动** ✔。

**一行式回滚**（等价物，已备好）：

```bash
bash /opt/emqx/emqx-tls-rollback.sh
```

它做三件事（幂等，可重复执行）：
① `/opt/emqx/.env` 的 `EMQX_MQTT_TLS_BIND_ADDR` 改回 `127.0.0.1`；
② `/opt/emqx/emqx.conf` 的 `listeners.ssl.default.enable` 改回 `false`；
③ 重建**仅 EMQX 容器**（`docker compose up -d emqx`）。

**手动等价写法**（不改脚本）：

```bash
sed -i 's/^EMQX_MQTT_TLS_BIND_ADDR=.*/EMQX_MQTT_TLS_BIND_ADDR=127.0.0.1/' /opt/emqx/.env
sed -i '/listeners.ssl.default {/,/^}/ s/enable *= *true/enable = false/' /opt/emqx/emqx.conf
cd /opt/emqx && docker compose up -d emqx
```

**回滚后校验**：宿主 `ss -lnt` 不再有 `0.0.0.0:8883`；`emqx ctl listeners` 中 `ssl:default.running=false`；
**1883 与平台链路不受影响**（`write.rows` 继续增长）。

- ⚠️ **数据卷不动**：`emqx-data` / `emqx-log` **不删**（会话、保留消息、认证/ACL 数据都在里面）
  ✗ 禁止 `docker compose down -v`，✗ 禁止任何 `prune`。
- ⚠️ **重建 EMQX 容器会波及平台管理面**（既有实测）：重建后平台的**空闲期首次下发**可能失败
  （h2c upgrade 缺陷，平台已以 HTTP/1.1 规避，`docs/EMQX-DEPLOY.md:214-236`）⇒ 回滚后**确认下行动作正常**。
- **彻底移除 TBD 产物**（若将来确认不再需要 TLS）——**本批不执行** ✗，仅登记：

  ```bash
  rm -rf /opt/emqx/certs /opt/emqx/gen-certs.sh /opt/emqx/emqx-tls-selfcheck.sh /opt/emqx/emqx-tls-rollback.sh
  ```

  （执行前**必须先**完成回滚，并确认无任何配置引用这些路径；`certs/` 内含私钥，删除即**不可恢复**，
  且**已签发设备凭据不受影响**但**证书将无法再用**。备份优先于删除。）

---

## 8. 刻意不做（防误报，非缺口）

| 项 | 为什么不做 |
|---|---|
| **集群 TLS**（节点间 Erlang distribution TLS / 多节点） | 当前**单节点**（不组集群），无节点间链路需要保护；且该机 4370/5370 **刻意不发布** |
| **双向认证（mTLS）**（首期） | 设备侧成本高（见 §3.4）；首期用单向 + 口令已覆盖主要风险 |
| **mTLS 自动化签发**（设备证书全生命周期） | 依赖 mTLS 先成立；属于**设备凭据体系重建**级工程，非 TLS 接入范围 |
| **WSS / 8084** | 面向**浏览器**直连；当前设备走原生 MQTT，无此需求；且 `listeners.wss.default` 仍 `enable=false` |
| **ACME/自动续期** | 与"只告警不自愈"冲突（§6）；且自签场景不适用 |
| **在宝塔上做 TLS 终止** | 宝塔是**别人的项目** ⇒ 需用户同意，本项目不擅动（§2.2） |

---

## 9. 对既有资产的说明（中间件机 `/opt/emqx`）

**本批实机核实（2026-09-30，只读取证）**：

| 资产 | 权限 | 说明 |
|---|---|---|
| `/opt/emqx/certs/` | 目录 **700** | `ca.crt`/`ca.key`/`server.crt`/`server.key`（**600**，属主 `www`）、`server.ext`（600，属主 `root`） |
| `server.crt` 指纹 | — | `sha256=73:53:3D:00:…:EF:94`，`CN=43.242.200.8`，SAN 含 `43.242.200.8 / 172.28.0.1 / ypbin-emqx / ypbin-emqx.local / localhost`，有效期至 **2029-01-01** |
| `ca.crt` 指纹 | — | `sha256=6C:F5:60:49:…:8D:9F`，`CN=ypbin-EMQX-SelfSigned-CA`；`openssl verify -CAfile ca.crt server.crt` ⇒ **OK** |
| `gen-certs.sh` | 711 | 自签 CA + 服务端证书生成（幂等；`--force` 才重签） |
| `emqx-tls-selfcheck.sh` | 711 | TLS 验收自检（T1–T9：握手/指纹/SAN/到期/鉴权/ACL/1883 仍在） |
| `emqx-tls-rollback.sh` | 711 | 一行式回滚（§7） |
| 私钥 | **600** | 🔴 **本批未打印任何私钥内容**，只报路径/权限/指纹 ✔ |

- **`.gitignore` 已排除 `certs/`**（`deploy/emqx/.gitignore`）⇒ **私钥不会入仓** ✔（本批已核实）。
  同时排除了 `.env`、`api-key/default_api_key.conf`、`backup/`。
- **⚠️ 与"未启用/半成品/预留"叙事的更正**：上述脚本与证书**不是**残留垃圾，而是**已投入使用的启用套件**；
  按 §1.3 的实机判据（2026-10-04 复核），**8883 已启用并在跑**，且仓内
  `deploy/emqx/{docker-compose.yml,emqx.conf,.env.example}` 的登记与之**一致**
  （`listeners.ssl.default.enable = true`、`EMQX_MQTT_TLS_BIND_ADDR=0.0.0.0`、A6 巡检判据）。
  ⇒ 因此本文**不使用"预留、未启用"字样**：那样会与实机及其他文档**冲突**，
  只会制造新的不一致（诚信优先，R1/R4）。**用户已裁定保持开启**（§1.3 末段）；
  §7 的回滚步骤**仅在将来确需停用 TLS 时**使用。
- **仓内对应物**：`deploy/emqx/gen-certs.sh`、`deploy/emqx/emqx-tls-selfcheck.sh`、
  `deploy/emqx/emqx-tls-rollback.sh`（三脚本**已随仓**），
  以及 `docs/EMQX-DEPLOY.md` §3.2（TLS 拓扑与生产替换步骤的既有登记位置）。
  ⚠️ **待办**：`docs/EMQX-DEPLOY.md:88` 仍写"`8883`/`8083`/`8084` **监听器已显式 `enable=false`**"，
  **与实机不符（8883 已启用）** —— 该行属部署文档，**本轮按变更最小化未改** ✗，**登记为待更正项**。

---

## 10. 外部事实出处（R2/R3）

| # | 事实 | 来源 | 一手/二手 | 访问日期 |
|---|---|---|---|---|
| S1 | `listeners.ssl.default` 配置键名与语义（`bind`/`max_connections`/`ssl_options.{cacertfile,certfile,keyfile,verify,fail_if_no_peer_cert}`）；`verify=verify_peer` 为校验客户端证书，`verify_none` 反之；`fail_if_no_peer_cert=false` 时**空证书视为合法**，仅在送了无效证书时拒 | 《Listener Configuration》§Configure SSL Listener <https://docs.emqx.com/en/emqx/latest/guides/configuration/listener.html> | **一手**（EMQX 官方文档） | 2026-09-30 |
| S2 | 单向认证配置 `verify = verify_none`；两向认证 `verify = verify_peer` + `fail_if_no_peer_cert = true`；两种模式（**EMQX 原生** vs **代理/LB 终止**）的优缺点原文；主机名不匹配错误 `ERR_TLS_CERT_ALTNAME_INVALID`；源 IP 转发头可被伪造的警告 | 《Enable SSL/TLS Connections》§Two Usage Modes / §One-Way-Two-Way Authentication / §Enable SSL/TLS with One-Way Authentication <https://docs.emqx.com/en/emqx/latest/guides/network/emqx-mqtt-tls.html> | **一手**（EMQX 官方文档） | 2026-09-30 |

**未能取得一手来源的项**（✗ 不得当作已核实）：

- **云安全组当前是否放行 8883**：仓内 `deploy/emqx/docker-compose.yml:96` 登记为"未放行（从生产机实测 CLOSED）"，
  但**未读云控制台规则内容**；2026-10-04 复核时"应用机 → `43.242.200.8:8883`"**可建立 TCP 连接**，
  **与该登记相矛盾** ⇒ 结论**待定**，**只作未核实项**，**不得**据此宣称公网可达或不可达。
  （既有同类未决项：`docs/EMQX-DEPLOY.md:838` U-G「仍未读云控制台规则…未定论」。）
- **公网 8883 端到端 TLS 可达性**：**未从公网独立实测**（未在外部网络发起 `openssl s_client`）✗；
  已实测范围仅限"宿主在听 + 应用机可连通"。**设备能否真从公网连上 8883，尚未验证**。
- **8883 上的鉴权/ACL 行为**：**本轮未实测**（属实施验收范畴，§5 的 V4/V5）✗。
- **EMQX 5.8.9 与官方"latest"（6.x）文档的版本差异**：上表来源为 `latest` 文档；
  本机跑 **5.8.9**。本批**只对 `ssl_options` 相关键名**做了与实机生效值的**交叉核对**（一致，见 §2.1 配置注释），
  **未逐条比对** 5.8 与 6.x 的差异 ⇒ 若在 5.8.9 上遇到与文档不符的行为，以**实机 `emqx ctl` 输出**为准。

---

## 附录：相关文档索引

- `docs/EMQX-DEPLOY.md` —— 部署与暴露面登记（§3.1 开发测试期暴露面、§3.2 TLS 拓扑与生产替换步骤、§9 回滚）
- `docs/EMQX-INGRESS-DESIGN.md` —— 入站/下行设计（§5.2 ACL、§8 部署方案）
- `docs/DEVICE-CREDENTIAL.md` —— 设备凭据签发/轮换口径（C2：明文只出现一次）
- `docs/TASK-BOARD.md` —— #9 TLS/收回 1883 的进度登记
- `deploy/emqx/` —— compose、`emqx.conf`、`.env.example`、三脚本与 `.gitignore`
