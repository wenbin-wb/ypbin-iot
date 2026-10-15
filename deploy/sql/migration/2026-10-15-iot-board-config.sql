-- =============================================================
-- 迁移：组态看板配置（表）
-- 适用：**已上线的库**（install.sh 只在全新安装时执行 deploy/sql/*.sql，不含 migration/）
-- 等价性：本文件与 deploy/sql/007-iot-data.sql 中「组态看板配置」那段的**语句**等价
--         （注释可不同）。改一边必须同步另一边；CI 有语句级等价校验
--         （tools/check-iot-sql-equivalence.sh）。
--         放置说明：本表属 2026-09-28 之后的增量，按仓内约定追加在 007-iot-data.sql 末尾，
--         使「006+007 按语句顺序」与「migration/*.sql 按文件名排序」严格对齐。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
-- 回滚：deploy/sql/rollback/2026-10-15-iot-board-config-rollback.sql
-- =============================================================

-- =============================================================
-- 组态看板配置（2026-10-15 追加）
-- 覆盖：iot_dashboard_board_config —— 组态看板按租户共享持久化的唯一落点。
-- 背景：看板组件此前只存浏览器 localStorage，换设备/换浏览器即丢；本表把它提升为
--       租户级共享事实（同租户任意用户看到同一份看板），并为后续多份看板打基础。
-- 租户表：含 tenant_id 且不进 deploy/nacos/ypbin-iot.yaml 的 ignore-tables，
--       由租户插件统一追加 tenant_id 条件（与 iot_device 一致）。
-- config_json：widgets 数组的 JSON 文本（空看板存 []），字段语义由前端 board-utils.ts 裁决
--       （id/type/title/deviceId/propertyId 必填同名；unit/decimals/max/min/windowMinutes 等原样透传）。
--       存 JSON 而不是拆列：组件类型随前端演进，拆列会把前端展示配置钉进后端表结构。
-- 唯一键：uk_iot_dashboard_board_config(tenant_id) —— 每租户一份（接口是「整体替换本租户配置」语义）；
--       应用层「先查后写」在并发首写下存在竞态，由该唯一键兜底（冲突时回退为更新）。
-- 等价性：本文件追加部分与 migration/2026-10-15-iot-board-config.sql 语句等价
--       （tools/check-iot-sql-equivalence.sh 校验）。
-- =============================================================

CREATE TABLE iot_dashboard_board_config
(
    id           BIGINT   NOT NULL COMMENT '主键',
    tenant_id    BIGINT   NOT NULL COMMENT '租户 ID',
    config_json  LONGTEXT NOT NULL COMMENT '看板组件配置：widgets 数组的 JSON 文本（空看板存 []）',
    create_user  BIGINT   NULL COMMENT '创建人',
    create_time  DATETIME NULL COMMENT '创建时间',
    update_user  BIGINT   NULL COMMENT '更新人',
    update_time  DATETIME NULL COMMENT '更新时间',
    status       TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_dashboard_board_config (tenant_id)
) COMMENT 'IoT 组态看板配置（每租户一份，config_json 存 widgets 数组）';
