-- =============================================================
-- ypbin-iot 菜单与权限（全新安装用）
-- 权限码约定：iot:device:list | iot:device:create | iot:device:delete
-- ⚠️ 002-data.sql 里那条「把所有 platform_only=1 的菜单授给角色 1」在本文件**之前**执行，
--    因此本文件必须自己再授一次权，否则新菜单不会出现在平台管理员菜单树里。
-- 已有库升级：见 deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3200, 0, 'IotDevice', 'menu', 1, '/iot/device', '/iot/device/list', 'iot:device:list', 'iot.device.title', 'carbon:iot', 6, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320001, 3200, 'IotDeviceCreate', 'button', 1, 'iot:device:create', 'common.create', 1, NOW(), 1, 0),
       (320002, 3200, 'IotDeviceDelete', 'button', 1, 'iot:device:delete', 'common.delete', 2, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND platform_only = 1 AND id IN (3200, 320001, 320002);
