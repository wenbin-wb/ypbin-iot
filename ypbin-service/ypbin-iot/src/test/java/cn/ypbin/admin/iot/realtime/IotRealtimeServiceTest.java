/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.starter.messaging.sse.SseEmitterManager;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时事件流的**业务语义**用例：租户维度映射、事件路由与**租户隔离**（安全性质）。
 *
 * <p><b>2026-10-15 能力上收（task-18）后的分工</b>：连接管理、心跳、断连清理、event 帧封装与跨实例扇出
 * 已由 starter 的 {@link SseEmitterManager} 承担（其行为由 starter 仓的用例覆盖）；本类钉的是
 * **IoT 业务侧仅剩的两件事**——「维度映射」（租户 → {@code tenant:<id>}、平台 → {@code platform:all}）
 * 与「事件路由」（哪条事件发给哪些 audience）。</p>
 *
 * <p><b>为什么用 mock 的 {@link SseEmitterManager} 而不是捕获真实 SSE 帧</b>：starter 的
 * {@code newEmitter()} 是包级可见（非公开 API），业务仓无法注入发射器替身；而「帧长什么样」
 * 本就属 starter 的职责边界。对 IoT 而言，**路由决定才是安全边界**：只要证明
 * 「租户 B 的 audience 从未收到租户 A 的事件」，越权就被钉死——这正是本类的核心断言。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
class IotRealtimeServiceTest {

    private static final Long TENANT_A = 11L;
    private static final Long TENANT_B = 22L;

    private static final String AUDIENCE_A = "tenant:11";
    private static final String AUDIENCE_B = "tenant:22";

    private final SseEmitterManager manager = mock(SseEmitterManager.class);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private IotRealtimeService service(TenantProvider provider) {
        return new IotRealtimeService(provider, manager);
    }

    @Test
    @DisplayName("★ 租户隔离（安全性质）：租户 A 事件**只**发给 tenant:A 与 platform:all，绝不发给 tenant:B")
    void tenantIsolationIsEnforcedAcrossAudiences() {
        IotRealtimeService service = service(() -> Optional.empty());

        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));

        verify(manager, times(1)).sendToAudience(eq(AUDIENCE_A), eq("latest"), any());
        verify(manager, times(1)).sendToAudience(eq(IotRealtimeService.AUDIENCE_PLATFORM), eq("latest"), any());
        verify(manager, never()).sendToAudience(eq(AUDIENCE_B), any(), any());
        // 关键：绝不能用 broadcast（那会把事件投给本实例上的全部连接，含其它租户用户）
        verify(manager, never()).broadcast(any(), any());
    }

    @Test
    @DisplayName("★ 双向隔离：先发 A 再发 B，各自只命中自己的租户 audience（不串台）")
    void eventsDoNotCrossTenantAudiences() {
        IotRealtimeService service = service(() -> Optional.empty());

        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));
        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_B, latest("humidity"));

        verify(manager, times(1)).sendToAudience(eq(AUDIENCE_A), eq("latest"), eq(latest("temperature")));
        verify(manager, times(1)).sendToAudience(eq(AUDIENCE_B), eq("latest"), eq(latest("humidity")));
        // A 的负载不得出现在 B 的 audience 上，反之亦然
        verify(manager, never()).sendToAudience(eq(AUDIENCE_A), any(), eq(latest("humidity")));
        verify(manager, never()).sendToAudience(eq(AUDIENCE_B), any(), eq(latest("temperature")));
    }

    @Test
    @DisplayName("★ 平台事件（tenantId=null）只发 platform:all，不下发给任何租户 audience")
    void platformOnlyEventMustNotReachTenantSubscribers() {
        IotRealtimeService service = service(() -> Optional.empty());

        service.publish(IotRealtimeService.EVENT_ALERT, null,
            new IotRealtimeService.AlertEvent(IotRealtimeService.ALERT_ACTION_ACK, List.of(9L), null, 1, null));

        verify(manager, times(1)).sendToAudience(eq(IotRealtimeService.AUDIENCE_PLATFORM), eq("alert"), any());
        verify(manager, never()).sendToAudience(eq(AUDIENCE_A), any(), any());
        verify(manager, never()).sendToAudience(eq(AUDIENCE_B), any(), any());
    }

    @Test
    @DisplayName("★ 维度键：租户 → tenant:<id>，平台 → platform:all（保留键，与租户键空间不相交）")
    void audienceKeysAreDerivedFromTenant() {
        assertThat(IotRealtimeService.audienceOf(TENANT_A)).isEqualTo(AUDIENCE_A);
        assertThat(IotRealtimeService.audienceOf(null)).isEqualTo(IotRealtimeService.AUDIENCE_PLATFORM);
        assertThat(IotRealtimeService.AUDIENCE_PLATFORM)
            .as("平台保留键必须与租户键前缀不相交：否则平台连接会收到租户事件，或租户键与平台键互相污染")
            .doesNotStartWith(IotRealtimeService.AUDIENCE_TENANT_PREFIX);
    }

    @Test
    @DisplayName("★ 订阅建连：租户用户挂 tenant:<id>，平台用户挂 platform:all")
    void subscribeBindsAudienceByTenantContext() {
        IotRealtimeService service = service(() -> Optional.empty());
        SseEmitter emitter = new SseEmitter();
        when(manager.connectAudience(any())).thenReturn(emitter);

        TenantContext.executeWithTenant(TENANT_A, service::subscribe);
        service.subscribe(); // 无租户上下文 = 平台订阅者

        verify(manager, times(1)).connectAudience(AUDIENCE_A);
        verify(manager, times(1)).connectAudience(IotRealtimeService.AUDIENCE_PLATFORM);
        verify(manager, never()).connectAudience(AUDIENCE_B);
        assertThat(TenantContext.executeWithTenant(TENANT_A, service::subscribe)).isSameAs(emitter);
    }

    @Test
    @DisplayName("★ 生产 HTTP 路径：TenantContext 空时按 TenantProvider（与 MP 插件同源）过滤，绝不误判为平台全量")
    void tenantProviderMustScopeSubscriptionWhenThreadLocalMissing() {
        // 生产请求：ThreadLocal 为空，租户来自 IdentityContext ⇒ TenantProvider 返回 A
        IotRealtimeService service = service(() -> Optional.of(TENANT_A));
        SseEmitter emitter = new SseEmitter();
        when(manager.connectAudience(any())).thenReturn(emitter);

        service.subscribe();
        verify(manager, times(1)).connectAudience(AUDIENCE_A);
        verify(manager, never()).connectAudience(IotRealtimeService.AUDIENCE_PLATFORM);
    }

    @Test
    @DisplayName("★ publishToCurrentTenant：ThreadLocal 空时按 TenantProvider 定租户，告警事件不会误伤/漏发")
    void publishToCurrentTenantMustUseProviderWhenThreadLocalMissing() {
        IotRealtimeService service = service(() -> Optional.of(TENANT_A));
        IotRealtimeService.AlertEvent alert = new IotRealtimeService.AlertEvent(
            IotRealtimeService.ALERT_ACTION_ACK, List.of(9L), null, 1, null);

        service.publishToCurrentTenant(IotRealtimeService.EVENT_ALERT, alert);

        verify(manager, times(1)).sendToAudience(eq(AUDIENCE_A), eq("alert"), eq(alert));
        verify(manager, never()).sendToAudience(eq(AUDIENCE_B), any(), any());
    }

    @Test
    @DisplayName("★ 事件名与负载原样透传（latest/device-status/alert 三个契约常量不变）")
    void eventNamesAndPayloadsArePassedThroughUnchanged() {
        IotRealtimeService service = service(() -> Optional.empty());

        IotRealtimeService.LatestEvent latest = latest("temperature");
        IotRealtimeService.DeviceStatusEvent status = new IotRealtimeService.DeviceStatusEvent(9L, 1);
        IotRealtimeService.AlertEvent alert = new IotRealtimeService.AlertEvent(
            IotRealtimeService.ALERT_ACTION_RESOLVE, null, List.of(7L), 2, null);

        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest);
        service.publish(IotRealtimeService.EVENT_DEVICE_STATUS, TENANT_A, status);
        service.publish(IotRealtimeService.EVENT_ALERT, TENANT_A, alert);

        assertThat(IotRealtimeService.EVENT_LATEST).isEqualTo("latest");
        assertThat(IotRealtimeService.EVENT_DEVICE_STATUS).isEqualTo("device-status");
        assertThat(IotRealtimeService.EVENT_ALERT).isEqualTo("alert");
        verify(manager, times(1)).sendToAudience(AUDIENCE_A, "latest", latest);
        verify(manager, times(1)).sendToAudience(AUDIENCE_A, "device-status", status);
        verify(manager, times(1)).sendToAudience(AUDIENCE_A, "alert", alert);
    }

    @Test
    @DisplayName("★ 事务提交后发布：活动事务中不立即发，提交后才发")
    void publishAfterCommitDefersUntilCommit() {
        IotRealtimeService service = service(() -> Optional.empty());
        TransactionSynchronizationManager.initSynchronization();

        service.publishAfterCommit(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));
        verifyNoInteractions(manager);

        // 模拟事务提交：Spring 会回调已注册的同步器
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.forEach(TransactionSynchronization::afterCommit);

        verify(manager, times(1)).sendToAudience(eq(AUDIENCE_A), eq("latest"), any());
    }

    @Test
    @DisplayName("★ 无活动事务时 publishAfterCommit 立即发布（与 publish 等价）")
    void publishAfterCommitWithoutTransactionPublishesImmediately() {
        IotRealtimeService service = service(() -> Optional.empty());

        service.publishAfterCommit(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));

        verify(manager, times(1)).sendToAudience(eq(AUDIENCE_A), eq("latest"), any());
    }

    @Test
    @DisplayName("★ 非法事件名/空载荷被拒绝（不得静默发出无法解析的帧）")
    void invalidEventIsRejected() {
        IotRealtimeService service = service(() -> Optional.empty());

        assertThatThrownBy(() -> service.publish(null, TENANT_A, latest("temperature")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish("  ", TENANT_A, latest("temperature")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish("a\nb", TENANT_A, latest("temperature")))
            .as("事件名含换行会伪造 SSE 帧结构").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, null))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(manager);
    }

    @Test
    @DisplayName("★ 心跳契约（能力已上收 starter）：IoT 侧不再自建 @Scheduled 心跳，仅保留 15s 配置常量")
    void heartbeatIsOwnedByStarterNotByThisClass() throws Exception {
        // 改造前 IoT 自建 @Scheduled 心跳（comment 帧 + 失败摘除）；2026-10-15 起由 starter 的
        // SseEmitterManager 按 ypbin.sse.heartbeat-interval-seconds 统一承担（避免两套心跳重复发送）。
        // 本仓保留的唯一契约是「IoT 侧配置 15 秒」——低于常见网关 60s 空闲超时。
        assertThat(IotRealtimeService.HEARTBEAT_INTERVAL_MS)
            .as("IoT 侧心跳间隔常量必须保持 15s（nacos 的 ypbin.sse.heartbeat-interval-seconds 与它一致）")
            .isEqualTo(15_000L);
        assertThat(IotRealtimeService.class.getMethod("subscribe")
            .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class))
            .as("心跳不再由本类自建（否则与 starter 的心跳重复发送）").isNull();
    }

    private static IotRealtimeService.LatestEvent latest(String propertyId) {
        return new IotRealtimeService.LatestEvent(9L, propertyId, "23.5", "GOOD", 1_700_000_000_000L);
    }
}
