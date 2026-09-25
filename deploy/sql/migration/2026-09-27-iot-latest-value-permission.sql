-- ypbin-iot G1 设备最新值查询权限（增量迁移；与 007-iot-data.sql 追加部分语句等价）
-- 权限码：iot:device:latest（读 Redis 哈希 iot:latest:{tenantId}:{deviceId}）
-- 背景：最新值此前「只写不读」（RedisLatestValueWriter 落库、全仓无读取端点），
--   本迁移为新端点 GET /iot/devices/{deviceId}/latest 登记权限码与菜单授权。
-- =============================================================
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320018, 3200, 'IotDeviceLatest', 'button', 0, 'iot:device:latest', 'page.iot.device.latest', 18, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320018);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320018);
