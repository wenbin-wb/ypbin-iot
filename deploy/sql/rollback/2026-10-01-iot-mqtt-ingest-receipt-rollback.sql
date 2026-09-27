-- =============================================================
-- 回滚：iot_mqtt_ingest_receipt（MQTT 入站幂等回执）
-- 影响：MQTT 入站端点将无法 dedup（重投会重复写最新值/时序）⇒ 必须**同时**把
--       EMQX 侧的入站规则/动作停用（deploy/emqx/emqx-ingress-rollback.sh），再执行本脚本。
-- 数据：纯粹是幂等回执，不含业务数据，可安全丢弃。
-- =============================================================

DROP TABLE IF EXISTS iot_mqtt_ingest_receipt;
