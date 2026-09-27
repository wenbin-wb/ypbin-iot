-- ypbin-iot 设备凭据权限码与菜单（增量迁移；与 007-iot-data.sql 追加部分的**菜单段**语句等价）
-- 权限码：iot:credential:{get,issue,revoke}（设计 P0-8 的凭据三项，id 段沿用设计附录 A：320019/320020/320021）
-- 设计依据：docs/EMQX-INGRESS-DESIGN.md §5.3 / P0-A-3；实现口径：docs/DEVICE-CREDENTIAL.md
-- =============================================================
-- 权限码与菜单（设计 P0-8 的凭据三项；id 段沿用设计附录 A：320019/320020/320021，
-- 挂在 3200「设备台账」下；platform_only=0 ⇒ 按 IotMaintenanceAdminGateTest 口径
-- 必须同时进 sys_role_menu 与 sys_template_menu）
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320019, 3200, 'IotCredentialGet', 'button', 0, 'iot:credential:get', 'page.iot.credential.get', 19, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320020, 3200, 'IotCredentialIssue', 'button', 0, 'iot:credential:issue', 'page.iot.credential.issue', 20, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320021, 3200, 'IotCredentialRevoke', 'button', 0, 'iot:credential:revoke', 'page.iot.credential.revoke', 21, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320019, 320020, 320021);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320019, 320020, 320021);
