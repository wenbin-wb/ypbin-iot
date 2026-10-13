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
import cn.ypbin.admin.access.egress.NormalizeFailure;
import cn.ypbin.admin.access.egress.PointValueNormalizeMetrics;
import cn.ypbin.admin.access.egress.PointValueNormalizer;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.admin.iot.enums.IotProtocol;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅数据回调：把「协议地址」翻译成「平台点位」，做物模型类型规范化 + 线性缩放后交给出口。
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
 * 该统一的口径见 {@code AccessReading} / {@link cn.ypbin.admin.iot.mapping.PointMappingIndex}。</p>
 *
 * <p><b>帧解析已交给 SDK，类型规范化仍在宿主</b>（2026-10-13）：接入侧已切到 starter 0.3.0 的
 * {@code payload-format=key-value}，由 {@code TcpValueSelector} 完成「UTF-8 + KEY=VALUE 拆分 +
 * 按 {@code raw_address}（= selector）取键」，本类不再做帧解析。但 SDK <b>不做数据类型转换</b>
 * （其类注释明确：类型属于宿主物模型语义）⇒ 这里仍按 {@code iot_property.data_type} 调用
 * {@link PointValueNormalizer}：它是 {@link AccessReading#applyScale}（只吃 {@code Number}/{@code BigDecimal}）
 * 与 iot 侧 {@code ReadingValueMapper} 落列形态的前置。理由与边界见 {@code PointValueNormalizer} 类注释。</p>
 *
 * <p><b>BAD 质量原样穿透</b>：SDK 在 selector 未命中/非 UTF-8/无键值字段时给出 <b>BAD</b>
 * （{@code value=null}）。这类值<b>不进入规范化</b>、不计规范化失败，直接以 BAD 质量上报——
 * 「失败不得被画成有效值」由 quality 保证；对应的帧/选择失败由 SDK 的 {@code iot.tcp.*} 指标承接。</p>
 *
 * <p><b>属性标识缺失（孤儿映射）不静默</b>：映射行引用的 {@code iot_property} 行被物模型重导入
 * 物理删除后，设备规格里该点位的 {@code identifier} 为空。此时**必须丢弃该读数并计数 + WARN**，
 * 绝不能退回主键字符串（那等于把刚统一掉的形态又写回去），也不能静默丢掉。</p>
 *
 * <p><b>未映射的地址不静默</b>：记 WARN 且同时计数——采集到的点却没进平台，属配置缺口，
 * 必须能从日志/指标看见，否则会表现为「页面一直没数据、但没人知道为什么」。</p>
 *
 * <p>线程安全：规范化失败/未映射/孤儿映射三个自检计数用 {@link LongAdder}（多协议线程可能并发回调；
 * 普通 {@code long} 自增会丢更新）。它们<b>只用于自检与测试断言</b>，不参与业务判定；
 * 面向运维的告警计数以 {@code iot.access.value.normalize.failure} 与 SDK 的 {@code iot.tcp.*} 指标为准。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class PointMappingDataListener implements DataListener {

    private static final Logger log = LoggerFactory.getLogger(PointMappingDataListener.class);

    private final String deviceId;

    /** 接入协议码：只有 TCP 需要宿主类型规范化（其它协议模块已交付带类型的值）。 */
    private final String protocol;

    /** 设备级采集周期（毫秒，未知为 {@code null}）：随读数一起上报，供断档判定用真周期。 */
    private final Integer pollIntervalMs;

    private final Map<String, AccessPointMappingDto> pointByAddress;
    private final AccessReadingSink sink;

    private final MeterRegistry meterRegistry;

    private final LongAdder unmappedCount = new LongAdder();

    /** 因映射缺少属性标识（孤儿映射）而丢弃的读数条数（与「地址未映射」分开计）。 */
    private final LongAdder orphanCount = new LongAdder();

    /** 因物模型类型规范化失败（非数值/非布尔/类型未知）而丢弃的读数条数（与上面两类原因分开计）。 */
    private final LongAdder normalizeFailureCount = new LongAdder();

    public PointMappingDataListener(String deviceId, String protocol, Integer pollIntervalMs,
                                    List<AccessPointMappingDto> points, AccessReadingSink sink,
                                    MeterRegistry meterRegistry) {
        this.deviceId = deviceId;
        this.protocol = protocol;
        this.pollIntervalMs = pollIntervalMs;
        this.sink = sink;
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
            unmappedCount.increment();
            log.warn("[access] 采集到未映射的地址，已丢弃：device={} address={}（点位映射缺失？）",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(address));
            return;
        }
        // 规范坐标：属性标识。缺标识说明该映射是孤儿（物模型属性行已被删除）⇒ 丢弃 + 计数 + WARN
        String identifier = point.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            orphanCount.increment();
            log.warn("[access] 点位映射缺少属性标识（物模型属性行可能已被重导入删除），已丢弃该读数："
                    + "device={} address={} 属性主键={}（请重建该点位映射）",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(address),
                LogSanitizer.sanitize(point.getPropertyId()));
            return;
        }
        // SDK 的 BAD 原样穿透：不规范化、不计失败（失败由 quality=BAD 表达，SDK 侧另有 iot.tcp.* 指标）
        if (!value.isGood()) {
            emit(identifier, value, value.value());
            return;
        }
        Object raw = value.value();
        if (IotProtocol.TCP.getCode().equals(protocol)) {
            // SDK key-value 只做字段选择、不做类型转换；这里按物模型 dataType 规范化，失败即丢弃 + 计数
            PointValueNormalizer.Outcome outcome = PointValueNormalizer.normalize(raw, point.getDataType());
            if (!outcome.success()) {
                onNormalizeFailure(point, outcome.failure(), raw);
                return;
            }
            raw = outcome.value();
        }
        emit(identifier, value, AccessReading.applyScale(raw, point.getScaleFactor(),
            point.getOffsetValue()));
    }

    /** 上报一条读数（质量/时刻/周期随原始 PointValue 带出；BAD 的 value 可为 null）。 */
    private void emit(String identifier, PointValue value, Object mapped) {
        sink.accept(new AccessReading(deviceId, identifier, mapped, value.quality().name(),
            value.timestamp(), pollIntervalMs));
    }

    /**
     * 记一次物模型类型规范化失败：丢弃该点 + 计数（按原因）+ WARN（脱敏：只记长度与配置，不 dump 值）。
     *
     * <p>为什么值内容不落日志：帧内容可能含序列号/凭据类字段，采集中转日志不该成为泄露面；
     * 排障需要的「是哪类失败」由原因码与点位配置给出，长度足以区分空值与格式不符。</p>
     *
     * @param point   点位映射
     * @param failure 失败原因
     * @param raw     原始值（只取长度）
     */
    private void onNormalizeFailure(AccessPointMappingDto point, NormalizeFailure failure, Object raw) {
        normalizeFailureCount.increment();
        PointValueNormalizeMetrics.counter(meterRegistry, failure).increment();
        log.warn("[access] 读数类型规范化失败，已丢弃该点：device={} property={} address={} 原因={}({}) "
                + "值长度={} 数据类型={}（见 {} 指标；物模型 dataType 与设备帧内容需一致）",
            LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(point.getIdentifier()),
            LogSanitizer.sanitize(point.getAddress()), failure.getCode(), failure.getDesc(),
            valueLength(raw), LogSanitizer.sanitize(point.getDataType()),
            PointValueNormalizeMetrics.METRIC_NAME);
    }

    /** 值长度（null 为 -1，用于区分「没拿到值」与「空串」）。 */
    private static int valueLength(Object raw) {
        return raw == null ? -1 : raw.toString().length();
    }

    /** 本设备被丢弃的未映射读数条数（供自检与测试断言；并发下无丢更新）。 */
    public long unmappedCount() {
        return unmappedCount.sum();
    }

    /** 本设备因孤儿映射（缺属性标识）被丢弃的读数条数（供自检与测试断言）。 */
    public long orphanCount() {
        return orphanCount.sum();
    }

    /** 本设备因物模型类型规范化失败被丢弃的读数条数（供自检与测试断言）。 */
    public long normalizeFailureCount() {
        return normalizeFailureCount.sum();
    }
}
