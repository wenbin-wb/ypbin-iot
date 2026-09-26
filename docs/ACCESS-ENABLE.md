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

- 一个 TCP 设备**只有第一个点位**会拿到数据；其余点位**根本不会被投递**（因此也**不会**触发
  `PointMappingDataListener` 的「未映射地址」WARN——独立复核 grep 该 WARN = **0 条**，不要指望用这条日志判断点位漏配）；
  实测佐证：9300012 的 `humidity`/`serialNo`/`demoBoundary` 在 Redis 里始终停在 09-25 的种子值；
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
| ⑤ | Redis field 数增长 | `redis-cli` | ✅ `iot:latest:1:9300012` 的 `temperature` 时间戳随采集持续前进（**这是有效判据**）。⚠️ **不要**用 `iot:latest:*` 的 key 总数当判据：实测 12 个 key 里另有 `iot:latest:1:9990001`/`9990002` 两个**台账内不存在的探针设备**（`iot_device` 中 COUNT=0、内嵌 ts 停在 09-25），**与 access 链路无关**；独立复核已据此判该条为错误归因 |
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

### 6.1 IoTDB 时序静默不落库（**已答复：当前构建下「没收集/没写」皆不成立；验收⑤与曲线已达成**）

> **2026-09-26 19:24–19:30 更新（部署「成功侧可观测」后的在产判据）**
>
> 部署 new jar（容器内 md5 `df221c25aa65527a88cb00ec8857b4a8`，含 `iot.timeseries.write.rows`）后
> **真实采集立刻落库**：
>
> | 指标 | 真值（两次采样） |
> |---|---|
> | `iot.timeseries.points.collected` | **47 → 76**（持续增长） |
> | `iot.timeseries.write.attempted` | **47 → 76** |
> | `iot.timeseries.write.rows` | **47 → 76**（= collected = attempted；⚠️ 这是**驱动回执**口径，见 §6.1b） |
> | `iot.timeseries.write.failed` | **0** |
>
> 日志：`[iot] 时序写入首次落库成功：表=reading 本批行数=1`。
> IoTDB：`WHERE time > 2026-09-26T03:00:00` 由 **0 → 274 行**；9300012 新行时间戳 `19:27:26–19:27:30`，
> `value_text='[B@…'`（与 §6.3 一致）。
> **曲线接口已可取数**：`GET /iot/devices/9300012/series?propertyId=temperature`（今天窗口）
> → `R.code=200`、**311 点**，末点 `ts=1790422050845`。
>
> **⇒ 对「没收集 / 收集了没写」两分支的答复：当前构建下二者皆不成立**（collected = attempted = rows > 0），
> 该症状**已无法复现**。
>
> **⚠️ 同时纠正本手册两处先前的错误结论**：
> 1. **先前「版本落后已排除」的判据太弱**：我只在线上 jar 的 `AvailabilityServiceImpl.class` 里
>    **搜到方法名字符串** `collectSeriesPoints` 就判「排除」。**方法名存在 ≠ 该代码路径可用**，这是不充分的排除。
>    实测：同一份「只加观测、不改语义」的补丁，在原 artifact 上 0 行、在「当前 main + 补丁」上 47→76 行
>    ⇒ **登记为两个「并列」假设，最终归因未完成**：
>    **(H1) 版本漂移** —— 原 artifact 自身有缺陷（其构建 commit 未知）；
>    **(H2) 一次重启治愈的卡死态** —— 原 artifact 逻辑无误，只是运行期卡死，重启即恢复。
>    **判别实验（回滚旧 jar 观察是否停写）决定不做**：需短暂停止写入且有生产风险，
>    而收益仅是历史归因 —— **代价大于收益**（用户 2026-09-26 决定）。
> 2. **先前「`/series` 返回 0 点」部分是我自己的参数错误**：该接口的 `propertyId` 取**标识符**
>    （`temperature`），我填的是数值主键 `9130001`；IoTDB 里存的 `property_id` 同样是标识符。
>    **验收④的「曲线」一项此前被我误判为未达成。**
>
> **⚠️ 后续新增观测（R6-2 独立复核发现，进一步削弱 H1）**：R6-2 实测 `iot.reading` 在 **hour10 UTC
> （= 18:00–19:00 CST，即 my deploy 之前、旧 jar 期间）已有 15 行**。这与我先前「原 artifact **0 行**」
> 的表述**存在张力**：旧 artifact 期间**并非全程 0 行**，而是「我采样时 0、之后又写了若干」⇒
> 更像**间歇性落库/静默丢批**，而非「旧 jar 完全不会写」。因此 **H1（版本漂移）不宜单独成立**，
> H1/H2 之外还应有 **(H3) 旧 artifact 间歇性失败**。三者**均未判别**（判别实验决定不做）。
> **仍未核实**：原 artifact 具体缺哪个上游修复（其构建 commit 未知）⇒ H1/H2 的最终归因**未完成**；
> 判别实验**决定不做**（理由见上）。**不得**把 H1 单独当作已证结论对外表述。
>
> **回滚物**：旧 jar `/opt/ypbin/ypbin-iot-jar-backup-20260926-112302.jar`（md5 `3e1f3fb0dbf87dfa7550657d45dc57b3`）
> + 镜像 tag `ypbin/ypbin-iot:rollback-timeseriesobs-20260926-112302`。
- 事实：`iot.reading` 当天 **0 行**；`/series?propertyId=9130001` 当天返回 **0 点**；`latest` 与 `device_liveness` 却在更新。
- live `ypbin-iot.yaml` 的 `ypbin.iot.timeseries.enabled: true`，启动日志也打 `时序写入已启用`。
- `AvailabilityServiceImpl.writeDerived` 对时序写入是**各自兜底并 `log.error`** 的；**日志里没有该 ERROR**，
  即这条路径**没有报错也没有落库**——属**静默失败**，本身违反本仓「禁静默降级」。
- **原因尚未定位**（不做猜测，也**不接受**「可能是环境问题」收尾）。**这不构成「数据没来自 access」的反证**：
  Redis 最新值、`device_liveness`、`outage_event` 三条都由同一次 `/internal/readings` 上报驱动，已证为当天新增。
- **已用数据排除的项**（2026-09-26 实测）：

  | 候选原因 | 判据 | 结论 |
  |---|---|---|
  | 线上 iot jar 版本落后、没有时序调用点 | 把 `ypbin-iot:/app/app.jar` 取出，用 zipfile 在 `AvailabilityServiceImpl.class` 里搜字符串 | **排除**：`collectSeriesPoints`/`writeDerived`/`writeDerivedAfterCommit`/`TimeSeriesPoint` 全在；`IotDbTimeSeriesWriter.class` 也在 jar 内 |
  | 写入失败（异常/批次失败） | `GET /actuator/metrics/iot.timeseries.write.failed` | **排除**：`count=0.0`（且该 meter 已注册 ⇒ 写入器 bean 已构造） |
  | 点位/值在 ingest 侧被拒或落空 | `iot.ingest.latest.failed` / `.regressed` / `.future_rejected` / `propertyid.orphan` / `.rejected` / `.unmapped` / `shadow.failed` | **排除**：全部 `count=0.0` |
  | 时序写入被关闭 | 启动日志 | **排除**：`时序写入已启用：url=jdbc:iotdb://ypbin-iotdb:6667/iot?sql_dialect=table 表=reading 批量=500` |
  | IoTDB 表/库不存在或列不符 | `DESC iot.reading` | **排除**：表存在，列为 `tenant_id/device_id/property_id/time/value_double/value_text/quality` |

- **剩余假设（需探针，不做结论）**：① `collectSeriesPoints` 实际返回空集（`isEnabled()` 在运行期取到 false，
  或与启动日志所见不是同一绑定路径）；② INSERT 被 IoTDB **无异常地丢弃**（JDBC 批量返回 `EXECUTE_FAILED`/0 行
  而写入器**没有成功侧观测**，因此完全看不见）。
- **下一步（也是修法的第一步，属本仓「禁静默降级」红线）**：给写入路径补**成功侧可观测**——
  在 `AvailabilityServiceImpl.writeDerived` 打 `seriesPoints.size()`、在 `IotDbTimeSeriesWriter` 记录
  「实际写入行数」计数/日志。当前「只有失败计数、没有成功计数」本身就是把该缺陷藏起来的根因，
  补齐后下一次运行即可自我判定到底是「没收集」还是「收集了没写」。
- 复核建议的其余查点（仍有效）：③ 在 iot 容器内用**同一** JDBC URL 手工执行同形 INSERT 排除驱动/权限/方言；
  ⑤ 对「ingest 条数 > 0 而 seriesPoints == 0」与失败计数上告警。

### 6.1b 「成功侧可观测」的第二轮整改：口径修正 + 库内真值对账（2026-09-26，R6 L2 复核）

> **结论**：上一轮把 `iot.timeseries.write.rows` 的描述写成「实际成功行数」是**过度声称**，已修正；
> 并补了唯一能测到「库内真值」的低频对账指标 `iot.timeseries.db.rows`。

- **错在哪（两轮修正）**：
  1. `Statement.SUCCESS_NO_INFO`（`-2`）的语义是「语句成功、但**受影响行数未知**」⇒ `rows` 不能叫
     「实际落库行数」；
  2. 更进一步：本仓钉死的运行时驱动 `iotdb-jdbc:2.0.1-beta` **根本不返回 JDBC 行数**——其
     `executeBatchSQL()` 把 **RPC 状态码**（成功 = 200，失败 = 6xx）逐条塞进返回的 `int[]`
     （一手核实：`javap -p -c org.apache.iotdb.jdbc.IoTDBStatement#executeBatchSQL`，2026-09-26）。
     后果比第 1 点更严重：`rows` 在生产上**恒等于本批条数**（缺口恒为 0），而且**非 200 的行级错误状态码
     也被当成 1 行计入**（既不抛异常、也不进批次级 `write.failed`）。
  > ⚠️ 复核同时指出：上一轮文字里的「探针实证生产驱动回执全 `SUCCESS_NO_INFO`」**没有依据**（与上述字节码
  > 证据矛盾），已从代码与本手册删除；站得住的只有上一段的第 2 点。
- **现在的口径**（都有单测钉住，且逐条做过变异验证）：
  - `iot.timeseries.write.rows`：`executeBatch` 回执中**非失败**的条数（上界钳制到本批条数）——
    **本驱动下它就是「受理条数」，不是行数**；
  - `iot.timeseries.write.rows.noinfo`：回执为 `SUCCESS_NO_INFO` 的条数（**本驱动恒为 0**；
    换驱动/JDBC 实现时口径自动仍然正确）；
  - `iot.timeseries.write.rows.unknown`：驱动违约（`executeBatch()` 返回 `null`）时按整批计入的**未知**条数；
  - `iot.timeseries.write.rows.nonstandard`：**既非 `-3/-2` 也非经典行数 `0/1`** 的回执条数——
    本驱动下 `nonstandard == rows`，这是「`rows` 不能当行数读」的**可观测证据**，
    也把「行级错误状态码被算成成功」这件事摆到台面上。
- **唯一能证伪「ack 了但没落库」的手段**：`iot.timeseries.db.rows` ——
  低频（默认 10 分钟、可配、**绝不高频**）执行一次 `SELECT COUNT(*) FROM reading` 的真值对账；
  未测得之前是哨兵 `-1`（**不是 0**，否则「没测」会被读成「空库」；时序写入关闭时该指标**不存在**）；
  查询失败只计数（`iot.timeseries.db.probe.failed`）+ ERROR 全堆栈、保留上一次取值、**绝不外抛**。
- **判据（必须按同一窗口比「增量」，别把两个量纲直接比）**：
  - ✅ 可取的口径：取一个时间窗（例如 10 分钟），同时记录 `Δwrite.rows`（窗口内受理条数）与
    `Δdb.rows`（窗口内表内行数变化），并扣掉干扰项：① 同刻重复写是 **UPDATE/覆盖**不是新增；
    ② TTL=90 天到期会**删除**行；③ 其它写入方/租户也计入 `count(*)`。在「窗口内确有持续新写入、
    且没有同刻覆盖与 TTL 到期」的前提下，`Δwrite.rows > 0` 而 `Δdb.rows == 0` 才是
    「ack 了但没落库」的**实证**。
  - ❌ 不可取的口径：把 `write.rows`（JVM 生命周期内的**累计受理数**，重启归零）与 `db.rows`
    （**表内当前行数**）直接比大小或比"是否同步增长"——量纲不同，会误判（这一条是复核纠正的）。
- **WARN 覆盖（上一轮 L2 的整改点）**：上一轮删掉两处 WARN（回执与提交数不符、`null` 回执）后
  18 个用例仍全绿 ⇒ 本轮用 logback `ListAppender` 逐条断言 WARN，删掉任一 WARN 必然转红；
  并给这两处告警加了**限流**（同一告警点 ≈30 条/分）。⚠️ 限流**不是「一条都不丢」**：被抑制的条数
  只在下一条放行的同类告警里报出，若违约在窗口内结束则最后一个窗口的条数不会出现在日志里（如实声明）。

### 6.2 租约自 fencing 抖动（**根因已定位，演示数据侧已修，框架侧待立项**）
- **现在做的修法（演示数据侧）**：把 9 台不可达 TCP 演示设备的 endpoint 从假 IP
  `tcp://10.10.0.x:15002` 改为**不可达但快速失败**的 `tcp://127.0.0.1:9`
  （实测容器内连接被**立即拒绝，0.012 s**，而非 10 s 超时）；`status` 保持 1，页面语义不变。
  回滚物 `/opt/ypbin/rollback-demo-endpoints.sql`。
  前后对比见 §4 与下方「修后」。
- **不要用「调大租约 TTL」糊**：那是语义变更（延长故障接管时间），已被否决。
- **框架侧待立项**：`DEFAULT_CONNECT_TIMEOUT=10s` 硬编码且宿主不可配 ⇒ 见 §6.6。

- 现象：`协议栈断链停采 … reason=本地租约已过期（未成功续约）` → 重新领取 → 再抖动，**实测周期约 90 s**
  （独立复核实测：停采→下次成功采集 18:08:28→18:09:58、18:30:36→18:32:06、18:33:16→18:34:46、18:39:16→18:40:46）。
- 根因（有证据）：租户下 12 台演示设备中 11 台 endpoint 是**不可达假 IP**，而 acquire 后的
  **同步建链**与续约跑在**同一个单线程调度器**上（`LeaseRenewScheduler.renew()`
  → `AccessLeaseManager.renewAndSelfCheck()` → `startCollecting()`（`synchronized`）逐设备阻塞；
  access 模块**没有自定义 TaskScheduler** ⇒ Spring 默认单线程池）。每台耗时
  `ConnectionSpec.DEFAULT_CONNECT_TIMEOUT = 10s`（框架硬编码，access 的 `HttpDeviceSpecSource`
  传 `null` 因而**不可配**）⇒ 单趟约 120 s ≫ 服务端租约 TTL（30 s）⇒ `renew` 被饿死。
  最强佐证：`周期重领到租户` 三次都**恰好出现在该趟阻塞结束的同一毫秒**；且 18:30:26 恢复 11 台设备后
  **18:30:36 立刻复发**（一次自然发生的 A/B）。
  **（修正·R6-2 C5）归因收紧**：正确表述是「**不可达设备的慢建链是抖动的主因，已被快速失败消除**」，
  **不是**「抖动已彻底根除」——实测 iot 服务重启（2026-09-26 11:24 UTC）同样触发过一次
  `自行停采` + 接管（`epoch` 11→12）。关键判据应取 **`自行停采`/`本地租约已过期` = 0 且 `lease_expire_at` 持续前进**，
  而不是易失的 ERROR 快照。
- **修前 / 修后对比（真实输出）**：

  | | 修前（假 IP `10.10.0.x`） | 修后（`tcp://127.0.0.1:9`） |
  |---|---|---|
  | 租约 state | `pending_takeover`（无有效持有者） | `active` |
  | `lease_expire_at` | 冻结在 18:42:26（三次采样不变） | **持续前进** 18:47:15 → 18:47:35 → 18:47:45 |
  | `epoch` | **9**（每次接管都 +1，抖动） | **10 且稳定**（不再跳） |
  | `自行停采` | 7 次 | 不再新增 |
  | access 的 `failed to bind device` | 每台各占 10 s（趟内串行） | 仍以 **9 台 / 约 2 分钟** 突发出现，但**单次突发约 8 ms 完成** ⇒ 不再阻塞续约。⚠️ **不要用「近 1 分钟 ERROR=0」当判据**（只是采样落在两次突发之间） |
  | 数据 | 断续 | `last_good_at` 与 Redis ts 持续前进 |

  另有一次**自然 A/B**佐证：18:30:26 恢复 11 台设备后，18:30:36 立刻再次 `本地租约已过期`——
  收窄→稳定、恢复→复发，方向完全一致。


### 6.3 TCP 读数值无语义
值为 `String.valueOf(byte[])` 的 `[B@<hash>`。**不影响**链路成立与断档/可用率判定（判定只看 `quality` 与时刻），
但**最新值展示无意义**，且该点位的曲线只能落文本列。要拿到可读值需要：给 TCP 模块加 `payload-format`
（MQTT 模块已有的能力，TCP 没有）或改用能解码数值的协议模块（如 Modbus）——**均属框架/依赖改动，超出本轮范围**。

### 6.4 对演示数据的影响（**必须知悉**）
真实链路已**改动**演示数据，`docs/DEMO-DATA.md` 中 9300012（`demo-dev-curve`）的以下原定口径**已不再成立**：
- Redis `iot:latest:1:9300012` 的 `temperature` 已被真实链路覆盖（值形态见 §6.3）；
- `device_liveness` 9300012 的 `last_good_at`/`update_time` 变为**当天**；
- 新增 **2 条 `outage_event`**（当天开、当天闭），`availability` 由 1.000000 变为 **0.996470**；
- **（修正·R6-2 A1）** `iot:latest:*` **不是** access 的归因：实测 12 个 key = **10 个设备 key**
  （9300001/2/5/6/7/8/9/10/12/13）+ **2 个台账外探针 key**（`9990001`/`9990002`，`iot_device` 中 COUNT=0，
  内嵌 ts 停在 09-25）。**设备 key 数在 access 之前与之后都是 10**，探针 key 与 access 无关。

### 6.6 待立项：连接超时不可配（框架/接入侧缺陷）
`ypbin-iot-core` 的 `ConnectionSpec.DEFAULT_CONNECT_TIMEOUT = 10s` 是**硬编码常量**，
`ConnectionSpec.of(...)` 在 `connectTimeout == null` 时套用它；而 access 的 `HttpDeviceSpecSource.findConnection`
**固定传 `null`** ⇒ **宿主无法配置建链超时**。后果见 §6.2。
**修法与验收口径**：
1. 给 access 增加配置项（如 `ypbin.access.connect-timeout`）并透传到 `ConnectionSpec`；
2. 更根本地，把逐设备同步建链移出续约调度线程（消费已完成的 future / 独立采集线程池），
   或至少给续约单独一个不与建链共享的单线程调度器；
3. **回归用例**：租户挂 N 台不可达设备时，`renew` 仍必须在 TTL 内完成（可用 `ApplicationContextRunner`
   + 假 endpoint 写集成测试）；并补「客户端自认在租约内、服务端已判失效」的偏差指标，
   避免只能靠事后 `pending_takeover` 发现。
4. 另需确认 `access_node.last_heartbeat_at` 自启动起冻结（复核实测停在 18:03:28）**是否为设计**。

---

## 7. 凭据卫生：排查与自查方式（**硬规则**）

轮换/排查凭据时，**禁止整段 dump / 回读** Nacos 配置或任何可能含 token 的文件——那会把真值送进
会话记录、终端回滚缓冲与日志。**一切校验只用元数据**：

```bash
# 允许：长度 / 指纹 / 计数 / 哈希 / 键名
printf '%s' "$VAL" | sha256sum | cut -c1-16        # 指纹比对（不打印值）
grep -c -F "$OLD" config.yaml                      # 旧值出现处数（应为 0）
grep -c -F "$NEW" config.yaml                      # 新值出现处数（应与替换前旧值处数相同）
md5sum new.yaml readback.yaml                      # 回读对比只比哈希
grep -n -F "$NEW" config.yaml | grep -c ':#'       # 真值不得落进注释行（应为 0）
```

- 需要「像 diff 一样确认没改别的」时：**比 md5**，或比「剥离插入块/替换行后的哈希」。
- 中间文件一律落在 `umask 077` 的 700 目录、文件 600，用完即删；旧值**受控留存**供一键回滚。
- 本仓已有两次同类事故（`INTERNAL_TOKEN` 与 `GATEWAY_SIGN_TOKEN` 明文进入会话记录）⇒ 这两个值**均已轮换**。
- 轮换必须**成对**：`deploy/.env` 与**所有**含该值的 live 配置（`INTERNAL_TOKEN` → `ypbin-common`/`ypbin-access`；
  `GATEWAY_SIGN_TOKEN` → 7 份配置含 `trusted-source-token`），**漏一处就恒 401/403**；
  轮换后按依赖顺序重启**全部持有方**（`INTERNAL_TOKEN` 不含 gateway；`GATEWAY_SIGN_TOKEN` 必须含 gateway）。
- 轮换后判据：`/internal/**` 用**新值 R.code=200 / 旧值 401**（iot 与 system 各验一枚）；各服务 health UP；
  `19000` 页面 200；错口令登录 `R.code=409`；端口仍仅回环；窗口外 ERROR 0。
- 轮换脚本可复用：`rotate-secret.py --env-key {INTERNAL_TOKEN|GATEWAY_SIGN_TOKEN} [--apply]`（默认 dry-run）。

---

## 8. 两个容易踩的接线/运维事实（本轮已做对，记录下来）

1. **起 access 的业务前置是「租户可接入」**：`tenant_ledger` 为空时节点持有租户 `[]`、零采集。
   用**平台自己的管理接口**标记即可（走真实业务路径、带 `config_epoch` 与审计）：
   `PUT /iot/tenant-ledger/{id}/assignable`，body `{"assignable":true}` → 实测 `change=created`、`configEpoch=1`。
   回滚：同一接口传 `false`。
2. **网关路由是 `/iot/**`（`StripPrefix=1`），不是 `/api/iot/**`**：
   iot 的网关谓词是 `Path=/iot/**` + `StripPrefix=1`，直连网关必须写成
   `http://127.0.0.1:18080/iot/devices/...`；写成 `/api/iot/devices/...` 会得到
   `{"code":404,"message":"接口不存在"}`（很容易误判成「接口没实现」）。
   前端从 `19000` 走的是另一条入口（nginx 侧把 `/api` 前缀转发给网关），所以页面上看到的
   `/api/iot/...` 与「直连网关」的路径**不是同一个写法**，排查时别混。
3. **版本判断只认容器内 jar md5，且不能复用服务器上的旧产物**：本部署的源码树停在旧 commit，
   服务器上那份 access jar 的时间**早于该树自身 HEAD**、缺后续提交 ⇒ 必须**本地从 HEAD 重建再上传**。
   判据：`docker exec ypbin-access md5sum /app/app.jar` 与本地构建产物 md5 一致。

---

## 9. 未验证项与已知副作用（汇总）

- **浏览器渲染**：只给到 HTTP 层证据（19000 → 200）；曲线/最新值/在线态的页面渲染**未经人工确认**。
- **IoTDB 时序静默不落库**（§6.1）：未定位，**验收⑤与曲线未达成**。
- **模拟器与真实设备的差异**（§3）⇒ **链路健壮性未被验证**。
- **内存与压力**：`available` 长期 0.5–0.75 GB、**无 swap**、根盘 94–96%；未做压力验证。
  复核期间观测到 load 一度冲到 **86**（滚动重启窗口），说明余量极薄。
- **Nacos 控制台仍是默认口令 `nacos`**（仅监听 `127.0.0.1:8080`），且回滚脚本里曾把该口令明文写死 ⇒ 应轮换。
- **验收期间未设变更冻结**：复核窗口内现场被并发改动，导致「只重建了 access」这类断言随时间失效
  （复核 C3 即如此）。后续验收建议先登记/冻结变更 —— 已升级为规范，见 §10.1。
- **演示数据已被真实链路污染**：见 §6.4 与 `DEMO-DATA.md` 顶部声明。**决定：不复位**（保留真数据）。

---

## 10. 方法论（两条，写成规范）

### 10.1 验收窗口内**冻结改动**（本轮踩过）
第一轮外委复核在 `18:28` 复现通过「只重建了 access」这一断言，但 `18:32–18:36` 的**并发**操作
（令牌轮换 + 滚动重启）让它在 `18:42` 已不成立 —— 复核据此无法给出 PASS 级结论（其 C3 项）。
**规则**：
1. 外委复核**开始前**先登记变更窗口，窗口内**冻结**：不改 Nacos 配置、不轮换凭据、不重启容器、不改库数据；
2. 若验收期间**必须**并发改动，须向复核者**显式登记**改了什么、时间段、影响哪些断言，
   让复核者据此**限定结论作用域**（而不是事后把失效断言当通过）；
3. 凡属「当前态」的断言必须附**取值时刻**；跨时刻的断言要说明是否仍成立。

**4.（硬规矩）冻结窗口必须显式包含「镜像/容器动作」的禁令**：`docker compose build` / `up -d` /
`restart` / `up -d --no-deps` / `docker tag` **一律在禁列**。仅写「不改配置、不轮换凭据、不改库数据」
**不足以**保护断言——**换掉容器里的 artifact 同样会让「只重建了 X」「某结论在某版本上成立」全部失效**。

**5.（硬规矩）验收前先声明被测 artifact 三元组**：`image id` + `容器内 jar md5` + `容器 StartedAt`。
复核者据此判断「你验的是不是我验的那个东西」；三者任一变化 ⇒ 之前对该 artifact 的断言**自动失效**，
必须重新取样而不是沿用。

**6.（硬规矩）用 `docker events` 审计窗口**：复核结束时拉一次窗口内的容器动作时间线
（`docker events --since <窗口起点> --until <窗口终点> --filter type=container`），
把「有没有容器动作」变成**可核对的事实**，而不是靠记忆或事后解释。

**❌ 本轮实例（如实登记）**：我向复核者登记的是「本窗口不会部署、`ypbin-iot` 容器不会被重建」，
但为在产判据，我在 **2026-09-26 11:23:49 UTC 重建镜像、11:24:00 UTC 重启了 `ypbin-iot`**。
后果（复核者实测）：access 在 11:24 一分钟内 **11 次 `读数上报失败`
（`NoFallbackAvailableException`，熔断无 fallback）**、续约失败、**自行停采并发生接管（`epoch` 11→12）**，
11:25 恢复；且「当天 0 行」「只重建 access」等断言的被测 artifact 已变。
**结论：冻结登记必须覆盖「镜像/容器动作」，否则断言失效；登记与执行不一致时，须在动作前重新登记并通知复核者。**

### 10.2 凭据事故处理模板（事故 → 轮换 → 成对更新 → 新旧判据）
本部署已发生两次同类事故（`INTERNAL_TOKEN`、`GATEWAY_SIGN_TOKEN` 明文进入会话/工具输出）。
**固定流程**（照做即可，别再即兴）：
1. **定级与止损**：确认该值**是否仍是 live**（已轮换则影响降级）；确认泄漏面（会话/日志/提交/文档）。
2. **成对列出持有方**：`.env` + **所有**含该值的 live Nacos 配置（`INTERNAL_TOKEN` → `ypbin-common`/`ypbin-access`；
   `GATEWAY_SIGN_TOKEN` → 7 份配置的 `trusted-source-token`）。**漏一处就恒 401/403。**
   - **`INTERNAL_TOKEN` 的持有方清单里没有 `gateway`**（已独立核实）：`ypbin-gateway` 既不发送也不校验
     `X-Internal-Token`，其配置里**没有** `ypbin.internal.token`，也不引用 `ypbin-common`。
     ⇒ **不要为 `INTERNAL_TOKEN` 轮换去重启 gateway**（多做一次无用重启 = 多一次中断窗口）。
   - `GATEWAY_SIGN_TOKEN` 则**必须**含 gateway（gateway 是签名侧；其键名为 `ypbin.gateway.auth.trusted-source-token`）。
3. **dry-run 先扫**：只打印「每份配置含旧值几处 + md5 + 指纹」，确认范围后再 `--apply`。
4. **改 + 回读**：断言「旧值 0 处、新值处数不变、真值不落注释行」；回读**只比 md5 与计数**。
5. **按依赖顺序重启全部持有方**：`nacos → 业务`（`GATEWAY_SIGN_TOKEN` 必须含 `gateway`；`INTERNAL_TOKEN` 不含）。
6. **新旧判据**（缺一不可）：`/internal/**` **新值 200 / 旧值 401**（iot 与 system 各验一枚）；
   经网关**已鉴权**的下游请求仍 `200`（证明签名身份头链路被接受）；各服务 health UP；页面 200；
   错口令 `R.code=409`；端口仍仅回环；**窗口外 ERROR 0**。
7. **留回滚物**：旧值受控留存（600）+ 一键回滚脚本（700）；**脚本内不得出现任何口令明文**
   （从 `deploy/.env` 的 `NACOS_ADMIN_USERNAME`/`NACOS_ADMIN_PASSWORD` 取）。
8. **登记**：把事故、轮换时间、新旧**指纹**（不是值）写进文档；未修的遗留物单列（见 §11）。

**反面样本（本轮实测）**：用 `grep -c "<口令>"` 校验「脚本里没有明文」是**错的**——
该口令恰是常见标识符的子串（`nacos` 出现在 `username=`、`NACOS_ADDR`、容器名、URL 路径里），
计数虚高到 3–19 处。**正确判据是「赋值上下文」**：`grep -c "password=<值>"` → 0。

---

## 11. 登记：未修项（留给后续轮次）

| 项 | 现状 | 归属 |
|---|---|---|
| Nacos 控制台**默认口令** | 仅监听 `127.0.0.1:8080`；口令为默认值 | **下一轮加固**（与「开 auth」一起做，那时 `NACOS_AUTH_IDENTITY_VALUE` 才有意义，一并轮换）。**本轮不动**（配错会全体注册失败） |
| `/opt/ypbin/rollback-20260925-013857.sh` 内**明文写死该口令** | `password=<值>` 仍有 **1** 处 | 遗留脚本，**仅登记**；本轮已把**新写的 6 个脚本**全部改为从 `deploy/.env` 取（`password=<值>` 均为 **0** 处，已 `bash -n` / `py_compile` 自检） |
| IoTDB 时序静默不落库 | 已补**成功侧可观测**（另开 PR），根因待下一次真实采集判定 | 见 §6.1 |
| 连接超时不可配 / 同步建链占用续约线程 | 未修（演示数据侧已用快速失败绕开） | `STARTER-FEEDBACK.md` **UP-2**（框架侧）+ 本仓 ROADMAP（access 侧） |
| 服务器 `/tmp` 残留 | `iotdb-2.0.11.tar.gz`(354M)、`ypbin-iot-{r20,merged}.jar` + `tm-read.jar`(3×132M)、`node.tar.xz`(30M) ≈ **805MB**，**非本轮产物**，未删 | 待用户确认后清理 |

### 10.3 「搜到名字」不等于「路径可用」——弱证据陷阱（本轮实测）

排查「这段代码到底有没有跑」时，我曾在线上 jar 的 `AvailabilityServiceImpl.class` 里**搜到方法名字符串**
`collectSeriesPoints`，就据此把「版本落后」列为**已排除**。**这是不充分的排除**：

- class 里**常量池字符串/方法名存在**，只说明「这段代码曾被编译进来」，**完全不说明**它在运行期被调用、
  没被条件短路、上游没提前 return、依赖装配正确；
- 实测反例：同一份「**只加观测、不改语义**」的补丁 —— **原 artifact 0 行**、**当前 main + 补丁 47→76 行**。
  补丁本身不可能修好写入 ⇒ 差异在 artifact / 运行期，而**先前那条「已排除」是错的**。

**规则**：
1. 「某段代码是否执行」的判据必须是**运行期证据**（指标 / 日志 / 数据落库 / A-B 对比），
   **不能**用静态字符串搜索代替；
2. 静态搜索只能用于排除「**代码根本不存在**」这一最弱情形，措辞必须写明
   「仅排除『代码不存在』，**未**排除『代码未生效』」；
3. 出现「同一代码、不同 artifact 表现不同」时，**默认怀疑 artifact / 运行期状态**，
   并把归因登记为**并列假设**（如 §6.1 的 H1/H2），**不要**为让结论好看而挑一个当已证。
