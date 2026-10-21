-- ypbin-iot 数字孪生：权限码与菜单（增量迁移）
-- 与 deploy/sql/007-iot-data.sql 的**数字孪生权限码与菜单段**语句等价（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 权限码四个：iot:twin:list | iot:twin:create | iot:twin:update | iot:twin:delete
-- id 段：3210 = 数字孪生页面；321001~321003 = 该页下的按钮。
-- platform_only=0 ⇒ 按 IotMaintenanceAdminGateTest 口径必须**同时**进 sys_role_menu 与 sys_template_menu。
-- =============================================================

-- =============================================================
-- 数字孪生权限码与菜单（2026-10-21 追加）
-- 权限码四个：iot:twin:list（读全部孪生资源）| iot:twin:create（建场景/加点位/传模型）|
--             iot:twin:update（改场景/改点位与绑定）| iot:twin:delete（删场景/删点位/删模型）
-- id 段：3210 = 数字孪生页面（IoT 段内新页面）；321001~321003 = 该页下的按钮。
--   2026-10-21 查仓内 SQL（007 与 migration/*.sql 全量）确认 3210 与 321001~321003 未被占用
--   （在用的 32xx 为 3200~3209、320001~320024、320101~、320201~、320301~、320701~、320901~）。
-- platform_only=0 ⇒ 按 IotMaintenanceAdminGateTest 口径必须**同时**进 sys_role_menu 与 sys_template_menu
--   （后者是租户可授菜单与「防孤儿」父目录补授的依据）。
-- 父菜单 3204（IoT 平台目录）已存在并已授权，本段不重复授权父目录。
-- 等价性：本文件追加部分与 migration/2026-10-21-iot-twin-menu.sql 语句等价。
-- 回滚：deploy/sql/rollback/2026-10-21-iot-twin-menu-rollback.sql
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3210, 3204, 'IotDigitalTwin', 'menu', 0, '/iot/twin', '/iot/twin/index', 'iot:twin:list', 'page.iot.twin.title', 'carbon:cube', 16, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (321001, 3210, 'IotTwinCreate', 'button', 0, 'iot:twin:create', 'page.iot.twin.create', 1, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (321002, 3210, 'IotTwinUpdate', 'button', 0, 'iot:twin:update', 'page.iot.twin.update', 2, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (321003, 3210, 'IotTwinDelete', 'button', 0, 'iot:twin:delete', 'page.iot.twin.delete', 3, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3210, 321001, 321002, 321003);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3210, 321001, 321002, 321003);
