/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;

/**
 * 单条「运行期事件」上报项（access → iot 内部接口，G6）。
 *
 * <p><b>为什么带 {@code idempotentKey}</b>：内部上报链路会重试（网络抖动、access 重启后重放队列），
 * 「同一条事件投两次」在没有幂等键时无法与服务端区分。幂等键由上报方生成并保证「同一事件稳定复现」，
 * 服务端按 {@code (租户, 设备, 幂等键)} 去重。</p>
 *
 * <p><b>为什么时刻是 epoch 毫秒</b>：与读数上报同一理由——跨服务内部调用两端 Jackson 时区/格式配置
 * 一旦不一致就会出现「同一时刻解析成不同瞬间」（见 {@code ReadingObservationDto}）；服务端用平台
 * 固定时区落成 {@code LocalDateTime}。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Getter
@Setter
public class EventIngestItemDto {

    /** 事件参数的最大长度（与 DDL 的 {@code params TEXT} 同量级的有界上限，防单条超大 JSON 灌库与日志）。 */
    public static final int MAX_PARAMS_LENGTH = 8192;

    /** 事件标识的最大长度（与 DDL 的 {@code event_code VARCHAR(64)} 对齐）。 */
    public static final int MAX_EVENT_CODE_LENGTH = 64;

    /** 事件名称的最大长度（与 DDL 的 {@code event_name VARCHAR(128)} 对齐）。 */
    public static final int MAX_EVENT_NAME_LENGTH = 128;

    /** 幂等键的最大长度（与 DDL 的 {@code idempotent_key VARCHAR(128)} 对齐）。 */
    public static final int MAX_IDEMPOTENT_KEY_LENGTH = 128;

    /** 设备 ID（iot_device.id）。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;

    /** 事件标识（对应物模型 {@code iot_event.identifier}；无对应定义时是上报方自定码）。 */
    @NotBlank(message = "事件标识不能为空")
    @Size(max = MAX_EVENT_CODE_LENGTH, message = "事件标识过长（超过 " + MAX_EVENT_CODE_LENGTH + " 字符）")
    private String eventCode;

    /** 事件名称（可选）。 */
    @Size(max = MAX_EVENT_NAME_LENGTH, message = "事件名称过长（超过 " + MAX_EVENT_NAME_LENGTH + " 字符）")
    private String eventName;

    /**
     * 事件级别码：{@code info} | {@code warn} | {@code error}（大小写不敏感，服务端规范化后落库）。
     *
     * <p>必填：级别决定详情页时间线的视觉与过滤口径，缺省值由上报方显式给出比服务端猜更诚实。</p>
     */
    @NotBlank(message = "事件级别不能为空")
    private String level;

    /** 事件参数（JSON 文本，可选；服务端只做长度校验，不解析结构）。 */
    @Size(max = MAX_PARAMS_LENGTH, message = "事件参数过长（超过 " + MAX_PARAMS_LENGTH + " 字符）")
    private String params;

    /** 事件发生时刻（epoch 毫秒）。 */
    @NotNull(message = "事件发生时刻不能为空")
    private Long ts;

    /** 幂等键（同租户同设备内唯一；重复投递靠它去重）。 */
    @NotBlank(message = "幂等键不能为空")
    @Size(max = MAX_IDEMPOTENT_KEY_LENGTH,
        message = "幂等键过长（超过 " + MAX_IDEMPOTENT_KEY_LENGTH + " 字符）")
    private String idempotentKey;

    /**
     * 规范化后的级别码（去空白 + 小写）。
     *
     * @return 规范化级别码；输入为空白时返回 {@code null}
     */
    public String normalizedLevel() {
        return level == null ? null : level.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化后的幂等键（去首尾空白）。
     *
     * @return 规范化幂等键；输入为 {@code null} 时返回 {@code null}
     */
    public String normalizedIdempotentKey() {
        return idempotentKey == null ? null : idempotentKey.trim();
    }
}
