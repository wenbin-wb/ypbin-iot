-- =============================================================
-- ypbin-iot 表结构（最终结构，唯一结构来源）
-- 覆盖：设备台账 iot_device
-- 约定：全部 tenant_id 为 BIGINT（对齐 TenantBaseEntity 的 Long tenantId）；
--       主键 BIGINT（业务侧雪花/ID 生成器），公共列与 admin 其它表一致
--       （create_user/create_time/update_user/update_time/status/is_deleted）;
--       逻辑删除后仍需保证「同租户内设备编码唯一」→ 唯一键带上 is_deleted 的替代方案见下方注释。
-- 已有库升级：本文件只服务全新安装；已上线的库请执行
--       deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql
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
    -- 唯一约束不包含 is_deleted：逻辑删除后编码仍不可被同租户复用（避免历史数据指向同一编码的两台设备）。
    -- 若将来需要「删除后可复用编码」，需改为「唯一键带删除标记 + 触发器/应用层保证」，属于显式设计变更。
    UNIQUE KEY uk_iot_device_tenant_code (tenant_id, device_code),
    KEY idx_iot_device_tenant (tenant_id)
) COMMENT 'IoT 设备台账';
-- =============================================================
-- ypbin-iot 菜单与权限（全新安装用）
-- 权限码约定：iot:device:list | iot:device:create | iot:device:delete
-- ⚠️ 002-data.sql 里那条「把所有 platform_only=1 的菜单授给角色 1」在本文件**之前**执行，
--    因此本文件必须自己再授一次权，否则新菜单不会出现在平台管理员菜单树里。
-- 已有库升级：见 deploy/sql/migration/2026-09-19-iot-device-schema-and-menu.sql
-- =============================================================

INSERT INTO sys_menu (id, pid, name, type, platform_only, path, component, auth_code, title, icon, sort, create_time, status, is_deleted)
VALUES (3200, 0, 'IotDevice', 'menu', 1, '/iot/device', '/iot/device/list', 'iot:device:list', 'iot.device.title', 'carbon:iot', 6, NOW(), 1, 0);
INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320001, 3200, 'IotDeviceCreate', 'button', 1, 'iot:device:create', 'common.create', 1, NOW(), 1, 0),
       (320002, 3200, 'IotDeviceDelete', 'button', 1, 'iot:device:delete', 'common.delete', 2, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND platform_only = 1 AND id IN (3200, 320001, 320002);
