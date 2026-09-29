-- ypbin-iot 设备批量导入：权限码与菜单（增量迁移）
-- 与 deploy/sql/007-iot-data.sql 的**批量导入菜单段**语句等价（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 权限码一个：iot:device:import（上传）；读路径沿用 iot:device:list。
-- id 段：320024（3200 段内 320023 之后的空位）。
-- platform_only=0 ⇒ 必须同时进 sys_role_menu 与 sys_template_menu。
-- ⚠️ 排序约束：本文件名必须排在 2026-10-03-iot-alert-menu.sql 之后。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320024, 3200, 'IotDeviceImport', 'button', 0, 'iot:device:import', 'page.iot.device.import.title', 24, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320024);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320024);
