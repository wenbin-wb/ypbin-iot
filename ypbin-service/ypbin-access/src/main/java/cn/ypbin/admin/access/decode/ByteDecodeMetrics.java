/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 宿主字节载荷解码失败指标（{@code iot.access.payload.decode.failure}，按 {@code reason} 打标签）。
 *
 * <p><b>为什么需要它</b>：注册了解码器之后，{@code byte[]} 不再一律被出口闸门丢弃——若解码失败却没有
 * 专属指标，运维只能看到「闸门计数不再增长但也没数据」，无法区分「解码器没认领」与「认领了但解错了」。
 * 本指标把后者显式化，且 {@code reason} 取值有界（{@link DecodeFailure}）。</p>
 *
 * <p><b>为什么预注册</b>：Micrometer 计数器惰性创建——不预注册时「零失败」会让
 * {@code /actuator/metrics/iot.access.payload.decode.failure} 返回 404，值班无法区分「没有失败」与
 * 「指标缺失」。故启动期按 {@link DecodeFailure} 全部原因码注册值为 0 的计数器。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class ByteDecodeMetrics {

    /** 指标名（对外契约：改名等于让既有看板与告警失联）。 */
    public static final String METRIC_NAME = "iot.access.payload.decode.failure";

    /** 原因标签名。 */
    public static final String TAG_REASON = "reason";

    private ByteDecodeMetrics() {
    }

    /**
     * 启动期预注册：为每个原因码建一个值为 0 的计数器（幂等）。
     *
     * @param meterRegistry 指标注册表
     */
    public static void registerAll(MeterRegistry meterRegistry) {
        for (DecodeFailure failure : DecodeFailure.values()) {
            counter(meterRegistry, failure);
        }
    }

    /**
     * 取（缺失时创建）某个原因码的计数器。
     *
     * @param meterRegistry 指标注册表
     * @param failure       失败原因
     * @return 计数器
     */
    public static Counter counter(MeterRegistry meterRegistry, DecodeFailure failure) {
        return meterRegistry.counter(METRIC_NAME, TAG_REASON, failure.getCode());
    }
}
