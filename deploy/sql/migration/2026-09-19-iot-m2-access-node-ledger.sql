-- 迁移：M0b-1/M0b-2（接入节点注册表 + 租户台账）
--
-- ⚠️ 文件名的日期前缀写 2026-09-19 而非实际编写日（2026-09-21）：
--    tools/check-iot-sql-equivalence.sh 按**文件名排序**拼接迁移并逐语句比较，
--    而 006-iot-schema.sql 是「所有表结构在前」、007-iot-data.sql 是「所有菜单数据在后」。
--    若用 09-21 前缀，本文件会排到菜单数据迁移之后，与 006 的追加顺序不一致 ⇒ 门禁转红。
--    命名规约要的是「顺序 = 结构演进顺序」，故此处以顺序为准。

-- ============================================================
-- M0b-1 / M0b-2：接入节点注册表 + 租户台账
--   · access_node：容量落库，使「容量判定」在多副本下成为数据库级原子（节点行 FOR UPDATE）
--   · tenant_ledger：可分配租户的来源（替代读配置）+ config_epoch（台账变更同事务 +1，M0b-3）
--   两张都是**平台表** ⇒ 必须加入 ypbin.tenant.ignore-tables（否则租户插件 fail-closed）
-- ============================================================

CREATE TABLE access_node (
    id                BIGINT       NOT NULL COMMENT '主键',
    access_node       VARCHAR(128) NOT NULL COMMENT '节点标识（租约归属的键，全局唯一）',
    max_tenants       INT          NULL COMMENT '最多可持有租户数；NULL = 不限（单节点全量）',
    last_heartbeat_at DATETIME     NULL COMMENT '最近一次注册/心跳时间',
    create_user       BIGINT       NULL COMMENT '创建人',
    create_time       DATETIME     NULL COMMENT '创建时间',
    update_user       BIGINT       NULL COMMENT '更新人',
    update_time       DATETIME     NULL COMMENT '更新时间',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_access_node (access_node)
) COMMENT '接入节点注册表（容量数据库级原子）';

CREATE TABLE tenant_ledger (
    id           BIGINT   NOT NULL COMMENT '主键',
    tenant_id    BIGINT   NOT NULL COMMENT '租户 ID',
    assignable   TINYINT  NOT NULL DEFAULT 1 COMMENT '是否可分配：1 可 0 不可',
    config_epoch BIGINT   NOT NULL DEFAULT 0 COMMENT '配置版本号：台账变更同事务 +1（变更推送对账）',
    create_user  BIGINT   NULL COMMENT '创建人',
    create_time  DATETIME NULL COMMENT '创建时间',
    update_user  BIGINT   NULL COMMENT '更新人',
    update_time  DATETIME NULL COMMENT '更新时间',
    status       TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_ledger (tenant_id)
) COMMENT '租户台账（可分配来源 + 配置版本号）';
