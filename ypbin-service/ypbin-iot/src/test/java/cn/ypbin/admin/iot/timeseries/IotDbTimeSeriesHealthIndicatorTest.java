/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * 健康指示器：把「已启用但连不上」暴露成 DOWN，并用缓存避免把健康检查变成对 IoTDB 的压测。
 *
 * <p>用假存储 + 可推进时钟，不依赖真库；{@code reachable()} 的调用次数即「探测频率」的断言点。</p>
 *
 * @author wenbin
 * @since 2026-10-12
 */
class IotDbTimeSeriesHealthIndicatorTest {

    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("★ reachable=true ⇒ UP；reachable=false ⇒ DOWN（配置了却连不上不再假装健康）")
    void healthMustReflectReachable() {
        assertThat(health(new FakeStore(true), new MutableClock(START)).getStatus())
            .isEqualTo(Status.UP);
        assertThat(health(new FakeStore(false), new MutableClock(START)).getStatus())
            .isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("★ 缓存：TTL 内重复 health() 只探测一次；超过 TTL 才再探测")
    void probeMustBeCachedWithinTtl() {
        FakeStore store = new FakeStore(true);
        MutableClock clock = new MutableClock(START);
        IotDbTimeSeriesHealthIndicator indicator =
            new IotDbTimeSeriesHealthIndicator(store, clock, 15_000L);

        indicator.health();
        indicator.health();
        assertThat(store.reachableCalls).as("TTL 内不得重复探测").isEqualTo(1);

        clock.advance(Duration.ofSeconds(16));
        indicator.health();
        assertThat(store.reachableCalls).as("超过 TTL 必须重新探测").isEqualTo(2);
    }

    private static Health health(TimeSeriesStore store, Clock clock) {
        return new IotDbTimeSeriesHealthIndicator(store, clock).health();
    }

    /** 假存储：只关心 reachable() 的取值与调用次数。 */
    private static final class FakeStore implements TimeSeriesStore {

        private final boolean reachable;
        private int reachableCalls;

        private FakeStore(boolean reachable) {
            this.reachable = reachable;
        }

        @Override
        public List<TimeSeriesPointResp> query(Long tenantId, Long deviceId, List<String> propertyIds,
                                               Long from, Long to, int limit) {
            return List.of();
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean reachable() {
            reachableCalls++;
            return reachable;
        }
    }

    /** 可推进时钟（测试缓存 TTL，不用 Thread.sleep）。 */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
