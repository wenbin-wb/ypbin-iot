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
 * 订阅数据回调：把「协议地址」翻译成「平台属性」，并应用线性缩放后交给出口。
 *
 * <p><b>为什么需要显式适配</b>：协议栈讲的坐标是<b>地址</b>（holding:1 / topic / nodeId），
 * 平台讲的坐标是<b>属性</b>（propertyId）。两者是不同模型，必须有一层翻译（§5.1 的「点位映射」），
 * 这不是「用改名掩盖分歧」。</p>
 *
 * <p><b>未映射的地址不静默</b>：记 WARN 且同时计数——采集到的点却没进平台，属配置缺口，
 * 必须能从日志/指标看见，否则会表现为「页面一直没数据、但没人知道为什么」。</p>
 *
 * <p>线程安全：本对象只在协议栈的订阅回调线程上使用；计数用 {@code long[]} 单元素数组由调用方
 * 保证可见性，或改由出口侧统计（3b-2 占位实现从简）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class PointMappingDataListener implements DataListener {

    private static final Logger log = LoggerFactory.getLogger(PointMappingDataListener.class);

    private final String deviceId;
    private final Map<String, AccessPointMappingDto> pointByAddress;
    private final AccessReadingSink sink;

    private long unmappedCount;

    public PointMappingDataListener(String deviceId, List<AccessPointMappingDto> points,
                                    AccessReadingSink sink) {
        this.deviceId = deviceId;
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
        Object mapped = AccessReading.applyScale(value.value(), point.getScaleFactor(),
            point.getOffsetValue());
        sink.accept(new AccessReading(deviceId, point.getPropertyId(), mapped,
            value.quality().name(), value.timestamp()));
    }

    /** 本设备被丢弃的未映射读数条数（供自检与测试断言）。 */
    public long unmappedCount() {
        return unmappedCount;
    }
}
