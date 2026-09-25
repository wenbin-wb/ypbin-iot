-- =============================================================
-- F6 接入向导 + F5 租户接入台账 页面菜单（已上线库升级用）
-- 与 deploy/sql/007-iot-data.sql 的同段**语句等价**（由 tools/check-iot-sql-equivalence.sh 校验）。
--
-- 背景：两个页面此前没有页面级菜单（F5 的 320014/320015 只是挂在设备菜单下的按钮权限载体），
--      导致「页面已实现但左侧点不开」。本段在 IoT 平台目录 3204 下补两条 type='menu' 的页面行。
--
-- id 占用核对（2026-09-25 实测）：活库 sys_menu 中 3205/3206 为 0 行（含已删除）；
--   仓内 3205/3206 在菜单命名空间内无占用；name IotOnboarding / IotTenantLedger 均未占用。
--
-- 授权口径（IotMaintenanceAdminGateTest 的硬要求）：
--   一律进 sys_role_menu（平台管理员角色 1）；
--   **仅** platform_only=0 的 3205 进 sys_template_menu（3206 是平台级，进了就等于让租户管理员
--   能改别的租户是否被采集）。
--
-- ⚠️ 排序约束：本文件必须排在 007 追加段的**同一位置**（即 007 的最末尾）。等价性脚本把
--   006+007 与 migration/*-iot-*.sql 按**文件名排序**后逐语句比较 ⇒ 若将来在 007 末尾再追加语句，
--   新迁移文件名必须排在 `2026-09-29-...` 之后（例如 2026-09-30-…）。
--
-- 回滚：deploy/sql/rollback/2026-09-29-iot-menu-onboarding-ledger-rollback.sql
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3205, 3204, 'IotOnboarding', 'menu', 0, '/iot/onboarding', '/iot/onboarding/index', NULL, 'page.iot.onboarding.title', 'carbon:idea', 5, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3206, 3204, 'IotTenantLedger', 'menu', 1, '/iot/tenant-ledger', '/iot/tenant-ledger/index', 'iot:ledger:list', 'page.iot.ledger.pageTitle', 'carbon:data-table', 10, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3205, 3206);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3205);
