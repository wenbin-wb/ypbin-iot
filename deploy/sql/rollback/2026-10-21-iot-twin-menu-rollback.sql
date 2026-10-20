-- 回滚：2026-10-21-iot-twin-menu.sql（数字孪生权限码与菜单）。
-- 影响：回滚后菜单与权限码消失 ⇒ 该页面在左侧导航不可见，
--       且已授予这些权限的角色会丢失对应操作能力（后端接口仍存在，调用会 403）。
-- 顺序：先回滚 jar（不再有 iot:twin:* 端点），或先接受「页面消失但接口仍可直连」的中间态。
DELETE FROM sys_template_menu WHERE menu_id IN (3210, 321001, 321002, 321003);
DELETE FROM sys_role_menu WHERE menu_id IN (3210, 321001, 321002, 321003);
DELETE FROM sys_menu WHERE id IN (3210, 321001, 321002, 321003);
