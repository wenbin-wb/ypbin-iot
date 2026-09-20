-- =============================================================
-- 迁移：租户节点归属表（增量 2）
-- 适用：**已上线的库**（install.sh 只在全新安装时执行 deploy/sql/*.sql，不含 migration/）
-- 等价性：本文件与 deploy/sql/006-iot-schema.sql 中「租户节点归属」那段的**语句**等价
--         （注释可不同）。改一边必须同步另一边；CI 有语句级等价校验
--         （tools/check-iot-sql-equivalence.sh）。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
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
    UNIQUE KEY uk_tenant_node_assignment_tenant (tenant_id),
    KEY idx_tenant_node_assignment_state_expire (state, lease_expire_at),
    KEY idx_tenant_node_assignment_node (access_node, state)
) COMMENT '租户节点归属（平台表）';
