-- 回滚：2026-10-06-iot-command-client-key.sql（看板 #11 O-7 C2）。
-- ⚠️ DROP COLUMN 会永久删除已存的客户端幂等键；回滚后同键重复提交将不再去重（产生重复下发）。
-- 执行前确认：无进行中的第三方重试窗口，且调用方已停止发送 client_request_id。
-- 顺序约束（必守）：先回滚 jar（旧 jar 不认识 client_request_id 列）再执行本 SQL；
--   反过来会让新 jar 因未知列写入失败。
-- 已发出命令无法撤回：回滚只删幂等键，不影响已下发到设备的命令（设备侧动作不可逆）。
ALTER TABLE iot_command_instance
    DROP INDEX uk_command_client_key,
    DROP COLUMN client_request_id;
