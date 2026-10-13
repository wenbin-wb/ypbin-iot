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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 类型规范化失败指标的登记纪律：**启动期预注册全部原因码（值 0）**，失败路径只自增。
 *
 * <p>若不预注册，Micrometer 的惰性计数器会让「零失败」表现为指标 404（无法与「指标根本没上报」
 * 区分）——这是旧 {@code iot.access.decode.failure} 的既有纪律，本指标继承。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class PointValueNormalizeMetricsTest {

    @Test
    @DisplayName("★ registerAll：三个原因码都被预注册为 0（可区分「零失败」与「指标缺失」）")
    void registerAllMustPreCreateEveryReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PointValueNormalizeMetrics.registerAll(registry);

        for (NormalizeFailure failure : NormalizeFailure.values()) {
            assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
                .tag(PointValueNormalizeMetrics.TAG_REASON, failure.getCode()).counter().count())
                .as("原因码 %s 必须预注册为 0", failure.getCode())
                .isZero();
        }
    }

    @Test
    @DisplayName("counter：按 reason 各自累加，互不串味")
    void counterMustAccumulatePerReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 先预注册（否则未失败过的 reason 取 counter 会返回 null）
        PointValueNormalizeMetrics.registerAll(registry);
        PointValueNormalizeMetrics.counter(registry, NormalizeFailure.NOT_NUMERIC).increment();
        PointValueNormalizeMetrics.counter(registry, NormalizeFailure.NOT_NUMERIC).increment();
        PointValueNormalizeMetrics.counter(registry, NormalizeFailure.NOT_BOOLEAN).increment();

        assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "not-numeric").counter().count())
            .isEqualTo(2.0d);
        assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "not-boolean").counter().count())
            .isEqualTo(1.0d);
        assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "unknown-data-type").counter().count())
            .isZero();
    }
}
