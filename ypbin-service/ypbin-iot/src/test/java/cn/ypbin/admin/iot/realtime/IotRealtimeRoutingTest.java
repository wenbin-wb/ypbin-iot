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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.starter.messaging.sse.SseEmitterManager;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * IoT SSE **发布路由**的用例：租户事件定向到 {@code tenant:<id> + platform:all}、平台事件只到平台键、
 * 全程**绝不使用 broadcast**。
 *
 * <p><b>为什么这是 IoT 的语义而不是 starter 的</b>：starter 只提供「把消息投给某个 audience」这一通用原语
 * （其自身行为由 starter 仓的用例覆盖）；「哪些事件发给哪些 audience」是 IoT 的业务决定，尤其
 * {@code platform:all} 这个平台保留键与「租户事件额外送一份给平台」的口径，完全属于本仓 —— 故必须留在
 * IoT 仓并由本类守住。</p>
 *
 * <p><b>原文为独立验收探针（verify-backend / task-20），经 Lead 决策转为正式用例</b>并保留全部断言力
 * （断言未做任何简化）。它用 Mockito 观测 {@link SseEmitterManager#sendToAudience} 的 audience 实参
 * 与 {@link SseEmitterManager#broadcast} 的调用情况——证明发布路径用定向键、绝不用 broadcast，
 * 且平台事件只到平台键。</p>
 *
 * <p><b>为什么{@code never().broadcast()} 是安全性质而非风格偏好</b>：starter 的 {@code broadcast()}
 * 语义是「投给**本实例上全部连接**」，其中包含所有租户用户 ⇒ 用它发平台事件会让租户 A 的用户收到
 * 租户 B 的事件，即越权泄漏。</p>
 *
 * @author verify-backend（原验收探针）→ wenbin（转正）
 * @since 2026-10-15
 */
class IotRealtimeRoutingTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("★★ 租户事件 => 恰好 tenant:<id> + platform:all 两次定向；绝无 broadcast")
    void tenantEventRoutesToTenantAndPlatform() {
        SseEmitterManager manager = mock(SseEmitterManager.class);
        IotRealtimeService service = new IotRealtimeService(() -> Optional.empty(), manager);

        service.publish("latest", 1L, new IotRealtimeService.LatestEvent(7L, "t", "35", "GOOD", 1L));

        verify(manager).sendToAudience(eq("tenant:1"), eq("latest"), any());
        verify(manager).sendToAudience(eq("platform:all"), eq("latest"), any());
        verify(manager, times(2)).sendToAudience(any(), any(), any());
        verify(manager, never()).broadcast(any(), any());
    }

    @Test
    @DisplayName("★★ 平台事件（tenantId=null）=> 只到 platform:all 一次；绝不进任何 tenant: 键")
    void platformEventRoutesOnlyToPlatform() {
        SseEmitterManager manager = mock(SseEmitterManager.class);
        IotRealtimeService service = new IotRealtimeService(() -> Optional.empty(), manager);

        service.publish("alert", null, new IotRealtimeService.AlertEvent("ack", null, null, 1, null));

        verify(manager).sendToAudience(eq("platform:all"), eq("alert"), any());
        verify(manager, times(1)).sendToAudience(any(), any(), any());
        verify(manager, never()).sendToAudience(eq("tenant:1"), any(), any());
        verify(manager, never()).broadcast(any(), any());
    }

    @Test
    @DisplayName("★ 不同租户发放不同定向键（tenant:1 vs tenant:12 不互相命中）")
    void differentTenantsGetDifferentKeys() {
        SseEmitterManager manager = mock(SseEmitterManager.class);
        IotRealtimeService service = new IotRealtimeService(() -> Optional.empty(), manager);

        service.publish("latest", 1L, new IotRealtimeService.LatestEvent(7L, "a", "1", "G", 1L));
        service.publish("latest", 12L, new IotRealtimeService.LatestEvent(9L, "b", "2", "G", 2L));

        verify(manager, times(1)).sendToAudience(eq("tenant:1"), any(), any());
        verify(manager, times(1)).sendToAudience(eq("tenant:12"), any(), any());
        verify(manager, never()).broadcast(any(), any());
    }

    @Test
    @DisplayName("★ subscribe() 按当前上下文租户建 audience 连接；无租户 => platform:all")
    void subscribeUsesCurrentTenantAudience() {
        SseEmitterManager manager = mock(SseEmitterManager.class);
        when(manager.connectAudience(any())).thenReturn(new SseEmitter());
        IotRealtimeService service = new IotRealtimeService(() -> Optional.empty(), manager);

        TenantContext.runWithTenant(1L, service::subscribe);
        service.subscribe();

        verify(manager).connectAudience("tenant:1");
        verify(manager).connectAudience("platform:all");
    }

    @Test
    @DisplayName("★ TenantProvider 兜底：ThreadLocal 为空时用 provider 的租户（生产 HTTP 路径）")
    void subscribeFallsBackToTenantProvider() {
        SseEmitterManager manager = mock(SseEmitterManager.class);
        when(manager.connectAudience(any())).thenReturn(new SseEmitter());
        IotRealtimeService service = new IotRealtimeService(() -> Optional.of(12L), manager);

        service.subscribe();

        verify(manager).connectAudience("tenant:12");
        verify(manager, never()).connectAudience("platform:all");
    }

    @Test
    @DisplayName("★ 源码结构性检查：发布路径不得出现 broadcast")
    void publishPathMustNotCallBroadcast() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/cn/ypbin/admin/iot/realtime/IotRealtimeService.java"));
        assertThat(source).as("用 broadcast 发平台事件会把事件投给全部租户连接 ⇒ 跨租户泄漏")
            .doesNotContain(".broadcast(");
        assertThat(source).contains("sendToAudience");
    }
}
