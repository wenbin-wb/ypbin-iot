# 启用 access 采集链路（生产实操手册）

> 适用对象：`ypbin-iot` 生产部署（`/opt/ypbin/ypbin-iot`，宿主机 `113.142.217.58`）。
> 本文记录 **2026-09-26** 实际执行「修 fail-open 前置 → 部署 access → 端到端验证」的**可复现步骤、证据与未解缺陷**。
> 部署机纪律见 [`DEPLOY-BACKEND.md`](./DEPLOY-BACKEND.md)：服务器连不上 GitHub、**禁止在服务器跑 mvn**、
> 只认「容器内 jar md5」、先备份再动、给一行回滚。

---

## 0. 结论先行

| 项 | 结果 |
|---|---|
| 前置 fail-open 修复 | ✅ 已完成并回读校验（两键在位、值非占位符、与 `.env` 一致、**除插入的 6 行外逐字未变**、真值只落在 value 行） |
| access 部署 | ✅ 单容器起在 `127.0.0.1:18086`，jar md5 `cac9b848296ccfcb85ef8e6cc758c19d`，`OOMKilled=false`，仅重建 access |
| 数据确实来自 access 链路 | ✅ 已证：access 日志有该设备订阅记录；模拟器记录到 **access 容器 IP `172.20.0.26`** 的连接；`device_liveness`/`outage_event`/Redis 最新值均为**当天新增**；停模拟器⇒数据停、开回来⇒恢复 |
| Redis 最新值 / 可用率 / 断档 | ✅ 真实链路产生 |
| **IoTDB 时序（历史曲线）** | ❌ **未达成**：`iot.reading` **当天 0 行**，`/series` 返回 0 点，且**日志无报错**（静默），原因**未定位** |
| 租约稳定性 | ⚠️ **有缺陷**：租户下存在多台不可达设备时会**自 fencing 抖动**（详见 §6.2） |
| 读数值语义 | ⚠️ TCP 透传交付 `byte[]`，落库为 `[B@<hash>` 这类**无语义**字符串（详见 §6.3） |

---

## 1. 前置：补 `ypbin-access.yaml` 的 trusted-source 两键（修 fail-open）

**为什么必须先做**：access 不引 `ypbin-common.yaml`，拿不到共享配置里的同名键。live 配置缺
`ypbin.cloud.feign.trusted-source-token` 与 `require-trusted-source: true` 时，access 走「未配置即恒可信」
的 **fail-open**——身份头透传没有来源门。必须补齐后 access 才有意义。

**不要用「重跑 install.sh」**：`deploy/install.sh` 的 `NACOS_DIR` 指向 `ypbin-admin/deploy/nacos`，
在本部署下**找不到本仓模板**。

### 做法：显式 dump → diff → POST（脚本化，可 dry-run）

脚本 `tools/fix-access-trusted-source.py`（本仓 `tools/`，只在运维机上跑）：

```bash
# 1) dry-run：dump live、构造新内容、全部校验，但**不写 Nacos**
set -a; . /opt/ypbin/ypbin-iot/deploy/.env; set +a
python3 fix-access-trusted-source.py
# 2) 核对 dry-run 输出无误后再落库（POST + 回读校验）
python3 fix-access-trusted-source.py --apply
```

脚本内建的关键约束（都是踩过的坑）：

1. **只插入缺失块**：以 live 里 `  # access 是下游服务` 注释行为锚点插入 6 行；断言「剥离插入块后与 live 逐字一致」。
2. **只替换非注释行**：真值只写进 `trusted-source-token:` 的 **value 行**；断言「真值在全文中只出现 1 次且该行以该键开头」，
   防止把真值写进注释（上一轮踩过）。
3. **值取自 `.env` 的新 `GATEWAY_SIGN_TOKEN`**，禁止占位符落库（断言值不含 `${`）。
4. **回读校验**：POST 后重新 dump，断言「回读 == 提交」且「**修复前每一行都仍在**」（防止 live-only 键被覆盖丢失）。
5. **每一步都留回滚物**：快照 `/opt/ypbin/nacos-ypbin-access.yaml.bak-<TS>`（600）+ 一键回滚脚本
   `/opt/ypbin/rollback-access-trusted-source-<TS>.sh`（700）。

> ⚠️ **Nacos 写入是「读后写有延迟」的**：POST 返回 `code=0/data=true` 后**立刻**回读可能仍拿到旧内容（实测命中）。
> 回读校验必须**重试/等待**，不要因为一次不一致就判定写失败。

### 一键回滚（还原 live 快照）

```bash
bash /opt/ypbin/rollback-access-trusted-source-<TS>.sh
# access 未启动则无需额外操作；已启动需重启（或等 Nacos 推送）才生效
```

---

## 2. 部署 access（单容器，逐个重启）

```bash
# ① 本地构建（**本地**，不要在服务器跑 mvn）
mvn -pl ypbin-service/ypbin-access -am -DskipTests package
md5sum ypbin-service/ypbin-access/target/ypbin-access-1.0.0-SNAPSHOT.jar

# ② 备份服务器旧 jar（回滚物）
ssh ... 'cp -a <target>/ypbin-access-1.0.0-SNAPSHOT.jar /opt/ypbin/ypbin-access-jar-backup-<TS>.jar'

# ③ 上传 ④ 只构建 access（Dockerfile 只 COPY jar）⑤ 只起 access
scp <jar> root@<host>:<target>/
ssh ... 'cd /opt/ypbin/ypbin-iot/deploy && docker compose -f docker-compose.yml -f docker-compose.override.yml build ypbin-access'
ssh ... 'cd /opt/ypbin/ypbin-iot/deploy && docker compose -f docker-compose.yml -f docker-compose.override.yml up -d --no-deps ypbin-access'

# ⑥ 认版本只看**容器内 jar md5**，不看宿主机文件
ssh ... 'docker exec ypbin-access md5sum /app/app.jar'
```

**起之前先记基线、起之后立刻看 `available`**（本机无 swap）：

```bash
free -m; docker stats --no-stream ypbin-access; docker inspect ypbin-access --format '{{.State.OOMKilled}}'
```

实测：access 常驻约 **580–780 MiB**；宿主机 `available` 由 1248 MB 降到 ~513–750 MB（取决于绑了多少条 TCP 会话）。
若 `available` 逼近 0 或出现 `OOMKilled=true`，**立刻停回**：

```bash
docker compose -f docker-compose.yml -f docker-compose.override.yml stop ypbin-access
```

### 2.1 起 access 之前必须满足的**业务前置**：租户可分配

access 领取租约的来源是 iot 的 `tenant_ledger`（`assignable=true`）；台账为空时才退回配置
`ypbin.iot.lease.assignable-tenant-ids`。**台账为空 ⇒ 节点持有租户 `[]` ⇒ 一个设备都不采**（日志：
`租户领取完成：node=access-1 持有租户=[]`）。

用**平台自己的管理接口**把租户标记为可接入（推荐，走真实业务路径、带 `config_epoch` 与审计）：

```bash
# 登录拿 token（凭据见运维交接，不入库/不入日志）
T=$(curl -sS -X POST http://127.0.0.1:18080/auth/login -H 'Content-Type: application/json' \
      --data @/root/iot-demo/login-req.json | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["accessToken"])')
# 注意：iot 网关路由是 /iot/**（StripPrefix=1），不是 /api/iot/**
curl -sS -X PUT "http://127.0.0.1:18080/iot/tenant-ledger/1/assignable" \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"assignable":true}'
# 回读
curl -sS -H "Authorization: Bearer $T" "http://127.0.0.1:18080/iot/tenant-ledger"
```

回滚：`PUT .../assignable` 传 `{"assignable":false}`。

---

## 3. 模拟设备（**测试工具，不是业务代码**）

生产没有物理设备。工具：**`docs/tools/access-tcp-simulator.py`**（最小 TCP 被动上报设备）。

```bash
# 起（宿主机，默认 19002）
python3 access-tcp-simulator.py --port 19002 --interval 2.0
# 停：按 PID kill（⚠️ 不要用 pkill -f access-tcp-simulator：该模式会匹配到你自己的 ssh 命令行）
kill "$(pgrep -f '[a]ccess-tcp-simulator')"
```

然后在**设备台账**里把目标设备的 `endpoint` 指向模拟器。容器→宿主用 **docker bridge 网关 IP**（实测 `172.20.0.1`）：

```sql
-- 记录原值以便回滚
SELECT endpoint FROM iot_device WHERE id = 9300012;     -- tcp://10.10.0.22:15002
UPDATE iot_device SET endpoint = 'tcp://172.20.0.1:19002' WHERE id = 9300012;
```

> **为什么必须改 endpoint**：演示数据的 endpoint 是 `tcp://10.10.0.x:15002` 这类**不可达的假 IP**（种子数据），
> access 连不上，链路不会产生任何数据。

**与真实设备的差异（不要把它当成采集能力的证明）**：
1. 只会「连上来就发」，不响应任何下行写；
2. 帧内容是固定文本，**不代表任何真实工业协议**（Modbus/OPC UA 的寄存器语义完全没覆盖）；
3. **不模拟断线重连/心跳超时/半开连接** ⇒ 链路健壮性**没有被验证**。

### 3.1 TCP 点位语义（重要限制）

`ypbin-iot-protocol-tcp` 的会话把**每一帧**都作为 `PointValue` 派发，地址恒取**订阅地址列表的第 0 个**
（`request.addresses().get(0)`）。因此：

- 一个 TCP 设备**只有第一个点位**会拿到数据，其余点位永远「未映射」（日志 WARN `采集到未映射的地址，已丢弃`）；
- 值为**原始 `byte[]`**，access 侧不做解码，最终落库是 `String.valueOf(byte[])` 即 `[B@<hash>`。

---

## 4. 端到端验收（真实输出摘录，均标注来源路径）

| # | 验收项 | 来源路径 | 结果 |
|---|---|---|---|
| ① | access 容器状态 / 端口 / 启动日志无真实 ERROR | `docker inspect` / `docker logs` | `running`、`18086`、`OOMKilled=false`、`RestartCount=0`；`读数出口参数自检通过`、`access 启动自检通过：node=access-1`、`1 protocol adapter(s) registered: [tcp]`。**ERROR 仅来自不可达演示设备的建链失败**（预期噪声） |
| ② | 只重建 access | `docker inspect <name> --format '{{.State.StartedAt}}'` | 其余 10 个容器 `StartedAt` 早于 access |
| ③ | 数据确由 access 产生 | access 日志 + 模拟器日志 + 停止实验 | `订阅成功：deviceId=9300012 点位数=4`；模拟器记录 `连接建立：('172.20.0.26', …)`（**= access 容器 IP**）；停模拟器后 `last_good_at` **冻结**，重启后**恢复前进** |
| ④ | 业务接口返回新数据 | `GET /iot/devices/9300012/{availability,latest,series}` | `availability`：`availability=0.996470`、`outageCount=2`、两条**当天新开且已闭合**的断档；`latest`：`temperature` `ts=1790418429047`（**当天**）而其余点位仍是 09-25 的种子值；`series`：**0 点（未达成）** |
| ⑤ | IoTDB 行数增长 | IoTDB CLI | ❌ **未达成**：当天 0 行 |
| ⑤ | Redis field 数增长 | `redis-cli` | ✅ `iot:latest:*` key 由 10 → 12；`iot:latest:1:9300012` 的 `temperature` 时间戳持续前进 |
| ⑥ | 页面侧等价 HTTP 证据 | `curl` | `GET http://127.0.0.1:19000/` → **HTTP 200**、2996 bytes、`<title>Ypbin Admin</title>`；**浏览器渲染仍需人工确认** |

---

## 5. 回滚清单（一行一条）

| 动作 | 回滚 |
|---|---|
| 补了 `ypbin-access.yaml` 两键 | `bash /opt/ypbin/rollback-access-trusted-source-<TS>.sh` |
| 起了 access 容器 | `docker compose -f docker-compose.yml -f docker-compose.override.yml stop ypbin-access`（或 `rm` 容器；旧 jar 在 `/opt/ypbin/ypbin-access-jar-backup-<TS>.jar`） |
| 改了设备 endpoint | `UPDATE iot_device SET endpoint='tcp://10.10.0.22:15002' WHERE id=9300012;`（备份见 `/opt/ypbin/rollback-device-9300012-endpoint.sql`） |
| 临时收窄了演示设备 `status` | `docker exec -i ypbin-mysql mysql … ypbin_admin < /opt/ypbin/rollback-demo-device-status.sql` |
| 租户可分配 | `PUT /iot/tenant-ledger/1/assignable` 传 `{"assignable":false}` |

---

## 6. 已知缺陷与未验证项（**如实声明**）

### 6.1 IoTDB 时序静默不落库（**未定位，未达成验收⑤/曲线**）
- 事实：`iot.reading` 当天 **0 行**；`/series?propertyId=9130001` 当天返回 **0 点**；`latest` 与 `device_liveness` 却在更新。
- live `ypbin-iot.yaml` 的 `ypbin.iot.timeseries.enabled: true`，启动日志也打 `时序写入已启用`。
- `AvailabilityServiceImpl.writeDerived` 对时序写入是**各自兜底并 `log.error`** 的；**日志里没有该 ERROR**，
  即这条路径**没有报错也没有落库**——属**静默失败**，本身违反本仓「禁静默降级」。
- **原因尚未定位**（不做猜测）。**这不构成「数据没来自 access」的反证**：Redis 最新值、`device_liveness`、
  `outage_event` 三条都由同一次 `/internal/readings` 上报驱动，已证为当天新增。
- 建议排查顺序：① 用容器内 jar md5 确认线上 iot 版本是否确含 #33 的时序调用点；② 抓 `iot.timeseries.write.failed`
  指标；③ 直连 IoTDB 手工执行一次同形 INSERT 验连接与库名/`sql_dialect`；④ 在 iot 上开
  `logging.level.cn.ypbin.admin.iot.timeseries=DEBUG` 复跑一批。

### 6.2 租约自 fencing 抖动（**根因已定位，未修**）
- 现象：`协议栈断链停采 … reason=本地租约已过期（未成功续约）` → 重新领取 → 再抖动，周期约 30–120 s。
- 根因（有证据）：租户下 12 台演示设备中 11 台 endpoint 是**不可达假 IP**，而 acquire 后的
  **同步建链**发生在 `LeaseRenewScheduler` 的**同一个调度线程**上，每台耗时约
  `ConnectionSpec.DEFAULT_CONNECT_TIMEOUT = 10s`（框架硬编码，access 的 `HttpDeviceSpecSource`
  传 `null` 因而**不可配**）⇒ 单趟约 120 s ≫ 服务端租约 TTL（30 s）⇒ `renew` 被饿死。
- 反证：把租户临时收窄到 1 台可达设备后，`tenant_node_assignment.lease_expire_at` **持续前进**、
  `epoch` 不再跳、`自行停采` 计数**冻结**。
- 可选修法（**需用户决策，本轮未擅自改**）：① 把不可达设备的 endpoint 改成**快速失败**（拒绝而非超时）；
  ② 调大 iot 侧租约 TTL（会延长故障接管时间，属语义变更）；③ 把建链移出续约调度线程（**代码改动，属修根因**）。

### 6.3 TCP 读数值无语义
值为 `String.valueOf(byte[])` 的 `[B@<hash>`。**不影响**链路成立与断档/可用率判定（判定只看 `quality` 与时刻），
但**最新值展示无意义**，且该点位的曲线只能落文本列。要拿到可读值需要：给 TCP 模块加 `payload-format`
（MQTT 模块已有的能力，TCP 没有）或改用能解码数值的协议模块（如 Modbus）——**均属框架/依赖改动，超出本轮范围**。

### 6.4 对演示数据的影响（**必须知悉**）
真实链路已**改动**演示数据，`docs/DEMO-DATA.md` 中 9300012（`demo-dev-curve`）的以下原定口径**已不再成立**：
- Redis `iot:latest:1:9300012` 的 `temperature` 已被真实链路覆盖（值形态见 §6.3）；
- `device_liveness` 9300012 的 `last_good_at`/`update_time` 变为**当天**；
- 新增 **2 条 `outage_event`**（当天开、当天闭），`availability` 由 1.000000 变为 **0.996470**；
- `iot:latest:*` key 数由 10 变 **12**。

### 6.5 未验证项
- **浏览器渲染**：本手册只给到 HTTP 层证据（19000 返回 200）；曲线/最新值/在线态在页面上的**实际渲染未经人工确认**。
- 内存：`available` 长期在 **~0.5–0.75 GB**、**无 swap**、根盘 94%。access 常驻后余量偏薄，**未做压力验证**。
- 模拟器与真实设备的差异（§3）导致**链路健壮性未被验证**。
- Nacos 控制台仍是默认口令 `nacos`（仅监听 `127.0.0.1:8080`，但仍是**应轮换的弱凭据**）。
