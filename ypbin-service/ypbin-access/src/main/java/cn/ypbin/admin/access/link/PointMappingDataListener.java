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

import cn.ypbin.admin.access.egress.AccessReading;
import cn.ypbin.admin.access.egress.AccessReadingSink;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.PointValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅数据回调：把「协议地址」翻译成「平台点位」，并应用线性缩放后交给出口。
 *
 * <p><b>为什么需要显式适配</b>：协议栈讲的坐标是<b>地址</b>（holding:1 / topic / nodeId），
 * 平台讲的坐标是<b>属性标识</b>（{@code iot_property.identifier}，即规范坐标）。两者是不同模型，
 * 必须有一层翻译（§5.1 的「点位映射」），这不是「用改名掩盖分歧」。</p>
 *
 * <p><b>上报的是属性标识，不是属性主键字符串</b>（2026-09-26 统一）：统一之前这里上报
 * {@code AccessPointMappingDto.propertyId}（主键的字符串形式），导致同一个
 * {@code iot:latest:{租户}:{设备}} 哈希里出现两种 field（access 来源是主键、MQTT/前端是标识），
 * 读侧按标识查询**拿不到 access 来源的数据**。现在一律上报 {@code identifier}，
 * 并由 iot 侧入站再归一一次（滚动升级期间旧实例仍可能发主键字符串，入站两种都认）。
 * 详见 {@code docs/IOT-ROADMAP.md} 四点十七补充段。</p>
 *
 * <p><b>属性标识缺失（孤儿映射）不静默</b>：映射行引用的 {@code iot_property} 行被物模型重导入
 * 物理删除后，设备规格里该点位的 {@code identifier} 为空。此时**必须丢弃该读数并计数 + WARN**，
 * 绝不能退回主键字符串（那等于把刚统一掉的形态又写回去），也不能静默丢掉。</p>
 *
 * <p><b>未映射的地址不静默</b>：记 WARN 且同时计数——采集到的点却没进平台，属配置缺口，
 * 必须能从日志/指标看见，否则会表现为「页面一直没数据、但没人知道为什么」。</p>
 *
 * <p>线程安全：本对象只在协议栈的订阅回调线程上使用；计数用普通 {@code long} 字段由调用方
 * 保证可见性，或改由出口侧统计（3b-2 占位实现从简）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class PointMappingDataListener implements DataListener {

    private static final Logger log = LoggerFactory.getLogger(PointMappingDataListener.class);

    private final String deviceId;

    /** 设备级采集周期（毫秒，未知为 {@code null}）：随读数一起上报，供断档判定用真周期。 */
    private final Integer pollIntervalMs;

    private final Map<String, AccessPointMappingDto> pointByAddress;
    private final AccessReadingSink sink;

    private long unmappedCount;

    /** 因映射缺少属性标识（孤儿映射）而丢弃的读数条数（与「地址未映射」分开计）。 */
    private long orphanCount;

    public PointMappingDataListener(String deviceId, Integer pollIntervalMs,
                                    List<AccessPointMappingDto> points, AccessReadingSink sink) {
        this.deviceId = deviceId;
        this.pollIntervalMs = pollIntervalMs;
        this.sink = sink;
        Map<String, AccessPointMappingDto> byAddress = new LinkedHashMap<>();
        if (points != null) {
            for (AccessPointMappingDto point : points) {
                if (point.getAddress() != null && !point.getAddress().isBlank()) {
                    byAddress.put(point.getAddress(), point);
                }
            }
        }
        this.pointByAddress = byAddress;
    }

    @Override
    public void onData(PointValue value) {
        String address = value.address().raw();
        AccessPointMappingDto point = pointByAddress.get(address);
        if (point == null) {
            unmappedCount++;
            log.warn("[access] 采集到未映射的地址，已丢弃：device={} address={}（点位映射缺失？）",
                deviceId, address);
            return;
        }
        // 规范坐标：属性标识。缺标识说明该映射是孤儿（物模型属性行已被删除）⇒ 丢弃 + 计数 + WARN
        String identifier = point.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            orphanCount++;
            log.warn("[access] 点位映射缺少属性标识（物模型属性行可能已被重导入删除），已丢弃该读数："
                    + "device={} address={} 属性主键={}（请重建该点位映射）",
                deviceId, address, point.getPropertyId());
            return;
        }
        Object mapped = AccessReading.applyScale(value.value(), point.getScaleFactor(),
            point.getOffsetValue());
        sink.accept(new AccessReading(deviceId, identifier, mapped,
            value.quality().name(), value.timestamp(), pollIntervalMs));
    }

    /** 本设备被丢弃的未映射读数条数（供自检与测试断言）。 */
    public long unmappedCount() {
        return unmappedCount;
    }

    /** 本设备因孤儿映射（缺属性标识）被丢弃的读数条数（供自检与测试断言）。 */
    public long orphanCount() {
        return orphanCount;
    }
}
