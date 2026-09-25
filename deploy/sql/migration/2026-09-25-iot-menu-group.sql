-- ypbin-iot 菜单归并（增量迁移；与 007-iot-data.sql 追加部分语句等价）
-- 内容：新增顶级「IoT 平台」主菜单（catalog，id=3204，pid=0），把 4 个 IoT 页面菜单
--       （3200 设备台账 / 3201 产品与物模型 / 3202 设备分组 / 3203 维护窗口）的 pid 由 0 改为 3204。
-- 新父 id 3204：2026-09-25 查活库 sys_menu（id BETWEEN 3000 AND 3400）确认未占用，仓内 SQL 亦无。
-- 授权：新父菜单同时补授 sys_role_menu（角色 1）与 sys_template_menu（模板 1）。
-- 回滚：见同批次回滚脚本（UPDATE pid 回 0 + 删除 3204 的三处行）。
-- =============================================================
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3204, 0, 'IotPlatform', 'catalog', 0, '/iot', 'BasicLayout', NULL, 'page.iot.title', 'carbon:iot', 6, NOW(), 1, 0);

-- 新父菜单必须同时落在两张授权表：sys_role_menu（平台管理员角色 1）与 sys_template_menu（租户可授模板 1），
-- 否则菜单建出来但平台管理员/租户都看不见（IotMaintenanceAdminGateTest 会校验 32xx 菜单必须被两张表覆盖）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3204);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3204);

-- 把 4 个 IoT 页面挂到新父菜单下（pid 由 0 改为 3204）；按钮权限因挂在页面下，层级随之自动下沉。
UPDATE sys_menu SET pid = 3204 WHERE id IN (3200, 3201, 3202, 3203);
