# ypbin-iot 演示数据（`demo-` 前缀 / 租户 1）

> 用途：让「设备台账 / 可用率 / 断档 / 历史曲线 / 维护窗口」五个界面**开箱就有真实数据可看**。
> 全部演示数据的识别前缀是 **`demo-`**（设备编码、产品编码、分组名、维护窗口 reason），
> 只落在**租户 1**，可被「清理手册」一节整体移除，且**不触碰任何既有数据**。
>
> 本文档同时是**数据来源的如实声明**：每一类数据都标注了走的是「平台业务路径」还是「直连库/CLI」。

---

## 0. 结论先行

| 项 | 值 |
|---|---|
| 设备 | **13 台**（`iot_device`，租户 1，`device_code LIKE 'demo-%'`） |
| 产品 / 物模型属性 | 2 个产品、10 个属性（int/decimal/bool/string/enum 多类型）、2 个服务、2 个版本 |
| 分组 | 2 个（`demo-grp-production` 有 9 台设备；`demo-grp-spare` 空分组），另有 1 台**未分组**设备 |
| 点位映射 | 18 条（`iot_point_mapping`） |
| 维护窗口 | 5 条（未来计划 / 进行中 / 已结束×2 / 租户级历史区间） |
| 断档事件 | 10 条（8 条历史闭合=直连库；1 条真扫描开+真上报闭合；1 条进行中=真扫描开） |
| 活性行 | 10 条（`device_liveness`，由读数上报路径产生） |
| 时序行 | **1392 行**（IoTDB `iot.reading`，`SELECT count(*)`） |
| 最新值 | Redis `iot:latest:1:<deviceId>` **10 个 key** |

---

## 1. 每一类数据走了哪条路径（如实声明）

| 数据 | 写入路径 | 说明 |
|---|---|---|
| 产品 / 物模型 / 点位映射 / 分组 / 设备 | **直连库 SQL** | 台账是配置数据，用 SQL 一次性写入，id 固定、便于清理 |
| 维护窗口 | **管理台 API**：`POST /api/iot/maintenance/windows` | 经网关注入租户身份，走 `MaintenanceWindowService.open`（含「窗口不得重叠」校验） |
| 读数（IoTDB 时序 + Redis 最新值 + `device_liveness` + 断档闭合） | **平台内部端点**：`POST /internal/readings`（`X-Internal-Token`） | 6 批共 1389 条，批批 `R.code=200`；这是**真业务路径** |
| 历史闭合断档事件（8 条） | **直连库 SQL** | 断档只能由 15s 周期的扫描「当刻」打开，无法用它复现任意历史窗口；故这 8 条按算法口径（起点=最后一次有效数据时刻、时长=缺口）直写 |
| `d13` 的断档 | **真扫描开 + 真上报闭合** | 先灌数据让 `last_good_at` 落在 22h 前 → 等 15s 扫描真开断档 → 再用一条 21h 前的有效数据真闭合（`duration=3600s`） |
| `d2` 的断档 | **真扫描开（进行中）** | `last_good_at = now-3h`，扫描按 `K×采集周期` 自动开，且**保持不闭合** |

---

## 2. 看效果导览（逐步可照做）

> 登录：`http://113.142.217.58:19000/` → 平台管理员账号（`admin`，口令见部署交接，**不在本文档内**）。
> 登录后菜单：**IoT 平台 → 设备台账 / 产品与物模型 / 设备分组 / 维护窗口（计划停机）**。
> 设备台账每行右侧有 **「可用率」** 与 **「历史曲线」** 两个按钮（抽屉）。

### A. 设备状态（打开「IoT 平台 → 设备台账」）

| # | 场景 | 看哪一行（设备编码） | 预期看到 |
|---|---|---|---|
| A1 | 在线 | `demo-dev-online` | 在线状态 `online`，最近在线 ≈ 2 分钟前 |
| A2 | 离线（断档未闭合） | `demo-dev-offline` | 在线状态 `offline`，最近在线 ≈ 3 小时前；可用率抽屉显示**进行中**断档 |
| A3 | 从未上报 | `demo-dev-never` | 在线状态 `unknown`，「最近在线」为空；可用率抽屉 24h 内无断档、可用率 100%（零数据≠断档） |
| A4 | 已停用 | `demo-dev-disabled` | `status=0`（列表/编辑表单里可见停用态；停用设备**不再参与断档判定**） |
| A5 | 未分组 | `demo-dev-ungrouped` | 在「设备分组」页的分组树里**找不到它**（`demo-grp-production` 只有 9 台） |

### B. 可用率 / 断档（点设备行 →「可用率」抽屉；默认窗口 = 最近 24h）

| # | 场景 | 设备 | 预期数字（实测） |
|---|---|---|---|
| B6 | 窗口内 100% 可用 | `demo-dev-ok100` | 可用率 **1.000000**、统计总时长 86400s、断档 0 次、**达标** |
| B7 | 单次断档（已闭合） | `demo-dev-single-outage` | 可用率 **0.989583**、断档 1 次 / 900s（15 分钟）、**未达标** |
| B8 | 碎片化多次断档 | `demo-dev-fragmented` | 可用率 **0.977778**、断档 **4 次** / 合计 1920s、最长 480s（每段 8 分钟） |
| B9 | 长断档（2 小时） | `demo-dev-long-outage` | 可用率 **0.916667**、断档 1 次 / 7200s、**未达标** |
| B10 | 断档与维护窗口重叠 | `demo-dev-maint-overlap` | 可用率 **0.956522**；维护窗口 3600s、**维护内断档 3600s**、计入断档 3600s、统计总时长 **82800s**（=86400−3600） |
| B11 | 整窗维护 | `demo-dev-maint-full` | 可用率 **1.000000**、维护窗口 **79200s**、统计总时长仅 **7200s**、断档 0 次（分母被维护扣掉，这是「计划停机不该扣可用率」的可见证据） |
| B12 | 进行中断档（未闭合边界） | `demo-dev-offline` | 断档明细里该行「结束」为空、显示**进行中**；可用率 **≈0.8747** 且随停留时间继续下降 |
| B13 | 真业务路径产生的断档 | `demo-dev-scan-outage` | 可用率 **0.958333**、断档 1 次 / 3600s（由真扫描开、真上报闭合） |
| B14 | 7 天尺度（显式窗口） | `demo-dev-fragmented` | `from=now-7d`：可用率 **0.996296**、断档 **5 次**、合计 1920s；维护窗口 **86400s** 来自**租户级**窗口 ⇒ 该窗口内 1 次断档被排除（维护内断档 600s） |

> B10/B11 的口径就是 `AvailabilityRules`/`AvailabilityCalculator` 的实现口径：
> **统计总时长 = 窗口 − 维护∩窗口**；**计入断档 = 断档 − 断档∩维护**。

### C. 历史曲线（点设备行 →「历史曲线」抽屉；需手工填**点位**与时间范围）

| # | 场景 | 设备 | 点位（`propertyId`） | 时间范围 | 预期 |
|---|---|---|---|---|---|
| C13 | 多点位（数值+文本） | `demo-dev-curve` | `temperature` | 最近 6 小时 | **299 点**，折线连续（数值点位） |
| C13 | 同上 | `demo-dev-curve` | `serialNo` | 最近 24 小时 | **47 点**（文本点位 ⇒ 只出明细表，不画折线） |
| C15 | 小时级密度 | `demo-dev-curve` | `humidity` | 最近 62 小时 | **60 点**（1 小时 1 点，≥48 点） |
| C14 | 数值形态边界 | `demo-dev-curve` | `demoBoundary` | 最近 2 小时 | **12 点**：`42`/`3.14`/`-7`/`1e3` 落 `value_double`；`9007199254740993`（>2^53）/`true`/`false`/`007`/`１２３`/JSON 串/空串 落 `value_text`（真库列级证据见 §3） |
| C18 | 跨自然日曲线 | `demo-dev-curve` | `temperature` | 最近 48 小时 | 曲线跨过当天 0 点（种子数据从 `now-5h` 起，7 天窗口自然跨日） |
| C19 | 空结果 | 任意设备 | 填一个不存在的点位（如 `noSuchPoint`） | 最近 24 小时 | **0 点**，页面显示「无数据」（不会误报成「时序库未启用」） |

### D. 维护窗口（菜单「维护窗口（计划停机）」）

| # | 场景 | 预期行 |
|---|---|---|
| D20 | 未来计划窗口 | `demo-dev-online`（设备 9300001）：`now+1h ~ now+3h` |
| D21 | 进行中窗口 | `demo-dev-ungrouped`（9300005）：`now-30m ~ 空`（结束列为空） |
| D22 | 已结束窗口 | `demo-dev-maint-overlap`（9300010）：`now-11h ~ now-10h`；`demo-dev-maint-full`（9300011）：`now-23h ~ now-1h` |
| D23 | 不同范围 | **租户级**窗口 `device_id=NULL`（`now-72h ~ now-48h`，作用域=全部设备）+ 4 条**单设备**窗口；在 7 天尺度可用率里能看到租户级窗口对每台设备都生效（B14 的 `maintenance=86400s`） |

### E. 产品与物模型 / 分组

| # | 场景 | 预期 |
|---|---|---|
| E24 | 产品与多类型属性 | 「产品与物模型」页有 `demo-prod-th`（已发布，属性：`temperature` decimal / `humidity` int / `switchState` bool / `serialNo` string / `workMode` enum / `demoBoundary` string）与 `demo-prod-meter`（草稿：`voltage`/`current`/`activePower`/`energy`） |
| E25 | 有设备分组 + 空分组 | 「设备分组」页：`demo-grp-production` 9 台、`demo-grp-spare` 0 台 |

---

## 3. 验收证据（真实输出摘录）

```
# ① 设备列表（运维台 token）
GET /api/iot/devices?page=1&pageSize=20   → R.code=200 total=13 items=13

# ② 可用率（默认 24h 窗口，逐个设备）
d1-在线        availability=1.000000  meets=True    outage=0s       count=0
d2-离线进行中   availability=0.874687  meets=False   outage=10827s   count=1（end_ts=NULL）
d6-100%       availability=1.000000  meets=True    outage=0s       count=0
d7-单次15m     availability=0.989583  meets=False   outage=900s     count=1
d8-碎片化      availability=0.977778  meets=False   outage=1920s    count=4
d9-长断档2h     availability=0.916667  meets=False   outage=7200s    count=1
d10-断档∩维护   availability=0.956522  meets=False   maint=3600s outageInMaint=3600s outage=3600s eff=82800s
d11-整窗维护    availability=1.000000  meets=True    maint=79200s eff=7200s
d13-真扫描断档  availability=0.958333  meets=False   outage=3600s    count=1

# ③ 历史曲线（/api/iot/devices/9300012/series）
propertyId=temperature  → 299 点（首 3 条 value=19.14 / 18.99 / 18.61）
propertyId=humidity     → 60 点
propertyId=serialNo     → 47 点（value='SN-DEMO-0001'）
propertyId=demoBoundary → 12 点
propertyId=noSuchPoint  → 0 点

# ④ 维护窗口
GET /api/iot/maintenance/windows → R.code=200 rows=5

# ⑤ IoTDB 容器内
SELECT count(*) FROM iot.reading;  → 1392

# ⑥ 数值/文本分列（真库，demoBoundary 前 12 行）
|2026-09-25T03:46:26.000Z|        42.0|            null|   GOOD|
|2026-09-25T03:47:26.000Z|        3.14|            null|   GOOD|
|2026-09-25T03:48:26.000Z|        -7.0|            null|   GOOD|
|2026-09-25T03:49:26.000Z|        null|9007199254740993|   GOOD|
|2026-09-25T03:50:26.000Z|        null|            true|   GOOD|
|2026-09-25T03:51:26.000Z|         2.0|           false|   GOOD|   ← 同刻重复写
|2026-09-25T03:52:26.000Z|        null|             007|   GOOD|
|2026-09-25T03:53:26.000Z|        null|             １２３|   GOOD|
|2026-09-25T03:54:26.000Z|        null| {"a":1,"b":"x"}|   GOOD|
|2026-09-25T03:55:26.000Z|      1000.0|            null|   GOOD|
|2026-09-25T03:56:26.000Z|        null|                |   GOOD|
|2026-09-25T03:57:26.000Z|        null|    SN-DEMO-0001|   GOOD|

# ⑦ Redis 最新值
HGETALL iot:latest:1:9300012
  temperature   {"v":"22.22","q":"GOOD","ts":1790310266000}
  humidity      {"v":"51","q":"GOOD","ts":1790306786000}
  serialNo      {"v":"SN-DEMO-0001","q":"GOOD","ts":1790308586000}
  demoBoundary  {"v":"SN-DEMO-0001","q":"GOOD","ts":1790308646000}
```

### §3.1 两个「如实说明」的观察（不是缺陷断言，是实测行为）

1. **同刻重复写（C17）**：对同一 `(device, point, ts)` 先写 `false`（文本列）再写 `2`（数值列）——IoTDB 表模型**不新增行**（总行数 1389→1387，少 2 行即两处同刻合并），但**不清除另一列**：该行最终 `value_double=2.0` 且 `value_text=false` 同时非空。
   查询接口按「数值列优先，其次文本列」返回（`IotDbTimeSeriesStore.toResp`），所以页面看到的是 `2`。结论：**单次写入**永远只落一列（`ReadingValueMapper` 的「永不双写」成立），但**跨次写入同刻**会留下另一列的历史残值。
2. **`serialNo` 返回 47 而非 48 点**：查询窗口 `from=查询时刻-24h` 比种子时刻晚了几分钟，恰好把最早那 1 个点排除在窗口外；不是丢数据。

---

## 4. 清理手册（一键清空全部演示数据）

> 三步：**IoTDB 行**（顺带 Redis 最新值）→ **业务表行**。清理**只认 `demo-` 前缀**（断档/活性/点位/成员通过「属于 demo 设备」判定），不会碰其它数据。

```bash
# 变量：demo 设备 id 列表（由前缀推导）
IDS=$(docker exec -i ypbin-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B \
  -e "SELECT GROUP_CONCAT(id) FROM iot_device WHERE tenant_id=1 AND device_code LIKE '\''demo-%'\'';" ypbin_admin')

# ① IoTDB：按 (tenant_id, device_id) 删时序行
for id in ${IDS//,/ }; do
  echo "DELETE FROM iot.reading WHERE tenant_id='1' AND device_id='$id';"
done | docker exec -i ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table

# ② Redis：删最新值 Hash
set -a; . /opt/ypbin/ypbin-iot/deploy/.env; set +a
for id in ${IDS//,/ }; do
  docker exec -e REDIS_PASSWORD -i ypbin-redis sh -c 'redis-cli -a "$REDIS_PASSWORD" DEL iot:latest:1:'"$id"
done

# ③ MySQL：按依赖顺序删业务表（断档/活性/点位/成员 → 设备 → 分组 → 物模型 → 产品）
docker exec -i ypbin-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 ypbin_admin' <<'SQL'
DELETE om FROM outage_event om JOIN iot_device d ON om.device_id = d.id
  WHERE d.tenant_id = 1 AND d.device_code LIKE 'demo-%';
DELETE dl FROM device_liveness dl JOIN iot_device d ON dl.device_id = d.id
  WHERE d.tenant_id = 1 AND d.device_code LIKE 'demo-%';
DELETE pm FROM iot_point_mapping pm JOIN iot_device d ON pm.device_id = d.id
  WHERE d.tenant_id = 1 AND d.device_code LIKE 'demo-%';
DELETE gm FROM iot_device_group_member gm JOIN iot_device d ON gm.device_id = d.id
  WHERE d.tenant_id = 1 AND d.device_code LIKE 'demo-%';
DELETE FROM maintenance_window WHERE tenant_id = 1 AND reason LIKE 'demo-%';
DELETE FROM iot_device WHERE tenant_id = 1 AND device_code LIKE 'demo-%';
DELETE FROM iot_device_group WHERE tenant_id = 1 AND group_name LIKE 'demo-%';
DELETE pr FROM iot_property pr JOIN iot_service s ON pr.service_id = s.id
  JOIN iot_product p ON s.product_id = p.id WHERE p.tenant_id = 1 AND p.product_code LIKE 'demo-%';
DELETE s FROM iot_service s JOIN iot_product p ON s.product_id = p.id
  WHERE p.tenant_id = 1 AND p.product_code LIKE 'demo-%';
DELETE v FROM iot_product_version v JOIN iot_product p ON v.product_id = p.id
  WHERE p.tenant_id = 1 AND p.product_code LIKE 'demo-%';
DELETE FROM iot_product WHERE tenant_id = 1 AND product_code LIKE 'demo-%';
SQL
```

**清理后校验（应全为 0）：**

```bash
docker exec -i ypbin-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "
SELECT \"device\", COUNT(*) FROM iot_device WHERE tenant_id=1 AND device_code LIKE \"demo-%\"
UNION ALL SELECT \"product\", COUNT(*) FROM iot_product WHERE tenant_id=1 AND product_code LIKE \"demo-%\"
UNION ALL SELECT \"group\", COUNT(*) FROM iot_device_group WHERE tenant_id=1 AND group_name LIKE \"demo-%\"
UNION ALL SELECT \"maintenance\", COUNT(*) FROM maintenance_window WHERE tenant_id=1 AND reason LIKE \"demo-%\"
UNION ALL SELECT \"outage\", COUNT(*) FROM outage_event WHERE tenant_id=1
UNION ALL SELECT \"liveness\", COUNT(*) FROM device_liveness WHERE tenant_id=1
UNION ALL SELECT \"point_mapping\", COUNT(*) FROM iot_point_mapping WHERE tenant_id=1;" ypbin_admin'
printf "SELECT count(*) FROM iot.reading;\n" | docker exec -i ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table
```

**重新造数（幂等）**：种子脚本会先按同样的 `demo-` 口径清理（含 IoTDB/Redis）再重建，
放在服务器 `/root/iot-demo/seed_demo.py`（600 权限，读 `.env` 里的内部凭证，不打印任何 token）：

```bash
python3 /root/iot-demo/seed_demo.py
```

---

## 5. 菜单归并的回滚（与演示数据无关，但在同一批次）

```bash
docker exec -i ypbin-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot ypbin_admin' \
  < /root/iot-demo/rollback-2026-09-25-iot-menu-group.sql
# 内容：UPDATE sys_menu SET pid=0 WHERE id IN (3200,3201,3202,3203);
#       DELETE FROM sys_role_menu WHERE menu_id=3204;
#       DELETE FROM sys_template_menu WHERE menu_id=3204;
#       DELETE FROM sys_menu WHERE id=3204;
```

菜单备份（迁移前）：`/root/iot-demo/backup-<ts>/sys_menu_grants.sql`（`mysqldump ypbin_admin sys_menu sys_role_menu sys_template_menu`）。

---

## 6. 已知边界（诚实清单）

1. **维护窗口的「分组」范围不存在**：`maintenance_window` 只有 `device_id`（单设备）或 `NULL`（租户全部）两种范围，**没有分组维度**；本文用「租户级历史窗口 + 单设备窗口」覆盖「不同范围」。
2. **租户级窗口与「进行中」窗口互斥**：平台的不重叠不变量把「进行中（`end_ts=NULL`，覆盖未来任意区间）」视为与任何租户级窗口重叠 ⇒ 二者不能同时存在；因此租户级演示窗口落在**历史区间**，不影响默认 24h 报表。
3. **演示设备的 `pollIntervalMs` 取 24h**（真接入时由 access 按点位周期上报）：断档由 15s 扫描按 `K×采集周期` 打开，若按真实 1 分钟周期填，所有「无断档」设备几分钟后都会被判成断档。这是为了让演示在数小时内**稳定**的取舍；点位**数据本身的密度**不受影响（温度 1 分钟 1 点）。
4. **`duration_sec` 列只是展示用**：可用率 SQL 用 `start_ts/end_ts` 现算窗口内秒数，所以造数时 `duration_sec` 必须等于 `end-start`（本仓已按此写入）。
5. **活库当前 `sys_menu.title`（id=3204）是中文兜底值「IoT 平台」**：因为运行中的前端产物还没有 `page.iot.title` 键；前端产物重建部署后应改回 `page.iot.title`（见 `rollback-menu-title.sql`）。
