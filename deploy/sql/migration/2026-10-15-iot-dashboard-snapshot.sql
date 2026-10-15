-- =============================================================
-- 迁移：仪表盘在线趋势每日快照（表）
-- 适用：**已上线的库**（install.sh 只在全新安装时执行 deploy/sql/*.sql，不含 migration/）
-- 等价性：本文件与 deploy/sql/007-iot-data.sql 中「仪表盘在线趋势每日快照」那段的**语句**等价
--         （注释可不同）。改一边必须同步另一边；CI 有语句级等价校验
--         （tools/check-iot-sql-equivalence.sh）。
--         放置说明：本表属 2026-09-28 之后的增量，按仓内约定追加在 007-iot-data.sql 末尾，
--         使「006+007 按语句顺序」与「migration/*.sql 按文件名排序」严格对齐。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
-- =============================================================

-- =============================================================
-- 仪表盘在线趋势每日快照（2026-10-15 追加）
-- 覆盖：iot_dashboard_daily_snapshot —— 在线趋势（近 14 天）的历史数据源。
-- 租户表：含 tenant_id 且不进 deploy/nacos/ypbin-iot.yaml 的 ignore-tables，
--       由租户插件统一追加 tenant_id 条件（与 iot_device 一致）。
-- 唯一键：uk_iot_dashboard_daily_snapshot(tenant_id, snapshot_date) —— 每租户每天一条；
--       应用层「先查后写」存在竞态窗口，并发首写由该唯一键兜底（冲突回退为更新）。
-- 等价性：本文件追加部分与 migration/2026-10-15-iot-dashboard-snapshot.sql 语句等价。
-- =============================================================

CREATE TABLE iot_dashboard_daily_snapshot
(
    id              BIGINT   NOT NULL COMMENT '主键',
    tenant_id       BIGINT   NOT NULL COMMENT '租户 ID',
    snapshot_date   DATE     NOT NULL COMMENT '快照日期（租户内唯一，每天一条）',
    device_total    INT      NOT NULL COMMENT '设备总数（含停用）',
    device_online   INT      NOT NULL COMMENT '在线设备数（online_status = online）',
    device_offline  INT      NOT NULL COMMENT '离线设备数（online_status = offline）',
    device_disabled INT      NOT NULL COMMENT '停用（禁用采集）设备数（status = 0）',
    create_user     BIGINT   NULL COMMENT '创建人',
    create_time     DATETIME NULL COMMENT '创建时间',
    update_user     BIGINT   NULL COMMENT '更新人',
    update_time     DATETIME NULL COMMENT '更新时间',
    status          TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted      TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_dashboard_daily_snapshot (tenant_id, snapshot_date)
) COMMENT 'IoT 仪表盘在线趋势每日快照（历史数据源）';
