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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
public class AccessSubscriptionPlanner {

    private static final Logger log = LoggerFactory.getLogger(AccessSubscriptionPlanner.class);

    private final Supplier<Map<String, DeviceSession>> sessions;
    private final ObjectMapper objectMapper;
    private final AccessReadingSink readingSink;

    public AccessSubscriptionPlanner(Supplier<Map<String, DeviceSession>> sessions,
                                     ObjectMapper objectMapper, AccessReadingSink readingSink) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.readingSink = readingSink;
    }

    /**
     * 为一批已绑定的设备建立订阅。
     *
     * @param devices 已发过 ADD 的设备规格（框架应已建链）
     * @return 成功建立订阅的设备数
     */
    public int subscribe(List<DeviceSpec> devices) {
        Map<String, DeviceSession> bound = sessions.get();
        int subscribed = 0;
        for (DeviceSpec device : devices) {
            DeviceSession session = bound.get(device.deviceId());
            if (session == null) {
                // 建链失败时框架已记日志；这里补一条说明「该设备不会有数据」
                log.warn("[access] 设备无可用会话，跳过订阅（本轮该设备不采集）：deviceId={}",
                    device.deviceId());
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
            session.subscribe(request, listener).whenComplete((handle, error) -> {
                if (error != null) {
                    // 订阅失败要暴露：否则表现为「采了但没数据」
                    log.error("[access] 订阅失败：deviceId={} addresses={}", device.deviceId(),
                        addresses.size(), error);
                } else {
                    log.info("[access] 订阅成功：deviceId={} 点位数={}", device.deviceId(),
                        addresses.size());
                }
            });
            subscribed++;
        }
        return subscribed;
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

