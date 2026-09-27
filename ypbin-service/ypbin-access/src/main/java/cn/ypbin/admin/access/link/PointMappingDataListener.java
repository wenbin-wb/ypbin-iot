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

import cn.ypbin.admin.access.decode.DecodeFailure;
import cn.ypbin.admin.access.decode.DecodeOutcome;
import cn.ypbin.admin.access.decode.ValueDecoder;
import cn.ypbin.admin.access.egress.AccessReading;
import cn.ypbin.admin.access.egress.AccessReadingSink;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p><b>原始字节必须先解码再上报</b>（2026-09-27）：TCP 透传的 {@code PointValue.value} 是**原始
 * {@code byte[]}**（框架侧 {@code TcpSession#dispatch} 不做解码），直接经出口 {@code String.valueOf}
 * 会落成 {@code [B@<hash>} 这种无值语义的文本（曲线只能进文本列 ⇒ 画不出来）。因此这里插入
 * {@link ValueDecoder} 一道：按协议码选解码器，按物模型数据类型与点位映射参数产出规范值；
 * **解不出来就丢弃 + 计数 + WARN**，绝不把 {@code [B@…} 交给出口。缺口归属与替代路径见
 * {@code docs/STARTER-FEEDBACK.md} SF-6 与 {@code docs/VALUE-DECODE-DESIGN.md}。</p>
 *
 * <p>线程安全：本对象只在协议栈的订阅回调线程上使用；计数用普通 {@code long} 字段由调用方
 * 保证可见性，或改由出口侧统计（3b-2 占位实现从简）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class PointMappingDataListener implements DataListener {

    /** 解码失败计数（按原因打标签，取值集合有界）。 */
    public static final String METRIC_DECODE_FAILURE = "iot.access.decode.failure";

    /** 失败原因标签名。 */
    private static final String TAG_REASON = "reason";

    private static final Logger log = LoggerFactory.getLogger(PointMappingDataListener.class);

    private final String deviceId;

    /** 接入协议码（决定用哪个解码器）。 */
    private final String protocol;

    /** 设备级采集周期（毫秒，未知为 {@code null}）：随读数一起上报，供断档判定用真周期。 */
    private final Integer pollIntervalMs;

    private final Map<String, AccessPointMappingDto> pointByAddress;
    private final AccessReadingSink sink;

    /** 解码器（先命中 {@code supports} 者生效；空表示「没有解码器可用」⇒ 原样透传）。 */
    private final List<ValueDecoder> decoders;

    private final MeterRegistry meterRegistry;

    private long unmappedCount;

    /** 因映射缺少属性标识（孤儿映射）而丢弃的读数条数（与「地址未映射」分开计）。 */
    private long orphanCount;

    /** 因解码失败（帧格式/键/类型/数值形态）而丢弃的读数条数（与上面两类原因分开计）。 */
    private long decodeFailureCount;

    public PointMappingDataListener(String deviceId, String protocol, Integer pollIntervalMs,
                                    List<AccessPointMappingDto> points, AccessReadingSink sink,
                                    List<ValueDecoder> decoders, MeterRegistry meterRegistry) {
        this.deviceId = deviceId;
        this.protocol = protocol;
        this.pollIntervalMs = pollIntervalMs;
        this.sink = sink;
        this.decoders = decoders == null ? List.of() : List.copyOf(decoders);
        this.meterRegistry = meterRegistry;
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
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(address));
            return;
        }
        // 规范坐标：属性标识。缺标识说明该映射是孤儿（物模型属性行已被删除）⇒ 丢弃 + 计数 + WARN
        String identifier = point.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            orphanCount++;
            log.warn("[access] 点位映射缺少属性标识（物模型属性行可能已被重导入删除），已丢弃该读数："
                    + "device={} address={} 属性主键={}（请重建该点位映射）",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(address),
                LogSanitizer.sanitize(point.getPropertyId()));
            return;
        }
        DecodeOutcome outcome = decode(value.value(), point);
        if (!outcome.success()) {
            onDecodeFailure(point, outcome.failure(), value.value());
            return;
        }
        Object mapped = AccessReading.applyScale(outcome.value(), point.getScaleFactor(),
            point.getOffsetValue());
        sink.accept(new AccessReading(deviceId, identifier, mapped,
            value.quality().name(), value.timestamp(), pollIntervalMs));
    }

    /**
     * 按协议码选解码器解码。
     *
     * <p>没有解码器处理该协议时**原样透传**：Modbus/OPC UA 等协议模块给出的已是带类型的值，
     * 这条路径保证「其它协议/其它设备的取值路径不受影响」。</p>
     *
     * @param rawValue 原始值
     * @param point    点位映射
     * @return 解码结果
     */
    private DecodeOutcome decode(Object rawValue, AccessPointMappingDto point) {
        for (ValueDecoder decoder : decoders) {
            if (decoder.supports(protocol)) {
                return decoder.decode(rawValue, point.getAddress(), point.getDataType());
            }
        }
        return DecodeOutcome.ok(rawValue);
    }

    /**
     * 记一次解码失败：计数（按原因）+ WARN（脱敏：只记长度与配置，不 dump 载荷内容）。
     *
     * <p>为什么载荷内容不落日志：帧内容可能含序列号/凭据类字段，采集中转日志不该成为泄露面；
     * 排障需要的「是哪类失败」由原因码与点位配置给出，载荷长度足以区分空帧与格式不符。</p>
     *
     * @param point     点位映射
     * @param failure   失败原因
     * @param rawValue  原始值（只取长度）
     */
    private void onDecodeFailure(AccessPointMappingDto point, DecodeFailure failure, Object rawValue) {
        decodeFailureCount++;
        Counter counter = meterRegistry.counter(METRIC_DECODE_FAILURE, TAG_REASON, failure.getCode());
        counter.increment();
        log.warn("[access] 读数解码失败，已丢弃该点：device={} property={} address={} 原因={}({}) "
                + "载荷长度={} 数据类型={}（见 {} 指标；帧格式/点位地址/物模型类型需一致）",
            LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(point.getIdentifier()),
            LogSanitizer.sanitize(point.getAddress()), failure.getCode(), failure.getDesc(),
            payloadLength(rawValue), LogSanitizer.sanitize(point.getDataType()),
            METRIC_DECODE_FAILURE);
    }

    /** 载荷长度（非字节载荷为 -1，用于区分「没拿到字节」与「空帧」）。 */
    private static int payloadLength(Object rawValue) {
        return rawValue instanceof byte[] payload ? payload.length : -1;
    }

    /** 本设备被丢弃的未映射读数条数（供自检与测试断言）。 */
    public long unmappedCount() {
        return unmappedCount;
    }

    /** 本设备因孤儿映射（缺属性标识）被丢弃的读数条数（供自检与测试断言）。 */
    public long orphanCount() {
        return orphanCount;
    }

    /** 本设备因解码失败被丢弃的读数条数（供自检与测试断言）。 */
    public long decodeFailureCount() {
        return decodeFailureCount;
    }
}
