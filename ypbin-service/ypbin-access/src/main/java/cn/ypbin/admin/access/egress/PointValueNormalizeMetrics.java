/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 宿主物模型类型规范化失败指标（{@code iot.access.value.normalize.failure}，按 {@code reason} 打标签）。
 *
 * <p><b>为什么需要它（而不是完全交给 SDK 指标）</b>：starter 0.3.0 的 {@code key-value} 只做帧内字段
 * 选择，<b>不做数据类型转换</b>（{@code TcpValueSelector} 类注释原文：类型属于宿主物模型语义）。
 * 因此「int/decimal 点收到非数值文本」「bool 点收到非法形态」「dataType 缺失/未知」这三类失败
 * <b>只能由宿主发现</b>，SDK 侧 {@code iot.tcp.selector.not-found} / {@code iot.tcp.payload.not-key-value}
 * 覆盖不到。本指标就是它们的可观测出口。</p>
 *
 * <p><b>为什么预注册</b>：Micrometer 计数器惰性创建——若把建 meter 留在失败路径里，「一条都没失败过」
 * 时 {@code /actuator/metrics/iot.access.value.normalize.failure} 会返回 404 而不是 0，值班无法区分
 * 「没有失败」与「指标根本没上报」。因此启动期按 {@link NormalizeFailure} 的全部原因码预注册（值 0）。</p>
 *
 * <p><b>与旧指标的关系</b>：{@code iot.access.decode.failure} 随帧解析过渡层一并下线；其中
 * 「帧格式/键/UTF-8」类原因由 SDK 的 {@code iot.tcp.*} 承接，本指标只承接「类型规范化」类原因。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class PointValueNormalizeMetrics {

    /** 指标名（对外契约：改名等于让既有看板与告警全部失联）。 */
    public static final String METRIC_NAME = "iot.access.value.normalize.failure";

    /** 原因标签名。 */
    public static final String TAG_REASON = "reason";

    private PointValueNormalizeMetrics() {
    }

    /**
     * 启动期预注册：为每个原因码建一个值为 0 的计数器。
     *
     * @param meterRegistry 指标注册表（幂等：同名字同标签重复注册只会返回同一个计数器）
     */
    public static void registerAll(MeterRegistry meterRegistry) {
        for (NormalizeFailure failure : NormalizeFailure.values()) {
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
    public static Counter counter(MeterRegistry meterRegistry, NormalizeFailure failure) {
        return meterRegistry.counter(METRIC_NAME, TAG_REASON, failure.getCode());
    }
}
