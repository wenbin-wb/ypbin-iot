-- =============================================================
-- 消息跟踪二批：上行结构化补齐（iot_mqtt_ingest_receipt 加 6 列 + 时间窗索引）
-- 与 deploy/sql/007-iot-data.sql 末尾的同名 ALTER **语句等价**（tools/check-iot-sql-equivalence.sh 校验）。
-- 回滚见 deploy/sql/rollback/2026-10-13-iot-mqtt-ingest-receipt-trace-rollback.sql。
-- =============================================================

ALTER TABLE iot_mqtt_ingest_receipt
    ADD COLUMN receipt_status VARCHAR(16)  NOT NULL DEFAULT 'accepted' COMMENT '行性质：accepted=已受理 / rejected=整批被拒（避让既有 status 启用/停用列）',
    ADD COLUMN received_count INT          NULL COMMENT '本批收到的读数条数（含被丢弃的）',
    ADD COLUMN persisted_rows INT          NULL COMMENT '实际落库条数（rejected 行为 0）',
    ADD COLUMN persisted_at   DATETIME     NULL COMMENT '落库完成时刻（显式列，不依赖审计列）',
    ADD COLUMN discard_reason VARCHAR(32)  NULL COMMENT '丢弃原因码（复用 MqttIngestRejectReason 枚举，不新造分类）',
    ADD COLUMN discard_detail VARCHAR(512) NULL COMMENT '丢弃明细：未映射 pointId 前 8 个逗号连接，超出记 ,...(+N) 后缀',
    ADD KEY idx_iot_mqtt_ingest_receipt_time (tenant_id, device_id, create_time);
