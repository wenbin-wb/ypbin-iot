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

import cn.ypbin.admin.access.egress.AccessReadingSink;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅规划器：把「点位映射」变成协议栈的订阅请求（3b-2 的功能核心）。
 *
 * <p><b>为什么订阅必须由宿主动发起</b>（对框架源码的核实结论）：框架只负责建链
 * （{@code IotLifecycle.bind} 创建 {@link DeviceSession}），**主源码里没有任何地方构造
 * SubscribeRequest 或调用 subscribe** —— 采集什么点由宿主决定。因此本类在
 * {@link IotProtocolTenantLinkManager#startCollecting} 发出 ADD（框架同步建链）之后被调用。</p>
 *
 * <p>设备级周期（{@code DeviceSpec.pollInterval}）作为订阅采样周期；为 0 时用框架默认值。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class AccessSubscriptionPlanner implements SubscriptionPlanner {

    private static final Logger log = LoggerFactory.getLogger(AccessSubscriptionPlanner.class);

    /** 订阅失败后的退避起点。 */
    static final Duration SUBSCRIBE_BACKOFF_BASE = Duration.ofSeconds(30);

    /** 订阅失败退避上限。 */
    static final Duration SUBSCRIBE_BACKOFF_MAX = Duration.ofMinutes(2);

    private final Supplier<Map<String, DeviceSession>> sessions;
    private final ObjectMapper objectMapper;
    private final AccessReadingSink readingSink;

    /**
     * 已订阅的**会话实例**（按 deviceId）。会话实例变了（框架重连会新建会话）就必须重订阅，
     * 否则表现为「链路恢复但数据不再上报」——这是框架不给宿主发重连事件时的唯一可靠判据。
     */
    private final Map<String, DeviceSession> subscribedSessions = new ConcurrentHashMap<>();

    /**
     * 在途订阅（G1）：{@code session.subscribe(...)} 是**异步**的，慢订阅（&gt; 一个租约周期）会让下一轮
     * 对账再发起一次 ⇒ 同一设备重复订阅、监听器重复挂、远端压力翻倍。这里按设备去重：完成（无论成败）才移除。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 订阅失败退避（G1）：失败设备不再每个周期重发，而是退避后重试（成功即清除）。 */
    private final Map<String, SubscriptionBackoff> failureBackoff = new ConcurrentHashMap<>();

    private final Clock clock;

    /** 订阅**成功**计数（会话建立后真正订阅成功的设备数）。 */
    private final Counter subscribeSuccess;

    /** 订阅**失败**计数（失败不写跟踪表 ⇒ 退避后会对账重试；这里让它可观测）。 */
    private final Counter subscribeFailure;

    /** 因「在途订阅」跳过的次数（观测重复订阅被挡下多少）。 */
    private final Counter inFlightSkipped;

    /** 因「失败退避」跳过的次数。 */
    private final Counter backoffSkipped;

    public AccessSubscriptionPlanner(Supplier<Map<String, DeviceSession>> sessions,
                                     ObjectMapper objectMapper, AccessReadingSink readingSink,
                                     MeterRegistry meterRegistry, Clock clock) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.readingSink = readingSink;
        this.clock = clock;
        // 指标前缀统一为 `iot.access.*`（与既有的 `iot.access.lease.*` 一致；
        // S5 引入时用的 `ypbin.access.*` 是同一批 access 指标，混用会让大盘上出现两套前缀）
        this.subscribeSuccess = meterRegistry.counter("iot.access.subscribe.success");
        this.subscribeFailure = meterRegistry.counter("iot.access.subscribe.failure");
        this.inFlightSkipped = meterRegistry.counter("iot.access.subscribe.inflight.skipped");
        this.backoffSkipped = meterRegistry.counter("iot.access.subscribe.backoff.skipped");
    }

    /**
     * 为一批已绑定设备发起订阅（幂等：同一会话实例只会订阅一次）。
     *
     * @param devices 设备规格
     * @return 本次**发起**订阅的设备数（注意：订阅完成是异步的，成功与否看计数与日志；失败不会记录跟踪，
     *     下一个租约周期会对账重试）
     */
    @Override
    public int subscribe(List<DeviceSpec> devices) {
        Map<String, DeviceSession> bound = sessions.get();
        int subscribed = 0;
        for (DeviceSpec device : devices) {
            DeviceSession session = bound.get(device.deviceId());
            if (session == null) {
                // 启动期会话尚未建链（ApplicationRunner 早于 ApplicationReadyEvent）或建链失败：
                // 本方法由每个租约周期重复调用，因此这里只是「本轮跳过」，下一轮会补上
                log.debug("[access] 设备暂无会话，本轮跳过订阅（下个周期重试）：deviceId={}",
                    device.deviceId());
                continue;
            }
            if (subscribedSessions.get(device.deviceId()) == session) {
                continue;
            }
            if (inFlight.contains(device.deviceId())) {
                // G1：上一轮发起的订阅还没完成（异步），本轮不得重复发起
                inFlightSkipped.increment();
                log.debug("[access] 订阅仍在途，本轮跳过（避免重复订阅）：deviceId={}",
                    device.deviceId());
                continue;
            }
            if (inFailureBackoff(device.deviceId(), session)) {
                backoffSkipped.increment();
                log.debug("[access] 订阅失败退避中，本轮跳过：deviceId={}", device.deviceId());
                continue;
            }
            List<AccessPointMappingDto> points = parsePoints(device);
            if (points.isEmpty()) {
                log.debug("[access] 设备无点位映射，无需订阅：deviceId={}", device.deviceId());
                continue;
            }
            List<PointAddress> addresses = points.stream()
                .map(point -> PointAddress.of(point.getAddress()))
                .toList();
            SubscribeRequest request = device.pollInterval().isZero()
                ? SubscribeRequest.of(addresses)
                : new SubscribeRequest(addresses, device.pollInterval(), device.pollInterval(), null,
                    Map.of());
            PointMappingDataListener listener = new PointMappingDataListener(device.deviceId(),
                pollIntervalMs(device), points, readingSink);
            // ⚠️ S5 修复：**订阅成功之后**才记录跟踪。
            //    此前是先写 `subscribedSessions` 再 subscribe ⇒ 异步失败时跟踪表已记上「已订阅」，
            //    对账会认为无需重试 ⇒ 该设备**永久停止采集**且只有一行 ERROR 日志。
            //    现在失败不写 ⇒ 下一个租约周期自动重试。
            inFlight.add(device.deviceId());
            try {
                startSubscribe(session, request, listener, device, addresses);
            } catch (RuntimeException ex) {
                // ⚠️ 同步抛出（适配器契约并不禁止）：必须立刻摘掉在途标记，否则该设备**永久无法订阅**
                //    ——注释自己说了「不移除比重复订阅更糟」，那就不能只防异步失败这条路径。
                inFlight.remove(device.deviceId());
                subscribeFailure.increment();
                armFailureBackoff(device.deviceId(), session);
                log.error("[access] 订阅调用同步抛出异常（退避后重试）：deviceId={} addresses={}",
                    device.deviceId(), addresses.size(), ex);
                continue;
            }
            // 返回值是「本次**发起**的订阅数」：完成与否是异步的，调用方不得据此判断成功
            subscribed++;
        }
        return subscribed;
    }

    /**
     * 发起订阅并挂完成回调（回调里维护跟踪/退避/在途状态）。
     *
     * @param session   会话
     * @param request   订阅请求
     * @param listener  数据监听器
     * @param device    设备规格
     * @param addresses 点位地址（日志用）
     */
    private void startSubscribe(DeviceSession session, SubscribeRequest request,
                                PointMappingDataListener listener, DeviceSpec device,
                                List<PointAddress> addresses) {
        session.subscribe(request, listener).whenComplete((handle, error) -> {
            // 无论成败都要摘掉「在途」标记，否则该设备再也不会被订阅（比重复订阅更糟）
            inFlight.remove(device.deviceId());
            if (error != null) {
                subscribeFailure.increment();
                armFailureBackoff(device.deviceId(), session);
                // 订阅失败要暴露：否则表现为「采了但没数据」
                log.error("[access] 订阅失败（退避后重试）：deviceId={} addresses={} 下次重试={}秒后",
                    device.deviceId(), addresses.size(),
                    currentBackoffSeconds(device.deviceId()), error);
            } else {
                failureBackoff.remove(device.deviceId());
                subscribedSessions.put(device.deviceId(), session);
                subscribeSuccess.increment();
                log.info("[access] 订阅成功：deviceId={} 点位数={}", device.deviceId(),
                    addresses.size());
            }
        });
    }

    /**
     * 忘记某设备的订阅跟踪（设备被移除时由对账路径调用）。
     *
     * @param deviceId 设备标识
     */
    @Override
    public void forget(String deviceId) {
        failureBackoff.remove(deviceId);
        // 设备被移除时在途标记也要清：否则同 id 重新出现会一直被「在途」挡住
        inFlight.remove(deviceId);
        if (subscribedSessions.remove(deviceId) != null) {
            log.debug("[access] 设备已移除，清理订阅跟踪：deviceId={}", deviceId);
        }
    }

    @Override
    public Set<String> devicesWithoutSession(List<DeviceSpec> devices) {
        if (devices == null || devices.isEmpty()) {
            return Set.of();
        }
        Map<String, DeviceSession> bound = sessions.get();
        Set<String> missing = new LinkedHashSet<>();
        for (DeviceSpec device : devices) {
            if (bound.get(device.deviceId()) == null) {
                missing.add(device.deviceId());
            }
        }
        return missing;
    }

    /**
     * 是否处于订阅失败退避窗口内。
     *
     * <p><b>会话实例变了就不再退避</b>：退避是为了不把「同一个坏会话上的失败订阅」每个周期重发一遍；
     * 而实例变化说明框架已经重连（换了一条链路），此时立刻重试才是正确行为——否则一次失败会把
     * 「设备恢复」也挡在退避窗口外。</p>
     *
     * @param deviceId 设备 ID
     * @param session  当前会话实例
     * @return 处于退避窗口内返回 {@code true}
     */
    private boolean inFailureBackoff(String deviceId, DeviceSession session) {
        SubscriptionBackoff backoff = failureBackoff.get(deviceId);
        if (backoff == null) {
            return false;
        }
        if (backoff.session() != session) {
            failureBackoff.remove(deviceId);
            return false;
        }
        return clock.instant().isBefore(backoff.nextRetryAt());
    }

    /** 记一次订阅失败：指数退避（起点 {@link #SUBSCRIBE_BACKOFF_BASE}，上限 {@link #SUBSCRIBE_BACKOFF_MAX}）。 */
    private void armFailureBackoff(String deviceId, DeviceSession session) {
        SubscriptionBackoff previous = failureBackoff.get(deviceId);
        int attempts = previous == null || previous.session() != session ? 1 : previous.attempts() + 1;
        long millis = Math.min(SUBSCRIBE_BACKOFF_BASE.toMillis() * (1L << Math.min(attempts - 1, 20)),
            SUBSCRIBE_BACKOFF_MAX.toMillis());
        failureBackoff.put(deviceId, new SubscriptionBackoff(session, attempts,
            clock.instant().plusMillis(millis)));
    }

    /** 当前退避剩余（秒，仅用于日志；无退避返回 0）。 */
    private long currentBackoffSeconds(String deviceId) {
        SubscriptionBackoff backoff = failureBackoff.get(deviceId);
        if (backoff == null) {
            return 0L;
        }
        long millis = Duration.between(clock.instant(), backoff.nextRetryAt()).toMillis();
        return Math.max(0L, millis / 1000L);
    }

    /**
     * 订阅失败退避状态。
     *
     * @param session     失败发生在哪个会话实例上（实例变化即视为新上下文，退避作废）
     * @param attempts    连续失败次数
     * @param nextRetryAt 下一次允许发起订阅的时刻
     */
    private record SubscriptionBackoff(DeviceSession session, int attempts, Instant nextRetryAt) {
    }

    /**
     * 设备级采集周期（毫秒）：断档判定要用**真周期**（不然只能用兜底值，阈值会偏大或偏小）。
     *
     * @param device 设备规格
     * @return 周期毫秒；未指定（ZERO）返回 {@code null}
     */
    private static Integer pollIntervalMs(DeviceSpec device) {
        Duration interval = device.pollInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return null;
        }
        // 上限钳到 Integer.MAX_VALUE：超过 ~24.8 天的周期在协议上无意义，静默溢出成负数才是坑
        return (int) Math.min(interval.toMillis(), Integer.MAX_VALUE);
    }

    /** 已跟踪会话数的观测入口（测试/自检）。 */
    public int trackedSessionCount() {
        return subscribedSessions.size();
    }

    private List<AccessPointMappingDto> parsePoints(DeviceSpec device) {
        String json = device.properties().get(HttpDeviceSpecSource.PROPERTY_POINTS);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AccessPointMappingDto>>() { });
        } catch (Exception ex) {
            // 解析失败属契约/编程错误：暴露，不降级为「没有点位」
            throw new IllegalStateException("设备点位清单解析失败：deviceId=" + device.deviceId(), ex);
        }
    }
}

