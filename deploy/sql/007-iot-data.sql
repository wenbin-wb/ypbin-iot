-- =============================================================
-- ypbin-iot 菜单与权限（全新安装用）
-- 权限码约定：iot:device:list | iot:device:create | iot:device:update | iot:device:delete
-- ⚠️ 002-data.sql 里那条「把所有 platform_only=1 的菜单授给角色 1」在本文件**之前**执行，
--    因此本文件必须自己再授一次权，否则新菜单不会出现在平台管理员菜单树里。
-- 已有库升级：见 deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3200, 0, 'IotDevice', 'menu', 0, '/iot/devices', '/iot/devices/index', 'iot:device:list', 'page.iot.device.title', 'carbon:iot', 6, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320001, 3200, 'IotDeviceCreate', 'button', 0, 'iot:device:create', 'common.create', 1, NOW(), 1, 0),
       (320002, 3200, 'IotDeviceDelete', 'button', 0, 'iot:device:delete', 'common.delete', 2, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）：002-data.sql 那条批量授权只覆盖 platform_only=1，
-- 而业务菜单是 platform_only=0（租户可见），因此这里必须自己授一次。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3200, 320001, 320002);

-- 租户可授菜单来自 sys_template_menu（SysAuthTemplateServiceImpl 从它推导）；
-- 002-data.sql 填它时 IoT 菜单还不存在 ⇒ 这里必须补授，否则**租户永远看不到 IoT 菜单**。
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3200, 320001, 320002);

-- =============================================================
-- M-1 物模型域菜单与权限（2026-09-20 追加）
-- 权限码约定：iot:product:* | iot:point:* | iot:shadow:* | iot:group:* | iot:tag:*
-- ⚠️ 002-data.sql 的批量授权只覆盖 platform_only=1；M-1 菜单是 platform_only=0，
--    必须自己再授一次权（与上方 3200 系列同样的原因）。
-- 等价性：本文件追加部分与 migration/2026-09-20-iot-m1-thing-model-menu.sql 语句等价。
-- =============================================================

-- 产品（物模型入口）
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3201, 0, 'IotProduct', 'menu', 0, '/iot/products', '/iot/products/index', 'iot:product:list', 'page.iot.product.title', 'carbon:product', 7, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320101, 3201, 'IotProductCreate', 'button', 0, 'iot:product:create', 'common.create', 1, NOW(), 1, 0),
       (320102, 3201, 'IotProductUpdate', 'button', 0, 'iot:product:update', 'common.edit', 2, NOW(), 1, 0),
       (320103, 3201, 'IotProductDelete', 'button', 0, 'iot:product:delete', 'common.delete', 3, NOW(), 1, 0),
       (320104, 3201, 'IotProductPublish', 'button', 0, 'iot:product:publish', 'page.iot.product.publish', 4, NOW(), 1, 0),
       (320105, 3201, 'IotProductTslImport', 'button', 0, 'iot:product:tsl-import', 'page.iot.product.tslImport', 5, NOW(), 1, 0),
       (320106, 3201, 'IotProductTslExport', 'button', 0, 'iot:product:tsl-export', 'page.iot.product.tslExport', 6, NOW(), 1, 0);

-- 设备分组
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3202, 0, 'IotDeviceGroup', 'menu', 0, '/iot/groups', '/iot/groups/index', 'iot:group:list', 'page.iot.group.title', 'carbon:group', 8, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320201, 3202, 'IotDeviceGroupCreate', 'button', 0, 'iot:group:create', 'common.create', 1, NOW(), 1, 0),
       (320202, 3202, 'IotDeviceGroupUpdate', 'button', 0, 'iot:group:update', 'common.edit', 2, NOW(), 1, 0),
       (320203, 3202, 'IotDeviceGroupDelete', 'button', 0, 'iot:group:delete', 'common.delete', 3, NOW(), 1, 0);

-- 点位映射（挂在设备菜单 3200 下的按钮，§13：/iot/devices/{id}/points）
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320003, 3200, 'IotPointList', 'button', 0, 'iot:point:list', 'page.iot.point.title', 3, NOW(), 1, 0),
       (320004, 3200, 'IotPointCreate', 'button', 0, 'iot:point:create', 'common.create', 4, NOW(), 1, 0),
       (320005, 3200, 'IotPointUpdate', 'button', 0, 'iot:point:update', 'common.edit', 5, NOW(), 1, 0),
       (320006, 3200, 'IotPointDelete', 'button', 0, 'iot:point:delete', 'common.delete', 6, NOW(), 1, 0);

-- 影子（§13：/iot/devices/{id}/shadow）
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320007, 3200, 'IotShadowGet', 'button', 0, 'iot:shadow:get', 'page.iot.shadow.get', 7, NOW(), 1, 0),
       (320008, 3200, 'IotShadowUpdate', 'button', 0, 'iot:shadow:update', 'page.iot.shadow.update', 8, NOW(), 1, 0);

-- 设备标签（§3.11）
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320009, 3200, 'IotTagList', 'button', 0, 'iot:tag:list', 'page.iot.tag.title', 9, NOW(), 1, 0),
       (320010, 3200, 'IotTagCreate', 'button', 0, 'iot:tag:create', 'common.create', 10, NOW(), 1, 0),
       (320011, 3200, 'IotTagDelete', 'button', 0, 'iot:tag:delete', 'common.delete', 11, NOW(), 1, 0),
       (320012, 3200, 'IotTagUpdate', 'button', 0, 'iot:tag:update', 'common.edit', 12, NOW(), 1, 0),
       (320013, 3200, 'IotDeviceUpdate', 'button', 0, 'iot:device:update', 'common.edit', 13, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3201, 320101, 320102, 320103, 320104, 320105, 320106, 3202, 320201, 320202, 320203, 320003, 320004, 320005, 320006, 320007, 320008, 320009, 320010, 320011, 320012, 320013);

-- 租户可授菜单补授
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3201, 320101, 320102, 320103, 320104, 320105, 320106, 3202, 320201, 320202, 320203, 320003, 320004, 320005, 320006, 320007, 320008, 320009, 320010, 320011, 320012, 320013);

-- =============================================================
-- M-2 租户台账运维权限（2026-09-21 追加）
-- 权限码：iot:ledger:list | iot:ledger:update
-- ⚠️ 这是**平台级**动作（决定哪些租户可被 access 节点采集，跨租户生效）⇒ platform_only=1，
--    且**只授平台管理员角色 1**，绝不进 sys_template_menu：否则任一租户管理员都能改
--    「别的租户是否被采集」。本端点没有独立前端页面，故按影子/标签的既有做法挂为设备菜单
--    3200 下的按钮权限（挂一个点不开的页面菜单才是更差的体验）。
-- 等价性：本文件追加部分与 migration/2026-09-21-iot-m2-ledger-menu.sql 语句等价。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320014, 3200, 'IotLedgerList', 'button', 1, 'iot:ledger:list', 'page.iot.ledger.title', 14, NOW(), 1, 0),
       (320015, 3200, 'IotLedgerUpdate', 'button', 1, 'iot:ledger:update', 'common.edit', 15, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）：002-data.sql 的批量授权只覆盖 platform_only=1 的**存量**菜单，
-- 且在本文件之前执行 ⇒ 新追加的菜单必须自己再授一次
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320014, 320015);

-- =============================================================
-- M-2 断档与可用率菜单与权限（2026-09-22 追加）
-- 权限码：iot:availability:get（逐台设备可用率查询，租户可见 —— 与影子/标签同为设备页内的能力）
-- 等价性：本文件追加部分与 migration/2026-09-22-iot-m2-availability-menu.sql 语句等价。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320016, 3200, 'IotAvailabilityGet', 'button', 0, 'iot:availability:get', 'page.iot.availability.title', 16, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）：002-data.sql 的批量授权只覆盖 platform_only=1，且在本文件之前执行
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320016);

-- 租户可授菜单来自 sys_template_menu（SysAuthTemplateServiceImpl 从它推导），必须一并补授
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320016);

-- =============================================================
-- M-2 维护窗口管理菜单与权限（2026-09-23 追加，A3 的「可配置」）
-- 权限码：iot:maintenance:list | iot:maintenance:create | iot:maintenance:close
-- 等价性：本文件追加部分与 migration/2026-09-23-iot-m2-maintenance-menu.sql 语句等价。
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
