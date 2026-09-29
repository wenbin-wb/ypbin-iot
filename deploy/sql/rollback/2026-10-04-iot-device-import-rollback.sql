-- =============================================================
-- 回滚：设备批量导入（CSV）+ 批次管理
--   （对应 migration/2026-09-30-iot-device-import-schema.sql
--     + migration/2026-10-04-iot-device-import-menu.sql 这两个增量迁移）
--
-- ⚠️ **不可逆部分（必须先读）**：批次与明细是**审计留痕**——「这批到底进去了哪几台、
--    哪几行为什么没进」只能靠它回答。本脚本会 DROP 掉两张表 ⇒ 全部导入历史**永久丢失**。
--    执行前请先导出：
--      · 批次：SELECT * FROM iot_device_import_batch;
--      · 明细：SELECT * FROM iot_device_import_row;
--    或对两张表整体 mysqldump。
--
-- ⚠️ **回滚不会删除已创建的设备**：设备是**业务数据**，不是本能力的附属物。
--    批量导入创建出来的设备与逐台手工创建出来的设备在库里完全同形，无法（也不应该）
--    靠「导入」这件事把它们区分开 ⇒ 删除它们只能由人来决定。
--    回滚后设备台账里那些设备仍然在，只是「它们的来源批次」这个上下文消失了。
--
-- 顺序：① 先清菜单（前端「批量导入」入口随权限码一起消失）；
--       ② 再 DROP 两张表（先明细后批次：明细逻辑上依赖批次）；
--       ③ 无需关任何功能开关——本能力没有后台任务/定时器，回滚不影响运行中的服务。
--
-- 回滚不改动既有能力：本能力**只读**了 iot_device / iot_product / iot_device_group /
-- iot_device_group_member，并**写入** iot_device 与 iot_device_group_member（导入的设备与
-- 分组归属）。后者是正常业务写入，不是本表结构的依赖 ⇒ 回滚面是本能力新增物。
-- =============================================================

DELETE FROM sys_role_menu WHERE menu_id IN (320024);

DELETE FROM sys_template_menu WHERE menu_id IN (320024);

DELETE FROM sys_menu WHERE id IN (320024);

DROP TABLE IF EXISTS iot_device_import_row;

DROP TABLE IF EXISTS iot_device_import_batch;
