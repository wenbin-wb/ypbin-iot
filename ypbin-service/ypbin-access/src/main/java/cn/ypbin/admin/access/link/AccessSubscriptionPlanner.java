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
import java.util.List;
import java.util.Map;
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

    private final Supplier<Map<String, DeviceSession>> sessions;
    private final ObjectMapper objectMapper;
    private final AccessReadingSink readingSink;

    /**
     * 已订阅的**会话实例**（按 deviceId）。会话实例变了（框架重连会新建会话）就必须重订阅，
     * 否则表现为「链路恢复但数据不再上报」——这是框架不给宿主发重连事件时的唯一可靠判据。
     */
    private final Map<String, DeviceSession> subscribedSessions = new ConcurrentHashMap<>();

    /** 订阅**成功**计数（会话建立后真正订阅成功的设备数）。 */
    private final Counter subscribeSuccess;

    /** 订阅**失败**计数（失败不写跟踪表 ⇒ 下一个租约周期会对账重试；这里让它可观测）。 */
    private final Counter subscribeFailure;

    public AccessSubscriptionPlanner(Supplier<Map<String, DeviceSession>> sessions,
                                     ObjectMapper objectMapper, AccessReadingSink readingSink,
                                     MeterRegistry meterRegistry) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.readingSink = readingSink;
        // 指标前缀统一为 `iot.access.*`（与既有的 `iot.access.lease.*` 一致；
        // S5 引入时用的 `ypbin.access.*` 是同一批 access 指标，混用会让大盘上出现两套前缀）
        this.subscribeSuccess = meterRegistry.counter("iot.access.subscribe.success");
        this.subscribeFailure = meterRegistry.counter("iot.access.subscribe.failure");
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
            PointMappingDataListener listener =
                new PointMappingDataListener(device.deviceId(), points, readingSink);
            // ⚠️ S5 修复：**订阅成功之后**才记录跟踪。
            //    此前是先写 `subscribedSessions` 再 subscribe ⇒ 异步失败时跟踪表已记上「已订阅」，
            //    对账会认为无需重试 ⇒ 该设备**永久停止采集**且只有一行 ERROR 日志。
            //    现在失败不写 ⇒ 下一个租约周期自动重试。
            session.subscribe(request, listener).whenComplete((handle, error) -> {
                if (error != null) {
                    subscribeFailure.increment();
                    // 订阅失败要暴露：否则表现为「采了但没数据」
                    log.error("[access] 订阅失败（下一轮对账将重试）：deviceId={} addresses={}",
                        device.deviceId(), addresses.size(), error);
                } else {
                    subscribedSessions.put(device.deviceId(), session);
                    subscribeSuccess.increment();
                    log.info("[access] 订阅成功：deviceId={} 点位数={}", device.deviceId(),
                        addresses.size());
                }
            });
            // 返回值是「本次**发起**的订阅数」：完成与否是异步的，调用方不得据此判断成功
            subscribed++;
        }
        return subscribed;
    }

    /**
     * 忘记某设备的订阅跟踪（设备被移除时由对账路径调用）。
     *
     * @param deviceId 设备标识
     */
    @Override
    public void forget(String deviceId) {
        if (subscribedSessions.remove(deviceId) != null) {
            log.debug("[access] 设备已移除，清理订阅跟踪：deviceId={}", deviceId);
        }
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

