-- ypbin-iot M-2 历史时序查询权限（增量迁移；与 007-iot-data.sql 追加部分语句等价）
-- 权限码：iot:series:get（§5.2.1 查询路径）
-- =============================================================
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320017, 3200, 'IotSeriesGet', 'button', 0, 'iot:series:get', 'page.iot.series.title', 17, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320017);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320017);
