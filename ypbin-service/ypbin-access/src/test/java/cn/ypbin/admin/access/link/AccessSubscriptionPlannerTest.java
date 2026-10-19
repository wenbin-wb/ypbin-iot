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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import cn.ypbin.iot.spring.subscription.DefaultSubscriptionOrchestrator;
import cn.ypbin.iot.spring.subscription.ProtocolDescriptorSource;
import cn.ypbin.iot.spring.subscription.SubscriptionBackoffPolicy;
import cn.ypbin.iot.spring.subscription.SubscriptionOrchestrator;
import cn.ypbin.admin.iot.modbus.ModbusRegisterAddress;
import cn.ypbin.iot.core.protocol.AddressExpansionExtension;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
        DeviceSession session = withExpansion(mock(DeviceSession.class));
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
        clock.advance(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY.plusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1")))).as("退避到期必须重试").isEqualTo(1);
        verify(session, times(2)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ 退避窗口内**绝不调用** session.subscribe（不只是返回 0），跨窗口后必须真的重试")
    void backoffMustNotTouchSessionAndMustRetryAfterWindow() {
        // lead 明确要求补的断言：只把返回值改小是不够的——必须证明「退避期间真的没有打远端」，
        // 以及「窗口过去后确实重发了」。这两条一起才能排除「返回值骗人、实际每轮都发」。
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("协议侧拒绝订阅"));
        when(session.subscribe(any(SubscribeRequest.class), any())).thenReturn(failed);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.subscribe(List.of(device("d1")))).as("首次确实发起").isEqualTo(1);
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());

        // 退避窗口内连打 5 轮：一次 session 调用都不能多
        for (int i = 0; i < 5; i++) {
            assertThat(planner.subscribe(List.of(device("d1")))).as("退避内不得算发起").isZero();
        }
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());

        // 跨过窗口 ⇒ 必须真的重发（证明恢复路径存在，不是永久卡死）
        clock.advance(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY.plusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1")))).as("跨窗口必须重试").isEqualTo(1);
        verify(session, times(2)).subscribe(any(SubscribeRequest.class), any());
    }

    // ==================================================================
    // iot-starter `release/0.5.0` 反哺 `SubscriptionAddressingPolicy` 后的新语义用例
    // （替代被删的「宿主自建 key-value 退避」用例：退避表已删，退避改由框架承担）
    // ==================================================================

    @Test
    @DisplayName("★ key-value 形态：框架按形态声明 BATCH ⇒ 3 个 selector **只调用 1 次**且带 3 个地址")
    void keyValueVariantMustSubscribeBatchThroughFramework() {
        // 这是「删除宿主分流分支与退避表」后必须成立的等价行为：
        // 宿主不再判断 payload-format、不再直调 session.subscribe，但一次多地址的语义必须保留
        // （否则 key-value 会退化成 3 次单地址订阅 ⇒ 请求数 1→3、SDK selector 行为变化）。
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, new SimpleMeterRegistry());

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"t\",\"address\":\"TEMP\"},"
            + "{\"propertyId\":\"2\",\"identifier\":\"h\",\"address\":\"HUMI\"},"
            + "{\"propertyId\":\"3\",\"identifier\":\"p\",\"address\":\"PRES\"}]";
        assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .as("key-value 形态：一次请求必须携带全部 3 个 selector")
            .containsExactlyInAnyOrder("TEMP", "HUMI", "PRES");
    }

    @Test
    @DisplayName("★ binary 形态：框架按形态声明 PER_ADDRESS ⇒ 3 个点位发 **3 次单地址**（不混成整批）")
    void binaryVariantMustSubscribePerAddressThroughFramework() {
        // 防「把两种形态混成一种」：同一 tcp 协议，binary 形态下裸帧无寻址语义，
        // TcpSession 对 >1 地址直接 fail-fast ⇒ 必须逐点位单订阅。
        // 宿主的 planner() 工厂用 binary 形态的描述符（见 orchestrator(TCP_BINARY_VARIANT)）。
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"t\",\"address\":\"TEMP\"},"
            + "{\"propertyId\":\"2\",\"identifier\":\"h\",\"address\":\"HUMI\"},"
            + "{\"propertyId\":\"3\",\"identifier\":\"p\",\"address\":\"PRES\"}]";
        assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(3)).subscribe(requests.capture(), any());
        assertThat(requests.getAllValues().stream()
            .map(r -> r.addresses().get(0).raw()).toList())
            .as("binary 形态：3 个点位各 1 次单地址订阅（每次只带 1 个地址）")
            .containsExactlyInAnyOrder("TEMP", "HUMI", "PRES");
        assertThat(requests.getAllValues())
            .allSatisfy(r -> assertThat(r.addresses()).as("绝不允许 >1 地址（会触发 TCP fail-fast）")
                .hasSize(1));
    }

    @Test
    @DisplayName("★ key-value 形态失败：退避由**框架**承担 ⇒ 窗口内 verify(session, never())")
    void keyValueFailureBackoffMustBeHandledByFramework() {
        // 替代被删的「宿主自建 key-value 退避」用例：宿主退避表已删除，
        // 但「失败后不退避、每轮猛重试」是不可接受的（原退避就是为防这个）。
        // 本用例证明退避能力没丢——只是执行者从宿主换成了框架的 SubscriptionBackoffPolicy。
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("协议侧拒绝订阅"));
        when(session.subscribe(any(SubscribeRequest.class), any())).thenReturn(failed);
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, new SimpleMeterRegistry());

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"t\",\"address\":\"TEMP\"},"
            + "{\"propertyId\":\"2\",\"identifier\":\"h\",\"address\":\"HUMI\"},"
            + "{\"propertyId\":\"3\",\"identifier\":\"p\",\"address\":\"PRES\"}]";
        assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).as("首次发起").isEqualTo(1);
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());

        // 退避窗口内连打 5 轮：框架必须一次 session 调用都不再发
        for (int i = 0; i < 5; i++) {
            assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).isZero();
        }
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());

        // 跨过窗口 ⇒ 框架必须重试（恢复路径存在）
        clock.advance(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY.plusSeconds(1));
        assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).isEqualTo(1);
        verify(session, times(2)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ G1：在途订阅（异步未完成）不得被下一轮重复发起")
    void inFlightSubscribeMustNotBeDuplicated() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
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
        DeviceSession failing = withExpansion(mock(DeviceSession.class));
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
        DeviceSession failing = withExpansion(mock(DeviceSession.class));
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
        DeviceSession flaky = withExpansion(mock(DeviceSession.class));
        // 第一次同步抛出、第二次成功：若「在途」标记没被清掉，第二次会一直被挡 ⇒ 设备永久无法订阅
        when(flaky.subscribe(any(SubscribeRequest.class), any()))
            .thenThrow(new IllegalStateException("适配器同步抛出"))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", flaky));
        AccessSubscriptionPlanner planner = planner(bound);

        // ⚠️ 口径变化（iot-starter #37 迁移，lead 决策）：框架把「适配器同步抛出」收敛成
        // SubscriptionStatus.FAILED，而 FAILED 属于「已发起过协议层调用」⇒ 本轮计入发起（返回 1）。
        // 迁移前该场景返回 0。**采集结果与重试行为不变**（本用例的核心断言在下面两条）。
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("框架把同步抛出收敛为 FAILED（已发起过调用）").isEqualTo(1);
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("紧接着的一轮在框架退避窗口内，跳过").isZero();

        // 退避到期后必须能再次尝试并成功：若在途标记没被清掉，这里会返回 0（永久无法订阅）
        clock.advance(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY.plusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("在途标记必须已清除，否则设备永久无法订阅").isEqualTo(1);
        verify(flaky, times(2)).subscribe(any(SubscribeRequest.class), any());
        assertThat(planner.trackedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ forget 必须同时清掉在途标记（同 id 复现不得被旧在途挡住）")
    void forgetMustClearInFlight() {
        DeviceSession pendingSession = withExpansion(mock(DeviceSession.class));
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
        DeviceSession failing = withExpansion(mock(DeviceSession.class));
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
        planner.subscribe(List.of(device("d1")));                    // 失败 3 ⇒ 120s（=封顶值）
        clock.advance(Duration.ofSeconds(121));
        planner.subscribe(List.of(device("d1")));                    // 失败 4 ⇒ 封顶 120s（不封顶会是 240s）

        clock.advance(SubscriptionBackoffPolicy.DEFAULT_MAX_DELAY.minusSeconds(1));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("封顶窗口内（差 1 秒）不得重试").isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(planner.subscribe(List.of(device("d1"))))
            .as("封顶恰为 120s：超过即必须重试（不封顶时为 240s，此断言会失败）").isEqualTo(1);
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

    @Test
    @DisplayName("★ UP-4：4 点位 TCP 设备必须发起 4 次**单地址**订阅（否则 starter 0.2.0 fail-fast ⇒ 整机零采集）")
    void tcpDeviceWithMultiplePointsMustSubscribePerAddress() throws Exception {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        // 一次 subscribe 轮次：应为 4 个点位各发起 1 次单地址订阅
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1"))))
            .as("本设备本轮发起了订阅").isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(4)).subscribe(requests.capture(), any());
        List<List<String>> addressLists = requests.getAllValues().stream()
            .map(r -> r.addresses().stream().map(PointAddress::raw).toList())
            .toList();
        // 咬人点①：绝不能有 >1 地址的请求（那正是 fail-fast 的触发条件）
        assertThat(addressLists)
            .as("每个 SubscribeRequest 必须只含 1 个地址，绝不把全部点位塞进一次订阅")
            .allSatisfy(list -> assertThat(list).hasSize(1));
        // 咬人点②：4 个点位各自的地址都被覆盖到，不重不漏
        assertThat(addressLists.stream().map(list -> list.get(0)))
            .containsExactlyInAnyOrder("TEMP", "HUMI", "SN", "BAT");
        // 4 次订阅共享同一个 listener（同一帧按各自 raw_address 取键）
        ArgumentCaptor<cn.ypbin.iot.core.model.DataListener> listeners =
            ArgumentCaptor.forClass(cn.ypbin.iot.core.model.DataListener.class);
        verify(session, times(4)).subscribe(any(SubscribeRequest.class), listeners.capture());
        assertThat(listeners.getAllValues().stream().distinct())
            .as("TCP 逐点位订阅应共享同一 listener（pointByAddress 含全部点位）").hasSize(1);

        // 完成回调后记为「设备已订阅」：下一轮不得重复订阅
        assertThat(planner.trackedSessionCount()).as("全部地址成功后记一次设备级跟踪").isEqualTo(1);
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1"))))
            .as("已订阅设备不得重复发起").isZero();
        // 「累计单地址订阅数 = 4（无重复）」已由上面两次 verify 共同证实：
        // 第 4 次调用后仍是 4 ⇒ 第二轮（返回 0）没有新增任何订阅。
        // 宿主侧的 tcpStartedAddressSubscriptionCount 计数器已删（逐点位计数归框架维护）。
        verify(session, times(4)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ UP-4：TCP 单点位设备仍只发 1 次单地址订阅（不得退化）")
    void tcpDeviceWithSinglePointMustSubscribeOnce() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        assertThat(planner.subscribe(List.of(tcpDevice("d1",
            "[{\"propertyId\":\"1\",\"identifier\":\"temperature\","
                + "\"address\":\"TEMP\",\"dataType\":\"decimal\"}]")))).isEqualTo(1);
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
        assertThat(planner.trackedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ UP-4：TCP 部分地址失败 ⇒ 只重试失败地址，已成功地址绝不重复挂监听（防重复读数）")
    void tcpPartialFailureMustRetryOnlyFailedAddresses() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        List<SubscribeRequest> seen = new CopyOnWriteArrayList<>();
        CompletableFuture<SubscriptionHandle> boom = new CompletableFuture<>();
        boom.completeExceptionally(new IllegalStateException(TcpFailFast.MULTI_BODY));
        when(session.subscribe(any(SubscribeRequest.class), any())).thenAnswer(inv -> {
            SubscribeRequest request = inv.getArgument(0);
            seen.add(request);
            // 模拟 starter 0.2.0：>1 地址 fail-fast；单地址成功
            if (request.addresses().size() > 1) {
                return boom;
            }
            return CompletableFuture.completedFuture(null);
        });
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccessSubscriptionPlanner planner = planner(bound, meterRegistry);

        // 第一轮：4 个地址各自单订阅 ⇒ 全部成功 ⇒ 设备级跟踪
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isEqualTo(1);
        assertThat(seen).hasSize(4);
        assertThat(planner.trackedSessionCount()).isEqualTo(1);
        assertThat(meterRegistry.get("iot.access.subscribe.failure").counter().count()).isZero();

        // 第二轮：不得再发起任何订阅（已全部成功）
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isZero();
        assertThat(seen).as("已成功地址不得被重复订阅").hasSize(4);
    }

    @Test
    @DisplayName("★ UP-4：TCP 全部地址失败 ⇒ 每次失败各计一次、退避后整体重试、且不写设备级跟踪（S5）")
    void tcpAllFailuresMustCountAndBackoff() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        List<SubscribeRequest> seen = new CopyOnWriteArrayList<>();
        CompletableFuture<SubscriptionHandle> boom = new CompletableFuture<>();
        boom.completeExceptionally(new IllegalStateException("拒绝订阅"));
        when(session.subscribe(any(SubscribeRequest.class), any())).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return boom;
        });
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccessSubscriptionPlanner planner = planner(bound, meterRegistry);

        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isEqualTo(1);
        assertThat(seen).as("4 个点位各 1 次单地址订阅").hasSize(4);
        assertThat(meterRegistry.get("iot.access.subscribe.failure").counter().count())
            .as("每次失败的订阅各计一次失败").isEqualTo(4.0d);
        assertThat(planner.trackedSessionCount())
            .as("全部失败不得写设备级跟踪（S5：写了就永不重试）").isZero();

        // 退避窗口内不得重发
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isZero();
        assertThat(seen).hasSize(4);

        // 退避到期 ⇒ 必须重试（自愈不能被退避破坏）
        clock.advance(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY.plusSeconds(1));
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isEqualTo(1);
        assertThat(seen).as("退避到期后 4 个地址都要重试").hasSize(8);
    }

    @Test
    @DisplayName("★ UP-4：TCP 会话实例变化 ⇒ 作废旧地址状态并逐点位重订阅")
    void tcpResubscribeOnSessionChange() {
        DeviceSession first = withExpansion(mock(DeviceSession.class));
        when(first.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", first));
        AccessSubscriptionPlanner planner = planner(bound);

        planner.subscribe(List.of(tcpDevice4Points("d1")));
        verify(first, times(4)).subscribe(any(SubscribeRequest.class), any());

        DeviceSession reconnected = withExpansion(mock(DeviceSession.class));
        when(reconnected.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        bound.set(Map.of("d1", reconnected));

        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1"))))
            .as("换会话后必须对新会话逐点位重订阅").isEqualTo(1);
        // 累计 8 次 = 原会话 4 次 + 新会话 4 次（宿主计数器已删，用两次 verify 直接表达）
        verify(first, times(4)).subscribe(any(SubscribeRequest.class), any());
        verify(reconnected, times(4)).subscribe(any(SubscribeRequest.class), any());
        assertThat(planner.trackedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ UP-4：非 TCP（Modbus）>1 点位仍走「一次多地址」——不得被 TCP 分支误伤")
    void nonTcpMultiPointStillUsesSingleMultiAddressRequest() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"a\",\"address\":\"holding:1\"},"
            + "{\"propertyId\":\"2\",\"identifier\":\"b\",\"address\":\"holding:2\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);
        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses()).as("Modbus 一次订阅覆盖全部地址")
            .hasSize(2);
    }

    @Test
    @DisplayName("★ UP-4：forget 必须清掉 TCP 逐点位状态（设备复现不得被旧成功集挡住）")
    void forgetMustClearTcpPerAddressState() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        planner.subscribe(List.of(tcpDevice4Points("d1")));
        planner.forget("d1");

        DeviceSession healthy = withExpansion(mock(DeviceSession.class));
        when(healthy.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        bound.set(Map.of("d1", healthy));
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1"))))
            .as("forget 后必须能重新逐点位订阅").isEqualTo(1);
        verify(healthy, times(4)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ key-value：4 点位 TCP 必须**一次**多地址订阅（1 个请求携带 4 个地址），不再 N 次单地址")
    void tcpKeyValueWithMultiplePointsMustSubscribeOnceWithAllAddresses() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, new SimpleMeterRegistry());

        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1"))))
            .as("key-value 下一设备本轮只发起 1 次订阅").isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .as("一次请求必须携带全部 4 个去重地址")
            .containsExactlyInAnyOrder("TEMP", "HUMI", "SN", "BAT");
        // 「key-value 不得走逐点位单订阅」已由上面的 times(1) 直接证实：
        // 1 次调用携带 4 个地址，而不是 4 次单地址调用。
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
        assertThat(planner.trackedSessionCount()).as("成功后记设备级跟踪").isEqualTo(1);

        // 已订阅设备不得重复发起
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isZero();
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ key-value：订阅失败 ⇒ **设备级 1 次**失败计数 + 不写跟踪 + 退避后整体重试")
    void tcpKeyValueFailureMustBackoffAndNotTrack() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        List<SubscribeRequest> seen = new CopyOnWriteArrayList<>();
        CompletableFuture<SubscriptionHandle> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("拒绝订阅"));
        when(session.subscribe(any(SubscribeRequest.class), any())).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return failed;
        });
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, meterRegistry);

        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isEqualTo(1);
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().addresses()).hasSize(4);
        // ⚠️ 口径变化（lead 决策 ③）：`subscribe.failure` 统一按**地址级**累加
        // （`outcome.failures().size()`），不再区分「整批 1 次 / 逐点位 N 次」。
        // 理由：运维看板口径不能从地址级突变设备级（否则同一故障看起来轻 N 倍，属静默弱化）。
        // key-value 整批 4 地址失败 ⇒ 计 4（与 binary 逐点位 4 次失败计 4 **一致**，
        // 这反而是更自洽的口径：同一故障不论部署形态，指标值相同）。
        assertThat(meterRegistry.get("iot.access.subscribe.failure").counter().count())
            .as("4 个地址全部失败 ⇒ 地址级计 4（与 binary 形态口径一致）").isEqualTo(4.0d);
        assertThat(planner.trackedSessionCount())
            .as("失败不写跟踪（S5：写了就永不重试）").isZero();

        // 退避窗口内不重发
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isZero();
        assertThat(seen).hasSize(1);

        // 退避到期 ⇒ 整体重试
        clock.advance(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY.plusSeconds(1));
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isEqualTo(1);
        assertThat(seen).hasSize(2);
    }

    @Test
    @DisplayName("★ key-value：单点位也只发 1 次（多地址路径的 N=1 退化）")
    void tcpKeyValueSinglePointMustSubscribeOnce() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, new SimpleMeterRegistry());

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"temperature\","
            + "\"address\":\"TEMP\",\"dataType\":\"decimal\"}]";
        assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).isEqualTo(1);
        verify(session, times(1)).subscribe(any(SubscribeRequest.class), any());
        assertThat(planner.trackedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ key-value：会话实例变化 ⇒ 对新会话重新发起一次多地址订阅（B2）")
    void tcpKeyValueResubscribeOnSessionChange() {
        DeviceSession first = subscribeReturnsFuture();
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", first));
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, new SimpleMeterRegistry());

        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1")))).isEqualTo(1);
        verify(first, times(1)).subscribe(any(SubscribeRequest.class), any());

        DeviceSession reconnected = subscribeReturnsFuture();
        bound.set(Map.of("d1", reconnected));
        assertThat(planner.subscribe(List.of(tcpDevice4Points("d1"))))
            .as("换会话后必须对新会话重新订阅").isEqualTo(1);
        verify(reconnected, times(1)).subscribe(any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("★ key-value：重复 raw 地址必须去重（否则 SDK 逐地址条目产出 ⇒ 同一帧重复读数）")
    void tcpKeyValueDuplicateRawAddressMustBeDeduped() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = plannerKeyValue(bound, new SimpleMeterRegistry());

        // 4 条映射、3 个不同 raw（TEMP 出现两次）
        String points = "[{\"propertyId\":\"1\",\"identifier\":\"t1\",\"address\":\"TEMP\"},"
            + "{\"propertyId\":\"2\",\"identifier\":\"t2\",\"address\":\"TEMP\"},"
            + "{\"propertyId\":\"3\",\"identifier\":\"h\",\"address\":\"HUMI\"},"
            + "{\"propertyId\":\"4\",\"identifier\":\"s\",\"address\":\"SN\"}]";
        assertThat(planner.subscribe(List.of(tcpDevice("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .as("重复 raw 必须去重（否则 SDK 对每个地址条目各产出一个 PointValue ⇒ 重复读数）")
            .containsExactly("TEMP", "HUMI", "SN");
    }

    @Test
    @DisplayName("★ Modbus word_count=2：一次订阅必须带 base 与 base+1 两个寄存器")
    void modbusWordCountTwoMustSubscribeBaseAndNext() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"counter\","
            + "\"address\":\"holding:0\",\"wordCount\":2,\"signed\":false,"
            + "\"byteOrder\":\"big\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .as("32 位点位必须同时订阅基址与下一个寄存器（SDK 合并成一次读）")
            .containsExactly("holding:0", "holding:1");
    }

    @Test
    @DisplayName("★ v3 string：word_count=3 一次订阅带 base..base+2（泛化扩地址）")
    void modbusStringWordCountThreeMustSubscribeAllRegisters() {
        DeviceSession session = subscribeReturnsFuture();
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"serial\","
            + "\"address\":\"holding:0\",\"wordCount\":3,\"registerFormat\":\"string\","
            + "\"signed\":false}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .as("string 的 N 个寄存器必须全部订阅（SDK 分块由 SDK 处理）")
            .containsExactly("holding:0", "holding:1", "holding:2");
    }

    @Test
    @DisplayName("★ v3 string：word_count=1 + 传统 5 位编号不做地址扩展（向后兼容）")
    void modbusStringWordCountOneTraditionalStaysSingle() {
        DeviceSession session = subscribeReturnsFuture();
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"serial\","
            + "\"address\":\"40001\",\"wordCount\":1,\"registerFormat\":\"string\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .containsExactly("40001");
    }

    @Test
    @DisplayName("★ v3 string：扩出的 base+1/base+2 与其它点位重叠 ⇒ 仍按 raw 去重")
    void modbusStringSharedRegistersMustDedupe() {
        DeviceSession session = subscribeReturnsFuture();
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"serial\","
            + "\"address\":\"holding:0\",\"wordCount\":3,\"registerFormat\":\"string\"},"
            + "{\"propertyId\":\"2\",\"identifier\":\"other\",\"address\":\"holding:1\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .containsExactly("holding:0", "holding:1", "holding:2");
    }

    @Test
    @DisplayName("★ v2 float：word_count=2 一次订阅带 base 与 base+1（复用 v1 扩地址骨架）")
    void modbusFloatMustSubscribeBaseAndNext() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"temperature\","
            + "\"address\":\"holding:0\",\"wordCount\":2,\"registerFormat\":\"float\","
            + "\"byteOrder\":\"little\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .containsExactly("holding:0", "holding:1");
    }

    @Test
    @DisplayName("★ Modbus word_count=1：订阅地址与今天完全一致（传统 5 位编号也照常）")
    void modbusWordCountOneMustStaySingle() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"temperature\","
            + "\"address\":\"40001\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .as("word_count=1 不做地址扩展（传统编号向后兼容）")
            .containsExactly("40001");
    }

    @Test
    @DisplayName("★ Modbus 寄存器重叠：扩出的 base+1 与其它点位地址重复 ⇒ 去重（不重复读数）")
    void modbusWordCountTwoSharedRegisterMustDedupe() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        AccessSubscriptionPlanner planner = planner(bound);

        // A 是 32 位（holding:0 + holding:1）；B 是 16 位 holding:1 ⇒ holding:1 必须只订阅一次
        String points = "[{\"propertyId\":\"1\",\"identifier\":\"counter\","
            + "\"address\":\"holding:0\",\"wordCount\":2},"
            + "{\"propertyId\":\"2\",\"identifier\":\"other\",\"address\":\"holding:1\"}]";
        assertThat(planner.subscribe(List.of(device("d1", points)))).isEqualTo(1);

        ArgumentCaptor<SubscribeRequest> requests = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(session, times(1)).subscribe(requests.capture(), any());
        assertThat(requests.getValue().addresses().stream().map(PointAddress::raw).toList())
            .containsExactly("holding:0", "holding:1");
    }

    @Test
    @DisplayName("★ Modbus word_count=2 但地址非法（传统 5 位编号）⇒ 跳过该设备、不抛断整轮")
    void modbusWordCountTwoInvalidAddressMustSkipDeviceNotTick() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        AtomicReference<Map<String, DeviceSession>> bound =
            new AtomicReference<>(Map.of("d1", session));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AccessSubscriptionPlanner planner = planner(bound, meterRegistry);

        String points = "[{\"propertyId\":\"1\",\"identifier\":\"counter\","
            + "\"address\":\"40001\",\"wordCount\":2}]";
        // ⚠️ 口径变化（iot-starter #37 迁移，lead 决策 2）：框架把「地址扩展失败」记为
        // 该点位的 SubscriptionFailure，设备级仍算发起（返回 1）；迁移前是「整设备跳过、返回 0」。
        // **采集结果一致**：都不订阅该点位地址、也**不**退而只订基址（框架显式选择「宁可失败也不订半截」）。
        // 因此本用例的核心断言改为：① session 未收到任何 subscribe 调用；② 失败可观测（计数/日志）。
        assertThat(planner.subscribe(List.of(device("d1", points))))
            .as("框架把地址扩展失败记为点位 failure，设备级仍算发起").isEqualTo(1);
        verify(session, never()).subscribe(any(SubscribeRequest.class), any());
        assertThat(meterRegistry.get("iot.access.subscribe.failure").counter().count())
            .as("地址扩展失败必须可观测（否则「发起 1 次但点位永远无数据」是新的静默失效）")
            .isEqualTo(1.0d);
    }

    /** 默认规划器：TCP 走 **binary 形态**（框架声明 PER_ADDRESS ⇒ 逐点位单订阅）。 */
    private AccessSubscriptionPlanner planner(AtomicReference<Map<String, DeviceSession>> bound) {
        return planner(bound, new SimpleMeterRegistry());
    }

    private AccessSubscriptionPlanner planner(AtomicReference<Map<String, DeviceSession>> bound,
                                              SimpleMeterRegistry meterRegistry) {
        return new AccessSubscriptionPlanner(bound::get, objectMapper, reading -> { },
            meterRegistry, orchestrator(TCP_BINARY_VARIANT), BytePayloadDecoderRegistry.empty(), clock);
    }

    /**
     * **key-value 形态**的规划器。
     *
     * <p>iot-starter `release/0.5.0` 反哺后，key-value 由**框架**按形态声明为
     * {@code BATCH}（{@code SubscriptionAddressingPolicy} 的 variant 表），宿主不再需要自己分流。
     * 本工厂只是把「该部署形态」告诉编排器的描述符来源（等价于生产里 {@code TcpAdapter} 在
     * {@code payload-format=key-value} 时构造的描述符）。</p>
     */
    private AccessSubscriptionPlanner plannerKeyValue(
            AtomicReference<Map<String, DeviceSession>> bound, SimpleMeterRegistry meterRegistry) {
        return new AccessSubscriptionPlanner(bound::get, objectMapper, reading -> { },
            meterRegistry, orchestrator(TCP_KEY_VALUE_VARIANT), BytePayloadDecoderRegistry.empty(), clock);
    }

    /**
     * 真实的框架订阅编排器（iot-starter #37 的 {@code DefaultSubscriptionOrchestrator}）。
     *
     * <p>刻意用**框架默认实现**而不是 mock：本类测试的价值就在于证明「宿主把正确输入交给框架后，
     * 框架产出的订阅请求与迁移前等价」（分流/展开/在途/部分成功/退避全在框架里）。mock 掉它就等于
     * 把被测逻辑抽空。</p>
     *
     * <p>协议描述符按本测试用到的协议给：modbus=整批（BATCH）、tcp=逐点位（PER_ADDRESS），
     * 与框架里两个适配器的**真实声明**一致（见 {@code ModbusAdapter}/{@code TcpAdapter}）。</p>
     */
    /** TCP 部署形态：{@code payload-format=binary}（框架声明 PER_ADDRESS ⇒ 逐点位）。 */
    private static final String TCP_BINARY_VARIANT = "binary";

    /** TCP 部署形态：{@code payload-format=key-value}（框架声明 BATCH ⇒ 一次多地址）。 */
    private static final String TCP_KEY_VALUE_VARIANT = "key-value";

    /**
     * 真实的框架订阅编排器（iot-starter #37 的 {@code DefaultSubscriptionOrchestrator}）。
     *
     * <p>刻意用**框架默认实现**而不是 mock：本类测试的价值就在于证明「宿主把正确输入交给框架后，
     * 框架产出的订阅请求与迁移前等价」（分流/展开/在途/部分成功/退避全在框架里）。
     *
     * <p>描述符按生产适配器的**真实声明**构造：Modbus = BATCH；TCP = 协议级保守侧 PER_ADDRESS +
     * **按形态覆盖表**（`release/0.5.0` 的 {@code SubscriptionAddressingPolicy}：
     * {@code key-value ⇒ BATCH}、其余 ⇒ PER_ADDRESS）+ 形态解析器（模拟 TcpAdapter 用实例
     * payloadFormat 解析）。这正是宿主不再需要自己分流的依据。</p>
     *
     * @param tcpVariant 本次部署的 TCP 形态（二进制/键值）
     * @return 编排器
     */
    private SubscriptionOrchestrator orchestrator(String tcpVariant) {
        ProtocolDescriptor modbus = ProtocolDescriptor.builder()
            .code(ProtocolCode.of("modbus"))
            .name("Modbus")
            .subscriptionAddressing(SubscriptionAddressing.BATCH)
            .build();
        ProtocolDescriptor tcp = ProtocolDescriptor.builder()
            .code(ProtocolCode.of("tcp"))
            .name("TCP")
            // 协议级默认 = 保守侧（未取到形态时按逐点位）
            .subscriptionAddressing(SubscriptionAddressing.PER_ADDRESS)
            // 按形态细分：只有 key-value 能一次多地址（与 TcpAdapter#buildDescriptor 一致）
            .subscriptionAddressingByVariant(Map.of(
                TCP_BINARY_VARIANT, SubscriptionAddressing.PER_ADDRESS,
                TCP_KEY_VALUE_VARIANT, SubscriptionAddressing.BATCH))
            // 形态解析器：生产里由 TcpAdapter 捕获实例的 payloadFormat；测试里固定成本次部署形态
            .variantResolver(device -> tcpVariant)
            .build();
        return new DefaultSubscriptionOrchestrator(ProtocolDescriptorSource.of(List.of(modbus, tcp)),
            SubscriptionBackoffPolicy.defaults(), clock);
    }


    /**
     * 建一个「像生产会话一样」的 mock：能 {@code unwrap} 出协议扩展。
     *
     * <p>为什么必须补这个桩：框架的 {@code DefaultSubscriptionOrchestrator} 通过
     * {@code session.unwrap(AddressExpansionExtension.class)} 拿协议声明的地址算术
     * （生产里 {@code ModbusSession#unwrap} 返回 {@code ModbusAddressExpansion}）。裸 mock 返回
     * {@code Optional.empty()} ⇒ 所有 {@code wordCount>1} 的点位都会「展开失败」而不是订阅基址。
     * 本测试要验的是宿主的翻译逻辑，故桩出一个与生产一致的扩展（modbus=+1、其它=不展开）。</p>
     *
     * @param <T> mock 类型
     * @param mock 已创建的 mock
     * @return 同一个 mock（便于内联）
     */
    /** 会话桩用的设备规格（只有 deviceId 会被框架读到，其余取安全默认值）。 */
    private static DeviceSpec specForSessionStub() {
        return new DeviceSpec("d1", "d1", ProtocolCode.of("modbus"), "t11-stub", "",
            Duration.ofSeconds(5), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <T extends DeviceSession> T withExpansion(T mock) {
        // 框架的 resetIfSessionChanged / 日志会读 session.device() 与 sessionId()：生产会话必非空，
        // 裸 mock 返回 null 会让框架抛 NPE ⇒ 这里补上与生产一致的桩。
        DeviceSpec stubSpec = specForSessionStub();
        when(mock.device()).thenReturn(stubSpec);
        when(mock.sessionId()).thenReturn("sess-" + System.identityHashCode(mock));
        when(mock.unwrap(AddressExpansionExtension.class)).thenReturn(java.util.Optional.of(
            new AddressExpansionExtension() {
                @Override
                public ProtocolCode protocol() {
                    return ProtocolCode.of("modbus");
                }

                @Override
                public PointAddress offset(PointAddress base, int delta) {
                    return PointAddress.of(ModbusRegisterAddress.plus(base.raw(), delta));
                }
            }));
        return mock;
    }

    private static DeviceSession subscribeReturnsFuture() {
        DeviceSession session = withExpansion(mock(DeviceSession.class));
        when(session.subscribe(any(SubscribeRequest.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        return session;
    }

    private static DeviceSpec device(String deviceId) {
        return spec(deviceId, "[{\"propertyId\":\"900\",\"address\":\"holding:1\"}]");
    }

    private static DeviceSpec device(String deviceId, String pointsJson) {
        return spec(deviceId, pointsJson);
    }

    private static DeviceSpec deviceWithoutPoints(String deviceId) {
        return spec(deviceId, "[]");
    }

    private static DeviceSpec spec(String deviceId, String pointsJson) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("modbus"), "t11-d" + deviceId, "",
            Duration.ofSeconds(5), Map.of(HttpDeviceSpecSource.PROPERTY_POINTS, pointsJson));
    }

    /** TCP 设备：4 点位（对齐生产演示设备 9300012 的 4 点位形态）。 */
    private static DeviceSpec tcpDevice4Points(String deviceId) {
        return tcpDevice(deviceId,
            "[{\"propertyId\":\"1\",\"identifier\":\"temperature\",\"address\":\"TEMP\","
                + "\"dataType\":\"decimal\"},"
                + "{\"propertyId\":\"2\",\"identifier\":\"humidity\",\"address\":\"HUMI\","
                + "\"dataType\":\"decimal\"},"
                + "{\"propertyId\":\"3\",\"identifier\":\"serialNo\",\"address\":\"SN\","
                + "\"dataType\":\"string\"},"
                + "{\"propertyId\":\"4\",\"identifier\":\"demoBoundary\",\"address\":\"BAT\","
                + "\"dataType\":\"int\"}]");
    }

    private static DeviceSpec tcpDevice(String deviceId, String pointsJson) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("tcp"), "t-d" + deviceId, "",
            Duration.ofSeconds(5), Map.of(HttpDeviceSpecSource.PROPERTY_POINTS, pointsJson));
    }

    /** starter 0.2.0 TCP fail-fast 的错误语义（仅用于测试桩文案，不依赖 starter 内部常量）。 */
    private static final class TcpFailFast {

        private static final String MULTI_BODY = "单点位流：一次订阅仅支持 1 个地址";

        private TcpFailFast() {
        }
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
