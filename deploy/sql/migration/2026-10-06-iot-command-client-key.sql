-- =============================================================
-- 命令实例客户端幂等键：iot_command_instance.client_request_id（看板 #11 O-7 C2）
-- 与 deploy/sql/007-iot-data.sql 末尾的同名语句**逐字等价**（tools/check-iot-sql-equivalence.sh 校验）。
-- 文件名按日期排序在最后，与"追加到 007 末尾"的位置对应。
-- 回滚见 deploy/sql/rollback/2026-10-06-iot-command-client-key-rollback.sql
--   （DROP COLUMN 会连带删掉已存的幂等键；执行前确认无进行中的第三方重试窗口）。
-- =============================================================

ALTER TABLE iot_command_instance
    ADD COLUMN client_request_id VARCHAR(64) NULL COMMENT '客户端幂等键（开放 API/第三方提供；同租户+同设备唯一；null=平台生成 request_id，不参与去重）',
    ADD UNIQUE KEY uk_command_client_key (tenant_id, device_id, client_request_id);
