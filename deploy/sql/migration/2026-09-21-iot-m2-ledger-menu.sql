-- =============================================================
-- ypbin-iot M-2 租户台账运维权限（增量迁移；与 deploy/sql/007-iot-data.sql 追加部分等价）
-- 权限码：iot:ledger:list | iot:ledger:update（平台级：平台管理员角色 1 专属，不进租户模板）
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320014, 3200, 'IotLedgerList', 'button', 1, 'iot:ledger:list', 'page.iot.ledger.title', 14, NOW(), 1, 0),
       (320015, 3200, 'IotLedgerUpdate', 'button', 1, 'iot:ledger:update', 'common.edit', 15, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320014, 320015);
