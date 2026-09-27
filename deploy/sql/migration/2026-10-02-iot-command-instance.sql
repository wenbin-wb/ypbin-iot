-- =============================================================
-- 运行期命令实例：iot_command_instance（段 B 下行/在线调试）
-- 与 deploy/sql/007-iot-data.sql 末尾的同名语句**逐字等价**（tools/check-iot-sql-equivalence.sh 校验）。
-- 文件名含 `-iot-` 且按名排序在最后，与"追加到 007 末尾"的位置对应。
-- 回滚见 deploy/sql/rollback/2026-10-02-iot-command-instance-rollback.sql。
-- =============================================================

CREATE TABLE iot_command_instance
(
    id               BIGINT       NOT NULL COMMENT '主键',
    tenant_id        BIGINT       NOT NULL COMMENT '租户 ID',
    device_id        BIGINT       NOT NULL COMMENT '设备 ID（iot_device.id）',
    command_id       BIGINT       NULL     COMMENT '物模型命令定义 ID（iot_command.id；属性类为空）',
    identifier       VARCHAR(64)  NOT NULL COMMENT '命令/属性标识（冗余自物模型，便于无关联查询与审计）',
    kind             VARCHAR(16)  NOT NULL COMMENT '类型码（枚举 code）：property_set|property_get|service_call',
    request_id       VARCHAR(64)  NOT NULL COMMENT '请求 ID（幂等键；平台生成，随 payload 下发）',
    topic            VARCHAR(255) NOT NULL COMMENT '下行主题（定向靠主题；官方无按 clientid 定向的端点）',
    payload          TEXT         NULL     COMMENT '下行报文体（JSON，含 requestId）',
    reply_payload    TEXT         NULL     COMMENT '上行回执体（JSON；不含凭据）',
    status_code      VARCHAR(16)  NOT NULL COMMENT '状态码（枚举 code）：pending|sent|succeeded|failed|timeout|cancelled',
    error_code       VARCHAR(32)  NULL     COMMENT '可区分失败原因码：DEVICE_OFFLINE|NO_SUBSCRIBER|EMQX_ERROR|DEVICE_REJECTED|TIMEOUT',
    error_msg        VARCHAR(500) NULL COMMENT '失败说明（面向人的文案，不含凭据）',
    timeout_ms       INT          NOT NULL COMMENT '超时（毫秒；取下发请求的 timeoutMs，缺省用全局默认）',
    retry_count      INT          NOT NULL DEFAULT 0 COMMENT '已重发次数（仅手动重发计数，不做自动重试）',
    emqx_message_id  VARCHAR(64)  NULL     COMMENT 'EMQX publish 返回的消息 ID（溯源用）',
    source           VARCHAR(16)  NOT NULL COMMENT '来源码：console|rule|api',
    operator_user_id BIGINT       NULL     COMMENT '下发人（来源为 console 时）',
    sent_at          DATETIME     NULL     COMMENT '实际投递到 EMQX 的时刻',
    finished_at      DATETIME     NULL     COMMENT '终态时刻',
    create_user      BIGINT       NULL     COMMENT '创建人',
    create_time      DATETIME     NULL     COMMENT '创建时间',
    update_user      BIGINT       NULL     COMMENT '更新人',
    update_time      DATETIME     NULL     COMMENT '更新时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_iot_command_instance_request (tenant_id, request_id),
    KEY idx_iot_command_instance_device (tenant_id, device_id, create_time),
    KEY idx_iot_command_instance_status (status_code, sent_at)
) COMMENT 'IoT 运行期命令实例（下行请求与回执）';

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320022, 3200, 'IotDebugSend', 'button', 0, 'iot:debug:send', 'page.iot.debug.send', 22, NOW(), 1, 0);

INSERT INTO sys_menu (id, pid, name, type, platform_only, auth_code, title, sort, create_time, status, is_deleted)
VALUES (320023, 3200, 'IotDebugGet', 'button', 0, 'iot:debug:get', 'page.iot.debug.get', 23, NOW(), 1, 0);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320022, 320023);

INSERT INTO sys_template_menu (template_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_deleted = 0 AND id IN (320022, 320023);
