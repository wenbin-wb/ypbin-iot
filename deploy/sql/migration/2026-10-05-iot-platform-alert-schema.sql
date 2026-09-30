-- 迁移：平台自告警实例表（看板 #10）
-- 与 deploy/sql/007-iot-data.sql 的**平台自告警段**语句等价
-- （由 tools/check-iot-sql-equivalence.sh 校验）。
-- ⚠️ 排序约束：本文件名必须排在 2026-10-04-iot-device-import-menu.sql 之后。
-- =============================================================

CREATE TABLE iot_platform_alert
(
    id                BIGINT       NOT NULL COMMENT '主键',
    tenant_id         BIGINT       NOT NULL COMMENT '租户 ID',
    rule_code         VARCHAR(48)  NOT NULL COMMENT '规则码（PlatformHealthRule.code）',
    dedup_key         VARCHAR(191) NOT NULL COMMENT '去重键（规则码+维度），不随状态变化',
    active_dedup_key  VARCHAR(191) NULL COMMENT '只在活动期间非 NULL（=dedup_key）；恢复时置 NULL',
    severity          VARCHAR(16)  NOT NULL COMMENT '严重度码（PlatformSeverity.code）：WARNING/CRITICAL',
    state             VARCHAR(16)  NOT NULL COMMENT '状态码（PlatformAlertState.code）：PENDING/FIRING/RESOLVED',
    summary           VARCHAR(512) NOT NULL COMMENT '面向运维的一句话（含实际观测值）',
    metric_snapshot   TEXT         NULL COMMENT '判定时的指标快照（JSON）；事后排障的唯一依据',
    start_ts          DATETIME     NULL COMMENT '首次观测到异常的时刻',
    firing_ts         DATETIME     NULL COMMENT '确认触发的时刻',
    resolved_ts       DATETIME     NULL COMMENT '恢复时刻（NULL=仍活动）',
    last_verdict      VARCHAR(32)  NULL COMMENT '最近一轮判定结果（便于看恶化还是好转）',
    observed_rounds   INT          NOT NULL DEFAULT 0 COMMENT '活动期间累计判定轮次',
    create_user       BIGINT       NULL COMMENT '创建人',
    create_time       DATETIME     NULL COMMENT '创建时间',
    update_user       BIGINT       NULL COMMENT '更新人',
    update_time       DATETIME     NULL COMMENT '更新时间',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_alert_active (tenant_id, active_dedup_key),
    KEY idx_platform_alert_state (tenant_id, state, start_ts),
    KEY idx_platform_alert_rule (tenant_id, rule_code, start_ts)
) COMMENT 'IoT 平台自告警实例（看板 #10；与设备告警物理分离，uk_platform_alert_active 是去重的库级保证）';
