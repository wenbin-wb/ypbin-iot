# 时序库（Apache IoTDB 表模型）部署与运维

> 覆盖：**IoTDB 服务编排、一次性建库建表、写入/查询验证、改保留期（TTL）、排障**。
> 设计与契约见 [`IOT-PLATFORM-DESIGN.md`](IOT-PLATFORM-DESIGN.md) §5.2.1；前端部署见 [`DEPLOY-UI.md`](DEPLOY-UI.md)。
> 涉及文件：`deploy/docker-compose.yml`（服务 `iotdb` / `iotdb-init`）、`deploy/iotdb-init.sql`、`deploy/iotdb-init.sh`、
> `deploy/nacos/ypbin-iot.yaml`（`ypbin.timeseries.*`）。
> 本文所有外部事实均来自官方一手文档，链接与访问日期见 §8。

## 0. 结论速览

1. **一条命令就有时序能力**：`docker compose -f deploy/docker-compose.yml up -d` 会起 `iotdb`（时序库）
   和 `iotdb-init`（一次性建库建表）。DDL 幂等（`IF NOT EXISTS`），重跑无副作用。
2. **⚠️ 最容易踩的坑（已核实）**：写入器/查询用的是**非限定表名**（`INSERT INTO reading` / `FROM reading`），
   所以 Nacos 里的 JDBC URL **必须带库名** `jdbc:iotdb://ypbin-iotdb:6667/iot?sql_dialect=table`。
   少了 `/iot`，IoTDB 会拒绝这类语句（报"未指定数据库"；该报文的一手出处见 §8，出自 ≤2.0.7 文档，
   2.0.11 上的确切码/文案**未实测**）——服务能起来、写入全失败。
3. **6667 默认只绑回环**（用**独立**变量 `IOTDB_BIND_ADDR`，不设时 = `127.0.0.1`），因为默认口令
   `root/root` 是公开的。之所以不复用 `INTERNAL_BIND_ADDR`：本仓 `deploy/.env.example` 把它设成了 `0.0.0.0`，
   共用会让"手抄 .env.example"顺带把 6667 暴露到全网（详见 §1 与 §6）。
4. **改保留期不是重建表**：官方支持 `ALTER TABLE iot.reading SET PROPERTIES TTL=<毫秒>`（见 §4），
   它是 DDL/迁移动作（会短暂影响数据可查询性），但**不需要重建表、不丢数据**。
5. **`ypbin.timeseries.enabled: true` 是 fail-fast 的**：url 为空、表名非法、`batch-size`/`connect-timeout-ms`
   非正 → `ypbin-iot` **拒绝启动**（不是静默降级）。
6. **⚠️ 前置条件：驱动必须真的被"注册"，光在类路径上不够**（jar 无 SPI 已核实；"缺它则启动失败"为**源码级推断**，部署阻塞级）。
   `org.apache.iotdb:iotdb-jdbc:2.0.1-beta` 的 jar **没有** `META-INF/services/java.sql.Driver`
   （本机下载 Central 制品 + `zipfile` 核验：jar 105258 字节，`META-INF/services` 条目 **0 个**，
   `org/apache/iotdb/jdbc/IoTDBDriver.class` 存在、只有 `OSGI-INF`）⇒ **JDBC 4 的 SPI 自动注册不会发生**，
   必须由代码显式 `Class.forName("org.apache.iotdb.jdbc.IoTDBDriver")`（官方 JDBC 示例正是这么写的）。
   平台侧需要显式注册（本分支的修复以 `IotDbDriverRegistrar.ensureRegistered()` 的形式引入，
   在启动自检里先注册再 `DriverManager.getDriver`）。
   **没有它，`enabled: true` 会让 `ypbin-iot` 启动即失败**（`DriverManager.getDriver` 抛 SQLException →
   `IotTimeSeriesConfiguration` 抛 IllegalStateException）。⚠️ 因此部署前**必须核对注册代码在你要部署的提交里**（下面这条命令**输出必须非空**，才可带 `enabled: true` 上线）；
   若在那个提交上 grep 为空（也就没有上述类），请先把 `ypbin.timeseries.enabled` 改回 `false`，否则服务起不来：

   ```bash
   # 合并/部署前的硬检查：恰好 1 条命中（生产调用点）才可带 enabled: true 上线；空则先改回 false。
   # ⚠️ 两个必须注意的点（都踩过/推演过）：
   #   1) 显式指定「要部署的那个提交」，别查工作树——工作树里可能正躺着未提交的修复（会假通过）；
   #   2) 别用宽模式 `Class.forName\|ensureRegistered` 搜整个模块：它会命中**类自身的定义/Javadoc/测试**，
   #      于是"类已提交但从未被调用"也会非空 ⇒ 死代码假通过（实测：宽模式 2 条命中里只有 1 条是生产调用点）。
   git grep -n "IotDbDriverRegistrar\.ensureRegistered()" <要部署的提交SHA> -- \
     ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/timeseries/IotTimeSeriesConfiguration.java
   ```

## 1. 组件与端口

| 项 | 值 | 说明 |
|---|---|---|
| 镜像 | `apache/iotdb:2.0.11-standalone` | 官方 Docker Hub `apache` 命名空间；tag 已用 `docker manifest inspect` 实测可解析（§8） |
| 服务名 / 容器名 | compose 服务名 `iotdb`；`container_name` 与 `hostname` 都是 `ypbin-iotdb` | 应用侧 JDBC URL 用 `ypbin-iotdb`（自定义网络内可解析）；容器内 CLI 互连也用 `-h iotdb`（服务名同样可解析） |
| 固定 IP | `172.20.0.13` | `ypbin-net` 内既有占用：.10/.11/.12/.20-.24/.26/.30/.40/.41，.13 空闲 |
| 客户端 RPC 口 | `6667` → 宿主 `${IOTDB_BIND_ADDR:-127.0.0.1}:6667` | JDBC 走这个口；⚠️ 用**独立**变量而非 `INTERNAL_BIND_ADDR`（后者在 `.env.example` 里是 `0.0.0.0`，共用会把公开默认口令的 6667 暴露到全网） |
| 内部端口 | 10710/10720/10730/10740/10750/10760 | ConfigNode/DataNode 内部通信，**不发布到宿主**（仅容器网络内） |
| 数据 / 日志卷 | `ypbin-iotdb-data` → `/iotdb/data`、`ypbin-iotdb-logs` → `/iotdb/logs` | 具名卷，`down` 不删；`down -v` 会删**全部时序数据** |
| 健康检查 | `start-cli.sh -h ypbin-iotdb … -e "SHOW DATABASES"` | **自建**探针（官方无 Docker 健康检查方案，§7），30s 间隔 / 90s 启动宽限。⚠️ **必须带 `-h ypbin-iotdb`**：`dn_rpc_address=ypbin-iotdb` 是**绑定地址**（只绑容器 eth0），`-h 127.0.0.1`（CLI 默认）**必然连不上**——本机实测见 §7 |
| 资源下限 | 2–4 核 / 2–4G 内存（≤10 万测点，standalone） | 官方 Database Resources 给出的 standalone 档位（§8） |

## 2. 一次性初始化（`iotdb-init`）

- **DDL 本体**在 `deploy/iotdb-init.sql`（一行一条语句；`CREATE DATABASE IF NOT EXISTS iot` +
  `CREATE TABLE IF NOT EXISTS iot.reading (…) WITH (TTL=7776000000)`，TTL 毫秒 = 90 天）。
- **执行器**是 `deploy/iotdb-init.sh`：等 RPC 口可用 → 按行读取 SQL → 用官方 CLI 的非交互模式
  `start-cli.sh -e "<语句>"` 逐条执行 → 回读 `SHOW TABLES FROM iot`。
- 两者以只读方式挂进 `iotdb-init` 容器（`/init/…`）。该容器 `restart: "no"`，跑完即退出：
  **退出码 0 = 成功，非 0 = 失败**（`docker compose ps -a` 可见）。
- **不会静默通过**（四道显式失败）：① `/init/iotdb-init.sql` 缺失或为空 → 立即 `exit 1`；
  ② 文件里**一条可执行语句都没有**（只有空行/注释）→ `exit 1`（否则主循环零次执行会"顺利成功"）；
  ③ 任一语句执行失败 → 整体重试 30×6s 后 `exit 1`；④ 末尾 `SHOW TABLES FROM iot` 回读失败 → `exit 1`。
  （脚本行为已用桩 `start-cli.sh` 实跑覆盖：正常/持续失败/仅回读失败/全注释/文件缺失/末行无换行 六种路径。）
- **口令**：`iotdb-init` 用 `IOTDB_USER`/`IOTDB_PASSWORD`（compose 里注入，默认 `root`/`root`）。
  这是口令的**第三处**，改口令时三处必须一起改，见 §6。
- 为什么不用 `install.sh` 而是放 compose：`install.sh` 的 Nacos 占位符替换键是**固定 4 个**
  （`MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` / `INTERNAL_TOKEN` / `GATEWAY_SIGN_TOKEN`），时序初始化与它无耦合；
  放 compose 则 `up -d` 一条命令可复现，不需要动那个 96KB 的脚本。
- 手动重跑（幂等，可随时执行）：
  ```bash
  cd deploy && docker compose up -d --force-recreate iotdb-init && docker logs ypbin-iotdb-init
  ```
- **启动顺序**：`ypbin-iot` **刻意不** `depends_on: iotdb`——时序写入失败本就不回滚上报事务（只计数 + ERROR 日志），
  加依赖只会把"启动顺序"和"IoTDB 可用性"耦合起来（用 `service_started` 只保证顺序、不会阻塞；一旦改成
  `service_healthy`，IoTDB 不健康就会**直接阻塞 ypbin-iot 启动**，把单点故障放大成全链路起不来）。
  代价是首次 `up -d` 后的一小段时间里写入会失败、查询会抛 `BusinessException`
  ⇒ **等 `iotdb-init` 退出码为 0 之后再开始上报/查询**（§3.1）。

> ⚠️ **`IF NOT EXISTS` 的语义**：表已存在时 `CREATE TABLE IF NOT EXISTS … WITH (TTL=…)` **不会**更新 TTL，
> 只是跳过。改 TTL 必须显式 `ALTER TABLE`（§4）。

## 3. 验证（起完服务后按序做这 4 步）

> ⚠️ **口令前提（2026-09-26 补，别踩）**：本节及 §5/§8 里的 `start-cli.sh … -e "…"` 示例**都省略了 `-pw`**，
> 而镜像内 wrapper 的默认值是 `passwd_param="-pw root"`（见 `/iotdb/sbin/start-cli.sh`）——
> 也就是说**这些命令只在口令仍是官方默认 `root` 时可用**；按 §6.1 轮换后它们会以 `801` 失败
> （那时「验证失败」是口令不对，不是功能坏了）。
> 轮换后请改用下面的包装函数（口令与 SQL **都走 stdin**：既不进 argv，也没有引号转义问题）。

```bash
iot() { { printf '%s\n' "$(sed -n 's/^IOTDB_PASSWORD=//p' /opt/ypbin/ypbin-iot/deploy/.env | head -1)"
          printf '%s;\n' "$1"; } \
  | docker exec -i ypbin-iotdb start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw -sql_dialect table; }
# 用法： iot "SHOW DATABASES"   /   iot "SHOW TABLES FROM iot"
# 说明：裸 `-pw` 让 CLI 从 stdin 首行读口令；⚠️ 交互模式**无论语句成败都退 0** ⇒ 本函数只用于**只读查询**，
#      自动化判定请用 §6.1 的 `run_cli`（带 `-e`，退出码可靠）
```

### 3.1 容器与初始化

```bash
docker compose -f deploy/docker-compose.yml ps iotdb iotdb-init
docker logs --tail 40 ypbin-iotdb-init          # 期望：DDL 执行完毕 + SHOW TABLES 输出里有 reading
```

### 3.2 库表与 TTL（服务端侧）

```bash
# 库在不在（CLI 默认是树模型，表模型必须显式 -sql_dialect table）
# ⚠️ 不带 -pw ⇒ 只在口令仍是官方默认值 root 时可用；轮换后用 §3 开头的 iot() 包装函数
docker exec ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table -e "SHOW DATABASES"

# 表在不在、TTL 对不对（期望 TTL(ms)=7776000000）
docker exec ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table -e "SHOW TABLES FROM iot"
```

### 3.3 写入 → 查询往返（服务端侧，绕开应用）

```bash
# 写一条（未指定 value_text，官方语义：未指定的列自动填 null）
docker exec ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table -e \
  "INSERT INTO iot.reading(tenant_id, device_id, property_id, time, value_double, quality) VALUES ('1','1','temp','2026-09-24 12:00:00', 1.5, 'GOOD')"

# 读回来（count(*) 语法见官方 Select Clause §3.1.2）
docker exec ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table -e "SELECT count(*) FROM iot.reading"
```

> 若带库名的表名被 CLI 解析拒绝，改用交互式会话里 `USE iot;` 后再查，或先用 `SHOW TABLES FROM iot` 作为元数据判据。

### 3.4 平台侧（应用真正在用的一条路）

```bash
# 1) 启动自检：驱动可用 + 表名合法（enabled=true 时必打这行，没打说明 enabled 没生效）
docker logs ypbin-iot 2>&1 | grep "时序写入已启用"

# 2) 端到端：上报一条读数（走 /internal/readings）后查历史曲线
#    GET /iot/devices/{deviceId}/series?propertyId=&from=&to=&limit=   （权限码 iot:series:get）
# 3) 写入失败会累计指标 iot.timeseries.write.failed，并打 ERROR 日志（不静默）：
docker logs ypbin-iot 2>&1 | grep "时序写入失败"
```

## 4. 改保留期（TTL）—— 是 DDL，不是重建表

**已核实**（官方 Table Management §2.5：`SET PROPERTIES` 目前只支持 TTL；TTL Delete Data §2.1 示例 2）：

```bash
# 表级 TTL 改 180 天（15552000000 ms）
docker exec ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table -e \
  "ALTER TABLE iot.reading SET PROPERTIES TTL=15552000000"

# 去掉 TTL（永不过期）
docker exec ypbin-iotdb start-cli.sh -h ypbin-iotdb -sql_dialect table -e "ALTER TABLE iot.reading SET PROPERTIES TTL='INF'"
```

要点：

- **表级 TTL 优先于库级**；库级 TTL 只是「新建表时的默认值」，改库级 TTL **不会**追溯影响已存在的表。
- `ALTER TABLE … TTL` 是 **迁移动作**，不是热配置：官方 TTL 文档明确「修改 TTL 可能短暂影响数据的可访问性」，
  过期数据的物理删除是**异步**发生的（由 compaction 完成），所以磁盘不会立刻回收。
- TTL 判定用的是**数据点时间戳**，不是写入时间。
- ⚠️ **官方文档自相矛盾处（本平台不受影响）**：TTL 页 §3 写「IoTDB 当前不支持修改数据库级 TTL」，
  而 Database Management §2.6 写 `ALTER DATABASE … SET PROPERTIES TTL=` 可改 TTL。本平台把 TTL 放在
  **表级**，两条说法都不影响我们；若将来要改库级 TTL，以 Database Management（更新日期 2026-09-11）为准并自行实测。
- 90 天（`7776000000` ms）是 D0.8 的原始时序保留期口径；改它要同步考虑 §5.2.1 的报表/曲线窗口。

## 5. 排障

| 现象 | 根因 | 处置 |
|---|---|---|
| `ypbin-iot` 启动失败：`… IoTDB JDBC 驱动不可用（请确认 org.apache.iotdb:iotdb-jdbc 在运行时类路径）` | ① jar 不在运行时类路径；**或 ② jar 在但没被注册**（该 jar 无 `META-INF/services/java.sql.Driver`，SPI 不会自动注册，见 §0.6） | ①在宿主机查证打包结果：`unzip -l ypbin-service/ypbin-iot/target/ypbin-iot-*.jar \| grep iotdb-jdbc`（本服务镜像是 fat jar，依赖在 `BOOT-INF/lib`，**不是** `/app/lib`；temurin JRE 镜像里没有 `unzip`/`jar`，别在容器里查）；②确认部署的提交里有 `IotDbDriverRegistrar.ensureRegistered()`（显式 `Class.forName`） |
| 启动失败：`table-name 只允许字母/数字/下划线` | Nacos 里 `table-name` 写了非法值（如 `iot.reading`、带引号） | 只写裸表名 `reading`；库名放 URL 路径里 |
| 写入失败日志「未指定数据库」（设计文档/≤2.0.7 文档写的码是 `701`，2.0.11 确切码未实测） | JDBC URL 缺库名（见 §0.2） | URL 改成 `jdbc:iotdb://ypbin-iotdb:6667/iot?sql_dialect=table` |
| 写入/查询报「表/列不存在」（错误码 **616** `COLUMN_NOT_EXIST`） | 未跑 `iotdb-init`，或表名与 DDL 不一致 | `docker logs ypbin-iotdb-init`；必要时 `--force-recreate iotdb-init` |
| 查询报「类型不匹配」（错误码 **614** `DATA_TYPE_MISMATCH`） | 写入值与列类型不符（如把文本写进 `value_double`），或 DDL 与代码列顺序漂移 | 核对 `deploy/iotdb-init.sql` 与 `IotDbTimeSeriesWriter.COLUMNS` 是否逐列一致 |
| `801: Username or password is wrong` | IoTDB 侧改了口令，Nacos 或 `iotdb-init` 没同步（或反之） | §6 的**三处联动** |
| `iotdb-init` 一直重试后失败 | IoTDB 未就绪 / 资源不足（内存不够会被 OOM kill）/ 口令未同步 | `docker logs ypbin-iotdb`、`docker logs ypbin-iotdb-init`；对照 §1 的资源下限与 §6 的三处口令 |
| 健康检查一直是 `starting`/`unhealthy` | **本机实测根因**：探针写的是 `-h 127.0.0.1`（也是官方 CLI 的默认值），而 `dn_rpc_address=ypbin-iotdb` 是**绑定地址**、服务端只绑容器 eth0 ⇒ 容器内 loopback 无监听，`start-cli.sh` 报 `Connection Error` 并以退出码 1 结束 | 探针与文档命令都加 `-h ypbin-iotdb`（本仓 compose 与 §3 已如此）。**不影响数据面**：容器网络内按主机名/服务名连接一切正常（本机实测） |
| 写入持续失败：`iot.timeseries.write.failed` 增长，ERROR 日志含 `The parameter cannot be null` | 对**未用的值列**显式 `setNull`，而驱动（`2.0.1-beta`）直接拒绝 null 参数（本机真库实测，`IoTDBPreparedStatement.setNull`） | 升级到含「按读数类型分两条 INSERT、未用列不出现在语句里」修复的版本；**不要**改回「一条语句 + setNull」——那种写法真库上数值/文本行**全部写不进去**，而写入器只计数不抛 ⇒ 单测照绿、数据全丢（本仓真实踩坑） |
| 查询报 `616: Column 'xxx' cannot be resolved` 或 `701: Cannot apply operator: STRING = INT32`（即使只是按点位查） | 查询用了 `PreparedStatement` + `?`：驱动把参数做**无引号文本替换**（本机真库实测），STRING TAG 谓词因此失效；官方表模型 JDBC 文档也只给 `Statement` 示例 | 查询侧用字面量 SQL（`IotDbTimeSeriesStore` 已如此）；点位标识走白名单 `[A-Za-z0-9_.:-]{1,128}` + 单引号转义，非法即报业务错误（不静默过滤） |

**关于错误码 614 / 616（✅ 已核实）**：官方表模型
[Write & Update Data §1.4 Notes](https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Write-Updata-Data_apache.html)
原文给出：`Referencing non-existent columns in SQL will result in an error code COLUMN_NOT_EXIST(616).`
与 `Data type mismatches … DATA_TYPE_MISMATCH(614).`（访问 2026-09-24）。
⚠️ 注意这两个码**不在**状态码总表里（[Status Codes](https://iotdb.incubator.apache.org/UserGuide/latest/Reference/Status-Codes.html)
止于 1403），所以"状态码页查不到"不等于"没有这个码"——**这正是本次第一版文档踩过的坑，已更正**。

> 附：镜像内**按 Dockerfile 推断有 `curl`**（基础镜像 `eclipse-temurin:17-jre-focal` 的
> [adoptium 官方 Dockerfile](https://raw.githubusercontent.com/adoptium/containers/e5b2b79da44d9488979a9a46d3cdebe992d3a804/17/jre/ubuntu/focal/Dockerfile)
> 显式安装 curl，注释写明 `curl required for historical reasons`；IoTDB 的 Dockerfile 未删任何包。
> ⚠️ **未在容器内 `command -v curl` 实测**，因此这只是"按 Dockerfile 推断"），**没有 `nc`**。
> 但 6667 是 Thrift RPC 口，**curl 对它没有意义**（不是 HTTP）⇒ 探活请用
> §3.2 的 CLI 查询，或最轻量的 bash TCP 探活
> `docker exec ypbin-iotdb bash -c 'exec 3<>/dev/tcp/ypbin-iotdb/6667'`。
> ⚠️ **必须连 `ypbin-iotdb` 而不要用 `127.0.0.1`**：`dn_rpc_address` 是**绑定地址**，设成主机名后服务端
> 只绑容器 eth0，loopback 无监听 ⇒ 探 `127.0.0.1:6667` 会**永远失败**（本机实测：同一容器
> `ypbin-iotdb` → 0、`127.0.0.1` → `Connection refused`）。这与 §3.2 用 CLI 时**必须带 `-h ypbin-iotdb`**
> 是同一个原因（详见 §7「健康检查方式」）。
> （curl 只有在启用了 IoTDB REST 服务时才有用，那是另一个端口。）

## 6. 安全与容量

- **6667 = 明文协议；口令必须轮换，端口仍只绑回环**。IoTDB 官方**默认**用户/口令是 `root` / `root`
  （官方 Authority Management 明写的默认值，**公开**）。生产必须按 §6.1 轮换，且 `IOTDB_BIND_ADDR`
  不设时只绑 `127.0.0.1`（见 §1）——口令只是第二道锁，两件事都要做。
  > 本仓**不记录**线上口令状态（口令值不入库）；轮换的执行记录落在轮换工作目录（700）与部署回执里。

### 6.1 口令轮换与「口令不进 argv」

**为什么必须轮换**：改动前的健康检查是
`start-cli.sh -h ypbin-iotdb -u root -pw <口令> -sql_dialect table -e "SHOW DATABASES"`，
**每 30 秒**一次 ⇒ 明文口令持续出现在进程 argv（`docker top ypbin-iotdb` 实测抓得到
`… org.apache.iotdb.cli.Cli -h ypbin-iotdb -p 6667 -u root -pw <口令> -sql_dialect table -e SHOW DATABASES`）
以及 `docker inspect -f '{{json .Config.Healthcheck.Test}}' ypbin-iotdb`。
既然它长期以明文落在可被读取的地方，旧口令**按已泄露处置**。

**为什么不是「换个方式传口令」**（一手核实，2026-09-26，读镜像内 `/iotdb/sbin/start-cli.sh` 原文）：
该脚本的口令只有 `-pw <值>` 一条路——`checkEnvVariables` 只识别 `IOTDB_INCLUDE` / `IOTDB_CLI_CONF`
两个 `-D` 变量，**没有**口令相关的环境变量或配置文件入口（脚本里 `passwd_param="-pw root"` 的默认值本身就是公开口令）。
⇒ 「受管 600 文件 / 环境变量」两条路都不通，于是分场景取两条不同的路：

| 场景 | 方案 | 理由 |
|---|---|---|
| `iotdb` 健康检查 | **不发口令的 TCP 探活**：`timeout 5 bash -c '</dev/tcp/ypbin-iotdb/6667'` | 探针不需要凭据；不带口令就没有可泄露的东西（折衷：它不校验凭据） |
| `iotdb-init` 建库建表 | 口令经 **stdin** 送入：`printf '%s\n' "$IOTDB_PASSWORD" \| start-cli.sh … -pw -e "<SQL>"` | 它必须认证；经实测，`-pw` **后面不跟值**时 CLI 转为从 stdin 读口令（`please input your password:`），且**仍保留 `-e` 批处理模式的退出码语义**（口令错 ⇒ 1、SQL 失败 ⇒ 1） |
| 「凭据到底对不对」 | 应用侧低频业务探针：`iot.timeseries.db.rows`（`SELECT COUNT(*)` 真值对账）+ `iot.timeseries.db.probe.failed` | healthcheck 不再校验凭据后，这条指标就是「口令错/库不可用」的可见面（默认 10 分钟一次，绝不高频） |

> ⚠️ 不要改用 CLI 的**交互模式**喂 SQL：交互模式无论语句成败都退 **0**（实测），
> 会让 `iotdb-init` 的失败判定失效（那就成了「看起来成功、其实没建表」）。
> 交互模式只用于**改口令**这一次性动作——那里由「新口令能连、旧口令被拒」复核结果，不依赖退出码。
>
> ⚠️ **残留暴露面（如实登记）**：本方案只消除 **argv**（与健康检查配置）里的明文口令。
> `iotdb-init` 容器的环境变量里仍有 `IOTDB_PASSWORD` ⇒ `docker inspect` 的 `Config.Env` 与
> `/proc/<pid>/environ`（仅 root/容器内可读）仍能看到它。要连这一面一起消除，需要让 compose 从
> 卷上的 600 文件读口令（compose 无此能力）或改用 secret 管理，本轮不做。

**轮换步骤（可原样粘贴）**：

> ⚠️ 建议**整块放在子 shell 里跑**（`( … )` 或 `bash -l`）：`set -euo pipefail` 会残留在你的交互 shell 里。
> `$W` / `$TS` 后面 §6.2 还要用，所以最后一次把它们打印出来。

```bash
set -euo pipefail
cd /opt/ypbin/ypbin-iot

# ⓪ 快照 + 700 工作目录（回滚物；口令值只落 600 文件，输出只给长度/指纹）
TS=$(date +%Y%m%d-%H%M%S); W=/opt/ypbin/iotdb-rotation-$TS; install -d -m 700 "$W"
docker tag ypbin/ypbin-iot:local ypbin/ypbin-iot:rollback-rot-$TS
cp -a deploy/.env "$W/before-deploy.env"; cp -a deploy/docker-compose.yml "$W/before-compose.yml"
cp -a deploy/iotdb-init.sh "$W/before-iotdb-init.sh"; chmod 600 "$W"/before-*
# ⚠️ override 文件是**部署机本地**的（本仓不带）：有就一并快照，后面 up -d 也要带上它
if [ -f deploy/docker-compose.override.yml ]; then
  cp -a deploy/docker-compose.override.yml "$W/before-compose.override.yml"; chmod 600 "$W"/before-*
fi
OLD=$(sed -n 's/^IOTDB_PASSWORD=//p' deploy/.env | head -1)     # 当前口令（只进 shell 变量，不进 argv）
NEW=$(openssl rand -hex 24); printf '%s' "$NEW" > "$W/.new-pw"; chmod 600 "$W/.new-pw"
printf '新口令长度=%s sha256=%s\n' "${#NEW}" "$(printf %s "$NEW" | sha256sum | cut -c1-16)"
[ -n "$OLD" ] || { echo "!! deploy/.env 没有 IOTDB_PASSWORD（当前口令未知）——先补齐再轮换"; exit 1; }

# ① 判据用的 CLI 包装：口令经 stdin，不进 argv
run_cli() { printf '%s\n' "$1" | docker exec -i ypbin-iotdb bash -c \
  'start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw -sql_dialect table -e "SHOW DATABASES"'; }

# ② IoTDB 内改口令：**当前口令** + ALTER 语句都经 stdin（宿主 argv 与容器 argv 都不含口令）
{ printf '%s\n' "$OLD"; printf "ALTER USER root SET PASSWORD '%s';\n" "$NEW"; } \
  | docker exec -i ypbin-iotdb bash -c 'start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw -sql_dialect table' \
  > "$W/alter.out" 2>&1 || true
chmod 600 "$W/alter.out"    # 交互输出可能回显语句（含新口令）⇒ 只留 600 副本，别打印
grep -c '801\|Error\|error' "$W/alter.out" || true

# ②.5 ★硬门★：新口令连不上就**立刻停**——绝不继续改 Nacos/.env（否则应用/init 全 801）
run_cli "$NEW" >/dev/null 2>&1 || { echo "!! 新口令连不上（ALTER 未生效/口令不符）——已中止，Nacos 与 .env 未改动"; exit 1; }
echo "新口令已在 IoTDB 生效"

# ③ Nacos 活配置 + deploy/.env：只改 timeseries.password 的值（先 dump → 值级替换 → 断言「除该值外逐字未变」→ POST → 回读复核）
#    工具默认 dry-run；确认后再加 --apply。它同时把新值写进 deploy/.env 的 IOTDB_PASSWORD（600）。
python3 tools/rotate-iotdb-password.py "$W"            # dry-run：只 dump + 断言（可跳过）
python3 tools/rotate-iotdb-password.py "$W" --apply    # 发布 Nacos + 改 deploy/.env

# ④ 重启应用（缩小 ②→④ 之间的 801 窗口）与 IoTDB（新健康检查 + 验证口令落盘）
#    ⚠️ override 文件是部署机本地的：有则带上，没有不要凭空加 `-f`（否则 compose 直接报文件不存在）
COMPOSE_FILES="-f deploy/docker-compose.yml"
[ -f deploy/docker-compose.override.yml ] && COMPOSE_FILES="$COMPOSE_FILES -f deploy/docker-compose.override.yml"
docker restart ypbin-iot
docker compose $COMPOSE_FILES up -d --no-deps iotdb
echo "W=$W TS=$TS"    # 记下来，§6.2 回滚要用
```

**验证判据（缺一不可；每条都要**同时看退出码与错误文本**，只判退出码会把「连不上」误认成「口令被拒」）**：

```bash
run_cli() { printf '%s\n' "$1" | docker exec -i ypbin-iotdb bash -c \
  'start-cli.sh -h ypbin-iotdb -p 6667 -u root -pw -sql_dialect table -e "SHOW DATABASES"'; }
# 1) 新口令能连（期望 exit 0）
run_cli "$NEW"; echo "NEW_EXIT=$?"
# 2) 旧口令被拒（期望非 0 **且**输出含 801/用户名或口令错——只判退出码不可证伪）
run_cli "$OLD" 2>&1 | tee /dev/stderr | grep -q '801\|用户名或口令错' && echo "旧口令被拒 OK"
# 3) 重启后仍生效（证明落盘而非只在内存）：docker restart ypbin-iotdb 后重做 1) 与 2)
# 4) argv 与健康检查配置里不再有 `-pw <值>`：
docker top ypbin-iotdb -eo pid,args | grep -c -- '-pw ';                 # 期望 0
docker inspect -f '{{json .Config.Healthcheck.Test}}' ypbin-iotdb;       # 期望 TCP 探活串（含 /dev/tcp）
#    ⚠️ iotdb-init 是**一次性**容器（跑完 Exited）⇒ `docker top` 对它无效（会因容器不存在而恒打印 0，
#       那是假保证）。它的 argv 保证请读脚本（只有裸 -pw，见 §6.1 表格）；这里只核它的退出码：
docker inspect -f '{{.State.ExitCode}}' ypbin-iotdb-init     # 期望 0（`docker compose ps -a` 里是 `Exited (0)`）
# 5) 业务仍通：write.rows 与 db.rows 继续增长，且按「同窗口增量」与库内 count(*) 对齐
curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.db.rows
curl -s http://127.0.0.1:18084/actuator/metrics/iot.timeseries.db.probe.failed   # 期望不再增长
```

### 6.2 回滚（轮换是**集合**动作，不能只回滚一个文件）

轮换牵动 4 处：**IoTDB 内的口令 / Nacos live / `deploy/.env` / 运行中的容器配置**。
只回滚其中一部分会立刻造成不一致：

- 只回滚 compose/`iotdb-init.sh`（保留新口令）⇒ 形态回到旧的（⚠️ 旧版健康检查会把 `-pw <值>` 带回来，
  明文口令重新进 argv —— 这正是本轮要消除的东西，非必要不回滚它）；
- 只回滚 Nacos/`.env`（把口令改回旧值，而 IoTDB 里已是新口令）⇒ 应用与 `iotdb-init` 全部 801，
  **时序写入中断**。

```bash
# 从轮换工作目录名反推 TS，并确认目录存在（§6.1 里打印过 W/TS）
W=$(ls -dt /opt/ypbin/iotdb-rotation-* 2>/dev/null | head -1); [ -n "$W" ] || { echo "找不到轮换工作目录"; exit 1; }
TS=${W##*/iotdb-rotation-}
cd /opt/ypbin/ypbin-iot
COMPOSE_FILES="-f deploy/docker-compose.yml"
[ -f deploy/docker-compose.override.yml ] && COMPOSE_FILES="$COMPOSE_FILES -f deploy/docker-compose.override.yml"

# 形态回滚（**不回滚口令**，保留新口令）：只还原 compose / iotdb-init.sh 与镜像
cp -a "$W/before-compose.yml" deploy/docker-compose.yml
cp -a "$W/before-iotdb-init.sh" deploy/iotdb-init.sh
docker tag ypbin/ypbin-iot:rollback-rot-$TS ypbin/ypbin-iot:local
docker compose $COMPOSE_FILES up -d --no-deps ypbin-iot
```

⚠️ **口令不能靠 `cp` 回滚**：IoTDB 里已经是新口令了。要真正回到旧口令，必须**用新口令**
再 ALTER 一次（`ALTER USER root SET PASSWORD '<旧口令>'`），并把 Nacos live 换回轮换前的 dump
（工具写的是 **`$W/before-ypbin-iot.yaml`**）、把 `$W/before-deploy.env` 复制回 `deploy/.env`。
而旧口令既然按已泄露处置，**推荐不回滚口令**（单向轮换），只回滚形态与镜像。
Nacos live 换回 dump 的最小做法：`python3 tools/rotate-iotdb-password.py` 不适用（那是单向），
直接 `curl -X POST …/v3/console/cs/config --data-urlencode "content@$W/before-ypbin-iot.yaml"`（参数同 §6.1 工具内）。

**结论：回滚方案 = 「形态/镜像可回滚（上面一段）+ 口令单向（要么用新口令 ALTER 回旧值，
要么接受不回滚）」**，两者必须在变更票里写清楚，不能只写「可回滚」。

⚠️ `deploy/nacos/ypbin-iot.yaml` **也过** `install.sh` 的同一段 `sed`（7 个 nacos 配置一起处理），
但可替换键**只有 4 个**：`MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` / `INTERNAL_TOKEN` / `GATEWAY_SIGN_TOKEN`
（该文件里的 `${GATEWAY_SIGN_TOKEN}` 正是会被替换的键之一）。所以别在 `password` 上写
`${IOTDB_PASSWORD}` 之类的新占位符：既不会被替换、也不会被自动生成，只会多一层"以为配了、其实没配"的错觉。
**未修项（已登记）**：因此「全新安装」仍需人工执行上述 ①～③——`install.sh` 渲染不了 IoTDB 内的口令，
也不会生成 `deploy/.env` 的 `IOTDB_PASSWORD`（补这个缺口要动 `install.sh` 的渲染键清单，属白名单文件的额外改动，本轮不做）。
现在的失败形态是**显式且局部**的：`docker compose up -d` 里只有 `iotdb-init` 会以
`未提供 IOTDB_PASSWORD（拒绝回退到官方公开默认口令 'root'）` 退出 1，其余服务照常起；
⚠️ **不要**把 `deploy/.env.example` 原样抄成 `.env`：那样 `IOTDB_PASSWORD` 会是占位串，
`iotdb-init` 会以 801 连续失败退出 1（失败很响，但仍需知道原因在口令）。
（这正是本轮**没有**在 compose 用 `${IOTDB_PASSWORD:?}` 的原因：compose 对整份文件插值，
`ps`/`up -d nacos redis mysql` 等无关命令都会被一起拦掉。）

- **端口暴露面**：`ports` 只发布 6667，且走**独立**变量 `IOTDB_BIND_ADDR`（默认回环）。
  ⚠️ 本仓 `deploy/.env.example` 把 `INTERNAL_BIND_ADDR` 设成 `0.0.0.0`——若 IoTDB 复用那个变量，
  手抄 `.env.example` 就会把"公开默认口令的 6667"暴露到全网（`install.sh` 生成的 `.env` 不含该键，反而安全）。
  改用独立变量后，**不显式设置 `IOTDB_BIND_ADDR` 就只允许宿主机内访问**。compose 网络内其他服务
  （`ypbin-iot`）用服务名直连，不经宿主端口。
- **磁盘**：TTL 过期是异步物理删除，容量规划按「保留期内的写入量」算，不要假设过期即释放。
  官方换算公式：`测点数 × 频率(Hz) × 单点字节 × 保留秒数 / 压缩比`（standalone 副本因子 1）。
- **备份**：时序数据在具名卷 `ypbin-iotdb-data` 里；`docker compose down -v` 会**删除**它。
  官方提供全量备份工具与数据导入导出（Tools System），需要时按官方文档做，别直接拷卷目录。

## 7. 核实状态清单（已核实 / 未能核实，部署前请留意）

| 项 | 状态 | 保守做法 |
|---|---|---|
| 镜像 tag | ✅ 已核实：`2.0.11-standalone` 存在（Docker Hub API + `docker manifest inspect`） | 已固定该 tag，不用 `latest` |
| 健康检查方式 | ✅ **TCP 探活已实测**（2026-09-26，本机容器）：`timeout 5 bash -c '</dev/tcp/ypbin-iotdb/6667'` → 退出码 **0**；换关闭端口 / 换 `127.0.0.1` → **1**（`Connection refused`）。此前基于 CLI 的探针退出码亦已实测（2026-09-24：成功 0、连接失败 1），但**因口令明文进 argv 已废弃**（见 §6.1）。⚠️ 探针**必须**连 `ypbin-iotdb` 而**不是** 127.0.0.1（原因见 §5 首行） | 探针只用于观测、不校验凭据，也不驱动其他服务启动；凭据维度看应用侧 `iot.timeseries.db.probe.failed`（§6.1）；`iotdb-init` 用 `service_started` + 自重试；人工验证见 §3 |
| 驱动 2.0.1-beta ↔ 服务端 2.0.11 实际互操作 | ✅ **已实测**（2026-09-24，本机容器 `apache/iotdb:2.0.11-standalone`）：该组合下 `CREATE DATABASE/TABLE`、`INSERT`、`SELECT`、`SHOW TABLES`、`LIMIT`、`ORDER BY time` 全部可用；同时也实测出两处**该驱动特有的坑**（`setNull` 被拒、查询 `?` 无引号替换，见 §5） | 当前组合可用；若要换版本，按官方「不要用更新的客户端连更旧的服务端」保持服务端 ≥ 客户端 |
| 错误码 614 / 616 | ✅ **已核实**（第一版误列为"未能核实"：错在只查了状态码总表，而它们在 Write & Update Data §1.4 Notes 里） | 见 §5 与 §8；总表查不到不等于没有这个码 |
| `IOTDB_JMX_OPTS` / `CONFIGNODE_JMX_OPTS` 是否被 standalone 镜像读取 | ⚠️ 官方仓库 compose 设了这两个变量（大写），但镜像 entrypoint 只把**全小写**变量写进 conf ⇒ 生效路径未核实 | 本仓**不设**这两个变量，避免"以为限制了堆、其实没限制"；内存不足请实测 `docker stats` / 容器内 JVM 参数后再定 |
| 显式声明 `time TIMESTAMP TIME` 的最低版本 | ⚠️ 官方 Table Management 说「V2.0.8 起支持自定义时间列命名」，我们固定 2.0.11 故可用 | 若把镜像降到 2.0.1-beta，须删掉 DDL 里 `time TIMESTAMP TIME,'` 一段 |
| 镜像内是否**真的**有 curl | ⚠️ 按 adoptium 官方 Dockerfile 与 IoTDB Dockerfile **推断有**（未在容器内 `command -v curl` 实测） | 探针不依赖 curl（6667 是 Thrift 口，curl 也没用）；要用 curl 前先实测 |
| `iotdb` 的 `hostname: ypbin-iotdb` 自解析 | ⚠️ 官方 standalone compose 同样把 hostname 设为内部地址同名，但**未在容器内验证**服务端自解析/绑定行为 | 若启动异常，先按官方 compose 的写法核查；容器名 `ypbin-iotdb` 在自定义网络内可解析属 Docker 文档化行为 |
| **驱动是否被注册**（不是"是否在类路径"） | ✅ 已核实前提：`iotdb-jdbc:2.0.1-beta` 的 jar **无** `META-INF/services/java.sql.Driver` ⇒ 必须显式注册（§0.6）；✅ 注册代码已在主线（`IotDbDriverRegistrar.ensureRegistered()` 在 `IotTimeSeriesConfiguration.afterPropertiesSet()` 与两个实现类的构造器里各调一次，本机实测启动自检通过） | 部署前仍建议 grep 一次部署提交：`git grep -n "IotDbDriverRegistrar" <部署提交>`；没有就把 `enabled` 改回 `false` |
| `start-cli.sh -e` 的退出码语义 | ✅ **已实测**（2026-09-24 连接类 + 2026-09-26 补测 SQL 类，本机容器）：语句成功 `exit 0`；连接失败 `exit 1`；**口令错 `exit 1`**；**SQL 语义错（`SHOW TABLES FROM no_such_db`）`exit 1`**。⚠️ **交互模式（不带 `-e`，语句经 stdin）无论语句成败都退 0** —— 不得用它跑初始化脚本 | 这是 `iotdb-init` 重试判定与失败判定的共同前提；因此 `iotdb-init` **坚持用 `-e`**，只把口令改走 stdin（§6.1） |
| `start-cli.sh` 是否支持配置文件/环境变量传口令 | ✅ **已核实不支持**（2026-09-26，读镜像内 `/iotdb/sbin/start-cli.sh` 原文）：口令只有命令行 `-pw <值>` 一条路；其 `checkEnvVariables` 只识别 `-D IOTDB_INCLUDE` / `-D IOTDB_CLI_CONF` | 健康检查改「不发口令的 TCP 探活」；需要认证的一次性任务把口令经 **stdin** 送入（裸 `-pw` 触发 `please input your password:`） |

## 8. 来源（官方一手文档 + 访问日期 2026-09-24）

- Docker 部署（镜像名 `apache/iotdb:2.0.x-standalone`、端口 6667、环境变量、CLI 验证的“Congratulations”日志、
  集群不支持 bridge 网络）— <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Deployment-and-Maintenance/Docker-Deployment_apache.html>
- 官方 compose（standalone，含 `cn_*`/`dn_*` 环境变量与 `IOTDB_JMX_OPTS`）—
  <https://github.com/apache/iotdb/blob/v2.0.11/docker/src/main/DockerCompose/docker-compose-standalone.yml>
- 镜像 Dockerfile / entrypoint（基础镜像 `eclipse-temurin:17-jre-focal`、`PATH` 含 `/iotdb/sbin`、
  小写环境变量写入 `iotdb-system.properties`；**链接钉在被部署的那个 tag `v2.0.11` 上**，不是 `master`）—
  <https://github.com/apache/iotdb/blob/v2.0.11/docker/src/main/Dockerfile-1.0.0-standalone>、
  <https://github.com/apache/iotdb/blob/v2.0.11/docker/src/main/DockerCompose/entrypoint.sh>、
  <https://github.com/apache/iotdb/blob/v2.0.11/docker/src/main/DockerCompose/replace-conf-from-env.sh>
- 镜像 tag 是否存在（131 个 tag，含 `2.0.11-standalone` / `2.0.1-beta-standalone` / `latest`）—
  <https://hub.docker.com/v2/repositories/apache/iotdb/tags>（Docker Hub 官方 `apache` 命名空间）
- JDBC（URL 必须带 `sql_dialect=table`、驱动类、`iotdb-jdbc:2.0.1-beta`、带库名的 URL 形式、
  「不要用更新的客户端连更旧的服务端」）—
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/API/Programming-JDBC_apache.html>
- CLI（`-e` 非交互批处理、`-sql_dialect table`、`-h/-p/-u/-pw`）—
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Tools-System/CLI_apache.html>
- 建表 / 列类别（`STRING TAG` / `DOUBLE FIELD` / `TIMESTAMP TIME`）/ `WITH (TTL=…)` / `SHOW TABLES` / `DESC` —
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Table-Management_apache.html>
- 库管理（`CREATE DATABASE IF NOT EXISTS`、`ALTER DATABASE … TTL`）—
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Database-Management_apache.html>
- TTL（毫秒、`ALTER TABLE … SET PROPERTIES TTL=`、`TTL='INF'`、过期异步物理删除、改 TTL 短暂影响可访问性）—
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/TTL-Delete-Data_apache.html>
- **错误码 614 / 616（`DATA_TYPE_MISMATCH` / `COLUMN_NOT_EXIST`）**、未指定列自动填 null —
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Write-Updata-Data_apache.html>（§1.4 Notes）
- `count(*)` 语法 — <https://iotdb.incubator.apache.org/UserGuide/latest-Table/SQL-Manual/Select-Clause_apache.html>
- 默认用户/口令 `root`/`root`、`ALTER USER … SET PASSWORD`（**From V2.0.7** 版本文档）—
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/User-Manual/Authority-Management-Upgrade_apache.html>
- 「未指定数据库」时插入报表名错误 `701: database is not specified` —
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/User-Manual/Authority-Management_apache.html>
  （**Before V2.0.7** 版本文档 §5.2 的示例输出；From V2.0.7 那页只给 `803`。
  ⚠️ 该串出自 ≤2.0.7 文档，**2.0.11 上的确切错误码/报文未实测**；结论"必须带库名"另有两条一手支撑：
  [Database Management §1.1/§2.2](https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Database-Management_apache.html)
  「未 `USE` 时表操作按当前库解析」+ [JDBC 示例](https://iotdb.incubator.apache.org/UserGuide/latest-Table/API/Programming-JDBC_apache.html)
  的带库名 URL 写法）
- 资源下限（standalone ≤10 万测点：2–4 核 / 2–4G）—
  <https://iotdb.incubator.apache.org/UserGuide/latest-Table/Deployment-and-Maintenance/Database-Resources_apache.html>
- 状态码总表（**止于 1403、不含 614/616**；用来解释"为什么总表查不到不等于没有这个码"）—
  <https://iotdb.incubator.apache.org/UserGuide/latest/Reference/Status-Codes.html>
- 基础镜像是否自带 curl（adoptium 官方 focal Dockerfile 显式安装，注释 `curl required for historical reasons`；
  该 tag 已 EOL，链接钉在最后一次含 focal 的提交）—
  <https://raw.githubusercontent.com/adoptium/containers/e5b2b79da44d9488979a9a46d3cdebe992d3a804/17/jre/ubuntu/focal/Dockerfile>
- **驱动 jar 里有没有 SPI 元数据**（本机下载制品 + `zipfile` 核验：无 `META-INF/services`）—
  <https://repo1.maven.org/maven2/org/apache/iotdb/iotdb-jdbc/2.0.1-beta/iotdb-jdbc-2.0.1-beta.jar>
  （Maven Central 官方制品，105258 字节；判据：`zipfile.ZipFile(...).namelist()` 里 `META-INF/services*` 条目数 = 0）
- Docker 对 `entrypoint` 覆盖时是否还注入镜像 `CMD`（结论：不注入；`iotdb-init` 因此不需要 `command: []`）—
  moby 官方源码 <https://raw.githubusercontent.com/moby/moby/master/daemon/commit.go>（`func merge`：
  `if len(userConf.Entrypoint) == 0 { … userConf.Cmd = imageConf.Cmd … }`）
- JDBC 驱动的 SPI 自动注册机制（`DriverManager` 通过 `ServiceLoader` 加载 `java.sql.Driver` 实现；
  因此"jar 里没有 `META-INF/services/java.sql.Driver`"就等于不会自动注册）—
  Oracle JDK 官方 javadoc <https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DriverManager.html>
