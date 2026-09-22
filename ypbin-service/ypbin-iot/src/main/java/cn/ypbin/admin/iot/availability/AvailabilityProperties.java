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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 断档与可用率参数（口径的**可调部分**；口径本身见 {@link AvailabilityRules}）。
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AvailabilityProperties.PREFIX)
public class AvailabilityProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.availability";

    /** 是否启用断档扫描（关闭时内部上报端点仍可用，只是不再自动开断档）。 */
    private boolean enabled = true;

    /** 断档判定倍数 K：连续 &gt; K × 采集周期 无有效数据即判为断档（spec §12.5 默认 2）。 */
    private int kFactor = 2;

    /** 采集周期未知（未上报/为 0）时的兜底周期（毫秒）。 */
    private long fallbackIntervalMs = 5_000L;

    /** 断档扫描周期（毫秒）。 */
    private long scanIntervalMs = 15_000L;

    /** 单次扫描最多处理的候选设备数（防一次扫描把库与线程拖住；下轮继续）。 */
    private int scanBatchSize = 200;

    /** 查询未给 {@code from} 时的默认窗口时长（小时）。 */
    private long defaultWindowHours = 24L;
}
