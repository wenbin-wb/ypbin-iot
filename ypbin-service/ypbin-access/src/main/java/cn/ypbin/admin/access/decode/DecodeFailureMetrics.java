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
 * 解码失败指标（{@code iot.access.decode.failure}，按 {@code reason} 打标签）的**登记与取值**。
 *
 * <p><b>为什么要有「预注册」这一步</b>：Micrometer 的计数器是**惰性**的——{@code registry.counter(name, …)}
 * 只在第一次调用时把 meter 建出来。若把建 meter 留在失败路径里，那么「一条都没失败过」时
 * {@code /actuator/metrics/iot.access.decode.failure} 会返回 <b>404 而不是 0</b>，值班无法区分
 * 「没有解码失败」与「这个指标根本没上报」（M-1 的入站指标也踩过同一形态的盲区）。
 * 因此启动期按 {@link DecodeFailure} 的**全部**原因码预注册一遍（值 0），失败路径只做自增。</p>
 *
 * <p>取值集合有界：标签值来自 {@link DecodeFailure#getCode()}，新增原因码只会加一条时间线。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public final class DecodeFailureMetrics {

    /** 指标名（对外契约：改名等于让既有看板与告警全部失联）。 */
    public static final String METRIC_NAME = "iot.access.decode.failure";

    /** 原因标签名。 */
    public static final String TAG_REASON = "reason";

    private DecodeFailureMetrics() {
    }

    /**
     * 启动期预注册：为每个原因码建一个值为 0 的计数器。
     *
     * @param meterRegistry 指标注册表（幂等：同名字同标签重复注册只会返回同一个计数器）
     */
    public static void registerAll(MeterRegistry meterRegistry) {
        for (DecodeFailure failure : DecodeFailure.values()) {
            counter(meterRegistry, failure);
        }
    }

    /**
     * 取（并在缺失时创建）某个原因码的计数器。
     *
     * @param meterRegistry 指标注册表
     * @param failure       失败原因
     * @return 计数器
     */
    public static Counter counter(MeterRegistry meterRegistry, DecodeFailure failure) {
        return meterRegistry.counter(METRIC_NAME, TAG_REASON, failure.getCode());
    }
}
