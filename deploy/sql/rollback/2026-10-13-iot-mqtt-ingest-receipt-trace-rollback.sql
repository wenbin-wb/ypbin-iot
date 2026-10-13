-- 回滚：2026-10-13-iot-mqtt-ingest-receipt-trace.sql（消息跟踪二批：上行结构化补齐）。
-- 影响：回滚后时间线不再呈现 UP_PERSISTED / UP_DISCARDED（退化为"上行已受理"），写入侧也不再记录落库/丢弃。
-- 顺序约束（必守）：先回滚 jar（旧 jar 不认识这 6 列，SELECT/INSERT 会失败）再执行本 SQL；
--   反过来会让新 jar 因未知列读写失败。
-- 数据：这 6 列与 rejected 行的历史随列删除而丢失；被拒行的应用日志与
--   iot.mqtt.ingest.rejected{reason} 指标不受影响（回滚声明必须含"已落库数据不可撤回"）。
ALTER TABLE iot_mqtt_ingest_receipt
    DROP INDEX idx_iot_mqtt_ingest_receipt_time,
    DROP COLUMN receipt_status,
    DROP COLUMN received_count,
    DROP COLUMN persisted_rows,
    DROP COLUMN persisted_at,
    DROP COLUMN discard_reason,
    DROP COLUMN discard_detail;
