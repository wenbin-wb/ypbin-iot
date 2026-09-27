-- ypbin-iot 设备凭据生命周期 · 回滚（对应 migration/2026-09-30-iot-credential-schema.sql
--   + 2026-09-30-iot-credential-menu.sql 这两个增量迁移）
-- ⚠️ 不可逆部分：已签发的凭据哈希与吊销审计一并消失（设备侧若已按签发结果配置了口令，
--    回滚后**平台侧不再校验**该口令；EMQX 接入后还需一并清理 broker 侧用户，见 docs/DEVICE-CREDENTIAL.md）。
-- 执行前建议先导出：SELECT device_id, credential_version, credential_issued_at, credential_revoked_at FROM iot_device
--    WHERE credential_ref IS NOT NULL;  以及 SELECT * FROM iot_device_credential（**含哈希，按凭据处理**）。
-- =============================================================
DELETE FROM sys_template_menu WHERE menu_id IN (320019, 320020, 320021);

DELETE FROM sys_role_menu WHERE menu_id IN (320019, 320020, 320021);

DELETE FROM sys_menu WHERE id IN (320019, 320020, 320021);

DROP TABLE IF EXISTS iot_device_credential;

ALTER TABLE iot_device
    DROP COLUMN credential_version,
    DROP COLUMN credential_issued_at,
    DROP COLUMN credential_revoked_at;
