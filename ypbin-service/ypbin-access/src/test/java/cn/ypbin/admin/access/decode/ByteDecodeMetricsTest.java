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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.spi.DecodeFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 解码失败指标单测：启动期把全部原因码预注册为 0（「零失败」与「指标缺失」必须可区分）。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ByteDecodeMetricsTest {

    @Test
    @DisplayName("预注册全部失败原因；计数按 type（消息键）打标签")
    void preRegistersAllReasons() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ByteDecodeMetrics.registerAll(registry);

        for (DecodeFailure failure : DecodeFailure.values()) {
            assertThat(registry.get(ByteDecodeMetrics.METRIC_NAME)
                .tag(ByteDecodeMetrics.TAG_TYPE, failure.messageKey()).counter().count())
                .as("type=%s 必须预注册为 0", failure.messageKey()).isZero();
        }

        ByteDecodeMetrics.counter(registry, DecodeFailure.NOT_FINITE).increment();
        assertThat(registry.get(ByteDecodeMetrics.METRIC_NAME)
            .tag(ByteDecodeMetrics.TAG_TYPE, "iot.common.payload.decode-failed.not-finite")
            .counter().count()).isEqualTo(1.0d);
        // 幂等：重复预注册不会叠加
        ByteDecodeMetrics.registerAll(registry);
        assertThat(registry.get(ByteDecodeMetrics.METRIC_NAME)
            .tag(ByteDecodeMetrics.TAG_TYPE, "iot.common.payload.decode-failed.length-mismatch")
            .counter().count()).isZero();
    }
}
