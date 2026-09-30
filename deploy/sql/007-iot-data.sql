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

-- =============================================================
-- 菜单图标缺陷修复（2026-09-28 追加）
-- 缺陷：id=3200「设备台账」与 id=3204「IoT 平台」的 icon 写成 `carbon:iot`，
--   而 Carbon 图标集**没有** `iot` 这个图标（Iconify API 对 carbon.json?icons=iot 返回
--   `not_found:["iot"]`，carbon 集合 2618 个图标里只有 `iot-connect` 与 `iot-platform`）。
--   前端 VbenIcon（packages/@core/ui-kit/shadcn-ui/src/components/icon/icon.vue）对字符串 icon
--   一律交给 @iconify/vue 的 Icon 渲染，取不到图标即渲染空 svg ⇒ 菜单左侧留白。
-- 修法：改用同集合中**确实存在**的图标名（逐个用 Iconify API 核验过）：
--   3200 设备台账 = `carbon:devices`（设备清单/台账语义）
--   3204 IoT 平台（顶级模块目录）= `carbon:iot-platform`（语义直配）
-- 只改 icon 一列，不动 path/component/auth_code/sort/pid ⇒ 书签、权限码、菜单顺序均不变。
-- 前端无需重建：产物里没有内置 Carbon 图标集（dist 中只有 api.iconify.design 运行时取图标），
--   图标名由 /auth/menu/all 的菜单树在浏览器端解析，刷新页面即生效。
-- 等价性：本文件追加部分与 migration/2026-09-28-iot-menu-icons.sql 语句等价。
-- 回滚：deploy/sql/rollback/2026-09-28-iot-menu-icons-rollback.sql（还原为修复前的 carbon:iot）。
-- 说明：上方 2026-09-19/2026-09-25 两段历史 INSERT 里的 'carbon:iot' 字面量保留不改，
--   由本段 UPDATE 覆盖，避免改动已应用的历史迁移。全新安装与存量库最终值一致。
-- =============================================================

UPDATE sys_menu SET icon = 'carbon:devices' WHERE id = 3200;

UPDATE sys_menu SET icon = 'carbon:iot-platform' WHERE id = 3204;

-- F6 接入向导 + F5 租户接入台账 页面菜单（2026-09-29 追加）
-- 起因：这两个页面此前**没有页面级菜单**（F5 的 320014/320015 只是挂在设备菜单下的按钮权限载体），
--      于是「页面已实现但左侧点不开」。本次在 IoT 平台目录 3204 下补两条 type='menu' 的页面行。
--
-- 3205 接入向导：**人人可见**（platform_only=0、auth_code 为 NULL —— 方案 §6.1「无需权限码，所有角色可见」，
--   先让用户知道下一步做什么）；sort=5 让它排在设备/产品/分组/维护窗口（6~9）**之前**，与方案的
--   「起步 → 接入向导放最前」一致。页面内的写动作仍各自受 iot:product:create/publish、iot:device:create 管控。
-- 3206 租户接入台账：**平台级**（platform_only=1；auth_code 沿用既有 iot:ledger:list，不新造权限码）。
--   ⇒ 只授平台管理员角色 1，**绝不进 sys_template_menu**（否则任一租户管理员能改别人的租户是否被采集）。
--
-- id 占用核对（2026-09-25 实测，两处都查）：
--   活库 sys_menu：3205/3206 **0 行**（含已删除）；仓内 SQL：3205/3206 在菜单命名空间内**无占用**。
-- name 唯一性：IotOnboarding / IotTenantLedger 活库与仓内均未占用。
-- 门禁：IotMaintenanceAdminGateTest 要求「32xx/33xx 菜单一律进 sys_role_menu，platform_only=0 才进
--   sys_template_menu」——下面两条授权语句按该口径写；IotPermissionCodeGateTest 不受影响（未新增权限码）。
-- 等价性：本文件追加部分与 migration/2026-09-29-iot-menu-onboarding-ledger.sql 语句等价。
-- ⚠️ 排序约束：本段在 007 的**最末尾**，因此后续任何 IoT 追加段的迁移文件名必须排在
--   `2026-09-29-iot-menu-onboarding-ledger.sql` **之后**（等价性脚本按文件名排序拼接）。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3205, 3204, 'IotOnboarding', 'menu', 0, '/iot/onboarding', '/iot/onboarding/index', NULL, 'page.iot.onboarding.title', 'carbon:idea', 5, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3206, 3204, 'IotTenantLedger', 'menu', 1, '/iot/tenant-ledger', '/iot/tenant-ledger/index', 'iot:ledger:list', 'page.iot.ledger.pageTitle', 'carbon:data-table', 10, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）：002-data.sql 的批量授权只覆盖 platform_only=1 的**存量**菜单，
-- 且在本文件之前执行 ⇒ 新增菜单必须自己再授一次
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3205, 3206);

-- 租户可授菜单补授：**只含 platform_only=0 的 3205**（3206 是平台级，绝不进租户模板）
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3205);

-- =============================================================
-- 设备凭据生命周期（G10 / 设计 P0-A-3，2026-09-27 追加）
-- 覆盖：iot_device 三列凭据元信息 + iot_device_credential（秘密侧）+ 三个权限码与授权。
--
-- 为什么秘密单独一张表（**与设计 §5.3 的一处有意差异**，见 docs/DEVICE-CREDENTIAL.md）：
--   设计假设「哈希只存在 EMQX 内置库」⇒ 平台侧不存任何秘密。但本环境**未部署 EMQX**
--   （资源决策见 EMQX-INGRESS-DESIGN.md 末节），照设计实现会得到「库里只有引用、没有任何可校验的东西」
--   ⇒ 「吊销后设备不得再认证成功」**没有任何可执行的判据**。因此平台侧自持哈希
--   （sha256 + salt 后缀，与 EMQX 内置库同口径，将来可直接 import 进 EMQX），
--   而把秘密放进**从不参与设备查询**的独立表：iot_device 的读路径（分页/详情/接入规格下发）
--   结构上拿不到秘密列。
--
-- iot_device_credential 是**租户表**：不要加进 ypbin.tenant.ignore-tables（fail-on-missing-tenant: true）。
-- 每设备一行（唯一键 tenant_id+device_id）：轮换=原地更新版本与秘密；吊销=清空秘密列
--   （空哈希不可能匹配任何口令）⇒ 本表不需要删除语义，也不依赖逻辑删除。
--
-- 等价性：本段（菜单 + DDL 两截）与 migration/2026-09-30-iot-credential-{menu,schema}.sql 按文件名排序拼接后语句等价
--   （跑 tools/check-iot-sql-equivalence.sh）。⚠️ **顺序不能改**：等价性脚本按文件名排序拼接迁移，
--   `-menu` 排在 `-schema` 之前 ⇒ 007 的追加顺序也必须是「先菜单、后 DDL」。
--   另一条约束：真库 IT 的建表助手 ItSchema 只加载 006 + 本功能的 `-schema` 迁移（006 不含本功能的结构），
--   因此 **DDL 必须独立成一个 `-schema.sql` 文件**，不能与菜单混在同一个迁移里。
-- 回滚：deploy/sql/rollback/2026-09-30-iot-device-credential-rollback.sql。
-- ⚠️ 排序约束：本段在 007 末尾，迁移文件名必须排在 `2026-09-29-iot-menu-onboarding-ledger.sql` 之后。
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

ALTER TABLE iot_device
    ADD COLUMN credential_version    INT      NULL COMMENT '凭据版本号（每次签发/轮换 +1；null=从未签发）',
    ADD COLUMN credential_issued_at  DATETIME NULL COMMENT '当前凭据签发时刻（null=从未签发）',
    ADD COLUMN credential_revoked_at DATETIME NULL COMMENT '凭据吊销时刻（null=未吊销）';

CREATE TABLE iot_device_credential
(
    id                 BIGINT       NOT NULL COMMENT '主键',
    tenant_id          BIGINT       NOT NULL COMMENT '租户 ID',
    device_id          BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    credential_version INT          NOT NULL COMMENT '本行凭据对应的版本号（与 iot_device.credential_version 一致）',
    username           VARCHAR(64)  NOT NULL COMMENT 'MQTT 用户名（{tenantId}.{deviceId}）',
    password_algo      VARCHAR(32)  NOT NULL COMMENT '口令哈希算法（含盐位置）：sha256-suffix',
    password_salt      VARCHAR(64)  NOT NULL COMMENT '盐（hex）；吊销后为空串',
    password_hash      VARCHAR(128) NOT NULL COMMENT '口令哈希（hex）= sha256(password + salt)；吊销后为空串',
    create_user        BIGINT       NULL COMMENT '创建人',
    create_time        DATETIME     NULL COMMENT '创建时间',
    update_user        BIGINT       NULL COMMENT '更新人',
    update_time        DATETIME     NULL COMMENT '更新时间',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_device_credential_device (tenant_id, device_id)
) COMMENT 'IoT 设备凭据秘密侧（只存哈希；明文只在签发响应出现一次）';

-- =============================================================
-- MQTT 入站幂等回执（EMQX 入站链路，设计 §6.5 方案 A / P0-B-2）
-- 一条 MQTT 上行消息 = 一台设备的一小批读数 = 一个 requestId；
-- 幂等键 (tenant_id, device_id, request_id)：QoS1 重发/桥接 max_retries 重投时，同一行只落一次，
-- 第二次起不再调用落库链路（Redis 最新值 / IoTDB 时序 / 活性 / 影子都不会被重复写）。
-- ⚠️ 唯一键含 device_id：requestId 由**设备**生成，跨设备不保证唯一（平台下发的 requestId 才是
--    平台生成的全局唯一值，见 iot_command_instance 的 (tenant_id, request_id)）。
-- =============================================================
-- 设备批量导入（CSV）+ 批次管理（2026-09-30 追加，任务看板 #7）
-- 覆盖：iot_device_import_batch（一次上传 = 一个批次）
--       iot_device_import_row  （批次下逐行的结果与错误，失败行可下载后改完重传）
-- 租户表：含 tenant_id 且**不**进 deploy/nacos/ypbin-iot.yaml 的 ignore-tables
--        （与 iot_device 一致，由租户插件统一追加 tenant_id 条件）。
-- 门禁口径（如实）：兜住「漏登记」的是 NacosTenantIgnoreConfigTest 的泛化反向门禁
--        （继承 TenantBaseEntity 的表一律不得进 ignore-tables）与「新增租户表须补进
--        IotTenantIsolationGateTest 的表清单」这条人工约定；后者是清单式门禁，不补就不覆盖。
-- 计数自洽：total_rows = success_rows + failed_rows 由应用层在同一事务内落库并断言
--        （DeviceImportServiceImpl#assertCountsConsistent），不做库级 CHECK——
--        MySQL 8.0.16 之前忽略 CHECK，而本仓要兼容既有 8.0.x 安装。
-- 等价性：本文件追加部分与 migration/2026-09-30-iot-device-import-schema.sql 语句等价。
-- ⚠️ 迁移文件名日期用 09-30（schema 批次）：等价性脚本按文件名排序拼接，
--    本段 DDL 必须排在 2026-09-30-iot-credential-schema.sql 之后
--    （凭据段在前，且同为 09-30 ⇒ 取 `device-import-schema` 使其排在 `credential-*` 之后）。
-- =============================================================

CREATE TABLE iot_device_import_batch
(
    id               BIGINT       NOT NULL COMMENT '主键',
    tenant_id        BIGINT       NOT NULL COMMENT '租户 ID',
    file_name        VARCHAR(200) NULL COMMENT '上传的原始文件名（原样留痕，便于用户认出是哪一批）',
    total_rows       INT          NOT NULL DEFAULT 0 COMMENT 'CSV 数据行总数（不含表头与说明行）',
    success_rows     INT          NOT NULL DEFAULT 0 COMMENT '成功创建的行数',
    failed_rows      INT          NOT NULL DEFAULT 0 COMMENT '失败的行数',
    batch_status     VARCHAR(16)  NOT NULL COMMENT '状态码（枚举 code，非 ordinal）：running/success/partial-failed/failed（列名避让基类 status）',
    error_summary    VARCHAR(1024) NULL COMMENT '错误摘要（面向用户的一句话；全部成功时为 NULL）',
    start_time       DATETIME     NULL COMMENT '导入开始时刻',
    end_time         DATETIME     NULL COMMENT '导入结束时刻（NULL=进行中）',
    operator_user_id BIGINT       NULL COMMENT '操作人用户 ID（审计：谁传的这批）',
    create_user      BIGINT       NULL COMMENT '创建人',
    create_time      DATETIME     NULL COMMENT '创建时间',
    update_user      BIGINT       NULL COMMENT '更新人',
    update_time      DATETIME     NULL COMMENT '更新时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_import_batch_tenant_time (tenant_id, create_time)
) COMMENT 'IoT 设备批量导入批次（一次 CSV 上传对应一行；批次管理页签的数据源）';

CREATE TABLE iot_device_import_row
(
    id            BIGINT       NOT NULL COMMENT '主键',
    tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
    batch_id      BIGINT       NOT NULL COMMENT '批次 ID（iot_device_import_batch.id）',
    row_no        INT          NOT NULL COMMENT '行号（数据行从 1 开始，不含表头与说明行）',
    raw_line      TEXT         NULL COMMENT '原始行内容（原样留痕；用 TEXT 而非 VARCHAR：单行是「列数×列宽」之和，用 VARCHAR(500) 会把「某一列太长」变成整批导入失败）',
    row_result    VARCHAR(16)  NOT NULL COMMENT '结果码（枚举 code）：success/failed',
    error_code    VARCHAR(48)  NULL COMMENT '错误码（DeviceImportErrorCode 的 code；成功行为 NULL）',
    error_message VARCHAR(512) NULL COMMENT '面向用户的错误信息（含具体是哪一列、哪个取值）',
    device_id     BIGINT       NULL COMMENT '成功创建出的设备 ID（不加外键：明细是审计留痕，设备后续被删仍要能解释当时发生了什么）',
    device_code   VARCHAR(64)  NULL COMMENT '该行的设备编码（成功/失败都存：失败行要靠它让用户认出是哪台设备）',
    create_user   BIGINT       NULL COMMENT '创建人',
    create_time   DATETIME     NULL COMMENT '创建时间',
    update_user   BIGINT       NULL COMMENT '更新人',
    update_time   DATETIME     NULL COMMENT '更新时间',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_import_row_batch (tenant_id, batch_id, row_no),
    KEY idx_iot_import_row_result (tenant_id, batch_id, row_result)
) COMMENT 'IoT 设备批量导入逐行明细（失败行可导出为 CSV 改后原样重传）';

-- =============================================================

CREATE TABLE iot_mqtt_ingest_receipt
(
    id          BIGINT      NOT NULL COMMENT '主键',
    tenant_id   BIGINT      NOT NULL COMMENT '租户 ID（由设备行解析，不信任报文声明）',
    device_id   BIGINT      NOT NULL COMMENT '设备 ID（iot_device.id；来自主题段，非报文声明）',
    request_id  VARCHAR(64) NOT NULL COMMENT '设备侧请求 ID（幂等键；MQTT 上行报文体携带）',
    item_count  INT         NOT NULL COMMENT '首次受理时通过校验的读数条数（重投时原样读回，不重算）',
    create_user BIGINT      NULL COMMENT '创建人',
    create_time DATETIME    NULL COMMENT '创建时间',
    update_user BIGINT      NULL COMMENT '更新人',
    update_time DATETIME    NULL COMMENT '更新时间',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_mqtt_ingest_receipt_request (tenant_id, device_id, request_id)
) COMMENT 'IoT MQTT 入站幂等回执（同一设备同一 requestId 只落一行）';

-- =============================================================
-- 运行期命令实例（段 B 下行/在线调试；设计 §7.1）
-- 与 iot_command（**物模型定义**，挂 service_id）是两回事：这里记「这一次下发的请求与回执」。
-- 幂等键 (tenant_id, request_id)：requestId 由**平台**生成（与设备侧上报的 requestId 不同——
-- 那张表 iot_mqtt_ingest_receipt 的键含 device_id，因为那是设备生成的）。
-- ⚠️ 本表是**租户表**：回执端点（只有 X-Internal-Token、无租户身份）必须先按 device_id 反查租户再进租户上下文；
--    **不要**把它加进 ypbin.tenant.ignore-tables（那会让唯一键的租户维度失去意义并绕过隔离）。
-- 终态（succeeded/failed/timeout/cancelled）**可被人工重发重新打开**（同 requestId、retry_count+1），
-- 这是设计 §7.2 的显式语义：不自动重试，只有人能在页面上重发。
-- =============================================================

CREATE TABLE iot_command_instance
(
    id               BIGINT       NOT NULL COMMENT '主键',
    tenant_id        BIGINT       NOT NULL COMMENT '租户 ID',
    device_id        BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    command_id       BIGINT       NULL     COMMENT '物模型命令定义 ID（iot_command.id；属性类为空）',
    identifier       VARCHAR(64)  NOT NULL COMMENT '命令/属性标识（冗余自物模型，便于无关联查询与审计）',
    kind             VARCHAR(16)  NOT NULL COMMENT '类型码（枚举 code）：property_set|property_get|service_call',
    request_id       VARCHAR(64)  NOT NULL COMMENT '请求 ID（幂等键；平台生成，随 payload 下发）',
    topic            VARCHAR(255) NOT NULL COMMENT '下行主题（定向靠主题；官方无按 clientid 定向的端点）',
    payload          TEXT         NULL     COMMENT '下行报文体（JSON，含 requestId）',
    reply_payload    TEXT         NULL     COMMENT '上行回执体（JSON；不含凭据）',
    status_code      VARCHAR(16)  NOT NULL COMMENT '状态码（枚举 code）：pending|sent|succeeded|failed|timeout|cancelled',
    error_code       VARCHAR(32)  NULL     COMMENT '可区分失败原因码：DEVICE_OFFLINE|NO_SUBSCRIBER|EMQX_ERROR|DEVICE_REJECTED|TIMEOUT',
    error_msg        VARCHAR(500) NULL COMMENT '失败说明（面向人的文案，不含凭据）',
    timeout_ms       INT          NOT NULL COMMENT '超时（毫秒；取下发请求的 timeoutMs，缺省用全局默认）',
    retry_count      INT          NOT NULL DEFAULT 0 COMMENT '已重发次数（仅手动重发计数，不做自动重试）',
    emqx_message_id  VARCHAR(64)  NULL     COMMENT 'EMQX publish 返回的消息 ID（溯源用）',
    source           VARCHAR(16)  NOT NULL COMMENT '来源码：console|rule|api',
    operator_user_id BIGINT       NULL     COMMENT '下发人（来源为 console 时）',
    sent_at          DATETIME     NULL     COMMENT '实际投递到 EMQX 的时刻',
    finished_at      DATETIME     NULL     COMMENT '终态时刻',
    create_user      BIGINT       NULL     COMMENT '创建人',
    create_time      DATETIME     NULL     COMMENT '创建时间',
    update_user      BIGINT       NULL     COMMENT '更新人',
    update_time      DATETIME     NULL     COMMENT '更新时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_command_instance_request (tenant_id, request_id),
    KEY idx_iot_command_instance_device (tenant_id, device_id, create_time),
    KEY idx_iot_command_instance_status (status_code, sent_at)
) COMMENT 'IoT 运行期命令实例（下行请求与回执）';

-- 权限码与菜单（设计 P0-8；沿用 3200 段：320022/320023 为 iot:debug 的两个按钮）
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320022, 3200, 'IotDebugSend', 'button', 0, 'iot:debug:send', 'page.iot.debug.send', 22, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320023, 3200, 'IotDebugGet', 'button', 0, 'iot:debug:get', 'page.iot.debug.get', 23, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320022, 320023);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320022, 320023);

-- =============================================================
-- 告警与阈值能力（设计 docs/ALERTING-DESIGN.md §2.1；用户已批准口径见任务回执）
-- 内容：告警页菜单与四个权限码 + 四张表（规则主表/点位条件行/告警实例/通知投递记录）。
-- ⚠️ 顺序约束：迁移文件名 `2026-10-03-iot-alert-menu.sql` < `2026-10-03-iot-alert-schema.sql`
--    ⇒ 本段的**菜单段必须在 DDL 段之前**（等价性校验按文件名排序拼接，与先例 2026-09-30 凭据段同款）。
-- 回滚：deploy/sql/rollback/2026-10-03-iot-alert-rollback.sql（DROP 四张表 + 删菜单；
--   告警数据会**永久丢失**，执行前先导出——脚本头注释给了导出语句）。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3207, 3204, 'IotAlert', 'menu', 0, '/iot/alerts', '/iot/alerts/index', 'iot:alert:list', 'page.iot.alert.title', 'carbon:warning-alt', 7, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320701, 3207, 'IotAlertAck', 'button', 0, 'iot:alert:ack', 'page.iot.alert.ack', 1, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320702, 3207, 'IotAlertRuleList', 'button', 0, 'iot:alert:rule-list', 'page.iot.alert.rule.list', 2, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320703, 3207, 'IotAlertRuleSave', 'button', 0, 'iot:alert:rule-save', 'page.iot.alert.rule.save', 3, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3207, 320701, 320702, 320703);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3207, 320701, 320702, 320703);

CREATE TABLE iot_alert_rule
(
    id                  BIGINT       NOT NULL COMMENT '主键',
    tenant_id           BIGINT       NOT NULL COMMENT '租户 ID',
    rule_name           VARCHAR(128) NOT NULL COMMENT '规则名（展示用；同名不禁止，靠 id 区分）',
    scope_type          VARCHAR(16)  NOT NULL COMMENT '作用域码（枚举 code）：TENANT/PRODUCT/DEVICE/POINT',
    scope_product_id    BIGINT       NULL COMMENT '作用域产品 ID（PRODUCT/POINT 时必填；其余必须为空）',
    scope_device_id     BIGINT       NULL COMMENT '作用域设备 ID（DEVICE/POINT 时必填；其余必须为空）',
    severity            VARCHAR(16)  NOT NULL COMMENT '级别码（枚举 code）：INFO/WARNING/CRITICAL',
    enabled             TINYINT      NOT NULL DEFAULT 1 COMMENT '启用开关（停用不删除，保留历史与解释能力）',
    trigger_mode        VARCHAR(24)  NOT NULL COMMENT '抖动抑制模式码：IMMEDIATE/CONSECUTIVE_COUNT/DURATION',
    trigger_threshold   INT          NOT NULL DEFAULT 0 COMMENT '连续次数 N 或持续秒数 T；IMMEDIATE 存 0',
    pending_ttl_sec     INT          NOT NULL DEFAULT 300 COMMENT 'pending 态最大挂起秒数（超时放弃候选）',
    repeat_interval_sec INT          NOT NULL DEFAULT 1800 COMMENT '重复通知抑制：活动期重发间隔（秒）',
    silence_start       DATETIME     NULL COMMENT '规则静默窗口起（与维护窗口取并集）',
    silence_end         DATETIME     NULL COMMENT '规则静默窗口止',
    notify_channels     VARCHAR(64)  NOT NULL DEFAULT 'INBOX,EMAIL' COMMENT '渠道码集合（逗号分隔）：INBOX/EMAIL',
    notify_targets      VARCHAR(512) NULL COMMENT '收件人（用户 ID/邮箱，逗号分隔）；NULL=规则创建者',
    description         VARCHAR(512) NULL COMMENT '说明（人会读的那一句）',
    create_user         BIGINT       NULL COMMENT '创建人',
    create_time         DATETIME     NULL COMMENT '创建时间',
    update_user         BIGINT       NULL COMMENT '更新人',
    update_time         DATETIME     NULL COMMENT '更新时间',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_alert_rule_scope (tenant_id, enabled, scope_type),
    KEY idx_iot_alert_rule_device (tenant_id, scope_device_id),
    KEY idx_iot_alert_rule_product (tenant_id, scope_product_id)
) COMMENT 'IoT 告警规则主表（设计 §2.1 表 A）';

CREATE TABLE iot_alert_rule_point
(
    id          BIGINT         NOT NULL COMMENT '主键',
    tenant_id   BIGINT         NOT NULL COMMENT '租户 ID',
    rule_id     BIGINT         NOT NULL COMMENT '规则 ID（iot_alert_rule.id）',
    property_id VARCHAR(64)    NOT NULL COMMENT '点位标识（与 PointMappingIndex 解析口径一致）',
    operator    VARCHAR(8)     NOT NULL COMMENT '比较符码（枚举 code）：GT/GTE/LT/LTE/EQ/NE',
    threshold   DECIMAL(24, 6) NOT NULL COMMENT '阈值（用 DECIMAL 而非 DOUBLE：阈值是配置，不能有二进制浮点误差）',
    value_type  VARCHAR(16)    NOT NULL DEFAULT 'NUMERIC' COMMENT '比较域码：NUMERIC/BOOLEAN',
    deadband    DECIMAL(24, 6) NULL COMMENT '回差（滞回）：恢复门槛比触发门槛往回退这么多',
    create_user BIGINT         NULL COMMENT '创建人',
    create_time DATETIME       NULL COMMENT '创建时间',
    update_user BIGINT         NULL COMMENT '更新人',
    update_time DATETIME       NULL COMMENT '更新时间',
    status      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_alert_rule_point_rule (tenant_id, rule_id)
) COMMENT 'IoT 告警规则点位条件行（设计 §2.1 表 B；零条件行=设备离线/数据中断类规则）';

CREATE TABLE iot_alert_instance
(
    id                 BIGINT       NOT NULL COMMENT '主键',
    tenant_id          BIGINT       NOT NULL COMMENT '租户 ID',
    rule_id            BIGINT       NOT NULL COMMENT '触发它的规则 ID（断档映射用保留值 0）',
    device_id          BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    property_id        VARCHAR(64)  NULL COMMENT '点位标识（设备级/断档类实例为空）',
    dedup_key          VARCHAR(191) NOT NULL COMMENT '去重键（rule_id:device_id:property_id；历史留痕用，不进唯一索引）',
    active_dedup_key   VARCHAR(191) NULL COMMENT '只在活动期间非 NULL（=dedup_key）；恢复时置 NULL',
    severity           VARCHAR(16)  NOT NULL COMMENT '触发时的级别（冗余存下，规则改级别不改写历史）',
    state              VARCHAR(16)  NOT NULL COMMENT '状态码（枚举 code）：PENDING/FIRING/ACKED/RESOLVED',
    consecutive_count  INT          NOT NULL DEFAULT 0 COMMENT '连续越界计数（抖动抑制；设计表 C 未列，见文档偏差）',
    trigger_value      VARCHAR(64)  NULL COMMENT '触发时读到的原始值（原样存）',
    threshold_snapshot VARCHAR(64)  NULL COMMENT '触发时的阈值快照（规则改阈值后仍能解释当时为何报警）',
    start_ts           DATETIME     NOT NULL COMMENT '首次越界时刻（pending 开始）',
    firing_ts          DATETIME     NULL COMMENT '正式触发时刻',
    resolved_ts        DATETIME     NULL COMMENT '恢复时刻（NULL=仍活动）',
    acked_ts           DATETIME     NULL COMMENT '确认时刻',
    acked_by           BIGINT       NULL COMMENT '确认人',
    last_notified_ts   DATETIME     NULL COMMENT '最近一次通知时刻（重复通知抑制的唯一依据）',
    notify_count       INT          NOT NULL DEFAULT 0 COMMENT '已生成通知次数',
    reason             VARCHAR(64)  NULL COMMENT '结束原因码：RECOVERED/RULE_DISABLED/OUTAGE_RECOVERED',
    silence_until      DATETIME     NULL COMMENT '实例级静默截止时刻（NULL=未静默；静默不是状态）',
    create_user        BIGINT       NULL COMMENT '创建人',
    create_time        DATETIME     NULL COMMENT '创建时间',
    update_user        BIGINT       NULL COMMENT '更新人',
    update_time        DATETIME     NULL COMMENT '更新时间',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_active (tenant_id, active_dedup_key),
    KEY idx_iot_alert_instance_device (tenant_id, device_id, start_ts),
    KEY idx_iot_alert_instance_state (tenant_id, state, start_ts),
    KEY idx_iot_alert_instance_severity (tenant_id, severity, state)
) COMMENT 'IoT 告警实例（设计 §2.1 表 C；uk_alert_active 是「同键至多一条活动告警」的库级保证）';

CREATE TABLE iot_alert_notification
(
    id             BIGINT       NOT NULL COMMENT '主键',
    tenant_id      BIGINT       NOT NULL COMMENT '租户 ID',
    instance_id    BIGINT       NOT NULL COMMENT '告警实例 ID',
    channel        VARCHAR(16)  NOT NULL COMMENT '渠道码（枚举 code）：INBOX/EMAIL',
    target         VARCHAR(191) NOT NULL COMMENT '收件人标识（用户 ID/邮箱；无可解析收件人时为空串并给出原因）',
    event          VARCHAR(16)  NOT NULL COMMENT '通知事件码：FIRING/RESOLVED/REPEAT/ACKED',
    notify_status  VARCHAR(16)  NOT NULL COMMENT '投递状态码：PENDING/SENT/FAILED/GIVEN_UP（列名避让基类 status）',
    attempt        INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    next_retry_ts  DATETIME     NULL COMMENT '下次重试时刻（退避 30s→2min→10min）',
    last_error     VARCHAR(512) NULL COMMENT '最近一次错误（原样记录，不吞）',
    idempotent_key VARCHAR(191) NOT NULL COMMENT '幂等键（instance+event+channel+target+轮次）',
    create_user    BIGINT       NULL COMMENT '创建人',
    create_time    DATETIME     NULL COMMENT '创建时间',
    update_user    BIGINT       NULL COMMENT '更新人',
    update_time    DATETIME     NULL COMMENT '更新时间',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_notification_idem (tenant_id, idempotent_key),
    KEY idx_iot_alert_notification_instance (tenant_id, instance_id),
    KEY idx_iot_alert_notification_due (notify_status, next_retry_ts)
) COMMENT 'IoT 告警通知投递记录（设计 §2.1 表 D；与告警状态分开存，通知全挂时告警仍可见）';

-- =============================================================
-- 设备批量导入（CSV）+ 批次管理：权限码与菜单（2026-09-30 追加，任务看板 #7）
-- 权限码**一个**：iot:device:import（上传）。读路径（模板/批次/明细/失败行）沿用 iot:device:list。
-- id 段：320024 是 id 段内的下一个空位（3200 下已用到 320023，见上一段的 debug 两项）。
--   2026-09-30 查仓内 SQL 与既有菜单段确认 3200~3207 与 320001~320024/320101~/320201~/320301~/
--   320701~ 的占用情况：3200 段最大为 320023 ⇒ 新按钮取 320024。
-- platform_only=0 ⇒ 按 IotMaintenanceAdminGateTest 口径必须**同时**进 sys_role_menu 与
--   sys_template_menu（后者是租户可授菜单与「防孤儿」父目录补授的依据）。
-- 挂在 3200「设备台账」下作为按钮码（与影子/标签/可用率/凭据/调试同一做法：
--   批量导入是设备页内的能力，不另立页面菜单——用户是在设备台账上点「批量导入」）。
-- ⚠️ 排序约束：本文件名必须排在 2026-10-03-iot-alert-menu.sql 之后（等价性脚本按文件名排序拼接）。
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320024, 3200, 'IotDeviceImport', 'button', 0, 'iot:device:import', 'page.iot.device.import.title', 24, NOW(), 1, 0);

-- 显式授权给平台管理员角色（role 1）与租户可授菜单（sys_template_menu）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320024);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320024);
-- =============================================================
-- 平台自告警实例表（看板 #10，设计 docs/PLATFORM-ALERTING-DESIGN.md §3.1）
-- 与设备告警 iot_alert_instance **物理分离**：平台健康规则没有设备也没有点位。
-- 去重形态照 iot_alert_instance：active_dedup_key 活动期间=dedup_key、恢复置 NULL。
-- =============================================================

CREATE TABLE iot_platform_alert
(
    id                BIGINT       NOT NULL COMMENT '主键',
    tenant_id         BIGINT       NOT NULL COMMENT '租户 ID',
    rule_code         VARCHAR(48)  NOT NULL COMMENT '规则码（PlatformHealthRule.code）',
    dedup_key         VARCHAR(191) NOT NULL COMMENT '去重键（规则码+维度），不随状态变化',
    active_dedup_key  VARCHAR(191) NULL COMMENT '只在活动期间非 NULL（=dedup_key）；恢复时置 NULL',
    severity          VARCHAR(16)  NOT NULL COMMENT '严重度码（PlatformSeverity.code）：WARNING/CRITICAL',
    state             VARCHAR(16)  NOT NULL COMMENT '状态码（PlatformAlertState.code）：PENDING/FIRING/RESOLVED',
    summary           VARCHAR(512) NOT NULL COMMENT '面向运维的一句话（含实际观测值）',
    metric_snapshot   TEXT         NULL COMMENT '判定时的指标快照（JSON）；事后排障的唯一依据',
    start_ts          DATETIME     NULL COMMENT '首次观测到异常的时刻',
    firing_ts         DATETIME     NULL COMMENT '确认触发的时刻',
    resolved_ts       DATETIME     NULL COMMENT '恢复时刻（NULL=仍活动）',
    last_verdict      VARCHAR(32)  NULL COMMENT '最近一轮判定结果（便于看恶化还是好转）',
    observed_rounds   INT          NOT NULL DEFAULT 0 COMMENT '活动期间累计判定轮次',
    create_user       BIGINT       NULL COMMENT '创建人',
    create_time       DATETIME     NULL COMMENT '创建时间',
    update_user       BIGINT       NULL COMMENT '更新人',
    update_time       DATETIME     NULL COMMENT '更新时间',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_alert_active (tenant_id, active_dedup_key),
    KEY idx_platform_alert_state (tenant_id, state, start_ts),
    KEY idx_platform_alert_rule (tenant_id, rule_code, start_ts)
) COMMENT 'IoT 平台自告警实例（看板 #10；与设备告警物理分离，uk_platform_alert_active 是去重的库级保证）';

-- 平台告警菜单（看板 #10 二批；与 migration/2026-10-05-iot-platform-alert-menu.sql 等价）
INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3208, 3204, 'IotPlatformAlert', 'menu', 0, '/iot/platform-alerts', '/iot/platformAlert/index', 'iot:alert:list', 'page.iot.platformAlert.title', 'carbon:alarm', 15, NOW(), 1, 0);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3208);
INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (3208);
