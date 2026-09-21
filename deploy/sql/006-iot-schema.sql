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

-- =============================================================
-- M-1 物模型域（2026-09-20 追加）
-- 覆盖：iot_product / iot_product_version / iot_service / iot_property / iot_command /
--       iot_event / iot_point_mapping / iot_device_group / iot_device_group_member /
--       iot_device_tag / iot_shadow，以及既有 iot_device 的 ALTER 扩展（§4.1）。
-- 等价性：本文件追加部分与 migration/2026-09-19-iot-m1-thing-model-schema.sql
--        （+ 007 与 2026-09-20-iot-m1-thing-model-menu.sql）语句等价。
-- =============================================================

-- 设备台账扩展（§4.1：product_id/product_version/credential_ref/online_status/shadow_json/last_seen_at）
ALTER TABLE iot_device
    ADD COLUMN product_id      BIGINT       NULL COMMENT '绑定产品 ID（§3.2）',
    ADD COLUMN product_version VARCHAR(32)  NULL COMMENT '绑定物模型版本（如 v1.0，§3.8）',
    ADD COLUMN credential_ref  VARCHAR(255) NULL COMMENT '凭据引用（不透明，access 本地解析，§4.2）',
    ADD COLUMN online_status   VARCHAR(16)  NOT NULL DEFAULT 'unknown' COMMENT '在线状态：online|offline|unknown（§4.3）',
    ADD COLUMN shadow_json     TEXT         NULL COMMENT '影子快照（§3.10）',
    ADD COLUMN last_seen_at    DATETIME     NULL COMMENT '最后心跳/上报时刻';

-- 产品（§3.2）
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

-- 物模型版本（§3.8：draft→published，发布时递增 v{major}.{minor}，已发布不可变）
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

-- 服务（§3.3，能力域；serviceId PascalCase）
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

-- 属性（§3.4，identifier camelCase）
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

-- 命令（§3.5，identifier UPPER_SNAKE）
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

-- 事件（§3.6，平台在服务层的扩展）
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

-- 点位映射（§3.9：物模型逻辑点位 ↔ 协议物理地址）
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

-- 设备分组（§3.11）
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

-- 设备分组-设备关联（§3.11 多对多）
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

-- 设备标签（§3.11 key/value）
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

-- 设备影子（§3.10；M-1 落库分支，Redis 化 M-2 与最新值一起落地）
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
