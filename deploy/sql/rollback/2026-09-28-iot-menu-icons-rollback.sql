-- =============================================================
-- 回滚：菜单图标缺陷修复（2026-09-28）
-- 对应迁移：deploy/sql/migration/2026-09-28-iot-menu-icons.sql
--           （同一批语句已逐字追加到 deploy/sql/007-iot-data.sql 末尾）
-- 作用：把 icon 还原成修复前的值。⚠️ 注意 `carbon:iot` 是**不存在的图标名**（Carbon 集合无 `iot`），
--   回滚等于把「设备台账 / IoT 平台」的图标重新变回空白，仅在需要严格回到旧状态时使用。
-- 用法（本机 → 生产，口令走容器环境变量，不入 argv/日志）：
--   ssh -i ~/.ssh/id_ed25519_iot_test -p 22 root@113.142.217.58 \
--     'docker exec -i ypbin-mysql bash -c '"'"'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 ypbin_admin'"'"' < /dev/stdin' \
--     < deploy/sql/rollback/2026-09-28-iot-menu-icons-rollback.sql
-- 验证（回滚后期望）：SELECT id, icon FROM sys_menu WHERE id IN (3200,3204) ⇒ 两行均为 carbon:iot
-- =============================================================

UPDATE sys_menu SET icon = 'carbon:iot' WHERE id IN (3200, 3204);
