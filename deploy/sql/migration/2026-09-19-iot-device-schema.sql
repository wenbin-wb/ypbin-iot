-- =============================================================
-- 迁移：IoT 设备台账（表 + 菜单 + 权限授予）
-- 适用：**已上线的库**（install.sh 只在全新安装时执行 deploy/sql/*.sql，不含 migration/）
-- 等价性：本文件与 deploy/sql/006-iot-schema.sql + 007-iot-data.sql 的**语句**等价
--         （注释可不同）。改其中一边必须同步另一边；CI 有语句级等价校验
--         （tools/check-iot-sql-equivalence.sh）。
-- 幂等性：本文件是**一次性**迁移脚本，不保证可重复执行（重复执行会因主键/唯一键冲突失败）。
-- =============================================================

CREATE TABLE iot_device
(
    id          BIGINT       NOT NULL COMMENT '主键',
    tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
    device_code VARCHAR(64)  NOT NULL COMMENT '设备编码（租户内唯一）',
    device_name VARCHAR(100) NOT NULL COMMENT '设备名称',
    protocol    VARCHAR(32)  NOT NULL COMMENT '接入协议码：tcp | modbus | mqtt | opcua',
    endpoint    VARCHAR(300) NOT NULL COMMENT '端点 URI，例如 tcp://127.0.0.1:15002',
    remark      VARCHAR(500) NULL COMMENT '备注',
    create_user BIGINT       NULL COMMENT '创建人',
    create_time DATETIME     NULL COMMENT '创建时间',
    update_user BIGINT       NULL COMMENT '更新人',
    update_time DATETIME     NULL COMMENT '更新时间',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_device_tenant_code (tenant_id, device_code),
    KEY idx_iot_device_tenant (tenant_id)
) COMMENT 'IoT 设备台账';
