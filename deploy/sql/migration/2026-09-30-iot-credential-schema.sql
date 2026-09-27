-- ypbin-iot 设备凭据结构（增量迁移；与 007-iot-data.sql 追加部分的**DDL 段**语句等价）
-- 内容：iot_device 三列凭据元信息（设计 §5.3 逐字）+ iot_device_credential（平台侧秘密，见 docs/DEVICE-CREDENTIAL.md §2）
-- 为什么单独成文件（除了「菜单/DDL 分家」的历史惯例）：
--   真库 IT（-Pit）的建表助手 ItSchema 只加载 deploy/sql/006-iot-schema.sql，而 006 不含本功能的结构；
--   把 DDL 独立成 `-schema.sql` 后，ItSchema 只需在 006 之后再执行这一个文件，业务读取就不会撞上
--   「Unknown column 'credential_version'」（MyBatis-Plus 的 SELECT 会带出实体全部列）。
-- =============================================================
ALTER TABLE iot_device
    ADD COLUMN credential_version    INT      NULL COMMENT '凭据版本号（每次签发/轮换 +1；null=从未签发）',
    ADD COLUMN credential_issued_at  DATETIME NULL COMMENT '当前凭据签发时刻（null=从未签发）',
    ADD COLUMN credential_revoked_at DATETIME NULL COMMENT '凭据吊销时刻（null=未吊销）';

CREATE TABLE iot_device_credential
(
    id                 BIGINT       NOT NULL COMMENT '主键',
    tenant_id          BIGINT       NOT NULL COMMENT '租户 ID',
    device_id          BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    credential_version INT          NOT NULL COMMENT '本行凭据对应的版本号（与 iot_device.credential_version 一致）',
    username           VARCHAR(64)  NOT NULL COMMENT 'MQTT 用户名（{tenantId}.{deviceId}）',
    password_algo      VARCHAR(32)  NOT NULL COMMENT '口令哈希算法（含盐位置）：sha256-suffix',
    password_salt      VARCHAR(64)  NOT NULL COMMENT '盐（hex）；吊销后为空串',
    password_hash      VARCHAR(128) NOT NULL COMMENT '口令哈希（hex）= sha256(password + salt)；吊销后为空串',
    create_user        BIGINT       NULL COMMENT '创建人',
    create_time        DATETIME     NULL COMMENT '创建时间',
    update_user        BIGINT       NULL COMMENT '更新人',
    update_time        DATETIME     NULL COMMENT '更新时间',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_device_credential_device (tenant_id, device_id)
) COMMENT 'IoT 设备凭据秘密侧（只存哈希；明文只在签发响应出现一次）';
