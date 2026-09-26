-- =============================================================
-- 菜单图标缺陷修复（2026-09-28）
-- 对应追加段：deploy/sql/007-iot-data.sql 末尾「菜单图标缺陷修复（2026-09-28 追加）」
-- 缺陷：id=3200「设备台账」与 id=3204「IoT 平台」的 icon 是 `carbon:iot`，
--   Carbon 图标集里没有 `iot`（Iconify API carbon.json?icons=iot 返回 not_found:["iot"]），
--   前端渲染为空 ⇒ 菜单图标缺失。
-- 修法：3200 → `carbon:devices`、3204 → `carbon:iot-platform`（均在 carbon 集合中核验存在）。
-- 幂等：UPDATE 按主键定位，可重复执行。
-- 用法（本机 → 生产，口令走容器环境变量，不入 argv/日志）：
--   ssh -i ~/.ssh/id_ed25519_iot_test -p 22 root@113.142.217.58 \
--     'docker exec -i ypbin-mysql bash -c '"'"'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 ypbin_admin'"'"' < /dev/stdin' \
--     < deploy/sql/migration/2026-09-28-iot-menu-icons.sql
-- 验证（执行后期望）：SELECT id, icon FROM sys_menu WHERE id IN (3200,3204) ⇒ carbon:devices / carbon:iot-platform
-- 回滚：deploy/sql/rollback/2026-09-28-iot-menu-icons-rollback.sql
-- =============================================================

UPDATE sys_menu SET icon = 'carbon:devices' WHERE id = 3200;

UPDATE sys_menu SET icon = 'carbon:iot-platform' WHERE id = 3204;
