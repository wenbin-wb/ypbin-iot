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
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅规划器单测（第二轮外委复核要求补齐：B2 的修复此前**零回归防线**）。
 *
 * <p>核心防线是「按**会话实例**判等」：框架重连会新建会话实例（且不给宿主发任何事件），
 * 只有实例比对才能发现「必须重订阅」。因此这里必须有一条用例专门造出**新实例**并断言重订阅——
 * 否则把判等改回「按 deviceId 只订阅一次」（即 B2 复发）整套测试仍会全绿。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class AccessSubscriptionPlannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("★ 会话实例变化 ⇒ 必须重订阅（B2 的防线：框架重连会新建会话且不发事件）")
    void changedSessionInstanceMustBeResubscribed() {
        DeviceSession first = subscribeReturnsFuture();
        DeviceSession reconnected = subscribeReturnsFuture();
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", first));
        AccessSubscriptionPlanner planner =
            new AccessSubscriptionPlanner(bound::get, objectMapper, reading -> { });

        assertThat(planner.subscribe(List.of(device("d1")))).as("首次订阅").isEqualTo(1);
        assertThat(planner.subscribe(List.of(device("d1")))).as("同一会话实例不得重复订阅").isZero();

        // 模拟框架重连：会话换了实例（关闭旧会话、放入新会话）
        bound.set(Map.of("d1", reconnected));
        assertThat(planner.subscribe(List.of(device("d1")))).as("实例变化必须重订阅").isEqualTo(1);
        verify(first, times(1)).subscribe(any(SubscribeRequest.class), any());
        verify(reconnected, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("无会话时跳过且不记录（下一轮对账要能补上）；点位为空时不订阅")
    void missingSessionAndEmptyPointsShouldBeSkipped() {
        AtomicReference<Map<String, DeviceSession>> bound = new AtomicReference<>(Map.of());
        AccessSubscriptionPlanner planner =
            new AccessSubscriptionPlanner(bound::get, objectMapper, reading -> { });

        assertThat(planner.subscribe(List.of(device("d1")))).as("无会话⇒跳过").isZero();
        assertThat(planner.trackedSessionCount()).as("跳过时不得留下跟踪记录").isZero();

        DeviceSession session = subscribeReturnsFuture();
        bound.set(Map.of("d1", session));
        assertThat(planner.subscribe(List.of(deviceWithoutPoints("d1")))).as("无点位⇒不订阅").isZero();
        verify(session, times(0)).subscribe(any(SubscribeRequest.class), any());
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
}
