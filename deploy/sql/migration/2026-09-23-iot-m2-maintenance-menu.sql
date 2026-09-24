-- ypbin-iot M-2 维护窗口管理菜单与权限（增量迁移；与 007-iot-data.sql 追加部分语句等价）
-- 权限码：iot:maintenance:list | iot:maintenance:create | iot:maintenance:close
-- =============================================================
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3203, 0, 'IotMaintenance', 'menu', 0, '/iot/maintenance', '/iot/maintenance/index', 'iot:maintenance:list', 'page.iot.maintenance.title', 'carbon:tools', 9, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320301, 3203, 'IotMaintenanceCreate', 'button', 0, 'iot:maintenance:create', 'common.create', 1, NOW(), 1, 0),
       (320302, 3203, 'IotMaintenanceClose', 'button', 0, 'iot:maintenance:close', 'common.close', 2, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）：002-data.sql 的批量授权只覆盖 platform_only=1，且在本文件之前执行
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3203, 320301, 320302);

-- 租户可授菜单来自 sys_template_menu（SysAuthTemplateServiceImpl 从它推导），必须一并补授
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3203, 320301, 320302);
