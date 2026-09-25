-- =============================================================
-- 回滚：平台模块目录（2026-09-26）
-- 对应迁移：deploy/sql/migration/2026-09-26-iot-platform-module-menu.sql
--           （同一批语句已逐字追加到 deploy/sql/007-iot-data.sql 末尾）
-- 作用：撤销 10 个既有顶级菜单的 reparent，删除 3310/3320 两个模块目录
--       及其在 sys_role_menu / sys_template_menu 里的**全部**授权行
--       （含迁移里"防孤儿"补授给其它角色/模板的行，按 menu_id 一并删除）。
-- 用法（本机 → 生产，口令走容器环境变量，不入 argv/日志）：
--   ssh -i ~/.ssh/id_ed25519_iot_test -p 22 root@113.142.217.58 \
--     'docker exec -i ypbin-mysql bash -c '"'"'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 ypbin_admin'"'"' < /dev/stdin' \
--     < deploy/sql/rollback/2026-09-26-iot-platform-module-menu-rollback.sql
-- 顺序：先解 reparent，再删授权行，最后删菜单目录（最安全，任一步失败都可重跑，语句幂等）。
-- 验证（回滚后期望）：sys_menu 里 pid=0 回到 13 行；id IN (3310,3320) 为 0 行；
--   sys_role_menu / sys_template_menu 里 menu_id IN (3310,3320) 均为 0 行。
-- =============================================================

UPDATE sys_menu SET pid = 0 WHERE id IN (3001, 3002, 3003, 3005, 3007, 2600, 3008, 4001, 3004, 3009);

DELETE FROM sys_role_menu WHERE menu_id IN (3310, 3320);

DELETE FROM sys_template_menu WHERE menu_id IN (3310, 3320);

DELETE FROM sys_menu WHERE id IN (3310, 3320);
