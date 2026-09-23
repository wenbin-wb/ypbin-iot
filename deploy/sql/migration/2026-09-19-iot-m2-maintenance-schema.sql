-- M-2：维护窗口表（可用率统计总时长排除计划停机；含租约交接自动窗口）
-- 与 006-iot-schema.sql 中的同名表**逐字等价**（tools/check-iot-sql-equivalence.sh 会校验）。
CREATE TABLE maintenance_window
(
    id          BIGINT      NOT NULL COMMENT '主键',
    tenant_id   BIGINT      NOT NULL COMMENT '租户 ID',
    device_id   BIGINT      NULL COMMENT '设备 ID（iot_device.id）；NULL=该租户全部设备',
    start_ts    DATETIME    NOT NULL COMMENT '窗口开始',
    end_ts      DATETIME    NULL COMMENT '窗口结束；NULL=进行中',
    source      VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源码（枚举 code，非 ordinal）：MANUAL/LEASE_HANDOVER',
    reason      VARCHAR(255) NULL COMMENT '说明（人工窗口写清计划停机原因；交接窗口由服务端写节点信息）',
    create_user BIGINT      NULL COMMENT '创建人',
    create_time DATETIME    NULL COMMENT '创建时间',
    update_user BIGINT      NULL COMMENT '更新人',
    update_time DATETIME    NULL COMMENT '更新时间',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_maintenance_window_tenant_start (tenant_id, start_ts),
    KEY idx_maintenance_window_device_start (tenant_id, device_id, start_ts)
) COMMENT '维护窗口（可用率统计总时长排除；含租约交接自动窗口）';
