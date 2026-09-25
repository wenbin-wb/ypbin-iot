-- =============================================================
-- G6 运行期事件实例：iot_event_log（已上线库升级用）
-- 与 deploy/sql/006-iot-schema.sql 的同名表**语句等价**（由 tools/check-iot-sql-equivalence.sh 校验）。
--
-- ⚠️ 文件名说明（不是笔误）：等价性脚本把 006 + 007 与 migration/*-iot-*.sql **按文件名排序**后
--    逐语句比较，因此文件名前缀即「语句在 006/007 里的位置」：
--      2026-09-19-iot-m2-availability-schema.sql  → 006 的 device_liveness / outage_event
--      2026-09-19-iot-m2-event-log-schema.sql     ← 本文件（紧跟 outage_event 之后）
--      2026-09-19-iot-m2-maintenance-schema.sql   → 006 的 maintenance_window
--    故本文件必须取 `m2-event-log-schema` 这个名字：`m2-e…` 排在 `m2-a…` 之后、`m2-m…` 之前。
--    日期用 09-19（schema 批次）而非实现日期：排序靠文件名，不靠日期语义（既有迁移同一约定）。
--
-- 幂等唯一键是「重复投递不产生重复行」的**唯一防线**：应用层先查后插存在竞态窗口。
--
-- 已执行过本文件的库不需要重跑；回滚见 deploy/sql/rollback/2026-09-19-iot-m2-event-log-schema-rollback.sql。
-- =============================================================

CREATE TABLE iot_event_log
(
    id             BIGINT       NOT NULL COMMENT '主键',
    tenant_id      BIGINT       NOT NULL COMMENT '租户 ID',
    device_id      BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    event_code     VARCHAR(64)  NOT NULL COMMENT '事件标识（对应物模型 iot_event.identifier；无对应定义时是上报方自定码）',
    event_name     VARCHAR(128) NULL COMMENT '事件名称（上报方可选给出，便于无物模型定义时展示）',
    level          VARCHAR(16)  NOT NULL DEFAULT 'info' COMMENT '事件级别码（枚举 code，非 ordinal）：info|warn|error',
    params         TEXT         NULL COMMENT '事件参数（JSON 文本，由上报方给出，服务端不解析）',
    event_ts       DATETIME     NOT NULL COMMENT '事件发生时刻（上报方给，不是入库时刻；乱序上报按它排序展示）',
    idempotent_key VARCHAR(128) NOT NULL COMMENT '幂等键（同租户同设备内唯一；重投用它去重）',
    create_user    BIGINT       NULL COMMENT '创建人',
    create_time    DATETIME     NULL COMMENT '创建时间',
    update_user    BIGINT       NULL COMMENT '更新人',
    update_time    DATETIME     NULL COMMENT '更新时间',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_event_log_idem (tenant_id, device_id, idempotent_key),
    KEY idx_iot_event_log_device_ts (tenant_id, device_id, event_ts),
    KEY idx_iot_event_log_level_ts (tenant_id, device_id, level, event_ts)
) COMMENT 'IoT 运行期事件实例（G6）';
