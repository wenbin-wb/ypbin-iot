-- =============================================================
-- 回滚：F6 接入向导（3205）与 F5 租户接入台账（3206）页面菜单
-- 对应正向迁移：deploy/sql/migration/2026-09-29-iot-menu-onboarding-ledger.sql
--
-- 先删授权再删菜单：
--   · sys_role_menu / sys_template_menu 里的行是**授权关系**，不删会让菜单树构建时出现指向不存在菜单的悬空行；
--   · 3206 从未进过 sys_template_menu，这里仍按 id 一并 DELETE（幂等，删不到就是 0 行）。
--
-- ⚠️ 只回滚本次新增的两条**页面**菜单及其授权，不动既有的 320014/320015
--    （那是 iot:ledger:* 权限码的载体，回滚它们会让平台管理员的台账写权限一起消失）。
-- =============================================================

DELETE FROM sys_role_menu WHERE menu_id IN (3205, 3206);
DELETE FROM sys_template_menu WHERE menu_id IN (3205);
DELETE FROM sys_menu WHERE id IN (3205, 3206);
