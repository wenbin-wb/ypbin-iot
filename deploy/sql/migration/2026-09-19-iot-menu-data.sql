-- =============================================================
-- 迁移：IoT 菜单与权限授予（增量 1 的数据部分）
-- 适用：**已上线的库**；与 deploy/sql/007-iot-data.sql 的**语句**等价。
-- 顺序：迁移目录按文件名排序拼接，本文件排在 *-device-schema / *-lease-schema 之后，
--       与全新安装的「006（结构）→ 007（数据）」顺序一致。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3200, 0, 'IotDevice', 'menu', 0, '/iot/devices', '/iot/devices/index', 'iot:device:list', 'page.iot.device.title', 'carbon:iot', 6, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320001, 3200, 'IotDeviceCreate', 'button', 0, 'iot:device:create', 'common.create', 1, NOW(), 1, 0),
       (320002, 3200, 'IotDeviceDelete', 'button', 0, 'iot:device:delete', 'common.delete', 2, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3200, 320001, 320002);

-- 租户可授菜单来自 sys_template_menu（SysAuthTemplateServiceImpl 从它推导）；
-- 002-data.sql 填它时 IoT 菜单还不存在 ⇒ 这里必须补授，否则**租户永远看不到 IoT 菜单**。
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3200, 320001, 320002);
