-- =============================================================
-- 迁移：数字孪生（4 张表）
-- 适用：**已上线的库**（install.sh 只在全新安装时执行 deploy/sql/*.sql，不含 migration/）
-- 等价性：本文件与 deploy/sql/006-iot-schema.sql 中「数字孪生域」那段的**语句**等价
--         （注释可不同）。改一边必须同步另一边；CI 有语句级等价校验
--         （tools/check-iot-sql-equivalence.sh）。
-- 放置说明：本表属 2026-10-21 的增量，按仓内约定追加在 006 末尾，
--         使「006+007 按语句顺序」与「migration/*.sql 按文件名排序」严格对齐。
-- 幂等性：一次性迁移脚本，不保证可重复执行。
-- 回滚：deploy/sql/rollback/2026-10-21-iot-twin-schema-rollback.sql
-- =============================================================

-- =============================================================
-- 数字孪生域（2026-10-21 追加）
-- 覆盖：iot_twin_model_asset / iot_twin_scene / iot_twin_point / iot_twin_point_binding
-- 租户表：4 张表都含 tenant_id 且**不**进 deploy/nacos/ypbin-iot.yaml 的 ignore-tables，
--       由 MyBatis-Plus 租户插件统一追加 tenant_id 条件（与 iot_device 同口径）。
-- 语义边界（最容易混淆的一处）：
--   iot_point_mapping 是**采集域**映射（协议地址 ↔ 物模型属性）；
--   iot_twin_point 是**展示域**映射（3D 空间锚点 ↔ 设备）。
--   两者语义正交，不得合并成一张表，也不得互相写入。
-- 等价性：本文件追加部分与 migration/2026-10-21-iot-twin-schema.sql 语句等价。
-- 回滚：deploy/sql/rollback/2026-10-21-iot-twin-schema-rollback.sql
-- =============================================================

CREATE TABLE iot_twin_model_asset
(
    id               BIGINT       NOT NULL COMMENT '主键',
    tenant_id        BIGINT       NOT NULL COMMENT '租户 ID',
    asset_name       VARCHAR(128) NOT NULL COMMENT '模型名称（租户内唯一）',
    asset_type       VARCHAR(16)  NOT NULL COMMENT '来源类型：upload 平台内上传 | external 外链登记',
    model_format     VARCHAR(16)  NOT NULL COMMENT '模型格式：glb | gltf',
    storage_platform VARCHAR(32)  NULL COMMENT '存储平台标识（asset_type=upload 时非空）',
    storage_bucket   VARCHAR(128) NULL COMMENT '存储桶（asset_type=upload 时非空）',
    storage_path     VARCHAR(512) NULL COMMENT '存储路径（asset_type=upload 时非空）',
    external_url     VARCHAR(1024) NULL COMMENT '外链地址（asset_type=external 时非空，仅允许 http/https）',
    original_name    VARCHAR(255) NULL COMMENT '上传时的原始文件名（仅 upload）',
    file_size        BIGINT       NULL COMMENT '文件字节数（仅 upload）',
    content_hash     VARCHAR(64)  NULL COMMENT 'SHA-256 十六进制摘要（仅 upload；用于判重与 ETag）',
    node_names_json  LONGTEXT     NULL COMMENT '模型节点名快照（JSON 字符串数组；仅作按节点名预匹配的辅助索引，不是权威数据）',
    create_user      BIGINT       NULL COMMENT '创建人',
    create_time      DATETIME     NULL COMMENT '创建时间',
    update_user      BIGINT       NULL COMMENT '更新人',
    update_time      DATETIME     NULL COMMENT '更新时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_model_asset (tenant_id, asset_name),
    KEY idx_iot_twin_model_asset_tenant (tenant_id)
) COMMENT 'IoT 数字孪生模型资产';

CREATE TABLE iot_twin_scene
(
    id             BIGINT         NOT NULL COMMENT '主键',
    tenant_id      BIGINT         NOT NULL COMMENT '租户 ID',
    scene_code     VARCHAR(64)    NOT NULL COMMENT '场景编码（租户内唯一，用于深链与免歧义引用）',
    scene_name     VARCHAR(128)   NOT NULL COMMENT '场景名称',
    parent_id      BIGINT         NOT NULL DEFAULT 0 COMMENT '父场景 ID（0=根场景；用 0 而不是 NULL：MySQL 唯一键对 NULL 不去重）',
    scene_type     VARCHAR(16)    NOT NULL COMMENT '场景类型：park | building | floor | room',
    model_asset_id BIGINT         NULL COMMENT '绑定的模型资产 ID（NULL=纯容器节点，不渲染模型）',
    anchor_x       DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 X',
    anchor_y       DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 Y',
    anchor_z       DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '本场景局部坐标系原点在父场景世界坐标中的 Z',
    anchor_rx      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 X 轴旋转（度）',
    anchor_ry      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 Y 轴旋转（度）',
    anchor_rz      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点绕 Z 轴旋转（度）',
    anchor_scale   DECIMAL(20, 6) NOT NULL DEFAULT 1 COMMENT '锚点缩放系数（> 0）',
    view_json      LONGTEXT       NULL COMMENT '默认视角与环境配置（相机位姿/背景/光照，JSON；空=前端用默认值）',
    sort           INT            NOT NULL DEFAULT 0 COMMENT '同级排序',
    remark         VARCHAR(500)   NULL COMMENT '备注',
    create_user    BIGINT         NULL COMMENT '创建人',
    create_time    DATETIME       NULL COMMENT '创建时间',
    update_user    BIGINT         NULL COMMENT '更新人',
    update_time    DATETIME       NULL COMMENT '更新时间',
    status         TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted     TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_scene (tenant_id, scene_code),
    KEY idx_iot_twin_scene_parent (tenant_id, parent_id)
) COMMENT 'IoT 数字孪生场景（树形）';

CREATE TABLE iot_twin_point
(
    id            BIGINT         NOT NULL COMMENT '主键',
    tenant_id     BIGINT         NOT NULL COMMENT '租户 ID',
    scene_id      BIGINT         NOT NULL COMMENT '所属场景',
    device_id     BIGINT         NOT NULL COMMENT '绑定的设备（iot_device.id）',
    node_name     VARCHAR(255)   NULL COMMENT '绑定的模型节点名（可空；仅用于高亮该节点与按节点名预匹配）',
    local_x       DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 X',
    local_y       DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 Y',
    local_z       DECIMAL(20, 6) NOT NULL COMMENT '场景局部坐标系中的 Z',
    normal_x      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点朝向法线 X（用于标签与图标贴面）',
    normal_y      DECIMAL(20, 6) NOT NULL DEFAULT 1 COMMENT '锚点朝向法线 Y',
    normal_z      DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '锚点朝向法线 Z',
    icon          VARCHAR(64)    NULL COMMENT '图标标识（图标集内的名字；空=默认设备图标）',
    label_visible TINYINT        NOT NULL DEFAULT 1 COMMENT '是否在运行态常显文字标签：1 是 0 否',
    sort          INT            NOT NULL DEFAULT 0 COMMENT '排序（同场景内标签叠放顺序）',
    remark        VARCHAR(500)   NULL COMMENT '备注',
    create_user   BIGINT         NULL COMMENT '创建人',
    create_time   DATETIME       NULL COMMENT '创建时间',
    update_user   BIGINT         NULL COMMENT '更新人',
    update_time   DATETIME       NULL COMMENT '更新时间',
    status        TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted    TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_point (tenant_id, scene_id, device_id),
    KEY idx_iot_twin_point_scene (tenant_id, scene_id),
    KEY idx_iot_twin_point_device (device_id)
) COMMENT 'IoT 数字孪生点位（设备在场景中的锚点）';

CREATE TABLE iot_twin_point_binding
(
    id                  BIGINT      NOT NULL COMMENT '主键',
    tenant_id           BIGINT      NOT NULL COMMENT '租户 ID',
    point_id            BIGINT      NOT NULL COMMENT '所属点位（iot_twin_point.id）',
    service_identifier  VARCHAR(64) NOT NULL COMMENT '物模型服务标识（iot_service.service_id）',
    property_identifier VARCHAR(64) NOT NULL COMMENT '物模型属性标识（iot_property.identifier）',
    property_id         BIGINT      NULL COMMENT '属性主键缓存（仅作联查加速；属性被 TSL 重导入删除后为悬空值，读取时按标识重解析）',
    display_type        VARCHAR(16) NOT NULL COMMENT '展示形态：value | gauge | switch | color | sparkline',
    display_role        VARCHAR(16) NOT NULL DEFAULT 'secondary' COMMENT '展示角色：primary 主展示 | secondary 次展示',
    decimals            TINYINT     NULL COMMENT '小数位（0~6；空=原样展示）',
    unit_override       VARCHAR(32) NULL COMMENT '单位覆盖（空=用物模型属性自带单位）',
    sort                INT         NOT NULL DEFAULT 0 COMMENT '排序',
    create_user         BIGINT      NULL COMMENT '创建人',
    create_time         DATETIME    NULL COMMENT '创建时间',
    update_user         BIGINT      NULL COMMENT '更新人',
    update_time         DATETIME    NULL COMMENT '更新时间',
    status              TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_twin_point_binding (tenant_id, point_id, service_identifier, property_identifier),
    KEY idx_iot_twin_point_binding_point (point_id)
) COMMENT 'IoT 数字孪生点位属性绑定';
