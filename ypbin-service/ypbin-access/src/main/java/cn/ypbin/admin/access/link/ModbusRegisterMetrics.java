/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.link;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Modbus 寄存器解码的宿主侧指标。
 *
 * <p>只有一条：{@code iot.access.modbus.pair.stale} —— 多寄存器点位暂存组**陈旧/同角色重复**被整组
 * 丢弃的次数（同一 {@code word_index} 重复到达，或组内最早到达的字距本次超过一个采集周期；
 * v3 起 N={@code word_count}，string 可达 32）。它是「配对没有静默丢失」的可观测出口；
 * 寄存器缺失/BAD 那条路径由读数 BAD 质量与 SDK 的 {@code iot.modbus.*} 指标承接，
 * **不另加指标**（见 {@code docs/VALUE-DECODE-DESIGN.md} §4.3.4/§4.5.3）。</p>
 *
 * <p>启动期预注册为 0（Micrometer 计数器惰性创建 ⇒ 不预注册时「零失败」会表现为指标 404，
 * 无法与「指标没上报」区分；与 {@code PointValueNormalizeMetrics} 同一纪律）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class ModbusRegisterMetrics {

    /** 半字配对陈旧被丢弃的次数（Counter）。 */
    public static final String METRIC_PAIR_STALE = "iot.access.modbus.pair.stale";

    private ModbusRegisterMetrics() {
    }

    /**
     * 取（并在缺失时创建）半字配对陈旧计数器。
     *
     * @param meterRegistry 指标注册表
     * @return 计数器
     */
    public static Counter pairStaleCounter(MeterRegistry meterRegistry) {
        return meterRegistry.counter(METRIC_PAIR_STALE);
    }
}
