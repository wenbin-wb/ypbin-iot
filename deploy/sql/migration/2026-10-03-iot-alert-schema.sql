-- ypbin-iot 告警与阈值能力：结构（增量迁移）
-- 与 deploy/sql/007-iot-data.sql 的**告警 DDL 段**语句等价（由 tools/check-iot-sql-equivalence.sh 校验）。
-- 设计契约：docs/ALERTING-DESIGN.md §2.1（表 A/B/C/D）；用户已批准口径见任务回执与文档的「偏差」小节。
-- 为什么 DDL 单独成文件（与 `-menu` 分家）：真库 IT 的建表助手只加载 006 + 本功能的 `-schema` 迁移，
--   混在菜单迁移里会让「只跑菜单」的场景把表建丢（先例：2026-09-30-iot-credential-schema.sql 的同款注释）。
-- ⚠️ 排序约束：本文件名必须排在 2026-10-03-iot-alert-menu.sql 之后、且整体排在全部既有 IoT 迁移之后
--   （等价性校验按文件名排序拼接后与 006+007 逐语句比较）。
-- 回滚：deploy/sql/rollback/2026-10-03-iot-alert-rollback.sql（**只回滚表结构，不删数据**）。
-- =============================================================

CREATE TABLE iot_alert_rule
(
    id                  BIGINT       NOT NULL COMMENT '主键',
    tenant_id           BIGINT       NOT NULL COMMENT '租户 ID',
    rule_name           VARCHAR(128) NOT NULL COMMENT '规则名（展示用；同名不禁止，靠 id 区分）',
    scope_type          VARCHAR(16)  NOT NULL COMMENT '作用域码（枚举 code）：TENANT/PRODUCT/DEVICE/POINT',
    scope_product_id    BIGINT       NULL COMMENT '作用域产品 ID（PRODUCT/POINT 时必填；其余必须为空）',
    scope_device_id     BIGINT       NULL COMMENT '作用域设备 ID（DEVICE/POINT 时必填；其余必须为空）',
    severity            VARCHAR(16)  NOT NULL COMMENT '级别码（枚举 code）：INFO/WARNING/CRITICAL',
    enabled             TINYINT      NOT NULL DEFAULT 1 COMMENT '启用开关（停用不删除，保留历史与解释能力）',
    trigger_mode        VARCHAR(24)  NOT NULL COMMENT '抖动抑制模式码：IMMEDIATE/CONSECUTIVE_COUNT/DURATION',
    trigger_threshold   INT          NOT NULL DEFAULT 0 COMMENT '连续次数 N 或持续秒数 T；IMMEDIATE 存 0',
    pending_ttl_sec     INT          NOT NULL DEFAULT 300 COMMENT 'pending 态最大挂起秒数（超时放弃候选）',
    repeat_interval_sec INT          NOT NULL DEFAULT 1800 COMMENT '重复通知抑制：活动期重发间隔（秒）',
    silence_start       DATETIME     NULL COMMENT '规则静默窗口起（与维护窗口取并集）',
    silence_end         DATETIME     NULL COMMENT '规则静默窗口止',
    notify_channels     VARCHAR(64)  NOT NULL DEFAULT 'INBOX,EMAIL' COMMENT '渠道码集合（逗号分隔）：INBOX/EMAIL',
    notify_targets      VARCHAR(512) NULL COMMENT '收件人（用户 ID/邮箱，逗号分隔）；NULL=规则创建者',
    description         VARCHAR(512) NULL COMMENT '说明（人会读的那一句）',
    create_user         BIGINT       NULL COMMENT '创建人',
    create_time         DATETIME     NULL COMMENT '创建时间',
    update_user         BIGINT       NULL COMMENT '更新人',
    update_time         DATETIME     NULL COMMENT '更新时间',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_alert_rule_scope (tenant_id, enabled, scope_type),
    KEY idx_iot_alert_rule_device (tenant_id, scope_device_id),
    KEY idx_iot_alert_rule_product (tenant_id, scope_product_id)
) COMMENT 'IoT 告警规则主表（设计 §2.1 表 A）';

CREATE TABLE iot_alert_rule_point
(
    id          BIGINT         NOT NULL COMMENT '主键',
    tenant_id   BIGINT         NOT NULL COMMENT '租户 ID',
    rule_id     BIGINT         NOT NULL COMMENT '规则 ID（iot_alert_rule.id）',
    property_id VARCHAR(64)    NOT NULL COMMENT '点位标识（与 PointMappingIndex 解析口径一致）',
    operator    VARCHAR(8)     NOT NULL COMMENT '比较符码（枚举 code）：GT/GTE/LT/LTE/EQ/NE',
    threshold   DECIMAL(24, 6) NOT NULL COMMENT '阈值（用 DECIMAL 而非 DOUBLE：阈值是配置，不能有二进制浮点误差）',
    value_type  VARCHAR(16)    NOT NULL DEFAULT 'NUMERIC' COMMENT '比较域码：NUMERIC/BOOLEAN',
    deadband    DECIMAL(24, 6) NULL COMMENT '回差（滞回）：恢复门槛比触发门槛往回退这么多',
    create_user BIGINT         NULL COMMENT '创建人',
    create_time DATETIME       NULL COMMENT '创建时间',
    update_user BIGINT         NULL COMMENT '更新人',
    update_time DATETIME       NULL COMMENT '更新时间',
    status      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted  TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_iot_alert_rule_point_rule (tenant_id, rule_id)
) COMMENT 'IoT 告警规则点位条件行（设计 §2.1 表 B；零条件行=设备离线/数据中断类规则）';

CREATE TABLE iot_alert_instance
(
    id                 BIGINT       NOT NULL COMMENT '主键',
    tenant_id          BIGINT       NOT NULL COMMENT '租户 ID',
    rule_id            BIGINT       NOT NULL COMMENT '触发它的规则 ID（断档映射用保留值 0）',
    device_id          BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    property_id        VARCHAR(64)  NULL COMMENT '点位标识（设备级/断档类实例为空）',
    dedup_key          VARCHAR(191) NOT NULL COMMENT '去重键（rule_id:device_id:property_id；历史留痕用，不进唯一索引）',
    active_dedup_key   VARCHAR(191) NULL COMMENT '只在活动期间非 NULL（=dedup_key）；恢复时置 NULL',
    severity           VARCHAR(16)  NOT NULL COMMENT '触发时的级别（冗余存下，规则改级别不改写历史）',
    state              VARCHAR(16)  NOT NULL COMMENT '状态码（枚举 code）：PENDING/FIRING/ACKED/RESOLVED',
    consecutive_count  INT          NOT NULL DEFAULT 0 COMMENT '连续越界计数（抖动抑制；设计表 C 未列，见文档偏差）',
    trigger_value      VARCHAR(64)  NULL COMMENT '触发时读到的原始值（原样存）',
    threshold_snapshot VARCHAR(64)  NULL COMMENT '触发时的阈值快照（规则改阈值后仍能解释当时为何报警）',
    start_ts           DATETIME     NOT NULL COMMENT '首次越界时刻（pending 开始）',
    firing_ts          DATETIME     NULL COMMENT '正式触发时刻',
    resolved_ts        DATETIME     NULL COMMENT '恢复时刻（NULL=仍活动）',
    acked_ts           DATETIME     NULL COMMENT '确认时刻',
    acked_by           BIGINT       NULL COMMENT '确认人',
    last_notified_ts   DATETIME     NULL COMMENT '最近一次通知时刻（重复通知抑制的唯一依据）',
    notify_count       INT          NOT NULL DEFAULT 0 COMMENT '已生成通知次数',
    reason             VARCHAR(64)  NULL COMMENT '结束原因码：RECOVERED/RULE_DISABLED/OUTAGE_RECOVERED',
    silence_until      DATETIME     NULL COMMENT '实例级静默截止时刻（NULL=未静默；静默不是状态）',
    create_user        BIGINT       NULL COMMENT '创建人',
    create_time        DATETIME     NULL COMMENT '创建时间',
    update_user        BIGINT       NULL COMMENT '更新人',
    update_time        DATETIME     NULL COMMENT '更新时间',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_active (tenant_id, active_dedup_key),
    KEY idx_iot_alert_instance_device (tenant_id, device_id, start_ts),
    KEY idx_iot_alert_instance_state (tenant_id, state, start_ts),
    KEY idx_iot_alert_instance_severity (tenant_id, severity, state)
) COMMENT 'IoT 告警实例（设计 §2.1 表 C；uk_alert_active 是「同键至多一条活动告警」的库级保证）';

CREATE TABLE iot_alert_notification
(
    id             BIGINT       NOT NULL COMMENT '主键',
    tenant_id      BIGINT       NOT NULL COMMENT '租户 ID',
    instance_id    BIGINT       NOT NULL COMMENT '告警实例 ID',
    channel        VARCHAR(16)  NOT NULL COMMENT '渠道码（枚举 code）：INBOX/EMAIL',
    target         VARCHAR(191) NOT NULL COMMENT '收件人标识（用户 ID/邮箱；无可解析收件人时为空串并给出原因）',
    event          VARCHAR(16)  NOT NULL COMMENT '通知事件码：FIRING/RESOLVED/REPEAT/ACKED',
    notify_status  VARCHAR(16)  NOT NULL COMMENT '投递状态码：PENDING/SENT/FAILED/GIVEN_UP（列名避让基类 status）',
    attempt        INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    next_retry_ts  DATETIME     NULL COMMENT '下次重试时刻（退避 30s→2min→10min）',
    last_error     VARCHAR(512) NULL COMMENT '最近一次错误（原样记录，不吞）',
    idempotent_key VARCHAR(191) NOT NULL COMMENT '幂等键（instance+event+channel+target+轮次）',
    create_user    BIGINT       NULL COMMENT '创建人',
    create_time    DATETIME     NULL COMMENT '创建时间',
    update_user    BIGINT       NULL COMMENT '更新人',
    update_time    DATETIME     NULL COMMENT '更新时间',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_notification_idem (tenant_id, idempotent_key),
    KEY idx_iot_alert_notification_instance (tenant_id, instance_id),
    KEY idx_iot_alert_notification_due (notify_status, next_retry_ts)
) COMMENT 'IoT 告警通知投递记录（设计 §2.1 表 D；与告警状态分开存，通知全挂时告警仍可见）';
