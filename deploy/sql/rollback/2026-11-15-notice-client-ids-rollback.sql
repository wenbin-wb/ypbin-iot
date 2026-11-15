-- 回滚：移除 sys_notice.client_ids
--
-- 仅回滚列本身；已配置的投放客户端信息不保留。

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_notice'
      AND column_name = 'client_ids'
);
SET @ddl := IF(@col_exists = 1,
    'ALTER TABLE sys_notice DROP COLUMN client_ids',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
