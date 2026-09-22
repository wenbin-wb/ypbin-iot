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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 订阅规划器单测（access 侧）。
 *
 * <p>三组防线：① <b>按会话实例判等</b>（框架重连会新建会话实例且不给宿主发事件，只有实例比对才能发现
 * 「必须重订阅」，即 B2 的防线）；② <b>S5</b>：订阅成功之后才记跟踪，失败不记；③ <b>G1</b>：订阅是异步的
 * ⇒ 在途去重（慢订阅不得被下一轮重复发起）与失败退避（失败设备不得每个周期重发）。
 * 另含 N-2 依赖的 {@code devicesWithoutSession} 查询。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class AccessSubscriptionPlannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 可推进的假时钟：退避是时间相关行为，必须能推进而不是 sleep。 */
    private final MutableClock clock = new MutableClock();

    @Test
    @DisplayName("★ 会话实例变化 ⇒ 必须重订阅（B2 的防线：框架重连会新建会话且不发事件）")
    void changedSessionInstanceMustBeResubscribed() {
        DeviceSession first = subscribeReturnsFuture();
        DeviceSession reconnected = subscribeReturnsFuture();
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", first));
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.subscribe(List.of(device("d1")))).as("首次订阅").isEqualTo(1);
        assertThat(planner.subscribe(List.of(device("d1")))).as("同一会话实例不得重复订阅").isZero();

        // 模拟框架重连：会话换了实例（关闭旧会话、放入新会话）
        bound.set(Map.of("d1", reconnected));
        assertThat(planner.subscribe(List.of(device("d1")))).as("实例变化必须重订阅").isEqualTo(1);
        verify(first, times(1)).subscribe(any(SubscribeRequest.class), any());
        verify(reconnected, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ S5+G1：订阅失败不记跟踪，且**退避窗口内不再重发**（退避到期后必须重试）")
    void failedSubscribeMustNotBeTrackedAndMustBackOffBeforeRetry() {
        DeviceSession session = mock(DeviceSession.class);
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("协议侧拒绝订阅"));
        when(session.subscribe(any(SubscribeRequest.class), any())).thenReturn(failed);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccessSubscriptionPlanner planner = planner(bound, meterRegistry);

        assertThat(planner.subscribe(List.of(device("d1")))).as("本次发起了 1 次订阅").isEqualTo(1);
        assertThat(planner.trackedSessionCount())
            .as("订阅失败绝不能写跟踪表——写了就会被对账认为「已订阅」而永不重试")
            .isZero();

        // G1：失败后进入退避，退避窗口内不得再打远端（否则失败设备每 10~15s 重发一次）
        assertThat(planner.subscribe(List.of(device("d1")))).as("退避窗口内不得重发").isZero();
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
        assertThat(meterRegistry.get("iot.access.subscribe.backoff.skipped").counter().count())
            .isEqualTo(1.0d);

        // 退避到期 ⇒ 必须重试（自愈不能被退避破坏）
        clock.advance(AccessSubscriptionPlanner.SUBSCRIBE_BACKOFF_BASE.plusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1")))).as("退避到期必须重试").isEqualTo(1);
        verify(session, times(2)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ G1：在途订阅（异步未完成）不得被下一轮重复发起")
    void inFlightSubscribeMustNotBeDuplicated() {
        DeviceSession session = mock(DeviceSession.class);
        CompletableFuture<SubscriptionHandle> pending = new CompletableFuture<>();
        when(session.subscribe(any(SubscribeRequest.class), any())).thenReturn(pending);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccessSubscriptionPlanner planner = planner(bound, meterRegistry);

        assertThat(planner.subscribe(List.of(device("d1")))).isEqualTo(1);
        // 慢订阅：下一轮对账在它完成前又来了（每个租约周期都会调）⇒ 必须跳过
        assertThat(planner.subscribe(List.of(device("d1")))).as("在途期间不得重复订阅").isZero();
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
        assertThat(meterRegistry.get("iot.access.subscribe.inflight.skipped").counter().count())
            .isEqualTo(1.0d);

        // 完成后：记为已订阅，且后续轮次不再重复
        pending.complete(null);
        assertThat(planner.trackedSessionCount()).as("完成后必须记上跟踪").isEqualTo(1);
        assertThat(planner.subscribe(List.of(device("d1")))).isZero();
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ G1：失败退避指数增长、成功后清除（也不得把「换会话」挡在退避外）")
    void failureBackoffMustGrowAndResetOnSuccess() {
        DeviceSession failing = mock(DeviceSession.class);
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        when(failing.subscribe(any(SubscribeRequest.class), any())).thenReturn(failed);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", failing));
        AccessSubscriptionPlanner planner = planner(bound);

        planner.subscribe(List.of(device("d1")));
        // 第 1 次失败 ⇒ 退避 30s
        clock.advance(Duration.ofSeconds(31));
        planner.subscribe(List.of(device("d1")));
        // 第 2 次失败 ⇒ 退避 60s：只推进 59s 不得重试
        clock.advance(Duration.ofSeconds(59));
        assertThat(planner.subscribe(List.of(device("d1")))).as("退避已翻倍 ⇒ 59s 还不够").isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(planner.subscribe(List.of(device("d1")))).as("61s 后必须重试").isEqualTo(1);
        verify(failing, times(3)).subscribe(any(SubscribeRequest.class), any());

        // 换会话并成功 ⇒ 退避清除：再做一轮不带退避的新会话订阅必须立刻发起
        DeviceSession healthy = subscribeReturnsFuture();
        bound.set(Map.of("d1", healthy));
        assertThat(planner.subscribe(List.of(device("d1")))).as("新会话实例必须重订阅").isEqualTo(1);
        verify(healthy, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("forget 必须清掉失败退避（设备被移除后重新出现要能立刻订阅）")
    void forgetMustClearFailureBackoff() {
        DeviceSession failing = mock(DeviceSession.class);
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        when(failing.subscribe(any(SubscribeRequest.class), any())).thenReturn(failed);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", failing));
        AccessSubscriptionPlanner planner = planner(bound);

        planner.subscribe(List.of(device("d1")));
        planner.forget("d1");

        DeviceSession healthy = subscribeReturnsFuture();
        bound.set(Map.of("d1", healthy));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("forget 后不得还吃上一次的退避").isEqualTo(1);
        verify(healthy, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ 同步抛出不得把设备永久锁死（在途标记必须清掉）")
    void synchronousThrowMustNotLockDeviceForever() {
        DeviceSession flaky = mock(DeviceSession.class);
        // 第一次同步抛出、第二次成功：若「在途」标记没被清掉，第二次会一直被挡 ⇒ 设备永久无法订阅
        when(flaky.subscribe(any(SubscribeRequest.class), any()))
            .thenThrow(new IllegalStateException("适配器同步抛出"))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", flaky));
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("同步抛出视为发起失败").isZero();
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("紧接着的一轮在退避窗口内，跳过").isZero();

        // 退避到期后必须能再次尝试并成功：若在途标记没被清掉，这里会返回 0（永久无法订阅）
        clock.advance(AccessSubscriptionPlanner.SUBSCRIBE_BACKOFF_BASE.plusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("在途标记必须已清除，否则设备永久无法订阅").isEqualTo(1);
        verify(flaky, times(2)).subscribe(any(SubscribeRequest.class), any());
        assertThat(planner.trackedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ forget 必须同时清掉在途标记（同 id 复现不得被旧在途挡住）")
    void forgetMustClearInFlight() {
        DeviceSession pendingSession = mock(DeviceSession.class);
        when(pendingSession.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(new CompletableFuture<>());
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", pendingSession));
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.subscribe(List.of(device("d1")))).isEqualTo(1);
        planner.forget("d1");

        DeviceSession healthy = subscribeReturnsFuture();
        bound.set(Map.of("d1", healthy));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("forget 后不得还被旧的在途标记挡住").isEqualTo(1);
    }

    @Test
    @DisplayName("★ G1：失败退避必须封顶（3 次失败后恒为 2 分钟，不得无限翻倍）")
    void failureBackoffMustCapAtConfiguredMax() {
        DeviceSession failing = mock(DeviceSession.class);
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));
        when(failing.subscribe(any(SubscribeRequest.class), any())).thenReturn(failed);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", failing));
        AccessSubscriptionPlanner planner = planner(bound);

        planner.subscribe(List.of(device("d1")));                    // 失败 1 ⇒ 30s
        clock.advance(Duration.ofSeconds(31));
        planner.subscribe(List.of(device("d1")));                    // 失败 2 ⇒ 60s
        clock.advance(Duration.ofSeconds(61));
        planner.subscribe(List.of(device("d1")));                    // 失败 3 ⇒ 封顶 120s

        clock.advance(AccessSubscriptionPlanner.SUBSCRIBE_BACKOFF_MAX.minusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("封顶后只差 1 秒不得重试（若封顶被去掉会退化成 240s，这里同样为 0——故下一条才是关键）")
            .isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("封顶恰为 120s：超过即必须重试").isEqualTo(1);
    }

    @Test
    @DisplayName("★ N-2：devicesWithoutSession 必须报出没有会话的设备（宿主据此重发 ADD）")
    void devicesWithoutSessionMustReportMissing() {
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", subscribeReturnsFuture()));
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.devicesWithoutSession(List.of(device("d1"), device("d2"))))
            .as("d1 有会话、d2 没有").containsExactly("d2");
        assertThat(planner.devicesWithoutSession(List.of()))
            .as("空清单短路，绝不返回 null").isEmpty();
    }

    @Test
    @DisplayName("无会话时跳过且不记录（下一轮对账要能补上）；点位为空时不订阅")
    void missingSessionAndEmptyPointsShouldBeSkipped() {
        AtomicReference<Map<String, DeviceSession>> bound = new AtomicReference<>(Map.of());
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.subscribe(List.of(device("d1")))).as("无会话⇒跳过").isZero();
        assertThat(planner.trackedSessionCount()).as("跳过时不得留下跟踪记录").isZero();

        DeviceSession session = subscribeReturnsFuture();
        bound.set(Map.of("d1", session));
        assertThat(planner.subscribe(List.of(deviceWithoutPoints("d1")))).as("无点位⇒不订阅").isZero();
        verify(session, times(0)).subscribe(any(SubscribeRequest.class), any());
    }

    private AccessSubscriptionPlanner planner(AtomicReference<Map<String, DeviceSession>> bound) {
        return planner(bound, new SimpleMeterRegistry());
    }

    private AccessSubscriptionPlanner planner(AtomicReference<Map<String, DeviceSession>> bound,
                                              SimpleMeterRegistry meterRegistry) {
        return new AccessSubscriptionPlanner(bound::get, objectMapper, reading -> { }, meterRegistry, clock);
    }

    private static DeviceSession subscribeReturnsFuture() {
        DeviceSession session = mock(DeviceSession.class);
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        return session;
    }

    private static DeviceSpec device(String deviceId) {
        return spec(deviceId, "[{\"propertyId\":\"900\",\"address\":\"holding:1\"}]");
    }

    private static DeviceSpec deviceWithoutPoints(String deviceId) {
        return spec(deviceId, "[]");
    }

    private static DeviceSpec spec(String deviceId, String pointsJson) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("modbus"), "t11-d" + deviceId, "",
            Duration.ofSeconds(5), Map.of(HttpDeviceSpecSource.PROPERTY_POINTS, pointsJson));
    }

    /** 可推进时钟（仅用于退避窗口）。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-22T06:00:00Z");

        void advance(Duration duration) {
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
