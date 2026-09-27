-- =============================================================
-- 回滚：iot_command_instance（运行期命令实例）+ iot:debug 权限码/菜单
-- 影响：在线调试的"下发/查询/重发/回执"全部不可用；**正在等待回执的命令实例会丢失**（不可逆，
--       需要保留历史请先 `SELECT * INTO OUTFILE`/mysqldump 导出）。
-- 顺序：先停前端"在线调试"入口（后端已不可用），再执行本脚本。
-- =============================================================

DELETE FROM sys_role_menu WHERE menu_id IN (320022, 320023);
DELETE FROM sys_template_menu WHERE menu_id IN (320022, 320023);
DELETE FROM sys_menu WHERE id IN (320022, 320023);
DROP TABLE IF EXISTS iot_command_instance;
