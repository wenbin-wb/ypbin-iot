/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.ypbin.admin.iot.access.metrics.AccessMetricsSnapshotResp;
import cn.ypbin.admin.iot.access.metrics.IAccessMetricsClient;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import org.mockito.ArgumentCaptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AccessMetricsReporter} 的单测。
 *
 * <p>重点验三件事：① 推送的**数值**来自真实 registry（含 Timer 的 max/Gauge 的当前值）；
 * ② **指标不存在 ⇒ `null`**（不能拿 0 冒充「观测到了」，否则平台侧会把「没数据」读成「很快」）；
 * ③ 推送失败**计数 + 不向上抛**（推送链路断不该把调度线程带崩）。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
class AccessMetricsReporterTest {

    private SimpleMeterRegistry meterRegistry;
    private IAccessMetricsClient client;
    private AccessMetricsReporter reporter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        client = mock(IAccessMetricsClient.class);
        reporter = new AccessMetricsReporter(client, meterRegistry);
    }

    @Test
    @DisplayName("推送内容取自真实 registry：计数取当前值、Timer 取 max、Gauge 取当前值")
    void snapshotMustReadRealMeters() {
        Counter.builder("iot.access.config.reconcile.deferred").register(meterRegistry).increment(3);
        Timer.builder("iot.access.lease.tick.duration").register(meterRegistry)
            .record(Duration.ofMillis(2_100));
        Gauge.builder("iot.access.egress.pending", () -> 7).register(meterRegistry);

        reporter.report();

        var captor = ArgumentCaptor.forClass(AccessMetricsSnapshotResp.class);
        verify(client).report(captor.capture());
        AccessMetricsSnapshotResp sent = captor.getValue();
        assertThat(sent.reconcileDeferredCount()).isEqualTo(3L);
        assertThat(sent.tickDurationMaxMs()).isEqualTo(2_100L);
        assertThat(sent.egressPendingCount()).isEqualTo(7L);
        assertThat(sent.reportedAt()).isNotNull();
        // 本轮没注册的指标必须原样为 null（不是 0）
        assertThat(sent.selfFencedCount()).isNull();
        assertThat(sent.specLoadDurationMaxMs()).isNull();
    }

    @Test
    @DisplayName("指标缺失 ⇒ null（禁止用 0 冒充「观测到了」）")
    void missingMetersMustBeNull() {
        reporter.report();

        var captor = ArgumentCaptor.forClass(AccessMetricsSnapshotResp.class);
        verify(client).report(captor.capture());
        assertThat(captor.getValue().reconcileDeferredCount()).isNull();
        assertThat(captor.getValue().tickDurationMaxMs()).isNull();
        assertThat(captor.getValue().egressPendingCount()).isNull();
    }

    @Test
    @DisplayName("推送失败：计数 + 不向上抛（不把调度线程带崩）")
    void pushFailureMustBeCountedAndNotPropagate() {
        doThrow(new RuntimeException("iot 不可达")).when(client).report(any());

        reporter.report();   // 不得向上抛（否则会把调度线程带崩）
        assertThat(meterRegistry.get("iot.access.metrics.report.failure").counter().count())
            .isEqualTo(1.0d);
    }
}
