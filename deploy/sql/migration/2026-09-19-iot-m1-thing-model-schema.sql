-- =============================================================
-- 迁移：M-1 物模型域表结构（iot_device 扩展 + 物模型/点位/分组/影子新表）
-- 适用：**已上线的库**
-- 等价性：本文件与 deploy/sql/006-iot-schema.sql 的**M-1 追加段**语句等价
--         （注释可不同；CI 有语句级等价校验 tools/check-iot-sql-equivalence.sh）。
-- 命名说明：文件名用 2026-09-19 前缀，是为了按字典序排在 2026-09-19-iot-menu-data.sql
--         **之前**（结构演进先于菜单数据），与 006（结构）→ 007（数据）的顺序一致；
--         2026-09-20-iot-m1-thing-model-menu.sql 排在最后对应 007 追加。
-- 幂等性：本文件是**一次性**迁移脚本，不保证可重复执行。
-- =============================================================

ALTER TABLE iot_device
    ADD COLUMN product_id      BIGINT       NULL COMMENT '绑定产品 ID（§3.2）',
    ADD COLUMN product_version VARCHAR(32)  NULL COMMENT '绑定物模型版本（如 v1.0，§3.8）',
    ADD COLUMN credential_ref  VARCHAR(255) NULL COMMENT '凭据引用（不透明，access 本地解析，§4.2）',
    ADD COLUMN online_status   VARCHAR(16)  NOT NULL DEFAULT 'unknown' COMMENT '在线状态：online|offline|unknown（§4.3）',
    ADD COLUMN shadow_json     TEXT         NULL COMMENT '影子快照（§3.10）',
    ADD COLUMN last_seen_at    DATETIME     NULL COMMENT '最后心跳/上报时刻';

CREATE TABLE iot_product
(
    id                BIGINT       NOT NULL COMMENT '主键',
    tenant_id         BIGINT       NOT NULL COMMENT '租户 ID',
    product_code      VARCHAR(64)  NOT NULL COMMENT '产品编码（租户内唯一）',
    product_name      VARCHAR(128) NOT NULL COMMENT '产品名称',
    protocol          VARCHAR(16)  NOT NULL COMMENT '接入协议码：tcp | modbus | mqtt | opcua',
    data_format       VARCHAR(16)  NOT NULL DEFAULT 'json' COMMENT '数据格式：json（默认）| binary（预留编解码插件）',
    device_type       VARCHAR(64)  NULL COMMENT '设备类型描述（IoTDA deviceType）',
    manufacturer_id   VARCHAR(128) NULL COMMENT '厂商 ID（可选）',
    manufacturer_name VARCHAR(128) NULL COMMENT '厂商名称（可选）',
    model_status      VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT '物模型状态：draft | published（与基类 status 启停位分离）',
    remark            VARCHAR(255) NULL COMMENT '备注',
    create_user       BIGINT       NULL COMMENT '创建人',
    create_time       DATETIME     NULL COMMENT '创建时间',
    update_user       BIGINT       NULL COMMENT '更新人',
    update_time       DATETIME     NULL COMMENT '更新时间',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_product_tenant_code (tenant_id, product_code),
    KEY idx_iot_product_tenant (tenant_id)
) COMMENT 'IoT 产品（物模型）';

CREATE TABLE iot_product_version
(
    id           BIGINT       NOT NULL COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '租户 ID',
    product_id   BIGINT       NOT NULL COMMENT '所属产品',
    version_no   VARCHAR(32)  NOT NULL COMMENT '版本号（语义化 v1.0）',
    model_status VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT '版本状态：draft | published',
    published_at DATETIME     NULL COMMENT '发布时间',
    remark       VARCHAR(255) NULL COMMENT '备注',
    create_user  BIGINT       NULL COMMENT '创建人',
    create_time  DATETIME     NULL COMMENT '创建时间',
    update_user  BIGINT       NULL COMMENT '更新人',
    update_time  DATETIME     NULL COMMENT '更新时间',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_product_version (tenant_id, product_id, version_no),
    KEY idx_iot_product_version_product (product_id)
) COMMENT 'IoT 物模型版本';

CREATE TABLE iot_service
(
    id           BIGINT       NOT NULL COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '租户 ID',
    product_id   BIGINT       NOT NULL COMMENT '所属产品',
    service_id   VARCHAR(64)  NOT NULL COMMENT '服务标识（PascalCase，产品内唯一）',
    service_name VARCHAR(128) NOT NULL COMMENT '服务名称',
    service_option VARCHAR(16) NOT NULL DEFAULT 'mandatory' COMMENT '服务选项：master|mandatory|optional（对齐 IoTDA；列名不用 option：MySQL 保留字）',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '排序',
    description  VARCHAR(255) NULL COMMENT '描述',
    create_user  BIGINT       NULL COMMENT '创建人',
    create_time  DATETIME     NULL COMMENT '创建时间',
    update_user  BIGINT       NULL COMMENT '更新人',
    update_time  DATETIME     NULL COMMENT '更新时间',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_service (tenant_id, product_id, service_id),
    KEY idx_iot_service_product (product_id)
) COMMENT 'IoT 物模型服务';

CREATE TABLE iot_property
(
    id            BIGINT          NOT NULL COMMENT '主键',
    tenant_id     BIGINT          NOT NULL COMMENT '租户 ID',
    service_id    BIGINT          NOT NULL COMMENT '所属服务',
    identifier    VARCHAR(64)     NOT NULL COMMENT '属性标识（camelCase，服务内唯一）',
    property_name VARCHAR(128)    NOT NULL COMMENT '属性名称',
    data_type     VARCHAR(16)     NOT NULL COMMENT '数据类型：int|long|decimal|string|bool|enum|date_time|json_object|array',
    access_mode   VARCHAR(8)      NOT NULL DEFAULT 'R' COMMENT '读写权限：R|W|RW',
    required      TINYINT         NOT NULL DEFAULT 0 COMMENT '是否必选：1 是 0 否',
    min_value     DECIMAL(20, 6)  NULL COMMENT '最小值',
    max_value     DECIMAL(20, 6)  NULL COMMENT '最大值',
    step          DECIMAL(20, 6)  NULL COMMENT '步长',
    max_length    INT             NULL COMMENT '最大长度（string）',
    unit          VARCHAR(32)     NULL COMMENT '单位',
    enum_list     TEXT            NULL COMMENT '枚举取值（JSON 数组）',
    default_value VARCHAR(255)    NULL COMMENT '默认值',
    expand        TEXT            NULL COMMENT '扩展（JSON）',
    sort          INT             NOT NULL DEFAULT 0 COMMENT '排序',
    create_user   BIGINT          NULL COMMENT '创建人',
    create_time   DATETIME        NULL COMMENT '创建时间',
    update_user   BIGINT          NULL COMMENT '更新人',
    update_time   DATETIME        NULL COMMENT '更新时间',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_property (tenant_id, service_id, identifier),
    KEY idx_iot_property_service (service_id)
) COMMENT 'IoT 物模型属性';

CREATE TABLE iot_command
(
    id            BIGINT       NOT NULL COMMENT '主键',
    tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
    service_id    BIGINT       NOT NULL COMMENT '所属服务',
    identifier    VARCHAR(64)  NOT NULL COMMENT '命令标识（UPPER_SNAKE，服务内唯一）',
    command_name  VARCHAR(128) NOT NULL COMMENT '命令名称',
    input_params  TEXT         NULL COMMENT '入参定义（JSON）',
    output_params TEXT         NULL COMMENT '出参定义（JSON）',
    timeout_ms    INT          NULL COMMENT '命令超时（毫秒，缺省用全局默认）',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_user   BIGINT       NULL COMMENT '创建人',
    create_time   DATETIME     NULL COMMENT '创建时间',
    update_user   BIGINT       NULL COMMENT '更新人',
    update_time   DATETIME     NULL COMMENT '更新时间',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_command (tenant_id, service_id, identifier),
    KEY idx_iot_command_service (service_id)
) COMMENT 'IoT 物模型命令';

CREATE TABLE iot_event
(
    id           BIGINT       NOT NULL COMMENT '主键',
    tenant_id    BIGINT       NOT NULL COMMENT '租户 ID',
    service_id   BIGINT       NOT NULL COMMENT '所属服务',
    identifier   VARCHAR(64)  NOT NULL COMMENT '事件标识（camelCase，服务内唯一）',
    event_name   VARCHAR(128) NOT NULL COMMENT '事件名称',
    data_type    VARCHAR(16)  NOT NULL COMMENT '数据类型（同属性）',
    max_length   INT          NULL COMMENT '最大长度',
    unit         VARCHAR(32)  NULL COMMENT '单位',
    enum_list    TEXT         NULL COMMENT '枚举取值（JSON 数组）',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_user  BIGINT       NULL COMMENT '创建人',
    create_time  DATETIME     NULL COMMENT '创建时间',
    update_user  BIGINT       NULL COMMENT '更新人',
    update_time  DATETIME     NULL COMMENT '更新时间',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_event (tenant_id, service_id, identifier),
    KEY idx_iot_event_service (service_id)
) COMMENT 'IoT 物模型事件';

CREATE TABLE iot_point_mapping
(
    id               BIGINT          NOT NULL COMMENT '主键',
    tenant_id        BIGINT          NOT NULL COMMENT '租户 ID',
    device_id        BIGINT          NOT NULL COMMENT '设备 ID',
    property_id      BIGINT          NOT NULL COMMENT '关联属性（或命令）',
    ref_type         VARCHAR(16)     NOT NULL DEFAULT 'property' COMMENT '关联类型：property | command',
    raw_address      VARCHAR(128)    NOT NULL COMMENT '协议内地址（寄存器地址/NodeId/topic 等）',
    address_type     VARCHAR(16)     NOT NULL COMMENT '地址类型：holding|input|coil|discrete|nodeid|topic…',
    poll_interval_ms INT             NOT NULL DEFAULT 0 COMMENT '采集周期（0=仅订阅不轮询，对齐 iot-starter pollInterval）',
    scale_factor     DECIMAL(20, 6)  NULL COMMENT '缩放系数：value = raw * scale + offset',
    offset_value     DECIMAL(20, 6)  NULL COMMENT '缩放偏移',
    byte_order       VARCHAR(16)     NULL COMMENT '字节序：big|little',
    rw               VARCHAR(8)      NOT NULL DEFAULT 'R' COMMENT '读写权限（与属性 access_mode 联动校验）',
    enabled          TINYINT         NOT NULL DEFAULT 1 COMMENT '启用/停采',
    create_user      BIGINT          NULL COMMENT '创建人',
    create_time      DATETIME        NULL COMMENT '创建时间',
    update_user      BIGINT          NULL COMMENT '更新人',
    update_time      DATETIME        NULL COMMENT '更新时间',
    status           TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_point_mapping (tenant_id, device_id, property_id, raw_address),
    KEY idx_iot_point_mapping_device (device_id)
) COMMENT 'IoT 点位映射';

CREATE TABLE iot_device_group
(
    id          BIGINT       NOT NULL COMMENT '主键',
    tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
    group_name  VARCHAR(128) NOT NULL COMMENT '分组名称',
    parent_id   BIGINT       NULL COMMENT '父分组 ID（null=根）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    remark      VARCHAR(255) NULL COMMENT '备注',
    create_user BIGINT       NULL COMMENT '创建人',
    create_time DATETIME     NULL COMMENT '创建时间',
    update_user BIGINT       NULL COMMENT '更新人',
    update_time DATETIME     NULL COMMENT '更新时间',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_device_group (tenant_id, group_name),
    KEY idx_iot_device_group_parent (parent_id)
) COMMENT 'IoT 设备分组';

CREATE TABLE iot_device_group_member
(
    id          BIGINT NOT NULL COMMENT '主键',
    tenant_id   BIGINT NOT NULL COMMENT '租户 ID',
    group_id    BIGINT NOT NULL COMMENT '分组 ID',
    device_id   BIGINT NOT NULL COMMENT '设备 ID',
    create_user BIGINT NULL COMMENT '创建人',
    create_time DATETIME NULL COMMENT '创建时间',
    update_user BIGINT NULL COMMENT '更新人',
    update_time DATETIME NULL COMMENT '更新时间',
    status      TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_device_group_member (tenant_id, group_id, device_id),
    KEY idx_iot_device_group_member_device (device_id)
) COMMENT 'IoT 设备分组-设备成员';

CREATE TABLE iot_device_tag
(
    id          BIGINT       NOT NULL COMMENT '主键',
    tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
    device_id   BIGINT       NOT NULL COMMENT '设备 ID',
    tag_key     VARCHAR(64)  NOT NULL COMMENT '标签键',
    tag_value   VARCHAR(255) NOT NULL COMMENT '标签值',
    create_user BIGINT       NULL COMMENT '创建人',
    create_time DATETIME     NULL COMMENT '创建时间',
    update_user BIGINT       NULL COMMENT '更新人',
    update_time DATETIME     NULL COMMENT '更新时间',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_device_tag (tenant_id, device_id, tag_key),
    KEY idx_iot_device_tag_device (device_id)
) COMMENT 'IoT 设备标签';

CREATE TABLE iot_shadow
(
    id         BIGINT   NOT NULL COMMENT '主键',
    tenant_id  BIGINT   NOT NULL COMMENT '租户 ID',
    device_id  BIGINT   NOT NULL COMMENT '设备 ID',
    reported   TEXT     NULL COMMENT '设备上报值（JSON）',
    desired    TEXT     NULL COMMENT '平台期望值（JSON）',
    report_ts  DATETIME NULL COMMENT '最近上报时刻',
    desired_ts DATETIME NULL COMMENT '最近期望时刻',
    create_user BIGINT  NULL COMMENT '创建人',
    create_time DATETIME NULL COMMENT '创建时间',
    update_user BIGINT  NULL COMMENT '更新人',
    update_time DATETIME NULL COMMENT '更新时间',
    status     TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_shadow (tenant_id, device_id),
    KEY idx_iot_shadow_device (device_id)
) COMMENT 'IoT 设备影子';
