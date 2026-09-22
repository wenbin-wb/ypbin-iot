-- =============================================================
-- ypbin-iot M-2 断档与可用率菜单与权限（增量迁移；与 007-iot-data.sql 追加部分语句等价）
-- 权限码：iot:availability:get（逐台设备可用率查询）
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320016, 3200, 'IotAvailabilityGet', 'button', 0, 'iot:availability:get', 'page.iot.availability.title', 16, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320016);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320016);
