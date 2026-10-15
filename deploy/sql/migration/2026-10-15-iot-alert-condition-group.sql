-- =============================================================
-- 告警真 AND 语义改造：去重键改为「规则 × 设备」+ 条件组快照列（2026-10-15）
--
-- 背景：改造前「一条规则 + 一条点位条件」各自独立判定、各产生一个告警实例（近似 OR），
--   且同一规则同一设备的多条同时越界条件会被 uk_alert_active 静默剔除掉多余的实例
--   （AlertTenantEvaluator 的 insertWithRetry 只记一个 dedup_conflict 指标，用户完全无感）。
--   改造后组内全部条件都越界才触发，去重键由 (rule, device, property) 收紧为 (rule, device)，
--   一条规则对一台设备至多一条活动实例；组内逐条件的取值落在新增列 condition_snapshot 里。
--   语义与理由见 docs/ALERTING-DESIGN.md 的 §2.6、§7.9。
--
-- 与 deploy/sql/007-iot-data.sql 的**同名追加段**语句等价（tools/check-iot-sql-equivalence.sh 校验）。
-- ⚠️ 排序约束：本文件名（2026-10-15-iot-alert-condition-group）必须排在
--   2026-10-13-iot-mqtt-ingest-receipt-trace 之后、2026-10-15-iot-board-config 之前，
--   故 007 里的追加段也必须夹在这两段之间，否则等价性校验转红。
-- 回滚见 deploy/sql/rollback/2026-10-15-iot-alert-condition-group-rollback.sql。
--
-- ⚠️ 本迁移含**数据改写**（不只是 DDL），执行前务必先跑下面的自查 SQL 并留存结果：
--
-- 【迁移前自查 A：行为会变化的规则（条件行数 > 1 的启用规则）】
--   这些规则的行为从「任一条越界即触发（近似 OR，且同点位会被静默合并）」变为「全部条件都越界才触发」。
--   SELECT p.tenant_id, p.rule_id, r.rule_name, COUNT(*) AS point_count
--   FROM iot_alert_rule_point p
--   JOIN iot_alert_rule r ON r.id = p.rule_id AND r.is_deleted = 0
--   WHERE p.is_deleted = 0 AND p.status = 1 AND r.enabled = 1
--   GROUP BY p.tenant_id, p.rule_id, r.rule_name
--   HAVING COUNT(*) > 1
--   ORDER BY point_count DESC, p.rule_id ASC;
--
-- 【迁移前自查 B：同规则同点位的重复条件行（改造后是「同一个条件写了两遍」，不再被静默合并）】
--   SELECT tenant_id, rule_id, property_id, COUNT(*) AS c
--   FROM iot_alert_rule_point WHERE is_deleted = 0
--   GROUP BY tenant_id, rule_id, property_id HAVING c > 1;
--
-- 【迁移前自查 C：同 (规则, 设备) 存在多条活动实例（本迁移会把多余的收口为 RESOLVED）】
--   SELECT tenant_id, rule_id, device_id, COUNT(*) AS c, GROUP_CONCAT(id ORDER BY id) AS instance_ids
--   FROM iot_alert_instance
--   WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND rule_id <> 0
--   GROUP BY tenant_id, rule_id, device_id HAVING c > 1;
--
-- 【迁移前自查 D：将被改写活动去重键的行数（预期等于规则类活动实例总数）】
--   SELECT COUNT(*) AS to_rewrite FROM iot_alert_instance
--   WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND rule_id <> 0
--     AND active_dedup_key NOT LIKE 'R:%';
--
-- 断档类实例（rule_id = 0，去重键 OUTAGE:deviceId:eventId）**不在本迁移的射程内**，一行都不改。
-- =============================================================

ALTER TABLE iot_alert_instance
    ADD COLUMN condition_snapshot VARCHAR(4000) NULL COMMENT '条件组逐条件快照（真 AND 语义）：prop|op|threshold|verdict|value 多行以分号连接；单条件规则同样写入';

-- ① 同一 (规则, 设备) 的多条旧活动实例收口为**至多一条**（否则新键下会撞 uk_alert_active）。
--    保留优先级：FIRING > ACKED > PENDING（用户已看到的告警优先），同级取 id 最小（最早产生，含最早 start_ts）。
--    被收口的行写成 RESOLVED + 明确原因码 CONDITION_GROUP_MERGED（不是「已恢复」——它没恢复，
--    是规则语义变了导致同一组只允许留一条；留下可区分的原因码，值班不需要猜）。
UPDATE iot_alert_instance i
JOIN (SELECT id,
             ROW_NUMBER() OVER (PARTITION BY tenant_id, rule_id, device_id
                                ORDER BY (state = 'FIRING') DESC, (state = 'ACKED') DESC, id ASC) AS rn
      FROM iot_alert_instance
      WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND rule_id <> 0) AS ranked
  ON ranked.id = i.id
SET i.state = 'RESOLVED',
    i.resolved_ts = NOW(),
    i.reason = 'CONDITION_GROUP_MERGED',
    i.active_dedup_key = NULL,
    i.update_time = NOW()
WHERE ranked.rn > 1;

-- ② 剩余活动实例的活动去重键规范化为 R:ruleId:deviceId（dedup_key 是**历史留痕**，保持原值不改写：
--    它记录了旧语义下「当时的触发点位」，是审计证据）。
UPDATE iot_alert_instance
SET active_dedup_key = CONCAT('R:', rule_id, ':', device_id),
    update_time = NOW()
WHERE is_deleted = 0 AND active_dedup_key IS NOT NULL AND rule_id <> 0;
