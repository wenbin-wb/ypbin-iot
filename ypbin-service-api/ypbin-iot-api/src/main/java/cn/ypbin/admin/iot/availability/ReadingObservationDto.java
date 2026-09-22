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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 单条「读数观察」（M-2 上报链路的最小形态）。
 *
 * <p><b>为什么只有质量与时刻</b>：M-2 的可用率口径只关心「有没有有效数据」（quality=GOOD）与它的时刻；
 * 读数**值的存储**属于数据面（IoTDB/Redis），依赖 Q8 选型，本轮不做——所以这里刻意不带值，
 * 避免发明一个马上要改的取值契约。</p>
 *
 * <p>时间用 {@link LocalDateTime}（全局序列化锁定 {@code yyyy-MM-dd HH:mm:ss}，时区 GMT+8），
 * 与平台其它接口一致；不使用 epoch 毫秒以免客户端与服务端时区歧义。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
public class ReadingObservationDto {

    /** 设备 ID（iot_device.id）。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;

    /** 设备级采集周期（毫秒，取各点位最小周期；{@code null}/0=未知，按平台兜底周期判定）。 */
    private Integer pollIntervalMs;

    /** 质量码（与协议栈 Quality 的 name 对齐：GOOD | UNCERTAIN | BAD | STALE | NOT_CONNECTED | CONFIG_ERROR）。 */
    @NotBlank(message = "质量码不能为空")
    private String quality;

    /** 读数时刻（全局格式 yyyy-MM-dd HH:mm:ss）。 */
    @NotNull(message = "读数时刻不能为空")
    private LocalDateTime ts;
}
