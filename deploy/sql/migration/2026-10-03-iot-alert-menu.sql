-- ypbin-iot 告警与阈值能力：权限码与菜单（增量迁移）
-- 与 deploy/sql/007-iot-data.sql 的**告警菜单段**语句等价（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 权限码四个：iot:alert:list（查告警）| iot:alert:ack（确认与静默）|
--             iot:alert:rule-list（查规则）| iot:alert:rule-save（建/改/启停规则）
-- id 段：3207 = 告警页（IoT 段内的新页面菜单）；320701~320704 = 该页下的按钮。
--   2026-10-03 查仓内 SQL 与既有菜单段确认 3200~3206 与 320001~320023/320101~/320201~/320301~ 已占用。
-- platform_only=0 ⇒ 按 IotMaintenanceAdminGateTest 口径必须**同时**进 sys_role_menu 与 sys_template_menu
--   （后者是租户可授菜单与「防孤儿」父目录补授的依据）。
-- ⚠️ 排序约束：本文件名必须排在 2026-10-02-iot-command-instance.sql 之后。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3207, 3204, 'IotAlert', 'menu', 0, '/iot/alerts', '/iot/alerts/index', 'iot:alert:list', 'page.iot.alert.title', 'carbon:warning-alt', 7, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320701, 3207, 'IotAlertAck', 'button', 0, 'iot:alert:ack', 'page.iot.alert.ack', 1, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320702, 3207, 'IotAlertRuleList', 'button', 0, 'iot:alert:rule-list', 'page.iot.alert.rule.list', 2, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320703, 3207, 'IotAlertRuleSave', 'button', 0, 'iot:alert:rule-save', 'page.iot.alert.rule.save', 3, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3207, 320701, 320702, 320703);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3207, 320701, 320702, 320703);
