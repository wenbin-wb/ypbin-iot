-- ypbin-iot 设备批量导入（CSV）+ 批次管理：两张表（增量迁移）
-- 与 deploy/sql/006-iot-schema.sql 的**批量导入段**语句等价（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 覆盖：iot_device_import_batch / iot_device_import_row。
-- ⚠️ 排序约束：本文件名必须排在 2026-09-30-iot-credential-schema.sql 之后（等价性脚本按名排序拼接）。
-- 回滚：deploy/sql/rollback/2026-09-30-iot-device-import-rollback.sql
-- =============================================================

CREATE TABLE iot_device_import_batch
(
    id               BIGINT       NOT NULL COMMENT '主键',
    tenant_id        BIGINT       NOT NULL COMMENT '租户 ID',
    file_name        VARCHAR(200) NULL COMMENT '上传的原始文件名（原样留痕，便于用户认出是哪一批）',
    total_rows       INT          NOT NULL DEFAULT 0 COMMENT 'CSV 数据行总数（不含表头与说明行）',
    success_rows     INT          NOT NULL DEFAULT 0 COMMENT '成功创建的行数',
    failed_rows      INT          NOT NULL DEFAULT 0 COMMENT '失败的行数',
    batch_status     VARCHAR(16)  NOT NULL COMMENT '状态码（枚举 code，非 ordinal）：running/success/partial-failed/failed（列名避让基类 status）',
    error_summary    VARCHAR(1024) NULL COMMENT '错误摘要（面向用户的一句话；全部成功时为 NULL）',
    start_time       DATETIME     NULL COMMENT '导入开始时刻',
    end_time         DATETIME     NULL COMMENT '导入结束时刻（NULL=进行中）',
    operator_user_id BIGINT       NULL COMMENT '操作人用户 ID（审计：谁传的这批）',
    create_user      BIGINT       NULL COMMENT '创建人',
    create_time      DATETIME     NULL COMMENT '创建时间',
    update_user      BIGINT       NULL COMMENT '更新人',
    update_time      DATETIME     NULL COMMENT '更新时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_import_batch_tenant_time (tenant_id, create_time)
) COMMENT 'IoT 设备批量导入批次（一次 CSV 上传对应一行；批次管理页签的数据源）';

CREATE TABLE iot_device_import_row
(
    id            BIGINT       NOT NULL COMMENT '主键',
    tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
    batch_id      BIGINT       NOT NULL COMMENT '批次 ID（iot_device_import_batch.id）',
    row_no        INT          NOT NULL COMMENT '行号（数据行从 1 开始，不含表头与说明行）',
    raw_line      TEXT         NULL COMMENT '原始行内容（原样留痕；用 TEXT 而非 VARCHAR：单行是「列数×列宽」之和，用 VARCHAR(500) 会把「某一列太长」变成整批导入失败）',
    row_result    VARCHAR(16)  NOT NULL COMMENT '结果码（枚举 code）：success/failed',
    error_code    VARCHAR(48)  NULL COMMENT '错误码（DeviceImportErrorCode 的 code；成功行为 NULL）',
    error_message VARCHAR(512) NULL COMMENT '面向用户的错误信息（含具体是哪一列、哪个取值）',
    device_id     BIGINT       NULL COMMENT '成功创建出的设备 ID（不加外键：明细是审计留痕，设备后续被删仍要能解释当时发生了什么）',
    device_code   VARCHAR(64)  NULL COMMENT '该行的设备编码（成功/失败都存：失败行要靠它让用户认出是哪台设备）',
    create_user   BIGINT       NULL COMMENT '创建人',
    create_time   DATETIME     NULL COMMENT '创建时间',
    update_user   BIGINT       NULL COMMENT '更新人',
    update_time   DATETIME     NULL COMMENT '更新时间',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_import_row_batch (tenant_id, batch_id, row_no),
    KEY idx_iot_import_row_result (tenant_id, batch_id, row_result)
) COMMENT 'IoT 设备批量导入逐行明细（失败行可导出为 CSV 改后原样重传）';
