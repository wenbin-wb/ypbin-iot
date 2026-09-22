-- =============================================================
-- ypbin-iot M-2 断档与可用率（增量迁移；与 006-iot-schema.sql 追加部分语句等价）
--   · device_liveness：每台设备的「最近有效数据」状态（断档判定的状态机）
--   · outage_event   ：断档事件（可用率的唯一数据来源）
-- 租户表（由租户插件追加 tenant_id 条件；不得登记进 ignore-tables）
-- ⚠️ 文件名日期取 schema 批次（09-19）：tools/check-iot-sql-equivalence.sh 按文件名排序拼接，
--    DDL 迁移必须排在菜单迁移之前，否则等价性门禁转红。
-- =============================================================

CREATE TABLE device_liveness
(
    id                BIGINT   NOT NULL COMMENT '主键',
    tenant_id         BIGINT   NOT NULL COMMENT '租户 ID',
    device_id         BIGINT   NOT NULL COMMENT '设备 ID（iot_device.id）',
    poll_interval_ms  INT      NOT NULL DEFAULT 0 COMMENT '采集周期（毫秒；0=未提供，按默认周期判定）',
    last_good_at      DATETIME NULL COMMENT '最近一次有效数据（quality=GOOD）的时刻',
    first_observed_at DATETIME NULL COMMENT '首次收到任何读数的时刻（从未有有效数据时断档起点的兜底）',
    last_observed_at  DATETIME NULL COMMENT '最近一次收到任何读数的时刻',
    open_outage_id    BIGINT   NULL COMMENT '进行中的断档事件 ID（NULL=当前无断档）',
    create_user       BIGINT   NULL COMMENT '创建人',
    create_time       DATETIME NULL COMMENT '创建时间',
    update_user       BIGINT   NULL COMMENT '更新人',
    update_time       DATETIME NULL COMMENT '更新时间',
    status            TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted        TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_liveness (tenant_id, device_id),
    KEY idx_device_liveness_good (tenant_id, last_good_at)
) COMMENT '设备活性状态（断档判定）';

CREATE TABLE outage_event
(
    id           BIGINT      NOT NULL COMMENT '主键',
    tenant_id    BIGINT      NOT NULL COMMENT '租户 ID',
    device_id    BIGINT      NOT NULL COMMENT '设备 ID（iot_device.id）',
    start_ts     DATETIME    NOT NULL COMMENT '断档开始（最后一次有效数据时刻，或首次观测时刻）',
    end_ts       DATETIME    NULL COMMENT '断档结束（恢复有效数据的时刻）；NULL=进行中',
    duration_sec BIGINT      NULL COMMENT '断档时长（秒）；进行中为 NULL',
    reason       VARCHAR(64) NOT NULL DEFAULT 'NO_GOOD_DATA' COMMENT '原因码（枚举 code，非 ordinal）',
    create_user  BIGINT      NULL COMMENT '创建人',
    create_time  DATETIME    NULL COMMENT '创建时间',
    update_user  BIGINT      NULL COMMENT '更新人',
    update_time  DATETIME    NULL COMMENT '更新时间',
    status       TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_outage_event_device_start (tenant_id, device_id, start_ts),
    KEY idx_outage_event_open (tenant_id, device_id, end_ts)
) COMMENT '断档事件（可用率的唯一数据来源）';
