# EMQX 平台侧集成（MQTT 入站 + 凭据同步）

> 状态：**段 A 已落地并验收通过**（2026-10-01）。段 B（下行命令/在线调试）与段 C（前端）**未做**，见 §8。
> 设计依据：`docs/EMQX-INGRESS-DESIGN.md`（入站主推、破例范围 D2、H6/H10、§6.5）；部署形态：`docs/EMQX-DEPLOY.md`（中间件机独立 compose + 隧道）。
> 本文件只写**本回合新增的东西**：薄适配端点契约、双向通道的**方向**、规则/动作落成资产、凭据同步、回滚与未验证项。

---

## 0. 结论先行

| # | 结论 | 证据强度 |
|---|---|---|
| **A1** | 入站链路**已端到端打通**：模拟设备（平台签发的凭据）发 `ypbin/v1/1/9300012/up/property` → EMQX 规则 → HTTP 动作 → `POST /internal/mqtt/readings` → `AvailabilityService.ingest` → Redis 最新值 / IoTDB 时序 / 活性 / 影子。 | 生产实测（`deploy/emqx/accept-emqx-ingress.sh` 五项判据全绿） |
| **A2** | 入站端点按决策 D2 返回**真 HTTP 状态码**（非法 400 / 可重试 503 / 成功 200），且**只有 `/internal/mqtt/**`** 破例（其余 `/internal/**` 与网关 API 维持 200 + `R.code`）。 | 单测断言原始状态码（`InternalMqttReadingControllerTest`）+ 生产实测 401/400 |
| **A3** | 设备凭据**签发/轮换 ⇒ EMQX 建账号（只上报哈希与盐）**、**吊销 ⇒ 删账号**已打通；`EMQX_API_KEY` 经容器 env 注入、Nacos 里只是 `${…}` 占位符（**不入库**）。 | 生产实测：签发后 EMQX 内置库出现 `1.9300012`；且用同口径哈希可认证成功 |
| **A4** | 🔴 **实测更正设计 §3.3 F11 的一处**：`POST /api/v5/authentication/{id}/users` **只收明文 `password`**（传 `password_hash`/`salt` 会 400 `unknown_fields`）；带哈希的导入必须走 **`POST .../import_users`**（multipart，字段名 `filename`，CSV 列 `user_id,password_hash,salt,is_superuser`）。 | 生产实测三种请求体的响应体（见 §4.2） |
| **A5** | 🔴 **ACL 拒绝在客户端看来是"成功"**：`deny_action=ignore`（H1 要求）⇒ 越权 publish 照常收到 PUBACK。**越权判据只能用 EMQX 指标差值**，用客户端退出码会得到假绿。 | 生产实测：越权发布退出码 0，但 `publish.auth_error`/`nomatch` 各 +1 |
| **A6** | 反向通道方案：**生产机发起** SSH 反向隧道（`-R 127.0.0.1:18084`）+ 中间件机 **systemd-socket-proxyd** 中继（`172.28.0.1:18084 → 127.0.0.1:18084`）。原因：中间件机 sshd 是 `GatewayPorts no`（反向转发只能绑回环），而 EMQX 容器**到不了宿主回环**。 | 实测：容器内 `curl http://172.28.0.1:18084/...` 有响应；动作状态 `connected` |
| **A7** | 中间件机宿主 **INPUT 策略是 DROP**（与设计 F44 记的生产机情况相同）⇒ 必须显式放行「emqx-edge 网桥 → 本机 18084」这一条，否则容器发出的连接被丢，表现为 EMQX「连接超时」。放行范围收到最窄（本网桥 + 本端口 + TCP），由 `emqx-ingress-firewall.service` 幂等维护。 | 实测：`iptables -L INPUT -n` 前后 |

---

## 1. 端点契约（新增的唯一后端入口）

### 1.1 `POST /internal/mqtt/readings`

| 项 | 值 |
|---|---|
| 守卫 | 头 `X-Internal-Token`（与既有 `/internal/**` 同一凭证）；**失败返回原始 401**（`InternalMqttTokenFilter`，不走全局异常处理器） |
| 请求体 | `{"requestId":"…","items":[{"deviceId":"9300012","propertyId":"temperature","value":"23.4","quality":"GOOD","ts":1758768000000,"pollIntervalMs":30000}]}` |
| 约束 | `requestId` `[A-Za-z0-9_.:-]{1,64}` 必填；`items` 1–50 条且**必须同属一台设备**；`propertyId` 走 `PropertyIdRules`（1–128，字符集白名单）；`quality` 走 `ReadingQuality` 白名单（`GOOD\|UNCERTAIN\|BAD\|STALE\|NOT_CONNECTED\|CONFIG_ERROR`，**大小写敏感**）；`value` 非空且 ≤4096 字符；`ts` 为正且不超前 `RedisLatestValueWriter.MAX_FUTURE_SKEW_MS`（5 分钟，与最新值写入器同一口径）；`pollIntervalMs` 为正且 ≤24h；报文 ≤64KB |
| 响应 | 成功/幂等重投 **200** + `R` 信封（`data.accepted/dropped/duplicated`）；契约非法 **400** + `{"code":"<原因码>","message":"…"}`；其它异常 **503**（EMQX 判 recoverable 会重试） |
| 租户 | **按设备行反查**（`TenantContext.executeIgnore` → `iot_device.tenant_id`），**不信任报文体里的任何 tenant 字段**；报文体带 `tenantId` 会被忽略（有实测用例） |
| 幂等 | 表 `iot_mqtt_ingest_receipt`，唯一键 `(tenant_id, device_id, request_id)`；命中即**整批跳过**（不调用 `ingest`，因此不会重复写最新值/时序/活性/影子）。⚠️ **顺序重投是强保证，并发重投不是**——预查 + `ON DUPLICATE KEY UPDATE` 只保证**回执行**唯一，两个真正并发的同 `requestId` 请求仍可能都调用到 `ingest`（IoTDB 非事务 ⇒ 时序可能双写）。当前被动作的 `inflight_window=1`（单节点串行投递）兜住，见 §8 U-A13。 |
| 落库 | **完全复用** `AvailabilityService.ingest`（本端点不新增任何落库链路；点位**映射**校验仍由该服务的既有实现承担） |

**为什么 `requestId` 必填、且唯一键含 `device_id`**：`requestId` 由**设备**生成，跨设备不保证唯一
（现场很可能每台都从 1 开始计数），所以不能照抄 `iot_command_instance` 的 `(tenant_id, request_id)`
——那是**平台生成**的 requestId。这一点是有意为之，写在 `IotMqttIngestReceipt` 的类注释里。

**为什么校验不用 Bean Validation**：`@Valid` 失败会被全局异常处理器转成 HTTP 200 + `R.code`，
而 EMQX 只看状态码 ⇒ 会变成「双端都绿、数据不存在」的静默丢数据（设计 C9/H10）。因此控制器接**原始字符串**、
由服务层解析并逐条校验，控制器只把结果/拒绝映射成原始状态码。

### 1.2 破例范围的守门

- 过滤器只注册在 `/internal/mqtt/*`（`InternalMqttGuardWebConfig.MQTT_INGRESS_URL_PATTERN`），
  **不存在**任何放行 `/internal/**` 全体的写法；
- 该路径仍在既有 `InternalTokenGuardInterceptor` 的 `/internal/**` 拦截范围内（纵深防御，行为一致）；
- 单测直接断言 `status().isUnauthorized()` / `isBadRequest()` / `isServiceUnavailable()`
  ——断言的是**原始 HTTP 状态**而不是 `R.code`（若有人改回 200 信封，用例会转红）。

---

## 2. 拓扑与**方向**（最容易搞反的一段）

```
                    中间件机 43.242.200.8                                   生产机 113.142.217.58
  ┌──────────────────────────────────────────┐        ┌──────────────────────────────────────────────┐
  │ EMQX 5.8.9（容器 ypbin-emqx，独立网络       │        │ ypbin-iot 容器 172.20.0.24:18084              │
  │ emqx-edge 172.28.0.0/16，网关 172.28.0.1） │        │   ↑ docker-proxy 只绑宿主 127.0.0.1           │
  │  ├ 1883  → 127.0.0.1:1883（只绑回环）      │        │                                              │
  │  └ REST → 127.0.0.1:18093（只绑回环）      │        │                                              │
  └──────────────────────────────────────────┘        └──────────────────────────────────────────────┘
        ▲                                    ▲                    │
        │ ①入站：动作 POST                    │ ②出向：Java 调 REST  │
        │   http://172.28.0.1:18084/...      │   http://172.20.0.1:18093/api/v5/...
        │                                    │                    │
  ┌─────┴──────────────────────┐   ┌─────────┴────────────────────┴───────────────────────────────────────┐
  │ emqx-ingress-relay.socket  │   │ emqx-ingress-tunnel.service（生产机，autossh，**由生产机发起**）        │
  │ 172.28.0.1:18084           │   │   -L 172.20.0.1:18093:127.0.0.1:18093   ← 出向（容器可达地址）        │
  │   ↓ systemd-socket-proxyd  │   │   -R 127.0.0.1:18084:127.0.0.1:18084   → 反向（EMQX 回平台）          │
  │ 127.0.0.1:18084 ←──────────┼───┘        （另一端在中间件机回环；sshd GatewayPorts=no ⇒ 只能绑回环）    │
  └────────────────────────────┘                                                                          │
```

**逐条说清方向与理由**：

1. **入站（EMQX → 平台）**：EMQX 的 HTTP 动作目标 = `http://172.28.0.1:18084/internal/mqtt/readings`。
   - 为什么不是 `127.0.0.1:18084`：那是**宿主回环**，容器里的回环是它自己，**连不上**。
   - 为什么不是 `172.28.0.1:1883` 那种「直接绑宿主」：宿主 INPUT 策略是 **DROP**，不放行就连不上（见 §3.2），
     所以放行规则与监听地址必须**成对**存在。
2. **出向（平台 → EMQX 管理面）**：Java 客户端 base-url = `http://172.20.0.1:18093`（docker 网桥网关）。
   - 既有的 `127.0.0.1:18093` 隧道**对容器不可见**（同样是回环问题），所以这条 `-L` 把同一个目标**再绑一份到网关地址**；
   - 只有本机容器网络可达，**公网不可达**（没有绑 0.0.0.0）。
3. **反向转发的授权事实**：隧道用的受限私钥在中间件机 `authorized_keys` 里是
   `restrict,port-forwarding,permitopen="127.0.0.1:18093"`。`permitopen` 只约束 **`-L` 的目标**，
   未设 `permitlisten` ⇒ **`-R` 未被禁止**（`docs/EMQX-DEPLOY.md` §6.1 已登记的残留风险，勿写成"只能出向"）。
4. **两条转发都在同一个新单元里**（`emqx-ingress-tunnel.service`），既有 `emqx-tunnel.service` **未改动** ⇒
   阶段②已验收的自愈与告警口径不变，两者失败互不牵连。

---

## 3. 部署资产（`deploy/emqx/` 增补）

| 文件 | 跑在哪 | 作用 |
|---|---|---|
| `emqx-ingress-tunnel.service` | 生产机 | autossh：出向 `-L 172.20.0.1:18093` + 反向 `-R 127.0.0.1:18084`（见 §2 的方向说明） |
| `emqx-ingress-relay.socket` / `.service` | 中间件机 | `systemd-socket-proxyd`：`172.28.0.1:18084 → 127.0.0.1:18084`（空转 5 分钟自动退出，由 socket 激活） |
| `emqx-ingress-firewall.service` | 中间件机 | 幂等维护一条 INPUT 放行：`-s 172.28.0.0/16 -p tcp --dport 18084`；`ExecStop` 会删掉它 |
| `emqx-ingress-install.sh` | 运维机 | 分发并启用上述单元 + 落 `/opt/emqx/ingress/internal-token`(600) + 双向连通性核验（值经 stdin 管道，不打印） |
| `emqx-ingress-init.sh` | 中间件机 | 幂等 upsert **规则 + Webhook 动作**，回读断言（含 `max_buffer_bytes=16MB`），动作状态 `connected` 自检 |
| `emqx-ingress-rollback.sh` | 中间件机 | 撤销规则/动作/中继/放行（**顺序硬约束**：先删规则再删动作，见 §3.3） |
| `mqtt-device-probe.py` | 中间件机 | 模拟设备（用平台凭据连接/发布；只用 `/opt/emqx/venv` 里既有的 paho-mqtt） |
| `accept-emqx-ingress.sh` | 运维机 | 端到端验收 ①–⑤（可复跑，见 §6） |

### 3.1 规则与动作（REST 落成，幂等）

**动作（v1 bridges API，type=webhook）**：`POST /api/v5/bridges`

| 键 | 值 | 依据 |
|---|---|---|
| `url` | `http://172.28.0.1:18084/internal/mqtt/readings` | §2；HTTP 动作的 scheme/host/port 不能用模板（设计 F24） |
| `method` | `post` | 同上 |
| `headers` | `content-type: application/json` + `X-Internal-Token: <deploy/.env 的同名值>` | 既有守卫头名 |
| `body` | `{"requestId":"${requestId}","items":[{"deviceId":"${deviceId}","propertyId":"${propertyId}","value":"${value}","quality":"${quality}","ts":${ts},"pollIntervalMs":${pollIntervalMs}}]}` | 一条消息 = 一台设备的一小批 |
| `max_retries` | `2`（默认） | 重试是**重复投递**的来源，故入站必须幂等 |
| `resource_opts.max_buffer_bytes` | **`16MB`**（默认 **256MB**，实测回读确认） | H6：中间件机 mem_limit=2g，256MB 缓冲是危险值 |
| `resource_opts.query_mode` | `async`（默认） | 异步下"订阅方可能已收到而外部系统尚未写入"（F28） |
| `resource_opts.inflight_window` | `1` | 官方原文：同客户端严格有序必须设 1；把乱序窗口压到最小 |
| `resource_opts.request_ttl` | `30s`（默认 45s） | 缩短"迟到重放"窗口 |

**规则**：`POST /api/v5/rules`（`actions: ["webhook:ypbin-ingress"]`）

```sql
SELECT nth(6, tokens(topic, '/')) AS kind,
       nth(3, tokens(topic, '/')) AS tenantId,
       nth(4, tokens(topic, '/')) AS deviceId,
       payload.requestId AS requestId, payload.propertyId AS propertyId,
       payload.value AS value, payload.quality AS quality,
       payload.ts AS ts, payload.pollIntervalMs AS pollIntervalMs
FROM "ypbin/v1/+/+/up/property"
WHERE nth(3, tokens(topic, '/')) = nth(1, tokens(username, '.'))
  AND nth(4, tokens(topic, '/')) = nth(2, tokens(username, '.'))
```

WHERE 是**纵深防御**：ACL 已挡住越权发布（实测 §6 ⑤），这里是防「ACL 配置漂移导致越权写」。

### 3.2 中间件机为什么需要一条防火墙规则

宿主 INPUT 策略实测 **DROP**（只显式放行了别人的 18080/8080）。EMQX 容器要访问宿主网关地址属于
**INPUT 路径**，不放行就被丢——而 EMQX 侧只显示"连接超时/ refused"，很容易被误判成"平台没起"。
放行范围收到最窄：**只本网桥来源（172.28.0.0/16）+ 只本端口（18084）+ 只 TCP**，不放行出口方向、
不改默认策略。规则由我们自己的 systemd 单元幂等维护（`iptables -C` 命中则不重复插），
回滚时随 `ExecStop` 删除。

### 3.3 幂等与顺序（两个实测坑）

1. **先删规则，再删动作**：EMQX **拒绝删除仍被规则引用的桥接**（实测 `DELETE /bridges/webhook:…`
   返回 **400** 而不是 204）。所以 upsert 顺序固定为「找同名规则 → 删规则 → 删桥接 → 建桥接 → 建规则」。
2. **动作名会被归一**：POST 时写 `webhook:ypbin-ingress`，回读是 `http:ypbin-ingress`（v2 名）。
   断言按"后缀同名"判定，写死前缀会假红。

---

## 4. 设备凭据与 EMQX 的同步

### 4.1 同步规则（幂等、可区分失败）

| 平台动作 | EMQX 侧动作 | 失败语义 |
|---|---|---|
| 签发 / 轮换（`POST /devices/{id}/credential`） | `DELETE`（404 视为成功）+ `POST .../import_users` | **fail-closed**：同步失败 ⇒ 抛业务异常、**事务回滚**、**不返回明文口令**。理由：返回一把连不上的口令是最难排查的静默故障 |
| 吊销（`DELETE /devices/{id}/credential`） | `DELETE /users/{username}` | **平台侧必成、EMQX 侧尽力而为**：失败记 ERROR 日志 + 指标 `iot.emqx.credential.sync.failed{operation=revoke}`，残留账号交给对账任务（设计 RK16/P1-7）。理由：安全动作不应被 broker 可用性绑架 |
| `ypbin.emqx.enabled=false`（降级形态） | 不调用管理面 | 平台侧签发/校验照常可用（哈希自持），日志如实说明"只做平台侧" |

**先删后导的理由（实测）**：`import_users` 对已存在的 `user_id` 是 **`skipped`**
（`{"success":0,"skipped":1}`，HTTP 仍 200）⇒ 不先删，轮换会**静默不生效**（旧口令继续可用）。
客户端因此**按计数断言**（`success==1 && failed==0`），只看 200 会假绿。

**只上报哈希**：平台口令是自持哈希（`sha256(password + salt)`，与 EMQX 内置库同口径），
同步只把 `password_hash` + `salt` 放进 CSV ⇒ **明文不出平台**。有真 HTTP 往返单测断言报文里
只有哈希与盐、且不含明文口令字段。

### 4.2 🔴 实测更正（与设计 §3.3 F11 不一致）

| 请求 | 结果 |
|---|---|
| `POST /api/v5/authentication/password_based%3Abuilt_in_database/users`，体 `{user_id, password_hash, salt, is_superuser}` | **HTTP 400**，外层 `{"code":"BAD_REQUEST","message":"…"}`，`message` **本身是一段被转义的 JSON 字符串**：`{\"unmatched\":\"password\",\"unknown\":\"password_hash,salt\",\"reason\":\"unknown_fields\"}`（复核者取回的原始响应体逐字如此） |
| 同上，体 `{user_id, password}`（明文） | **HTTP 201**（可用，但明文会离开平台 ⇒ **不采用**） |
| `POST …/import_users`，multipart 字段名 **`file`** | **HTTP 400** `Missing required parameter: filename` |
| `POST …/import_users`，multipart 字段名 **`filename`**，CSV `user_id,password_hash,salt,is_superuser` | **HTTP 200** `{"total":1,"success":1,"failed":0,"override":0,"skipped":0}` |
| 同一用户**未先删**再 import | **HTTP 200** `{"success":0,"skipped":1}`（不覆盖） |
| 删后 import 新哈希 + 用**旧口令**连接 | **认证被拒**（`connack_rc=134`） |
| 用**新口令**连接 | **认证通过**（连接成功、可发布自己主题） |

⇒ 结论：**平台侧凭据同步走 `import_users`（哈希 import），并先 DELETE**；设计里"User Management API 收 password_hash"
这一句在本镜像上不成立，属**已实测更正**（不是本仓的偏差）。

### 4.3 凭据落位（不入库/不入日志）

- `EMQX_API_KEY`/`EMQX_API_SECRET`：**只在**中间件机 `/opt/emqx/.env`(600) 与生产机 `deploy/.env`(600)+容器 env；
  Nacos 活配置里写的是 `${EMQX_API_KEY}` 占位符（Spring 读取时从容器 env 解析）⇒ **不进 Nacos 配置库**。
  ⚠️ OSS 版 API Key **无角色约束**（官方：role-based API credentials 仅企业版），能改 ACL/建用户/删规则，
  比 `INTERNAL_TOKEN` 权限更大 ⇒ 18083/18093 永远只绑回环。
- `X-Internal-Token`（入站动作的 headers 里必须有）：以 600 文件落在中间件机
  `/opt/emqx/ingress/internal-token`，由 `emqx-ingress-install.sh` 经 **stdin 管道**写入（不进 argv/不打印）。
  这是**已登记的残留风险**（RK9/P1-5）：它就是平台 `/internal/**` 的同一把凭证。
- 一切排查**只比长度/指纹**；`emqx-ingress-init.sh` 的所有回显都过 `redact()`（把 token 值替换成 `***`），
  因为动作的 `headers` 里就含它——直接打印桥接 JSON 等于把内部凭证写进终端与日志。

---

## 5. 配置与开关

| 层 | 键 | 值 |
|---|---|---|
| Nacos 活配置（`ypbin-iot.yaml`） | `ypbin.emqx.enabled` | `true` |
| | `ypbin.emqx.base-url` | `http://172.20.0.1:18093` |
| | `ypbin.emqx.api-key` / `api-secret` | `${EMQX_API_KEY}` / `${EMQX_API_SECRET}`（占位符） |
| | `connect-timeout-ms` / `read-timeout-ms` | `2000` / `5000`（远程调用必须显式超时） |
| | `broker-host` / `broker-port` | 中间件机地址 / `1883`（**接入信息展示用**；当前 1883 只绑回环，见 §8） |
| compose（`ypbin-iot.environment`） | `EMQX_API_KEY` / `EMQX_API_SECRET` | `${…:?}`（缺键让 compose 解析期就失败） |
| `deploy/.env`(600) | 同名键 | 真值（与中间件机 `/opt/emqx/.env` 同一把） |

活配置由 **`tools/patch-nacos-iot-emqx.py`** 幂等写入（默认 dry-run，`--apply` 才发布；
改前 dump live 到 600 备份、改后断言"除该段外逐字未变"）。**`enabled=true` 但缺 base-url/API Key 时
应用启动即失败**（fail-fast，不等到第一次签发才炸）。

---

## 6. 端到端验收（可复跑）

```bash
bash deploy/emqx/emqx-ingress-install.sh \
     --mw-ssh "root@43.242.200.8 -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw" \
     --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test"     # ① 通道就绪（幂等）
ssh <mw> 'bash /opt/emqx/emqx-ingress-init.sh'                          # ② 规则/动作（幂等）
bash deploy/emqx/accept-emqx-ingress.sh \
     --mw-ssh "root@43.242.200.8 -p 61260 -i ~/.ssh/id_ed25519_ypbin_mw" \
     --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test"     # ③ 五项判据
```

`accept-emqx-ingress.sh` 的判据与实测结果（2026-10-01，**PASS**）：

| 判据 | 期望 | 实测 |
|---|---|---|
| ① 数值落库 | IoTDB `iot.reading` 出现**本条消息**的 time+value | `23.4` 行与 ts 精确一致（与 access 通道并行的 `23.5` 可区分） |
| ② 曲线点 | `GET /devices/9300012/series?propertyId=temperature` 返回该点 | 返回 `{"ts":"…","value":"23.4","quality":"GOOD"}` |
| ③ 指标增长 | `iot.mqtt.ingest.accepted`、`iot.timeseries.write.rows` 增长 | `+1` / `+13`（后者含 access 并行写入，只作增长判据） |
| ④ 幂等 | 同一 `requestId` 重投 ⇒ `duplicated+1`、**不新增时序行**、MySQL 回执仍 1 行 | 全部命中（按 ts+value 精确定位仍为 1 行） |
| ⑤ ACL 负向 | 匿名连接被拒、发别人主题被拒 | `connack.auth_error +1`；`publish.auth_error +1`、`nomatch +1` |

> ⚠️ 该脚本**每次运行都会轮换设备凭据并在收尾 shred 明文**（因为它必须用平台现签的凭据才叫端到端）。
> 运行后该设备没有"已知口令"；如需让真设备继续用，跑完重新签发一次并把明文交付出去。
> 另外：① 与 ③ 两条"增长"判据**刻意不作 PASS 判据**（access 通道每 2s 也在写同一设备同一点位，
> 设备总行数/写行数会被它推高）——承重的是紧随其后的"按 ts+value 精确定位恰好 1 行"。

**另两条独立用例**（直接打端点，证明「不信任报文」与「非法即 4xx」）：

| 用例 | 实测 |
|---|---|
| 租户伪装：报文体带 `tenantId:9`，设备真实租户=1 | 受理成功，且回执行 `tenant_id=1`（伪造值被忽略） |
| 非法质量码 `"good"`（小写） | **HTTP 400** `{"code":"QUALITY_INVALID"}`；EMQX 侧判不可重试、不重放 |
| 未映射点位 `no_such_point` | **HTTP 400** `{"code":"NO_ACCEPTED_ITEM"}` + `iot.ingest.propertyid.unmapped +1` |
| 凭证缺失/错误 | **HTTP 401**（原始状态码，不是 200 + `R.code`） |

### 6.1 独立复核（R6/L3，2026-10-01）

由**独立子代理**复核（不继承作者结论、自己重跑门禁与两台真机实测、3 次变异实验）。

**总判定 PASS**（有条件），7 个复核维度全部 PASS，并给出 1 个真缺陷 + 若干文档/资产不准确与未登记风险。
**复核者发现并已在本提交整改**：

| 复核发现 | 严重度 | 处置 |
|---|---|---|
| `iot.mqtt.ingest.rejected{reason=BODY_TOO_LARGE}` **永不增长**（体积护栏写在控制器里，绕过服务层唯一会计数的 `reject()`） | 真缺陷·中 | ✅ 护栏**下沉到服务层**（仍早于 JSON 解析），并新增回归用例 `oversizedBodyMustBeRejectedAndCounted` 断言该指标 ==1；变异（去掉护栏）已实测转红 |
| `MqttReadingIngestServiceImpl` 有整块重复 import | 真缺陷·低 | ✅ 已删（javac 容忍，故门禁未拦） |
| U-A6「拿不到 EMQX 侧丢弃读数」**不成立** | 文档不准确·中 | ✅ 改为：读数出口是 `GET /api/v5/bridges/webhook:ypbin-ingress/metrics` 与 `GET /api/v5/rules/{id}/metrics`（复核实测 `{"dropped":0,"matched":13,"success":10,"failed":3,…}`） |
| §4.2 的 400 响应体引用的是内层对象 | 文档不准确·低 | ✅ 已按外层包装改写 |
| 中间件机部署的 `mqtt-device-probe.py` 与仓库版有 docstring 漂移 | 资产漂移·低 | ✅ 已重新分发仓库版 |
| **新隧道单元没有停摆告警**（既有 watch 只盯 `emqx-tunnel.service`） | 未登记风险·中 | ✅ 登记为 U-A12（P1 用同一脚本加第二实例） |
| 幂等非原子（并发同 requestId 可能双写时序） | 未登记边界·低 | ✅ 登记为 U-A13，并在 §1.1 补前提 |
| sa-token 防火墙对 `//`/`..` 路径返回 **HTTP 200 + `非法请求`**（另一种 200=成功形态，当前不可达） | 未登记·低 | ✅ 登记为 U-A14 |
| 验收脚本两条增长判据不特异（设备总行数/写行数含 access 并行写入） | 验收判据·低 | ✅ 已降级为**信息行**（不再作为 PASS 判据），承重判据是"按 ts+value 精确定位恰好 1 行" |

**复核者的诚实边界（照抄）**：真设备经 1883/8883 的外部接入、TLS、平台侧 503 重试路径、ACL 漂移本身、
`value` 含引号破 JSON、EMQX 集群复制语义 —— 均**未核实**（与 §8 一致）。
> 复核副作用（已登记，勿当作缺陷）：复核与验收脚本**每次运行都会轮换设备 9300012 的凭据**
> 并在收尾 `shred` 明文 ⇒ 运行后设备没有"已知口令"，真设备会被踢掉；如需保持可用，跑完重新签发一次并留存明文交付设备。

---

## 6.2 段 B：下行（命令实例 + 在线调试）

> 段 B 的实现与验收记录。契约口径见 `docs/EMQX-INGRESS-DESIGN.md` **§7.6**（评审确认补齐，含 B1–B9）。

### 6.2.1 表与端点

| 对象 | 说明 |
|---|---|
| `iot_command_instance` | 运行期命令实例（**与物模型定义 `iot_command` 严格区分**）；幂等键 `uk(tenant_id, request_id)`（平台生成的 requestId）；含 `topic` 列（无关联审计）与 `emqx_message_id`（broker 侧溯源） |
| `POST /iot/devices/{id}/commands` | 下发（权限 `iot:debug:send`）：`kind` + `identifier` + `params` + `timeoutMs` + `writeDesired`；返回 requestId 与初始状态 |
| `GET /iot/devices/{id}/commands` | 分页查询（`iot:debug:get`，按创建时刻倒序 + 状态过滤） |
| `POST /iot/devices/{id}/commands/{requestId}/resend` | 人工重发（`iot:debug:send`；**同 requestId**、`retry_count+1`，仅 `failed`/`timeout` 可重发） |
| `POST /internal/command-replies` | 设备回执入口（`X-Internal-Token` 守卫；**维持 200 + `R.code`**——真状态码例外只在 `/internal/mqtt/**`） |

**下行主题与 payload**（唯一构造口径 `CommandPayloads`）：`property_set` → `down/property/set` +
`{"requestId":…,"properties":{"temperature":25.0}}`；`property_get` → `down/property/get` +
`{"requestId":…,"properties":["temperature"]}`（**空数组 = 全部可读属性**）；`service_call` →
`down/service/{identifier}` + `{"requestId":…,"params":{…}}`。`qos=1`、`retain=false`（设计 F32 口径）。

**报文形态（实测更正）**：`params`/`data` 在报文里都是 **JSON 值**（线上故障实证：声明成 `String` 会让发对象的客户端得到
`R.code=500`）⇒ Java 侧用 `Object` 接收、由平台重新序列化（数字/空白归一，语义等价）；`params` **必须是对象**
（否则明确业务错误），`data` 原样存储不解析（字符串就按字符串存）。

**校验时点**：一切校验都在 **publish 之前**——设备存在性 → 端点字段（`writeDesired=true` 本轮**显式拒绝**并指引改用
既有 `PUT /devices/{id}/shadow`；params ≤64KB 且必须是 JSON 对象）→ 物模型（标识必须在该产品物模型内；
属性设置要求 `accessMode ∈ {W,RW}`、读属性要求 `∈ {R,RW}`）→ 标识白名单（`PropertyIdRules`，防**主题注入**）。
任一失败 ⇒ 业务错误且**不发布**（避免"设备动了、平台说没下发"）。

**投递与状态**：`200` ⇒ `sent`；**`202`（No matched subscribers）⇒ 立即 `failed/NO_SUBSCRIBER`（"设备未连接"）**，
不必等超时；EMQX 异常 ⇒ `failed/EMQX_ERROR`（可人工重发）。状态更新用**显式 set 的 wrapper**
（重发要把 `error_code`/`error_msg`/`finished_at` 置回 NULL，`updateById` 的"非 NULL 才更新"会静默跳过）。

**超时扫描**：`CommandTimeoutScanner` 周期批量（`ypbin.emqx.command-scan-interval-ms`），
候选一次查、**一条 UPDATE** 批量置超时（**绝不循环内 update**）；候选为空**先判空短路**；**不自动重试**。
默认超时 `ypbin.emqx.default-command-timeout-ms=30000ms` **不小于**入站动作的 `request_ttl=30s`。

**回执**：`up/reply` 载荷 `{deviceId, requestId, code, message, data, ts}`；**`code == 0` 判成功**，
非 0 判失败并**原样保留设备的 code/message**；`data` 原样存储不解析；平台另记落库时间（`finished_at`），
设备时间留在 `reply_payload` 里。幂等：CAS 更新（只从 `pending`/`sent` 出发），重复回执**不改终态、不计数**。
**不信任报文**：`deviceId` 只用于反查租户；回执设备必须与实例设备一致。

**权限码与菜单**：`iot:debug:send` / `iot:debug:get`（`320022`/`320023`），
按规矩**双写**（`007` 末尾 + `migration/2026-10-02-iot-command-instance.sql`）+ `sys_role_menu` + `sys_template_menu`
（`tools/check-iot-sql-equivalence.sh` 绿）。

### 6.2.2 验收判据（段 B）

| 判据 | 期望 | 结果 |
|---|---|---|
| 状态机 | 6×6=36 种转换逐个断言（非法被拒）；`failed`/`timeout` 可重发、其余不可 | ✅ `CommandInstanceStatusTest` 4 用例 |
| 下发成功 | 实例 `pending→sent`；payload/topic 与契约一致；`qos=1`/`retain=false` | ✅ `CommandInstanceServiceImplTest` |
| 设备未连 | `202` ⇒ **立即** `failed/NO_SUBSCRIBER`（不等超时） | 单测 ✅；**生产实测 ✅（2026-09-27，1072ms ≪ 15000ms，见 §6.2.3）** |
| 校验不发布 | 未知标识 / 属性不可写 / 未知命令 / 非法 kind / `writeDesired=true` / params 非法 ⇒ 业务错误且 `verify(never()) publish` | ✅ 4 用例 |
| 重发 | 同 requestId、`retry_count+1`、失败原因被清空（显式 set） | ✅ |
| 回执 | `code=0→succeeded`（`reply_payload` 原样含 data/ts）；非 0→`failed`（保留 code/message）；重复→`duplicated` 不更新；设备不一致/未知 requestId/设备不存在→丢弃 | ✅ 4 用例 |
| 超时扫描 | 批量一条 UPDATE；候选为空**短路**不打库 | ✅ 2 用例 |
| 主题注入 | `/ + # 空格 超长` 在**发布前**被拒（三个类型都验） | ✅ `CommandPayloadsTest` 6 用例 |
| 例外范围 | 下发/查询/重发/回执**均无**原始响应对象、均返回 `R`；回执端点无权限码 | ✅ `IotCommandControllerGateTest` 4 用例 |

### 6.2.3 端到端（模拟设备真收指令并回执）

脚本：`deploy/emqx/accept-emqx-downlink.sh`（可复跑）。流程：模拟设备用平台签发的凭据订阅
`ypbin/v1/{t}/{d}/down/#`（**先等 `SUBSCRIBED` 再下发**，避免订阅未完成即发布的竞态）→ 平台下发 →
设备收到并回 `up/reply` → 实例变 `succeeded`；另跑"设备不在线"（不订阅）⇒ 平台**立即**得到
`failed/NO_SUBSCRIBER`（耗时 <5s，而该用例超时设 15s ⇒ 证明不是等超时）；再加"连着但不回执"⇒ 扫描置 `timeout`。

**状态：✅ 已实测通过（2026-09-27，`accept-emqx-downlink.sh` **整改后** PASS ①–⑤；首轮失败见下方 ⚠️）**

被验 artifact 三元组（生产 `ypbin-iot`）：image id `sha256:69ca8d255626a6097e783ddd2fec8d341ed43fe98d00a79792fbc22ba9a0b79c`、
容器内 jar md5 `efc9103a872e503be61d084b5464e22b`、StartedAt `2026-09-27T10:57:05Z`（restarts=0）；
jar 由**合并后的 main**（`0b6471f`）本地构建后上传部署（生产机未跑 mvn）。

关键原始输出（**节选，为可读性做了合并/缩写**——逐字原文见 PR #84 回执与脚本自身输出）：

```
  ·  下发响应 statusCode=sent emqxMessageId=00065C74DBF68B741F6D000068CE0000
  ·  RECEIVED topic=ypbin/v1/1/9300012/down/property/set
      payload={"requestId":"cmd-2104164292996689921","properties":{"switchState":26.5}}
  ·  REPLY_SENT requestId=cmd-2104164292996689921 code=0 published=True
  ✅ 实例终态 = succeeded
  ·  reply_payload={"deviceId":9300012,"requestId":"…","code":0,"message":"ok",
                    "data":{"applied":26.5},"ts":1790506836628,
                    "receivedAt":"2026-09-27T19:00:35.820266518"}
  ·  finishedAt=2026-09-27 19:00:36 emqxMessageId=00065C74DBF68B741F6D000068CE0000
  ✅ ① 回执落库字段齐全（设备 ts + 平台 receivedAt + finished_at）
  ✅ 重复回执后实例仍为 succeeded（不被改写）
  ·  端点级幂等：code=200 duplicated=true accepted=False
  ✅ 重复回执在**端点**上被明确判为 duplicated（不依赖全局指标）；iot.command.reply.duplicated +1
  ·  statusCode=failed errorCode=NO_SUBSCRIBER errorMsg=设备未连接（无订阅者）
  ✅ 无订阅者时**同步**判定 failed（耗时 1072ms，远小于超时 15000ms）；原因码 = NO_SUBSCRIBER
  ✅ 扫描已把实例置为 timeout；超时的原因码 = TIMEOUT
  ·  伪造回执已投递（载荷 deviceId=9999999，认证设备=9300012）
  ✅ 平台按认证主题丢弃了伪造回执（日志计数 1 → 2，增量 1）
结论: PASS（①–⑤ 全部命中）
```

> ⚠️ 首次运行时**脚本默认目标用了 `temperature`**（本产品里是 `accessMode=R`），被平台**正确拒绝**为
> "属性不可写" ⇒ 已把默认改为可写的 `switchState`。这条恰好是"物模型校验真的生效"的反向证据。
> 另一处已在 §6.2.1 登记：`params`/`data` 声明成 `String` 时发对象会得 `R.code=500`（生产实测），
> 已改为 `Object`（PR #83）——**单元测试当时漏抓，因为测试直接构造 JSON 文本、与线上报文形态不符**。

脚本判据（每条都刻意避免假绿）：
① 下发响应必须 `statusCode=sent`，且 `reply_payload` 同时含**设备 ts**与**平台 receivedAt**、`finished_at` 非空；
② 重复回执在**端点**上必须返回 `duplicated=true`（不依赖全局指标差值）；
③ 无订阅者时必须 `errorCode=NO_SUBSCRIBER`（只判 `failed` 会把 `EMQX_ERROR` 也放过）且耗时 <5s；
④ 超时必须 `statusCode=timeout` **且** `errorCode=TIMEOUT`；
⑤ **伪造回执必须被丢弃**：在设备自己的 `up/reply` 主题上发一条 `payload.deviceId = 别的设备` 的回执，
必须命中原因为「认证主题不一致」的丢弃日志（按**前后增量**判定）——这是「EMQX 模板头 `X-Mqtt-Device` 失效」
的判别用例（头失效时平台会退回用载荷设备，该伪造就可能被受理）。

### 6.2.4 回滚（段 B）

`deploy/sql/rollback/2026-10-02-iot-command-instance-rollback.sql`（删表 + 删两个菜单与两张授权表行）。
**影响**：在线调试的下发/查询/重发/回执全部不可用；**正在等待回执的实例会丢失**（不可逆，需先导出）。
后端代码回滚用镜像 tag `ypbin/ypbin-iot:rollback-emqx-<date>`。

### 6.2.5 未验证项（段 B）

| # | 项 | 现状 |
|---|---|---|
| **U-B1** | `writeDesired`（下发同时写影子 desired） | **未实现**：端点显式拒绝并指引改用既有 `PUT /devices/{id}/shadow`（不静默忽略） |
| **U-B2** | 设备侧持久会话（离线期间的下行排队） | 未做：设备离线时 `202` 即判失败（不排队）。官方默认队列 `max_mqueue_len=1000` 与 `session_expiry_interval=2h` 只在设备**保持会话**时生效，属 P1 与设备侧约定 |
| **U-B3** | 取消端点（`cancelled` 态） | 状态机已支持 `cancelled`，但**未提供**取消端点（设计 §7.4 未列）；当前该态只能由未来的取消动作产生 |
| **U-B4** | 自动重试 | **刻意不做**（设备侧不保证幂等）⇒ 只有人工重发 |
| **U-B5** | 多副本下的超时扫描竞争 | 扫描是跨租户批量 UPDATE（按主键），多副本同时跑理论上会重复计数指标（状态转换本身幂等）；单副本部署下不触发 |

### 6.2.6 🔴 已知陷阱（**已修**）：EMQX 重建/重启后，「空闲期首次下发」必失败且**看起来像管理面不可达**

**现象**（2026-10-03 实测）：重建 EMQX 容器后，平台下发命令**一律** `failed / EMQX_ERROR / EMQX 管理面不可达`。
极具误导性——**管理面其实是健康的**：同一容器里 `curl` 正常、隧道（`127.0.0.1:18093` 与 `172.20.0.1:18093`）正常、
`import_users` 设备账号同步也正常。**只有平台自己的 JDK 客户端会失败**，很容易被误判成网络/隧道/暴露面问题。

**根因**：平台 `EmqxRestAdminClient` 用 JDK `HttpClient` 的**默认 HTTP/2**。当**带体 POST**
（`POST /api/v5/publish`）是某条**新连接上的第一个请求**时，h2c upgrade 握手会被 EMQX/Cowboy 断开，
抛 `java.io.IOException: EOF reached while reading`（`Http2Connection$Http2TubeSubscriber`）
⇒ 本类映射成 `UNREACHABLE`。EMQX 重建/重启会**清掉客户端的连接池**，于是"重建后第一次下发"正好是这个形态。

**判据（可复跑，只读、只发无人订阅的诊断主题）**：

```bash
bash deploy/emqx/diagnose-emqx-admin-h2c/run.sh \
     --prod-ssh "root@113.142.217.58 -i ~/.ssh/id_ed25519_iot_test"
```

实测：HTTP/2 直接 POST `/api/v5/publish` = **3/3 EOF**；强制 HTTP/1.1 = **3/3 HTTP 202**；
先发一个**无体** `GET /status` 把 h2c 连接建起来再 POST = **3/3 HTTP 202**。

**已修**：`EmqxRestAdminClient` 构造里显式 `.version(HttpClient.Version.HTTP_1_1)`（见该类内注释）。
防回归：`EmqxRestAdminClientTest#httpClientMustBeConfiguredForHttp11`（配置级）与
`#bodyCarryingPostOnFreshConnectionMustNotAttemptH2cUpgrade`（**线上报文级**：断言首个带体 POST 不带
`Upgrade: h2c` / `HTTP2-Settings` 头——只看 `exchange.getProtocol()` 会恒真假绿，因为 JDK 内建
`HttpServer` 只用 1.1 应答）。

**运维含义（修复落地前）**：**重建/重启 EMQX 后，平台的下行发布在"连接空闲后第一次发布"时会失败**；
重启 `ypbin-iot` **不能**恢复（会重新协商 h2c）。修复落地后不再有这个窗口。

**为什么选"固定 HTTP/1.1"而不是重试/预热**：重试只是掩盖（每次新建连接的第一次带体请求仍失败）、
预热只是碰巧（依赖连接存活时间，空闲后照样失败且每次多一次往返）；固定 1.1 与 EMQX REST 自身的
HTTP/1.1 语义一致，且已由上述判据证明 3/3 成功。

---

## 7. 回滚

| 层次 | 动作 | 影响 |
|---|---|---|
| EMQX 入站配置 | 中间件机 `bash /opt/emqx/emqx-ingress-rollback.sh`（先删规则再删动作 + 停中继/放行；**默认保留** EMQX 认证与 ACL） | MQTT 入站停；**下行与凭据同步不受影响**（若段 B 已上） |
| 反向通道 | 生产机 `systemctl disable --now emqx-ingress-tunnel.service` | 出向 18093 也没了 ⇒ EMQX 管理面调用（凭据同步/下行）同时停；既有 `emqx-tunnel.service`（127.0.0.1:18093）**仍在** |
| 平台配置 | Nacos 里 `ypbin.emqx.enabled: false` → 重启 `ypbin-iot`（或回滚 live 配置：`tools/patch-nacos-iot-emqx.py` 的 600 dump 就在工作目录里） | 入站端点仍在（可继续收），但**凭据不再同步**、接入信息回 `emqxEnabled=false` |
| 平台代码 | `ypbin/ypbin-iot:rollback-emqx-<date>` 镜像 tag + 旧 jar 重新 `up -d --no-deps ypbin-iot` | 回到没有 MQTT 入站端点的版本（旧容器对端点的请求会 404） |
| 数据 | `deploy/sql/rollback/2026-10-01-iot-mqtt-ingest-receipt-rollback.sql`（`DROP TABLE iot_mqtt_ingest_receipt`） | 只丢幂等回执，无业务数据；**必须先停入站**（否则重投会重复写最新值/时序） |
| 设备侧 | 上报切回既有 HTTP 通道（`POST /internal/readings`） | 入站停掉后设备不能只靠 MQTT |

> **回滚顺序**：先停 EMQX 侧入站（规则/动作）→ 再停设备 MQTT 上报（切回 HTTP）→ 最后才动平台配置与表。
> 反过来做会留下"设备在发、平台已不认"的空窗。

---

## 8. 未验证项与后续（**不得当作已做**）

| # | 项 | 现状 | 影响 / 触发条件 |
|---|---|---|---|
| **U-A1** | **真设备接入面**：1883 仍只绑中间件机回环 | 本轮验收用「在中间件机上跑的模拟设备」+ 平台签发凭据；**没有**从外部网络连 1883 | 真设备上不了；开放 1883 属 P1 的独立决策（设计 §8.2/P1-2），必须与 TLS(8883) 一起评估 |
| **U-A2** | **TLS / 8883** | 未启用 | 明文口令只在隧道内传输；对外接入前必须启用 |
| **U-A3** | **集群 / 高可用** | EMQX 单节点 | broker 挂 ⇒ MQTT 入站全断（既有 HTTP 通道仍在）；集群化的内置库复制语义未核实（设计 U11） |
| **U-A4** | `value` 里含双引号/反斜杠 | 规则 body 模板直插在 `"value":"${value}"` 里，**未做 JSON 转义** | 该条会变成非法 JSON ⇒ 平台 400（EMQX 侧计失败），**不会静默丢**；P1 可在规则里用 `json_encode` 修 |
| **U-A5** | 平台侧 503 重试的可观测性 | 本轮未构造 503 场景（需停平台或塞慢查询） | 503 路径只有单测覆盖（`InternalMqttReadingControllerTest`），**生产未实测** |
| **U-A6** | EMQX 侧 `dropped.*` / 动作级 metrics | v1 bridges API 回读的 `metrics` 为空（实测），因此"EMQX 侧丢弃计数"本轮**没拿到直接读数** | 判据改用了规则/动作的回读配置 + 平台侧指标；P1-8 的巡检要另找读数口径（如 `/api/v5/metrics` 的 `dropped.*`） |
| **U-A7** | 属性批量上报（一条消息多点位） | 未做（P0 契约一条消息一个点位） | 规则 body 模板是否支持数组展开未核实（设计 U24） |
| **U-A8** | 入站凭证最小化 | 入站动作复用全局 `INTERNAL_TOKEN`（RK9） | 泄露它 = 拿到 `/internal/**` 写权限；P1-5 |
| **U-A9** | 凭据对账任务（平台台账 ↔ EMQX 用户差集） | 未做（P1-7） | 吊销时 EMQX 不可达会留残留账号；吊销路径已记 ERROR + 指标 |
| **U-A10** | 现网设备 9300012 的**演示数据**与 access 通道并行写入 | 验收期间 access 通道每 2s 也在写同一设备同一温度点位（值 23.5） | 判据因此改为"按 ts+value 精确定位"，**不是**按设备总行数；读"最新值"时可能看到 access 的 23.5（属预期，不是 MQTT 丢数据） |
| **U-A12** | **新隧道单元没有停摆告警**（独立复核指出）：既有 `emqx-tunnel-watch.sh` 只判 `emqx-tunnel.service` 与 `127.0.0.1:18093`；入站用的 `-R 127.0.0.1:18084` 与新的出向 `-L 172.20.0.1:18093` 都在 `emqx-ingress-tunnel.service` 里，它"进程在但转发没建起来"时没有任何判据 | 未做 | 入站静默停摆（EMQX 侧看 bridge `failed`/`dropped`，平台侧无感）⇒ **P1-8 巡检必须覆盖**：用同一 watch 脚本加第二实例（`--unit emqx-ingress-tunnel.service` + 两个端口判据） |
| **U-A13** | **同 requestId 的幂等不是原子的**：预查 + `ON DUPLICATE KEY UPDATE` 只保证回执行唯一，不阻止两个**并发**请求都调用 `ingest`（IoTDB 非事务 ⇒ 时序可能双写） | 当前由动作 `inflight_window=1`（同客户端串行）+ 单节点兜住 | 多节点/提高 inflight 窗口前必须改成"插入回执成功者才继续落库"（插入先行、失败即 return） |
| **U-A14** | sa-token 防火墙对 `//`、`..` 类路径返回 **HTTP 200 + text/plain `非法请求：<path>`**（`sa-token-core` 的 `SaFirewallCheckHookForBlackPath`）——又一种"200 = 成功"的静默形态 | 当前**不可达**：动作 URL 回读为 `http://172.28.0.1:18084/internal/mqtt/readings`，无重复斜杠 | 若将来动作 URL / 隧道 / 中继配置引入多余斜杠，双端会全绿而数据不存在 ⇒ 变更 URL 时必须实测一次端到端 |
| **U-A11** | 中间件机防火墙规则的持久性 | 由 `emqx-ingress-firewall.service` 幂等维护，`systemctl enable` 后开机自启 | 若有人在宝塔面板里重置 iptables 规则，本服务不会自动重跑 ⇒ 归 P1-8 巡检（与隧道停摆告警同类） |

---

## 8.1 已决策（**不再跟进**）

| 事项 | 决定 | 依据/备注 |
|---|---|---|
| **`admin.ypbin.cn` 502** | **用户已决定不处理（2026-09-27）**：「`admin.ypbin.cn` 不用管」。**勿再作为待办重新提出**。 | 根因是"删除中间件机旧 ypbin 栈"的**预期连带影响**（宝塔 vhost 把 `/` 反代到已删除的 `127.0.0.1:18080`）；近 7 天的访问命中经核查**全是扫描器/CVE 探测、无真实用户会话**（该结论来自本轮部署记录中的访问日志人工归类，**本节不附原始日志**；如需一手证据请查宝塔站点访问日志，结论强度按"部署记录"而非"独立复核"看待）。将来若要恢复，路径 = **重指向到 `113.142.217.58`** 或**改宝塔 vhost**（本轮**不做**任何生产改动：不碰宝塔、不碰 vhost、不重启 nginx）。 |

## 8.2 已决策：开发测试期开放 1883（**另一条线在落地，本节只引用，不与它矛盾**）

| 事项 | 决定与落地位置 | 生产前必须做的事 | 风险（如实登记） |
|---|---|---|---|
| **真设备 1883 对外暴露** | **已决策：开发测试期开放**——用户口径「可以对外暴露没关系，反正现在是开发测试阶段」（落地见**中间件机** `/opt/emqx/docker-compose.yml` 该端口的注释块与 `${EMQX_MQTT_BIND_ADDR:-0.0.0.0}:1883:1883`，以及 `/opt/emqx/.env` 的 `EMQX_MQTT_BIND_ADDR=0.0.0.0`）。**本节记录的实测**：`2026-09-27`（复核时间同）`docker ps` = `ypbin-emqx … 0.0.0.0:1883->1883/tcp`、宿主 `ss -ltn` = `LISTEN 0 4096 0.0.0.0:1883 0.0.0.0:*`；观测窗口内**无容器写动作**（只读命令 `docker ps`/`ss`，未 exec/restart，口径同 `docs/NACOS-AUTH.md:171` 的"先声明 artifact 三元组 + 窗口内无写动作"）。 | ① **收回 1883 或改 TLS 8883**（生产前必做，二选一）；② **轮换在明文窗口内使用过的设备凭据**（🔴 独立复核 R8 提示：1883 是**明文** MQTT，口令经公网即**视为已暴露**——"收回端口/上 TLS"只能阻止**将来**的嗅探，**不能撤销已经发生的暴露**；① 与 ② 都要做）；③ 只放行**设备来源**（不要全网）；④ 关闭/清理测试账号；⑤ 重跑一次端到端与负向（匿名/越权）用例 | ① 1883 是**明文** MQTT ⇒ **设备口令经公网传输**，理论上可被中间人读取（开发测试期可接受，**生产不可**）；② 端口对全网开放 ⇒ **扫描器/爆破**暴露面变大（EMQX 侧认证失败计数会增长，需与隧道停摆告警同类巡检）；③ 与 §8.2 的"仅放行设备来源"目标相比，当前是更宽的暴露面。 |

> 口径纪律：**本节只陈述"已决策 + 落地在哪 + 生产前必须做什么 + 风险"**，不重复描述另一条线的实施细节；
> 若那条线的实施文档与本节冲突，**以它在 `deploy/emqx/` 的记录为准**，并在本节点明差异（而不是各写一套）。

## 8.3 本回合留下的两条工程教训（值得留档）

1. **单元测试直接构造 JSON 文本 ⇒ 不经过绑定层 ⇒ 报文形态错了也能全绿。**
   实例：`CommandSendReq.params` / `CommandReplyReq.data` 声明成 `String`，而线上报文是 **JSON 对象**——
   全部单测绿，生产一跑就是 `R.code=500`（`MismatchedInputException`）。
   **规则**：报文形态必须**按线上真实形态**构造（对象就用对象/`Map`），并且**至少一条用例走真实绑定层**
   （`ObjectMapper.readValue(线上报文, DTO.class)`）——本仓已补 `IotCommandControllerGateTest#paramsMustBindAsJsonObject`。
2. **计数/拒绝口径必须与数据面在同一层。**
   实例：入站的体积护栏一度写在**控制器**里，绕过了服务层唯一会计数的 `reject()` ⇒
   `iot.mqtt.ingest.rejected{reason=BODY_TOO_LARGE}` 恒为 0（监控规则"看起来配好了、却永不触发"），
   而数据本身仍被正确拒绝——**可观测性假阴性**，独立复核才判出。
   **规则**：凡"拒绝/丢弃"都要有计数出口，且**计数点必须在拒绝判定的同一层**；只有状态码没有计数的守卫一律视为缺陷。

---

## 9. 与设计文档的差异登记（本回合新增）

| 设计处 | 设计写法 | 实测/落地 | 处置 |
|---|---|---|---|
| §3.3 F11 | User Management API 收 `password_hash` + `salt` | 只收明文 `password`；哈希导入要走 `import_users`（multipart `filename`） | 已按实测实现（§4.2），并在代码 Javadoc 与本节登记 |
| §6.1 主题 | `…/up/property` | 一致（任务书里写的 `…/up/property/set` 未采用，ACL 的 `up/#` 两种都放行，但契约以设计为准） | 已按设计落地 |
| §5.1 payload | `{propertyId,value,quality,ts,pollIntervalMs}` | **新增必填 `requestId`**（幂等键；QoS1 重发与桥接重试必然产生重复） | 契约变更已在 §1.1 写清；`requestId` 幂等键落在 `iot_mqtt_ingest_receipt` |
| §6.5 propertyId 白名单落点 | 建议放"入站适配层" | 现状在**服务层** `AvailabilityServiceImpl`（上一批已按用户任务书提前吃掉 P2-7，MQTT 与 HTTP 通道共用） | 未改设计文档；本端点因此**不重复实现**映射校验（避免第二份真源） |
| §8.5 初始化脚本 | 建议扩展 `emqx-init.sh` | 入站单独成 `emqx-ingress-init.sh`（职责分离，既有脚本一行未改） | 二者都幂等、都可单独回滚 |
