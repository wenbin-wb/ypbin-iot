-- =============================================================
-- MQTT 入站幂等回执：iot_mqtt_ingest_receipt（已上线库升级用）
-- 与 deploy/sql/007-iot-data.sql 末尾的同名表**语句等价**（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 文件名含 `-iot-` 且按名排序在最后，与「追加到 007 末尾」的位置对应。
-- 回滚见 deploy/sql/rollback/2026-10-01-iot-mqtt-ingest-receipt-rollback.sql。
-- =============================================================

CREATE TABLE iot_mqtt_ingest_receipt
(
    id          BIGINT      NOT NULL COMMENT '主键',
    tenant_id   BIGINT      NOT NULL COMMENT '租户 ID（由设备行解析，不信任报文声明）',
    device_id   BIGINT      NOT NULL COMMENT '设备 ID（iot_device.id；来自主题段，非报文声明）',
    request_id  VARCHAR(64) NOT NULL COMMENT '设备侧请求 ID（幂等键；MQTT 上行报文体携带）',
    item_count  INT         NOT NULL COMMENT '首次受理时通过校验的读数条数（重投时原样读回，不重算）',
    create_user BIGINT      NULL COMMENT '创建人',
    create_time DATETIME    NULL COMMENT '创建时间',
    update_user BIGINT      NULL COMMENT '更新人',
    update_time DATETIME    NULL COMMENT '更新时间',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_mqtt_ingest_receipt_request (tenant_id, device_id, request_id)
) COMMENT 'IoT MQTT 入站幂等回执（同一设备同一 requestId 只落一行）';
