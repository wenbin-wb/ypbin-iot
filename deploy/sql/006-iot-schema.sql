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
-- 租户节点归属（增量 2）
-- 平台表：记录「哪个 access 节点在采哪个租户」，**不受租户插件约束**
--       ⇒ 必须登记进 ypbin.tenant.ignore-tables（见 deploy/nacos/ypbin-iot.yaml），
--         否则跨租户的失效扫描/对账会被自动追加 tenant_id 条件而查不到数据。
-- state 存 LeaseState 的稳定码（active | pending_takeover | released），不存 ordinal。
-- =============================================================

CREATE TABLE tenant_node_assignment
(
    id              BIGINT       NOT NULL COMMENT '主键',
    tenant_id       BIGINT       NOT NULL COMMENT '租户 ID',
    access_node     VARCHAR(128) NOT NULL COMMENT '当前归属的 access 节点标识',
    epoch           BIGINT       NOT NULL COMMENT '台账版本号（归属每次变更都推进）',
    state           VARCHAR(32)  NOT NULL COMMENT '租约状态码：active | pending_takeover | released',
    lease_expire_at DATETIME     NOT NULL COMMENT '租约到期时间',
    create_user     BIGINT       NULL COMMENT '创建人',
    create_time     DATETIME     NULL COMMENT '创建时间',
    update_user     BIGINT       NULL COMMENT '更新人',
    update_time     DATETIME     NULL COMMENT '更新时间',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 一个租户同一时刻只能有一个归属：并发分配的兜底就靠它（INSERT 冲突即「别人先到」）
    UNIQUE KEY uk_tenant_node_assignment_tenant (tenant_id),
    -- 失效扫描走 (state, lease_expire_at)
    KEY idx_tenant_node_assignment_state_expire (state, lease_expire_at),
    -- 节点续约/释放走 (access_node, state)
    KEY idx_tenant_node_assignment_node (access_node, state)
) COMMENT '租户节点归属（平台表）';
