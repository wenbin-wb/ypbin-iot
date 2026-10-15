-- =============================================================
-- 回滚：告警真 AND 语义改造（对应 migration/2026-10-15-iot-alert-condition-group.sql）
--
-- 顺序（**必须按此顺序**，否则会留下无法解释的实例或撞唯一键）：
--   ① 先把总开关关掉并重启服务：ypbin.alert.enabled=false（评估器/投递器停跑，避免回滚中途继续按新键写入）；
--   ② 再执行本脚本；
--   ③ 最后部署**旧版本** jar（旧代码用 rule:device:property 键），确认评估器恢复正常后按需再开总开关。
--
-- 回滚面（两步，均**不删任何行**）：
--   · 活动实例的 active_dedup_key 从 R:rule:device 还原为 rule:device:property（旧形态）；
--   · 删除 condition_snapshot 列。
--
-- 🔁 **本脚本幂等（可重复执行）**：两条语句都带条件——
--   · 第一条 UPDATE 用 active_dedup_key LIKE 'R:%' 过滤，重跑时没有可改的行（0 rows affected）；
--   · 第二条删列先查 information_schema.columns 再用 PREPARE 条件执行：列不存在时跳过。
--     ⚠️ MySQL 8.4 **不支持** ALTER TABLE ... DROP COLUMN IF EXISTS（实测 8.4.11 报
--     ERROR 1064 语法错误），故只能用「查 information_schema + 动态 SQL」这一形态；
--     这与 DROP TABLE IF EXISTS（DDL 层面原生支持 IF EXISTS）不同，后者的先例不能照抄。
--
-- ⚠️ 不可逆部分（如实登记）：迁移中因「同一 (规则, 设备) 多条活动实例」而被收口的那些行
--   （reason = CONDITION_GROUP_MERGED）**不会**被本脚本恢复为活动——旧语义下它们本就是同键重复实例，
--   恢复后反而会撞 uk_alert_active。回滚后评估器会按旧语义在需要时重新建实例（所以不会漏报，
--   但会多出一条新的历史行）。如需完整还原这些行，请在回滚前先导出：
--     SELECT * FROM iot_alert_instance WHERE reason = 'CONDITION_GROUP_MERGED';
-- =============================================================

-- ① 活动去重键还原为旧形态 rule:device:property（property_id 为空时用 '-'，与 AlertRules.NO_POINT_TOKEN 一致）。
--    还原前已保证每个 (规则, 设备) 至多一条活动实例（迁移已收口）⇒ 新键在 (tenant_id, active_dedup_key)
--    下不会与其它活动行冲突。重跑安全：过滤条件为 LIKE 'R:%'，已还原的行不再命中。
UPDATE iot_alert_instance
SET active_dedup_key = CONCAT(rule_id, ':', device_id, ':',
        COALESCE(NULLIF(TRIM(property_id), ''), '-')),
    update_time = NOW()
WHERE is_deleted = 0 AND active_dedup_key LIKE 'R:%';

-- ② 删除条件组快照列（列本身就是本次新增的）。条件执行 ⇒ 重跑时列已不存在也不会报错。
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'iot_alert_instance'
      AND column_name = 'condition_snapshot');
SET @stmt = IF(@col_exists > 0,
    'ALTER TABLE iot_alert_instance DROP COLUMN condition_snapshot',
    'DO 0');
PREPARE drop_condition_snapshot FROM @stmt;
EXECUTE drop_condition_snapshot;
DEALLOCATE PREPARE drop_condition_snapshot;
