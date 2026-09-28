-- =============================================================
-- 回滚：告警与阈值能力（对应 migration/2026-10-03-iot-alert-menu.sql
--   + migration/2026-10-03-iot-alert-schema.sql 这两个增量迁移）
--
-- ⚠️ **不可逆部分（必须先读）**：告警实例与投递记录是**审计证据**（「上周那台设备到底报警了没有」只能靠它回答）。
--    本脚本会 DROP 掉四张表 ⇒ 全部规则配置与历史告警**永久丢失**。执行前请先导出：
--      · 规则：SELECT * FROM iot_alert_rule;  SELECT * FROM iot_alert_rule_point;
--      · 实例：SELECT * FROM iot_alert_instance;   （活动告警按平台口径**永久保留**，删掉即不可恢复）
--      · 投递：SELECT * FROM iot_alert_notification;
--    或对四张表整体 mysqldump。
--
-- 顺序：① 先关功能开关（ypbin.alert.enabled=false）并重启服务 —— 否则评估器/投递器会在表被删掉后持续报错；
--       ② 再执行本脚本；③ 最后清理前端菜单（前端页面入口随菜单一起消失）。
--
-- 回滚不改动既有能力：本能力**只读**了 outage_event / device_liveness / maintenance_window，
-- 没有修改它们的任何语义 ⇒ 回滚面是本能力新增物，不影响既有链路。
-- =============================================================

DROP TABLE IF EXISTS iot_alert_notification;
DROP TABLE IF EXISTS iot_alert_instance;
DROP TABLE IF EXISTS iot_alert_rule_point;
DROP TABLE IF EXISTS iot_alert_rule;

DELETE FROM sys_role_menu WHERE menu_id IN (3207, 320701, 320702, 320703);

DELETE FROM sys_template_menu WHERE menu_id IN (3207, 320701, 320702, 320703);

DELETE FROM sys_menu WHERE id IN (3207, 320701, 320702, 320703);
