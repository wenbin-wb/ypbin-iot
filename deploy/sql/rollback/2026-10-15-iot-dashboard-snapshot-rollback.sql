-- 回滚：2026-10-15-iot-dashboard-snapshot.sql（仪表盘在线趋势每日快照）。
-- 影响：回滚后在线趋势没有快照来源（前端退化为空态/演示兜底）；已积累的每日快照历史随表删除而丢失，
--       不可恢复 —— 若要保留历史，请先 mysqldump iot_dashboard_daily_snapshot。
-- 顺序：先回滚 jar（新代码不再写快照）再执行本脚本；反过来会让新 jar 因表不存在持续报错。
DROP TABLE IF EXISTS iot_dashboard_daily_snapshot;
