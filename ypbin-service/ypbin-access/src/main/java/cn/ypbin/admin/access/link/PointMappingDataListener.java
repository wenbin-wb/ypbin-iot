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
import cn.ypbin.admin.iot.modbus.ModbusRegisterAddress;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅数据回调：把「协议地址」翻译成「平台点位」，做类型/寄存器解码与线性缩放后交给出口。
 *
 * <p><b>协议地址 → 点位</b>：协议栈按地址回调，平台按属性标识（{@code iot_property.identifier}）上报。
 * 本类同时承担三条取值路径：</p>
 * <ol>
 *   <li><b>TCP key-value</b>：SDK 交付 {@code String}，按物模型 {@code data_type} 规范化
 *       （见 {@link PointValueNormalizer}）；</li>
 *   <li><b>Modbus 单寄存器</b>（{@code word_count=1}）：SDK 交付 16 位无符号 {@code Integer}；
 *       {@code signed=true} 时做符号扩展，否则**原样透传**（向后兼容锚点）；</li>
 *   <li><b>Modbus 多寄存器</b>（{@code word_count=2}）：SDK 对每个寄存器**逐值回调**（不是批量），
 *       本类按点位**配对**两个寄存器后再合成一条读数——任一寄存器 BAD/缺失 ⇒ 该点位 BAD，
 *       **绝不用陈旧一半拼值、绝不用 0 充数**（见 {@link #onMultiRegister}）。</li>
 * </ol>
 *
 * <p><b>BAD 质量原样穿透</b>：SDK 的 BAD（TCP selector 未命中、Modbus 超时/响应短等）以 BAD 质量表达，
 * 宿主不把它「洗白」成 GOOD；对应失败由 SDK 指标承接。</p>
 *
 * <p>线程安全：计数用 {@link LongAdder}；32 位点位的半字暂存用并发 Map + 短临界区保证「配对/清空」原子。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class PointMappingDataListener implements DataListener {

    private static final Logger log = LoggerFactory.getLogger(PointMappingDataListener.class);

    /** 半字配对容差兜底（毫秒）：采集周期未知时使用（超过它即视为陈旧一半）。 */
    static final long DEFAULT_PAIR_TTL_MS = 5_000L;

    private final String deviceId;

    /** 接入协议码（决定取值路径：tcp 规范化 / modbus 寄存器解码）。 */
    private final String protocol;

    /** 设备级采集周期（毫秒，未知为 {@code null}）：随读数上报，并作为半字配对容差。 */
    private final Integer pollIntervalMs;

    /** 寄存器地址 → (点位, 第几个字)。word_count=2 时 base→0、base+1→1。 */
    private final Map<String, RegisterTarget> registerByAddress;

    private final AccessReadingSink sink;

    private final MeterRegistry meterRegistry;

    private final LongAdder unmappedCount = new LongAdder();

    /** 因映射缺少属性标识（孤儿映射）而丢弃的读数条数（与「地址未映射」分开计）。 */
    private final LongAdder orphanCount = new LongAdder();

    /** 因物模型类型规范化失败（TCP）而丢弃的读数条数。 */
    private final LongAdder normalizeFailureCount = new LongAdder();

    /** 32 位点位半字配对陈旧被丢弃的次数（与 Micrometer 计数器同源）。 */
    private final LongAdder pairStaleCount = new LongAdder();

    /** Modbus float 解出 NaN/±Inf 而按 BAD 上报的条数（与 Micrometer 计数器同源）。 */
    private final LongAdder nonFiniteFloatCount = new LongAdder();

    /** 32 位点位的半字暂存：base 地址 → 已到达的一半。每设备至多「word_count=2 点位数」条。 */
    private final Map<String, Half> pairBuffer = new ConcurrentHashMap<>();

    /** 半字配对的短临界区锁（配对/清空必须原子，避免并发回调把两半错配）。 */
    private final Object pairLock = new Object();

    public PointMappingDataListener(String deviceId, String protocol, Integer pollIntervalMs,
                                    List<AccessPointMappingDto> points, AccessReadingSink sink,
                                    MeterRegistry meterRegistry) {
        this.deviceId = deviceId;
        this.protocol = protocol;
        this.pollIntervalMs = pollIntervalMs;
        this.sink = sink;
        this.meterRegistry = meterRegistry;
        boolean modbus = IotProtocol.MODBUS.getCode().equals(protocol);
        Map<String, RegisterTarget> byAddress = new LinkedHashMap<>();
        if (points != null) {
            for (AccessPointMappingDto point : points) {
                String base = point.getAddress();
                if (base == null || base.isBlank()) {
                    continue;
                }
                if (modbus && isMultiRegister(point)) {
                    try {
                        byAddress.put(base, new RegisterTarget(point, 0));
                        byAddress.put(ModbusRegisterAddress.next(base), new RegisterTarget(point, 1));
                    } catch (RuntimeException ex) {
                        // 映射校验应已拦住（word_count=2 必须是显式 type:offset）；直连 DB 的坏数据在这里暴露
                        log.error("[access] Modbus 多寄存器点位地址非法，跳过该点位：device={} address={}",
                            LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(base), ex);
                    }
                } else {
                    byAddress.put(base, new RegisterTarget(point, 0));
                }
            }
        }
        this.registerByAddress = byAddress;
    }

    @Override
    public void onData(PointValue value) {
        String address = value.address().raw();
        RegisterTarget target = registerByAddress.get(address);
        if (target == null) {
            unmappedCount.increment();
            log.warn("[access] 采集到未映射的地址，已丢弃：device={} address={}（点位映射缺失？）",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(address));
            return;
        }
        AccessPointMappingDto point = target.point();
        String identifier = point.getIdentifier();
        if (identifier == null || identifier.isBlank()) {
            orphanCount.increment();
            log.warn("[access] 点位映射缺少属性标识（物模型属性行可能已被重导入删除），已丢弃该读数："
                    + "device={} address={} 属性主键={}（请重建该点位映射）",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(address),
                LogSanitizer.sanitize(point.getPropertyId()));
            return;
        }
        boolean modbus = IotProtocol.MODBUS.getCode().equals(protocol);
        if (modbus && isMultiRegister(point)) {
            onMultiRegister(value, point, target.wordIndex());
            return;
        }
        // 单寄存器 / 其它协议：BAD 原样穿透（不规范化、不计失败）
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
        } else if (modbus && isBits(point) && raw instanceof Number number) {
            // bits 形态：单寄存器无符号位域（length=1 ⇒ Boolean，>1 ⇒ Integer）；
            // 区间非法（直连 DB 坏数据）按 BAD 上报，不把垃圾写进库
            try {
                raw = ModbusRegisterDecoder.decodeBits(number.intValue(), point.getBitOffset(),
                    point.getBitLength());
            } catch (RuntimeException ex) {
                log.warn("[access] Modbus 位域区间非法（直连 DB 坏数据），按 BAD 上报：device={} address={}",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(point.getAddress()));
                emitBad(identifier, value, "modbus.bits.range-invalid");
                return;
            }
        } else if (modbus && Boolean.TRUE.equals(point.getSigned()) && raw instanceof Number number) {
            // word_count=1 + signed：16 位符号扩展；signed=false 原样透传（逐字节兼容）
            raw = ModbusRegisterDecoder.decodeSingle(number.intValue(), true);
        }
        emit(identifier, value, AccessReading.applyScale(raw, point.getScaleFactor(),
            point.getOffsetValue()));
    }

    /**
     * 32 位点位的半字配对与合成。
     *
     * <p><b>失败语义（不许猜）</b>：任一寄存器 BAD ⇒ 立即产出该点位 BAD 并清空暂存；
     * <b>绝不</b>用陈旧的一半拼值、<b>绝不</b>用 0 充数。半字陈旧（同一 half 重复到达，或另一 half
     * 与暂存 half 的时间差超过一个采集周期）⇒ 丢弃暂存 + 计数 {@code iot.access.modbus.pair.stale}
     * 后再用新值暂存，不与新值混。</p>
     *
     * @param value     本次回调（某一半）
     * @param point     点位
     * @param wordIndex 本次是第几个字（base=0、base+1=1）
     */
    private void onMultiRegister(PointValue value, AccessPointMappingDto point, int wordIndex) {
        String key = point.getAddress();
        if (!value.isGood()) {
            clearPair(key);
            log.warn("[access] Modbus 多寄存器点位收到 BAD 寄存器，按点位 BAD 上报（不用陈旧一半拼值）："
                    + "device={} address={} wordIndex={} reason={}",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(value.address().raw()), wordIndex,
                LogSanitizer.sanitize(value.qualityReason()));
            emit(point.getIdentifier(), value, value.value());
            return;
        }
        Object raw = value.value();
        if (!(raw instanceof Number number)) {
            // 防御：SDK Modbus 必交付 Integer；非数值按 BAD 上报而不是把垃圾写进库
            clearPair(key);
            log.warn("[access] Modbus 多寄存器点位收到非数值寄存器值，按 BAD 上报：device={} address={}",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(value.address().raw()));
            emit(point.getIdentifier(), PointValue.bad(value.address(), Quality.BAD,
                "modbus.register.non-numeric", value.timestamp()), null);
            return;
        }
        synchronized (pairLock) {
            Half existing = pairBuffer.get(key);
            if (existing != null && (isStale(existing, value.timestamp())
                || existing.wordIndex() == wordIndex)) {
                pairStaleCount.increment();
                ModbusRegisterMetrics.pairStaleCounter(meterRegistry).increment();
                log.warn("[access] Modbus 半字配对陈旧/同角色重复，丢弃暂存的一半（不与新值混）："
                        + "device={} address={} 暂存word={} 本次word={} 见 {} 指标",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(key), existing.wordIndex(),
                    wordIndex, ModbusRegisterMetrics.METRIC_PAIR_STALE);
                pairBuffer.remove(key);
                existing = null;
            }
            if (existing == null) {
                pairBuffer.put(key, new Half(wordIndex, number.intValue(), value.timestamp()));
                return;
            }
            int first = existing.wordIndex() == 0 ? existing.value() : number.intValue();
            int second = existing.wordIndex() == 0 ? number.intValue() : existing.value();
            pairBuffer.remove(key);
            if (isFloat(point)) {
                // float 形态：两半齐后按 IEEE754 解释（NaN/Inf 按 BAD 上报 + 计数，见 §4.4.6）
                float decoded = ModbusRegisterDecoder.decodeFloatBits(first, second, point.getByteOrder());
                if (!Float.isFinite(decoded)) {
                    onNonFiniteFloat(point, value);
                    return;
                }
                // float32 的十进制表示取**最短可回读**形态：Float.toString 保证
                // parseFloat(toString(f)) == f（round-trip 恒等），不声明超出 float32 精度的位数；
                // 用 Double.toString 会给出 17 位精确值噪声（如 0.1f → 0.10000000149011612）。
                BigDecimal floatValue = new BigDecimal(Float.toString(decoded));
                emit(point.getIdentifier(), value, AccessReading.applyScale(floatValue,
                    point.getScaleFactor(), point.getOffsetValue()));
                return;
            }
            long decoded = ModbusRegisterDecoder.decodePair(first, second, point.getByteOrder(),
                Boolean.TRUE.equals(point.getSigned()));
            emit(point.getIdentifier(), value, AccessReading.applyScale(decoded,
                point.getScaleFactor(), point.getOffsetValue()));
        }
    }

    /** 暂存的一半是否已陈旧（超过一个采集周期；周期未知用兜底值）。 */
    private boolean isStale(Half existing, Instant currentTs) {
        long tolerance = pollIntervalMs != null && pollIntervalMs > 0
            ? pollIntervalMs : DEFAULT_PAIR_TTL_MS;
        return currentTs.toEpochMilli() - existing.ts().toEpochMilli() > tolerance;
    }

    private void clearPair(String key) {
        synchronized (pairLock) {
            pairBuffer.remove(key);
        }
    }

    /** bits 形态（register_format=bits）。 */
    private static boolean isBits(AccessPointMappingDto point) {
        return "bits".equals(registerFormatOf(point));
    }

    /** float 形态（register_format=float）。 */
    private static boolean isFloat(AccessPointMappingDto point) {
        return "float".equals(registerFormatOf(point));
    }

    /** 归一化 register_format（null/blank ⇒ int，兼容 v1 规格）。 */
    private static String registerFormatOf(AccessPointMappingDto point) {
        String format = point.getRegisterFormat();
        return format == null || format.isBlank() ? "int" : format.trim();
    }

    /**
     * Modbus float 解出 NaN/±Inf：按 BAD 上报 + 计数（复用 {@code non-finite} reason，不新增指标）。
     *
     * <p>理由见 {@code docs/VALUE-DECODE-DESIGN.md} §4.4.6：NaN/Inf 不是有效测量、会被 iot 侧
     * {@code ReadingValueMapper} 拒到文本列、且 {@code applyScale} 的 BigDecimal 不可表示。</p>
     */
    private void onNonFiniteFloat(AccessPointMappingDto point, PointValue value) {
        nonFiniteFloatCount.increment();
        PointValueNormalizeMetrics.counter(meterRegistry, NormalizeFailure.NON_FINITE).increment();
        log.warn("[access] Modbus float 解出 NaN/Inf，按 BAD 上报（不落库；见 {} 指标）：device={} address={}",
            PointValueNormalizeMetrics.METRIC_NAME, LogSanitizer.sanitize(deviceId),
            LogSanitizer.sanitize(point.getAddress()));
        emitBad(point.getIdentifier(), value, "modbus.float.non-finite");
    }

    /** 上报一条 BAD 读数（value=null；宿主侧解码失败用，不把垃圾写进库）。 */
    private void emitBad(String identifier, PointValue value, String reason) {
        emit(identifier, PointValue.bad(value.address(), Quality.BAD, reason, value.timestamp()), null);
    }

    /** 是否 32 位点位（word_count=2）。 */
    private static boolean isMultiRegister(AccessPointMappingDto point) {
        return point.getWordCount() != null && point.getWordCount() == 2;
    }

    /** 上报一条读数（质量/时刻/周期随原始 PointValue 带出；BAD 的 value 可为 null）。 */
    private void emit(String identifier, PointValue value, Object mapped) {
        sink.accept(new AccessReading(deviceId, identifier, mapped, value.quality().name(),
            value.timestamp(), pollIntervalMs));
    }

    /**
     * 记一次物模型类型规范化失败：丢弃该点 + 计数（按原因）+ WARN（脱敏：只记长度与配置，不 dump 值）。
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

    /** 本设备因半字配对陈旧/同角色重复被丢弃的读数条数（供自检与测试断言）。 */
    public long pairStaleCount() {
        return pairStaleCount.sum();
    }

    /** 本设备因 Modbus float 解出 NaN/±Inf 而按 BAD 上报的条数（供自检与测试断言）。 */
    public long nonFiniteFloatCount() {
        return nonFiniteFloatCount.sum();
    }

    /**
     * 寄存器地址 → 点位 + 第几个字。
     *
     * @param point     点位
     * @param wordIndex 0=base、1=base+1
     */
    private record RegisterTarget(AccessPointMappingDto point, int wordIndex) {
    }

    /**
     * 已到达的一半。
     *
     * @param wordIndex 第几个字
     * @param value     16 位无符号值
     * @param ts        到达时刻（配对容差用）
     */
    private record Half(int wordIndex, int value, Instant ts) {
    }
}
