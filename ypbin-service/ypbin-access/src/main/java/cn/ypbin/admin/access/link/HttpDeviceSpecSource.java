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

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.admin.iot.device.IDeviceSpecClient;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.starter.core.model.R;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 经内部接口取设备规格的 {@link DeviceSpecSource} 实现（access 无 JDBC，只能走内部 API）。
 *
 * <p><b>两次调用只发一次请求</b>：协议栈在建链时会按 {@code connectionId} 回调
 * {@link #findConnection(String)} 取连接参数，而它拿不到租户。因此这里把 {@code connectionId} 约定为
 * {@code t{tenantId}-d{deviceId}}（服务端生成，自带租户信息），并缓存「按租户拉到的规格」：
 * 建链时只查缓存/最多补一次拉取，不重复打远端。</p>
 *
 * <p><b>失败语义（不静默）</b>：内部接口返回非成功信封或调用异常时，记 ERROR 日志并**返回空集合**——
 * 让本轮引导「少采」而不是让租约调度线程被远端拖挂；失败原因在日志里可见，下一轮会自然重试。
 * 这一点与「连接参数缺失返回 {@code Optional.empty()} 让框架跳过设备」是同一取向。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class HttpDeviceSpecSource implements DeviceSpecSource {

    private static final Logger log = LoggerFactory.getLogger(HttpDeviceSpecSource.class);

    /** 点位清单随设备规格一起传递的属性键（订阅规划器据此构造 SubscribeRequest）。 */
    public static final String PROPERTY_POINTS = "points";

    private static final char TENANT_PREFIX = 't';

    private static final String DEVICE_MARKER = "-d";

    private final IDeviceSpecClient client;
    private final ObjectMapper objectMapper;

    /** 最近一次成功拉取结果：tenantId → (connectionId → 规格)。 */
    private final Map<Long, Map<String, AccessDeviceSpecResp>> cache = new ConcurrentHashMap<>();

    public HttpDeviceSpecSource(IDeviceSpecClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DeviceSpec> loadByTenant(Long tenantId) {
        Map<String, AccessDeviceSpecResp> specs = fetch(tenantId);
        return specs.values().stream().map(this::toDeviceSpec).toList();
    }

    @Override
    public Optional<ConnectionSpec> findConnection(String connectionId) {
        Long tenantId = parseTenantId(connectionId);
        if (tenantId == null) {
            log.warn("[access] 无法从连接标识解析租户，连接参数不可解析：connectionId={}", connectionId);
            return Optional.empty();
        }
        Map<String, AccessDeviceSpecResp> specs = cache.get(tenantId);
        if (specs == null) {
            specs = fetch(tenantId);
        }
        AccessDeviceSpecResp spec = specs.get(connectionId);
        if (spec == null) {
            return Optional.empty();
        }
        // connectTimeout/requestTimeout/tls 传 null：record 的紧凑构造器会填框架默认值
        return Optional.of(new ConnectionSpec(spec.getConnectionId(),
            ProtocolCode.of(spec.getProtocol()), Endpoint.of(spec.getEndpoint()),
            null, null, null, spec.getCredentialRef(), Map.of()));
    }

    /** 拉取并缓存；失败返回空表（已记 ERROR，不静默）。 */
    private Map<String, AccessDeviceSpecResp> fetch(Long tenantId) {
        R<List<AccessDeviceSpecResp>> response;
        try {
            response = client.listByTenant(tenantId);
        } catch (RuntimeException ex) {
            log.error("[access] 拉取设备规格失败（本轮该租户按无设备处理）：tenantId={}", tenantId, ex);
            return Map.of();
        }
        if (response == null || !response.isSuccess() || response.getData() == null) {
            log.error("[access] 设备规格内部接口返回失败信封（本轮该租户按无设备处理）：tenantId={} code={} msg={}",
                tenantId, response == null ? null : response.getCode(),
                response == null ? null : response.getMessage());
            return Map.of();
        }
        Map<String, AccessDeviceSpecResp> byConnection = new LinkedHashMap<>();
        for (AccessDeviceSpecResp spec : response.getData()) {
            byConnection.put(spec.getConnectionId(), spec);
        }
        cache.put(tenantId, byConnection);
        return byConnection;
    }

    private DeviceSpec toDeviceSpec(AccessDeviceSpecResp spec) {
        Duration pollInterval = spec.getPollIntervalMs() == null || spec.getPollIntervalMs() <= 0
            ? Duration.ZERO : Duration.ofMillis(spec.getPollIntervalMs());
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PROPERTY_POINTS, writePoints(spec.getPoints()));
        return new DeviceSpec(spec.getDeviceId(), spec.getDeviceName(),
            ProtocolCode.of(spec.getProtocol()), spec.getConnectionId(), "", pollInterval, properties);
    }

    private String writePoints(List<AccessPointMappingDto> points) {
        try {
            return objectMapper.writeValueAsString(points == null ? List.of() : points);
        } catch (JsonProcessingException ex) {
            // 点位序列化失败属编程/模型错误：暴露出来，不静默降级成「没有点位」
            throw new IllegalStateException("点位清单序列化失败：device spec 无法交给协议栈", ex);
        }
    }

    /** 解析 {@code t{tenantId}-d{deviceId}} 中的租户号；不合法返回 {@code null}。 */
    private Long parseTenantId(String connectionId) {
        if (connectionId == null || connectionId.isEmpty() || connectionId.charAt(0) != TENANT_PREFIX) {
            return null;
        }
        int marker = connectionId.indexOf(DEVICE_MARKER);
        if (marker <= 1) {
            return null;
        }
        try {
            return Long.valueOf(connectionId.substring(1, marker));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
