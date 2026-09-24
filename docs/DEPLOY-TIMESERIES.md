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
| 健康检查 | `start-cli.sh … -e "SHOW DATABASES"` | **自建**探针（官方无 Docker 健康检查方案，§7），30s 间隔 / 90s 启动宽限 |
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

### 3.1 容器与初始化

```bash
docker compose -f deploy/docker-compose.yml ps iotdb iotdb-init
docker logs --tail 40 ypbin-iotdb-init          # 期望：DDL 执行完毕 + SHOW TABLES 输出里有 reading
```

### 3.2 库表与 TTL（服务端侧）

```bash
# 库在不在（CLI 默认是树模型，表模型必须显式 -sql_dialect table）
docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e "SHOW DATABASES"

# 表在不在、TTL 对不对（期望 TTL(ms)=7776000000）
docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e "SHOW TABLES FROM iot"
```

### 3.3 写入 → 查询往返（服务端侧，绕开应用）

```bash
# 写一条（未指定 value_text，官方语义：未指定的列自动填 null）
docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e \
  "INSERT INTO iot.reading(tenant_id, device_id, property_id, time, value_double, quality) VALUES ('1','1','temp','2026-09-24 12:00:00', 1.5, 'GOOD')"

# 读回来（count(*) 语法见官方 Select Clause §3.1.2）
docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e "SELECT count(*) FROM iot.reading"
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
docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e \
  "ALTER TABLE iot.reading SET PROPERTIES TTL=15552000000"

# 去掉 TTL（永不过期）
docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e "ALTER TABLE iot.reading SET PROPERTIES TTL='INF'"
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
| 健康检查一直是 `starting` | 自建 CLI 探针不被镜像支持或转义有问题 | 不影响读写；用 §3.2/§3.3 人工确认，或把 `healthcheck.test` 退化为 TCP 探活（见下方注） |

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
> §3.2 的 CLI 查询，或最轻量的 bash TCP 探活 `docker exec ypbin-iotdb bash -c 'exec 3<>/dev/tcp/127.0.0.1/6667'`。
> （curl 只有在启用了 IoTDB REST 服务时才有用，那是另一个端口。）

## 6. 安全与容量

- **6667 = 明文 + 默认口令**。默认用户/口令是 `root` / `root`（官方 Authority Management 明文写的默认值）。
  生产**必须**二选一或都做：① 只绑回环（`IOTDB_BIND_ADDR` 不设时默认就是 `127.0.0.1`，见 §1）；② 改口令。
  改口令要**三处联动**（官方语法 `ALTER USER`，见 §8）：
  ```bash
  # ① IoTDB 内改（官方语法 ALTER USER，需 root 身份）
  docker exec ypbin-iotdb start-cli.sh -sql_dialect table -e "ALTER USER root SET PASSWORD '换成强口令'"
  # ② deploy/nacos/ypbin-iot.yaml 的 ypbin.timeseries.password 改成同一个值，重导 Nacos，重启 ypbin-iot
  # ③ deploy/docker-compose.yml 的 iotdb-init 服务里 IOTDB_PASSWORD（以及 IOTDB_USER）改成同一个值
  #    ⚠️ 漏掉 ③ 的后果：以后每次 `docker compose up -d` 重跑 iotdb-init 都会连续 801 失败（约 3 分钟后 exit 1）
  ```
  ⚠️ `deploy/nacos/ypbin-iot.yaml` **也过** `install.sh` 的同一段 `sed`（7 个 nacos 配置一起处理），
  但可替换键**只有 4 个**：`MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` / `INTERNAL_TOKEN` / `GATEWAY_SIGN_TOKEN`
  （该文件里的 `${GATEWAY_SIGN_TOKEN}` 正是会被替换的键之一）。所以别在 `password` 上写
  `${IOTDB_PASSWORD}` 之类的新占位符：既不会被替换、也不会被自动生成，只会多一层"以为配了、其实没配"的错觉。
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
| 健康检查方式 | ⚠️ 官方**未提供** Docker 健康检查方案；自建 CLI 探针的**退出码语义**未在真实容器验证 | 探针只用于观测，不驱动其他服务启动；`iotdb-init` 用 `service_started` + 自重试；人工验证见 §3 |
| 驱动 2.0.1-beta ↔ 服务端 2.0.11 实际互操作 | ⚠️ 仅核实官方「不要用更新的客户端连更旧的服务端」这一方向性约束（本组合是安全方向），**未做特性级实测** | 若嗅到协议/特性异常，把镜像换成同版本 `apache/iotdb:2.0.1-beta-standalone`（tag 已核实存在） |
| 错误码 614 / 616 | ✅ **已核实**（第一版误列为"未能核实"：错在只查了状态码总表，而它们在 Write & Update Data §1.4 Notes 里） | 见 §5 与 §8；总表查不到不等于没有这个码 |
| `IOTDB_JMX_OPTS` / `CONFIGNODE_JMX_OPTS` 是否被 standalone 镜像读取 | ⚠️ 官方仓库 compose 设了这两个变量（大写），但镜像 entrypoint 只把**全小写**变量写进 conf ⇒ 生效路径未核实 | 本仓**不设**这两个变量，避免"以为限制了堆、其实没限制"；内存不足请实测 `docker stats` / 容器内 JVM 参数后再定 |
| 显式声明 `time TIMESTAMP TIME` 的最低版本 | ⚠️ 官方 Table Management 说「V2.0.8 起支持自定义时间列命名」，我们固定 2.0.11 故可用 | 若把镜像降到 2.0.1-beta，须删掉 DDL 里 `time TIMESTAMP TIME,'` 一段 |
| 镜像内是否**真的**有 curl | ⚠️ 按 adoptium 官方 Dockerfile 与 IoTDB Dockerfile **推断有**（未在容器内 `command -v curl` 实测） | 探针不依赖 curl（6667 是 Thrift 口，curl 也没用）；要用 curl 前先实测 |
| `iotdb` 的 `hostname: ypbin-iotdb` 自解析 | ⚠️ 官方 standalone compose 同样把 hostname 设为内部地址同名，但**未在容器内验证**服务端自解析/绑定行为 | 若启动异常，先按官方 compose 的写法核查；容器名 `ypbin-iotdb` 在自定义网络内可解析属 Docker 文档化行为 |
| **驱动是否被注册**（不是"是否在类路径"） | ✅ 已核实前提：`iotdb-jdbc:2.0.1-beta` 的 jar **无** `META-INF/services/java.sql.Driver` ⇒ 必须显式注册（§0.6）；⏳ "本分支的注册代码在部署提交里"需按提交核对 | 部署前 grep 部署提交里是否有 `Class.forName("org.apache.iotdb.jdbc.IoTDBDriver")`/`IotDbDriverRegistrar`；没有就把 `enabled` 改回 `false` |
| `start-cli.sh -e` 在 SQL 出错时是否返回非 0 | ⚠️ 这是 `iotdb-init` 重试判定与 healthcheck 语义的**共同前提**，本机不允许跑容器故未实测 | 已用桩脚本覆盖脚本自身逻辑（§2）；真实退出码请在首次部署时用一条**故意写错**的语句观测一次 |

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
