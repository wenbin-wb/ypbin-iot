-- ============================================================
-- ypbin IoT 时序库（Apache IoTDB 表模型）初始化 DDL
--
-- 执行方式：由 deploy/docker-compose.yml 的 iotdb-init 一次性容器执行
--           （本文件以只读方式挂载到容器内 /init/iotdb-init.sql）
-- 执行器约束：按行读取，**一行一条语句**（官方也提示终端不支持多行粘贴时把语句压成一行）
--             行首 # 或 -- 为注释行，空行跳过
-- 一致性约束：列顺序必须与 ypbin-service/ypbin-iot 的 IotDbTimeSeriesWriter.COLUMNS
--             一致，查询列必须与 IotDbTimeSeriesStore 的 SELECT 一致（改本文件就要同步改代码）
--
-- 语法来源（官方一手文档，访问 2026-09-24）：
--   CREATE DATABASE (IF NOT EXISTS) 语法：
--     https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Database-Management_apache.html
--   CREATE TABLE / 列类别(TAG|FIELD|TIME) / WITH (TTL=...) 语法：
--     https://iotdb.incubator.apache.org/UserGuide/latest-Table/Basic-Concept/Table-Management_apache.html
--   列类别与 TTL 的可运行示例（JDBC）：
--     https://iotdb.incubator.apache.org/UserGuide/latest-Table/API/Programming-JDBC_apache.html
-- ============================================================

-- 库名 iot 必须与 Nacos 配置 ypbin.timeseries.url 里的库名（jdbc:iotdb://ypbin-iotdb:6667/iot?sql_dialect=table）一致。
-- 多租户用 TAG 列（tenant_id）区分，**不分库分表**（§5.2.1）。IF NOT EXISTS 让本文件可重复执行。
CREATE DATABASE IF NOT EXISTS iot;

-- TTL=7776000000 ms = 90 天（D0.8 的原始时序保留期）。
-- ⚠️ 显式声明 time 列要求服务端 >= V2.0.8（官方 Table Management：V2.0.8 起支持自定义时间列命名）；
--    本仓 compose 固定 apache/iotdb:2.0.11-standalone。若把镜像降到 2.0.1-beta，须删掉
--    "time TIMESTAMP TIME," 一段（IoTDB 会自动补一个同名列，但那样就不受本文件的显式约束）。
CREATE TABLE IF NOT EXISTS iot.reading (tenant_id STRING TAG, device_id STRING TAG, property_id STRING TAG, time TIMESTAMP TIME, value_double DOUBLE FIELD, value_text STRING FIELD, quality STRING FIELD) WITH (TTL=7776000000);
