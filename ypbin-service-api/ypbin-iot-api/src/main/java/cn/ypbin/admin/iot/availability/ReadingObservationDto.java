/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 单条「读数观察」（M-2 上报链路的最小形态）。
 *
 * <p><b>为什么只有质量与时刻</b>：M-2 的可用率口径只关心「有没有有效数据」（quality=GOOD）与它的时刻；
 * 读数**值的存储**属于数据面（IoTDB/Redis），依赖 Q8 选型，本轮不做——所以这里刻意不带值，
 * 避免发明一个马上要改的取值契约。</p>
 *
 * <p><b>时间用 epoch 毫秒</b>（而非 {@code LocalDateTime} 字符串）：这是**跨服务**的内部上报，
 * 两端各自的 Jackson 时区/格式配置一旦不一致就会出现「同一时刻被解析成不同瞬间」或直接解析失败；
 * epoch 毫秒没有这个耦合面（access 侧协议栈给的本就是 {@code Instant}），服务端再用平台固定时区
 * 落成 {@code LocalDateTime}。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
public class ReadingObservationDto {

    /** 读数值的最大长度（防单条超大值灌进最新值存储与日志；协议原值字符串化后不应超过这个量级）。 */
    public static final int MAX_VALUE_LENGTH = 4096;

    /** 设备 ID（iot_device.id）。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;

    /**
     * 设备级采集周期（毫秒；{@code null}/0=未知，按平台兜底周期判定）。
     *
     * <p>当前由 access 侧按设备（= 各点位最小周期）统一填同一个值；服务端聚合同一批时取
     * **最后一个非空值**——一旦上游改成逐点周期上报，这里会静默变成「最后一条的周期」，
     * 需要同步改成显式语义（例如取最小）。</p>
     */
    private Integer pollIntervalMs;

    /**
     * 点位标识（协议的 propertyId/pointId；可为空——空表示这条上报只用于可用率判定，不带值）。
     *
     * <p>与 {@link #value} 成对出现：两者都非空时，服务端会把它写进**最新值**存储（Redis）；
     * 只给时刻+质量同样合法（老客户端/只做断档判定的采集器）。</p>
     */
    private String propertyId;

    /**
     * 读数**值**（字符串化的原值；类型由物模型属性定义，服务端不做隐式转换）。
     *
     * <p>为什么用字符串：协议栈的值类型多样（数值/布尔/字符串/字节数组），在上报契约里做窄化会丢信息；
     * 存储层按物模型定义解析。空值合法（表示该点本次无值）。</p>
     */
    @Size(max = ReadingObservationDto.MAX_VALUE_LENGTH,
        message = "读数值过长（超过 " + ReadingObservationDto.MAX_VALUE_LENGTH + " 字符）")
    private String value;

    /** 质量码（与协议栈 Quality 的 name 对齐：GOOD | UNCERTAIN | BAD | STALE | NOT_CONNECTED | CONFIG_ERROR）。 */
    @NotBlank(message = "质量码不能为空")
    private String quality;

    /** 读数时刻（epoch 毫秒）。 */
    @NotNull(message = "读数时刻不能为空")
    private Long ts;
}
