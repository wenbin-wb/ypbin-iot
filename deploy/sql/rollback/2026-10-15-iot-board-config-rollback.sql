-- 回滚：2026-10-15-iot-board-config.sql（组态看板按租户共享持久化）。
-- 影响：回滚后看板配置退回浏览器 localStorage（前端已有本地兜底）；平台侧已保存的**租户共享配置随表删除而丢失**，
--       不可恢复 —— 若要保留，请先 mysqldump iot_dashboard_board_config。
-- 顺序：先回滚 jar（新代码不再读写该表）再执行本脚本；反过来会让新 jar 因表不存在持续报错。
DROP TABLE IF EXISTS iot_dashboard_board_config;
