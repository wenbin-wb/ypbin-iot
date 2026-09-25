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
