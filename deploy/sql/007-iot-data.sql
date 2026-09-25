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

-- =============================================================
-- M-2 历史时序查询权限（2026-09-24 追加，§5.2.1 查询路径）
-- 权限码：iot:series:get（逐台设备的历史曲线查询）
-- 等价性：本文件追加部分与 migration/2026-09-24-iot-m2-series-permission.sql 语句等价。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320017, 3200, 'IotSeriesGet', 'button', 0, 'iot:series:get', 'page.iot.series.title', 17, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320017);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320017);

-- =============================================================
-- IoT 菜单归并（2026-09-25 追加）：新增顶级「IoT 平台」主菜单，4 个 IoT 页面移作其子菜单
-- 新父 id 3204：2026-09-25 查**活库** sys_menu（id BETWEEN 3000 AND 3400）确认未被占用
--   （当时在用的 32xx 只有 3200-3203 与 320001-320017），仓内 SQL 亦无 3204。
-- 层级形态参照平台既有分组菜单：type=catalog + component=BasicLayout（同 3009 TrackingManage，
--   子菜单路径保持各自的绝对路径 /iot/xxx 不变）。
-- 等价性：本文件追加部分与 migration/2026-09-25-iot-menu-group.sql 语句等价。
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

-- =============================================================
-- 平台模块目录（2026-09-26 追加）：新增「基础管理」「运维与监控」两个顶级模块目录，
-- 并把 10 个既有顶级菜单 reparent 到模块下（8 个进 3310、2 个进 3320）；其余 3 个顶级（1/3204/5000）不动。
-- 方案见 docs/PLATFORM-IA-PROPOSAL.md §7.1.1（第二轮独立复核 PASS（有条件），见 §8.6）。
-- id 规划：33xx = 平台模块目录段（只放 type=catalog 且 pid=0 的模块目录），不与 32xx（IoT 段）混用。
--   2026-09-26 查**活库** sys_menu（id BETWEEN 3300 AND 3399）确认 0 行、仓内 SQL 亦无 3310/3320。
-- 层级形态参照平台既有分组菜单：type=catalog + component=BasicLayout，子菜单路径保持各自的绝对路径不变。
-- platform_only 规则（SysMenuServiceImpl#buildRoutes：非平台用户先被丢掉 platform_only=1 的行，再按 pid 挂树）：
--   模块目录下只要有 platform_only=0 的子菜单，目录自身就必须是 0，否则该子菜单整棵成孤儿。
--   ⇒ 3310 基础管理 = 0（下有 3001/3002/3007/4001 等租户可见项）；3320 运维与监控 = 1（子项 3004/3009 均 1）。
-- 等价性：本文件追加部分与 migration/2026-09-26-iot-platform-module-menu.sql 语句等价。
-- 回滚：deploy/sql/rollback/2026-09-26-iot-platform-module-menu-rollback.sql（pid 回 0 + 删授权行 + 删菜单行）。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3310, 0, 'BasicAdmin', 'catalog', 0, '/admin', 'BasicLayout', NULL, 'page.admin.title', 'carbon:settings-adjust', 2, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3320, 0, 'PlatformOps', 'catalog', 1, '/ops', 'BasicLayout', NULL, 'page.ops.title', 'carbon:activity', 12, NOW(), 1, 0);

-- 模块目录自身的授权：sys_role_menu 给平台管理员角色 1；sys_template_menu 给租户可授模板 1。
-- 3310 的 platform_only=0 ⇒ 会被 SysAuthTemplateServiceImpl#resolveAvailableMenuIds 收进「租户可授菜单」，
--   必须进模板，否则租户管理员新建/修改角色时会被 SysRoleServiceImpl#validateMenus（:236-252，由 :132/:152 调用）
--   抛「角色授权包含租户权限模板之外的菜单」——租户侧连角色都保存不了。
-- 3320 下全是 platform_only=1 的平台专用菜单，按既有约定不进 sys_template_menu（先例：本文件上方 M2 台账菜单）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3310, 3320);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3310);

-- 防孤儿（K2）：把模块目录补授给「已经拥有其任一子菜单」的**所有**角色/模板。
-- 只授 role 1 不够：任何角色/租户模板只要拥有子菜单而缺父目录，该子菜单就会被 buildRouteTree 整棵丢弃
-- （SysMenuServiceImpl.java:78-79 从 pid=0 挂树 + :82-101 只补「已在用户菜单集合里」的祖先）。
-- INSERT IGNORE 兜住 PK(role_id,menu_id) / PK(template_id,menu_id) 的重复（001-schema.sql:147-152、:477-482）。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 3310 FROM sys_role_menu rm
WHERE rm.menu_id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001) AND rm.role_id <> 1;

INSERT IGNORE INTO sys_template_menu (template_id, menu_id)
SELECT DISTINCT tm.template_id, 3310 FROM sys_template_menu tm
WHERE tm.menu_id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001) AND tm.template_id <> 1;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 3320 FROM sys_role_menu rm
WHERE rm.menu_id IN (3004, 3009) AND rm.role_id <> 1;

-- 3320 的模板侧同款补授（第二轮复核新增）：当前数据下不可能命中 —— resolveAvailableMenuIds
-- （SysAuthTemplateServiceImpl.java:199-209，:207 过滤 platform_only=false）不允许 platform_only=1 的菜单进模板。
-- 但「模板侧孤儿恒为 0 行」是本方案要维持的不变量，补这一条把 latent gap 变成结构性保证：
-- 将来若 3004/3009 或 3320 被改成 platform_only=0，不会有整棵丢失的窗口。
INSERT IGNORE INTO sys_template_menu (template_id, menu_id)
SELECT DISTINCT tm.template_id, 3320 FROM sys_template_menu tm
WHERE tm.menu_id IN (3004, 3009) AND tm.template_id <> 1;

-- reparent：子菜单的 path / component / auth_code 一律不动 ⇒ 书签 URL 与权限码不变。
UPDATE sys_menu SET pid = 3310 WHERE id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001);
UPDATE sys_menu SET pid = 3320 WHERE id IN (3004, 3009);

-- =============================================================
-- G1 设备最新值查询权限（2026-09-27 追加）
-- 权限码：iot:device:latest（读 Redis 最新值哈希 iot:latest:{tenantId}:{deviceId}；
--   补的是「最新值只写不读」这个缺口，端点 GET /iot/devices/{deviceId}/latest）
-- 归设备菜单 3200 下的按钮权限（与影子/标签/可用率/历史曲线同一做法：能力属设备页内，不另立页面）。
-- 等价性：本文件追加部分与 migration/2026-09-27-iot-latest-value-permission.sql 语句等价。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320018, 3200, 'IotDeviceLatest', 'button', 0, 'iot:device:latest', 'page.iot.device.latest', 18, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）：002-data.sql 的批量授权只覆盖 platform_only=1，且在本文件之前执行
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320018);

-- 租户可授菜单来自 sys_template_menu（SysAuthTemplateServiceImpl 从它推导），必须一并补授
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320018);
