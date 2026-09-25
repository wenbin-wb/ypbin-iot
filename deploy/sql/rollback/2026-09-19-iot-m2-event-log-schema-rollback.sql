-- =============================================================
-- 回滚：G6 运行期事件实例表 iot_event_log
-- 对应正向迁移：deploy/sql/migration/2026-09-19-iot-m2-event-log-schema.sql
--
-- ⚠️ 这是**破坏性回滚**：表里的运行期事件实例会被整表丢弃，且不可从其它表恢复
--    （事件实例没有第二份副本）。执行前必须先备份：
--      CREATE TABLE iot_event_log_bak_<日期> AS SELECT * FROM iot_event_log;
--    确认无误后再执行本文件，并保留备份至观察期结束。
-- =============================================================

DROP TABLE IF EXISTS iot_event_log;
