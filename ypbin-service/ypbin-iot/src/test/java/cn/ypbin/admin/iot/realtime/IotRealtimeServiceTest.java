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

import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时事件流纯单测（不起 Spring、不启真实 HTTP）：租户过滤、事件帧格式、心跳与断连清理。
 *
 * <p>测试用 {@link TestRealtimeService} 覆写发射器工厂，{@link CapturingEmitter} 捕获
 * {@link SseEmitter#send(SseEmitter.SseEventBuilder)} 的构建器；不依赖容器生命周期。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
class IotRealtimeServiceTest {

    private static final Long TENANT_A = 11L;
    private static final Long TENANT_B = 22L;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("★ 订阅快照租户：publish 只推同租户；平台订阅（无租户上下文）收全量")
    void publishMustOnlyReachSameTenantAndPlatformSubscribers() {
        TestRealtimeService service = new TestRealtimeService(() -> Optional.empty());
        CapturingEmitter tenantA = service.newEmitter();
        CapturingEmitter tenantB = service.newEmitter();
        CapturingEmitter platform = service.newEmitter();

        TenantContext.executeWithTenant(TENANT_A, service::subscribe);
        TenantContext.executeWithTenant(TENANT_B, service::subscribe);
        service.subscribe(); // 无租户上下文 = 平台订阅者

        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));
        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_B, latest("humidity"));

        assertThat(tenantA.events).as("A 租户只收 A 的事件").hasSize(1);
        assertThat(tenantB.events).as("B 租户只收 B 的事件").hasSize(1);
        assertThat(platform.events).as("平台订阅者收全量").hasSize(2);
        assertThat(service.subscriptionCount(TENANT_A)).isEqualTo(1);
        assertThat(service.subscriptionCount(null)).as("平台订阅者单独计数").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 生产 HTTP 路径：TenantContext 空时按 TenantProvider（与 MP 插件同源）过滤，绝不误判为平台全量")
    void tenantProviderMustScopeSubscriptionWhenThreadLocalMissing() {
        // 生产请求：ThreadLocal 为空，租户来自 IdentityContext ⇒ TenantProvider 返回 A
        TestRealtimeService service = new TestRealtimeService(() -> Optional.of(TENANT_A));
        CapturingEmitter fromProvider = service.newEmitter();
        service.subscribe();
        // 显式绑定（后台任务/单测）优先于 TenantProvider：这里订 B，不该收 A
        CapturingEmitter fromThreadLocal = service.newEmitter();
        TenantContext.executeWithTenant(TENANT_B, service::subscribe);

        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));
        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_B, latest("humidity"));

        assertThat(fromProvider.events).as("Provider 提供的 A 租户只收 A 事件").hasSize(1);
        assertThat(fromThreadLocal.events).as("TenantContext 显式绑定优先，B 租户只收 B 事件").hasSize(1);
    }

    @Test
    @DisplayName("★ publishToCurrentTenant：ThreadLocal 空时按 TenantProvider 定租户，告警事件不会误伤/漏发")
    void publishToCurrentTenantMustUseProviderWhenThreadLocalMissing() {
        TestRealtimeService service = new TestRealtimeService(() -> Optional.of(TENANT_A));
        CapturingEmitter tenantA = service.newEmitter();
        CapturingEmitter tenantB = service.newEmitter();
        TenantContext.executeWithTenant(TENANT_A, service::subscribe);
        TenantContext.executeWithTenant(TENANT_B, service::subscribe);

        IotRealtimeService.AlertEvent alert = new IotRealtimeService.AlertEvent(
            IotRealtimeService.ALERT_ACTION_ACK, List.of(9L), null, 1, null);
        service.publishToCurrentTenant(IotRealtimeService.EVENT_ALERT, alert);

        assertThat(tenantA.events).as("Provider 租户 A 收到本次发布").hasSize(1);
        assertThat(tenantB.events).as("租户 B 不得收到 A 的事件").isEmpty();
    }

    @Test
    @DisplayName("★ 事件格式：event:<type> 前缀 + data 负载对象；事件名用 latest/device-status/alert 常量")
    void eventFrameMustCarryEventNameAndPayload() {
        TestRealtimeService service = new TestRealtimeService(() -> Optional.empty());
        CapturingEmitter emitter = service.newEmitter();
        TenantContext.executeWithTenant(TENANT_A, service::subscribe);

        IotRealtimeService.LatestEvent payload = latest("temperature");
        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, payload);

        SseEmitter.SseEventBuilder frame = emitter.events.getFirst();
        assertThat(frameText(frame)).as("SSE 事件名帧").contains("event:latest").contains("data:");
        assertThat(framePayloads(frame)).as("data 必须是原始负载对象，由 MVC 的 Jackson 转换器序列化")
            .containsExactly(payload);
        assertThat(IotRealtimeService.EVENT_LATEST).isEqualTo("latest");
        assertThat(IotRealtimeService.EVENT_DEVICE_STATUS).isEqualTo("device-status");
        assertThat(IotRealtimeService.EVENT_ALERT).isEqualTo("alert");
    }

    @Test
    @DisplayName("★ 断连清理：完成/错误回调摘除订阅，之后 publish 不再发送")
    void disconnectCallbackMustRemoveSubscription() {
        TestRealtimeService service = new TestRealtimeService(() -> Optional.empty());
        CapturingEmitter completed = service.newEmitter();
        TenantContext.executeWithTenant(TENANT_A, service::subscribe);

        completed.completion.run();

        assertThat(service.subscriptionCount(TENANT_A)).isZero();
        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));
        assertThat(completed.events).as("已断连的订阅不得再收到事件").isEmpty();

        CapturingEmitter errored = service.newEmitter();
        TenantContext.executeWithTenant(TENANT_A, service::subscribe);
        errored.error.accept(new IOException("connection reset"));

        assertThat(service.subscriptionCount(TENANT_A)).isZero();
        service.publish(IotRealtimeService.EVENT_LATEST, TENANT_A, latest("temperature"));
        assertThat(errored.events).isEmpty();
    }

    @Test
    @DisplayName("★ 心跳：固定 15s 发 comment 帧；发送失败视为断连并清理，不影响健康订阅")
    void heartbeatMustSendCommentAndCleanBrokenSubscriptions() throws Exception {
        TestRealtimeService service = new TestRealtimeService(() -> Optional.empty());
        CapturingEmitter healthy = service.newEmitter();
        CapturingEmitter broken = service.newEmitter();
        TenantContext.executeWithTenant(TENANT_A, service::subscribe);
        TenantContext.executeWithTenant(TENANT_A, service::subscribe);

        broken.failNextSend = true;
        service.heartbeat();

        assertThat(frameText(healthy.events.getFirst())).as("心跳是 comment 帧，不触发客户端 onmessage")
            .contains(":heartbeat");
        assertThat(service.subscriptionCount()).as("发送失败即断连，必须摘除").isEqualTo(1);

        Scheduled scheduled = IotRealtimeService.class.getMethod("heartbeat").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).as("心跳间隔必须是 15s（低于常见网关空闲超时）")
            .isEqualTo(15_000L)
            .isEqualTo(IotRealtimeService.HEARTBEAT_INTERVAL_MS);
    }

    /** 数据帧中的文本片段（event:/data: 前缀与 comment 都通过它断言）。 */
    private static String frameText(SseEmitter.SseEventBuilder frame) {
        return frame.build().stream()
            .map(ResponseBodyEmitter.DataWithMediaType::getData)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.joining());
    }

    /** 数据帧中的非字符串负载对象（Jackson 序列化前的原始对象）。 */
    private static List<Object> framePayloads(SseEmitter.SseEventBuilder frame) {
        return frame.build().stream()
            .map(ResponseBodyEmitter.DataWithMediaType::getData)
            .filter(data -> !(data instanceof String))
            .toList();
    }

    private static IotRealtimeService.LatestEvent latest(String propertyId) {
        return new IotRealtimeService.LatestEvent(9L, propertyId, "23.5", "GOOD", 1_700_000_000_000L);
    }

    /** 覆写发射器工厂的测试替身：调用方先用 newEmitter() 排队，再 subscribe() 取用。 */
    private static final class TestRealtimeService extends IotRealtimeService {

        private final Deque<CapturingEmitter> pending = new ArrayDeque<>();

        TestRealtimeService(TenantProvider tenantProvider) {
            super(tenantProvider);
        }

        CapturingEmitter newEmitter() {
            CapturingEmitter emitter = new CapturingEmitter();
            pending.addLast(emitter);
            return emitter;
        }

        @Override
        SseEmitter createEmitter() {
            return Objects.requireNonNull(pending.pollFirst(), "测试未预置发射器");
        }
    }

    /** 捕获发送帧并暴露生命周期回调的 SseEmitter 替身。 */
    private static final class CapturingEmitter extends SseEmitter {

        private final List<SseEmitter.SseEventBuilder> events = new ArrayList<>();

        private Runnable completion = () -> {
        };

        private Consumer<Throwable> error = ex -> {
        };

        private boolean failNextSend;

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            if (failNextSend) {
                failNextSend = false;
                throw new IOException("broken pipe");
            }
            events.add(builder);
        }

        @Override
        public void onCompletion(Runnable callback) {
            this.completion = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.error = callback;
        }
    }
}
