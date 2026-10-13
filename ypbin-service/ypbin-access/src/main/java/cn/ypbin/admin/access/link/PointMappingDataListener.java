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

import cn.ypbin.admin.access.decode.ByteDecodeMetrics;
import cn.ypbin.admin.access.decode.BytePayloadDecoder;
import cn.ypbin.admin.access.decode.BytePayloadDecoderRegistry;
import cn.ypbin.admin.access.decode.DecodeContext;
import cn.ypbin.admin.access.decode.DecodeFailure;
import cn.ypbin.admin.access.decode.DecodeOutcome;
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
import java.nio.charset.CharacterCodingException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   <li><b>Modbus 多寄存器</b>（{@code word_count>1}：int/float=2、double=4、string=1..32）：SDK 对每个寄存器
 *       **逐值回调**（不是批量），本类按点位**按 word_index 收齐 N 个字**后再合成一条读数——
 *       任一寄存器 BAD/缺失 ⇒ 该点位 BAD，**绝不用陈旧一半拼值、绝不用 0 充数**
 *       （见 {@link #onMultiRegister}；string 的拼接/严格 UTF-8 规则见 §4.5）。</li>
 *   <li><b>其余 {@code byte[]} 交付路径</b>（MQTT {@code binary}、OPC UA {@code ByteString} 等）：
 *       交给宿主可插解码 SPI（{@link BytePayloadDecoderRegistry}）。**未注册解码器时不认领**，
 *       原样透传到出口闸门丢弃（与今天逐字一致）；认领后解码失败以 BAD + 原因码指标显式表达
 *       （见 {@link #onBytePayload}）。</li>
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

    /** 寄存器地址 → (点位, 第几个字)。word_count>1 时 base+i→i（i=0..N-1）。 */
    private final Map<String, RegisterTarget> registerByAddress;

    private final AccessReadingSink sink;

    /** 宿主可插字节载荷解码器注册表（空注册表 ⇒ 与「没有 SPI」行为一致）。 */
    private final BytePayloadDecoderRegistry decoderRegistry;

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

    /** 宿主字节载荷解码失败而按 BAD 上报的条数（与 Micrometer 计数器同源）。 */
    private final LongAdder decodeFailureCount = new LongAdder();

    /** 多寄存器点位的字暂存：base 地址 → 已到达的字槽（按 word_index 定位）。每设备至多「多寄存器点位数」组。 */
    private final Map<String, PendingWords> pairBuffer = new ConcurrentHashMap<>();

    /** 半字配对的短临界区锁（配对/清空必须原子，避免并发回调把两半错配）。 */
    private final Object pairLock = new Object();

    public PointMappingDataListener(String deviceId, String protocol, Integer pollIntervalMs,
                                    List<AccessPointMappingDto> points, AccessReadingSink sink,
                                    MeterRegistry meterRegistry,
                                    BytePayloadDecoderRegistry decoderRegistry) {
        this.deviceId = deviceId;
        this.protocol = protocol;
        this.pollIntervalMs = pollIntervalMs;
        this.sink = sink;
        this.meterRegistry = meterRegistry;
        // 空注册表与「未注册解码器」等价：byte[] 仍走出口闸门，不静默降级
        this.decoderRegistry = decoderRegistry == null
            ? BytePayloadDecoderRegistry.empty() : decoderRegistry;
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
                        // i=0 用**原始 base**（订阅侧就是 PointAddress.of(raw_address)，SDK 回调原样带回）；
                        // i>0 用 ModbusRegisterAddress.plus 归一化地址（与 expandAddresses 完全同源）。
                        byAddress.put(base, new RegisterTarget(point, 0));
                        int wordCount = wordCountOf(point);
                        for (int i = 1; i < wordCount; i++) {
                            byAddress.put(ModbusRegisterAddress.plus(base, i),
                                new RegisterTarget(point, i));
                        }
                    } catch (RuntimeException ex) {
                        // 映射校验应已拦住（word_count>1 必须是显式 type:offset）；直连 DB 的坏数据在这里暴露
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
        if (raw instanceof byte[] payload) {
            // 其余 byte[] 交付路径（MQTT binary / OPC UA ByteString 等）：宿主可插解码 SPI
            onBytePayload(identifier, value, point, payload);
            return;
        }
        if (IotProtocol.TCP.getCode().equals(protocol)) {
            // SDK key-value 只做字段选择、不做类型转换；这里按物模型 dataType 规范化，失败即丢弃 + 计数
            PointValueNormalizer.Outcome outcome = PointValueNormalizer.normalize(raw, point.getDataType());
            if (!outcome.success()) {
                onNormalizeFailure(point, outcome.failure(), raw);
                return;
            }
            raw = outcome.value();
        } else if (modbus && isString(point)) {
            // v3 单寄存器 string：一个字的严格 UTF-8（byte_order 忽略）
            if (!(raw instanceof Number number)) {
                emitBad(identifier, value, "modbus.register.non-numeric");
                return;
            }
            try {
                raw = ModbusRegisterDecoder.decodeStringUtf8(new int[] {number.intValue()});
            } catch (CharacterCodingException ex) {
                onNotUtf8(point, value);
                return;
            }
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
        // 落列由 iot 侧按物模型类型决定；本层只做值规范化与缩放（string 的 applyScale 是 no-op）
        emit(identifier, value, AccessReading.applyScale(raw, point.getScaleFactor(),
            point.getOffsetValue()));
    }

    /**
     * 多寄存器点位的**按 word_index 收齐 N 个字**与合成（v3 起 N=word_count，string 1..32）。
     *
     * <p><b>失败语义（不许猜）</b>：任一寄存器 BAD ⇒ 立即产出该点位 BAD 并清空暂存；
     * <b>绝不</b>用陈旧的字拼值、<b>绝不</b>用 0 充数。暂存组陈旧（同一 slot 重复到达，或组内最早到达的
     * 字与本次时间差超过一个采集周期）⇒ 整组丢弃 + 计数 {@code iot.access.modbus.pair.stale}
     * 后再用新值重建，不与新值混。N 个 slot 全齐才合成。</p>
     *
     * @param value     本次回调（某一个寄存器）
     * @param point     点位
     * @param wordIndex 本次是第几个字（base=0、base+1=1、…）
     */
    private void onMultiRegister(PointValue value, AccessPointMappingDto point, int wordIndex) {
        String key = point.getAddress();
        if (!value.isGood()) {
            clearPair(key);
            log.warn("[access] Modbus 多寄存器点位收到 BAD 寄存器，按点位 BAD 上报（不用陈旧字拼值）："
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
        int wordCount = wordCountOf(point);
        synchronized (pairLock) {
            PendingWords pending = pairBuffer.get(key);
            if (pending != null && (isStale(pending, value.timestamp())
                || pending.halves()[wordIndex] != null)) {
                pairStaleCount.increment();
                ModbusRegisterMetrics.pairStaleCounter(meterRegistry).increment();
                log.warn("[access] Modbus 多寄存器暂存陈旧/同角色重复，丢弃整组（不与新值混）："
                        + "device={} address={} expected={} 本次word={} 见 {} 指标",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(key), wordCount,
                    wordIndex, ModbusRegisterMetrics.METRIC_PAIR_STALE);
                pairBuffer.remove(key);
                pending = null;
            }
            if (pending == null) {
                pending = new PendingWords(wordCount, new Half[wordCount], value.timestamp());
                pairBuffer.put(key, pending);
            }
            pending.halves()[wordIndex] = new Half(wordIndex, number.intValue(), value.timestamp());
            if (!allFilled(pending)) {
                return;
            }
            pairBuffer.remove(key);
            assembleAndEmit(point, pending, value);
        }
    }

    /** N 个 slot 全齐 ⇒ 按 word_index 升序合成一条读数；string 走严格 UTF-8，int/float/float64 复用 v1/v2 的泛化配对。 */
    private void assembleAndEmit(AccessPointMappingDto point, PendingWords pending, PointValue value) {
        int expected = pending.expected();
        int[] words = new int[expected];
        for (int i = 0; i < expected; i++) {
            words[i] = pending.halves()[i].value();
        }
        if (isString(point)) {
            try {
                String decoded = ModbusRegisterDecoder.decodeStringUtf8(words);
                // string 的 applyScale 是 no-op；落列由 iot 侧按物模型类型决定
                emit(point.getIdentifier(), value, AccessReading.applyScale(decoded,
                    point.getScaleFactor(), point.getOffsetValue()));
            } catch (CharacterCodingException ex) {
                onNotUtf8(point, value);
            }
            return;
        }
        if (isDouble(point)) {
            // v4：4 寄存器走同一泛化配对路径（word_count=4），不另起一套
            if (expected != 4) {
                // 直连 DB 的坏数据：double 必须恰好 4 个寄存器；按 BAD 暴露而不是只用前两个/四个
                log.warn("[access] Modbus register_format=double 的 word_count 必须是 4（直连 DB 坏数据），"
                        + "按 BAD 上报：device={} address={} word_count={}",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(point.getAddress()), expected);
                emitBad(point.getIdentifier(), value, "modbus.register.word-count-invalid");
                return;
            }
            double decoded = ModbusRegisterDecoder.decodeDoubleBits(words, point.getByteOrder());
            if (!Double.isFinite(decoded)) {
                onNonFinite(point, value, "double");
                return;
            }
            // double 的十进制表示取**最短可回读**形态（BigDecimal.valueOf = Double.toString 最短表示；
            // 绝不用 new BigDecimal(double) 的 17 位二进制展开，见 §4.4.7）
            BigDecimal doubleValue = BigDecimal.valueOf(decoded);
            emit(point.getIdentifier(), value, AccessReading.applyScale(doubleValue,
                point.getScaleFactor(), point.getOffsetValue()));
            return;
        }
        if (expected != 2) {
            // 直连 DB 的坏数据：int/float 只允许 2 个寄存器；按 BAD 暴露而不是只用前两个
            log.warn("[access] Modbus int/float 形态的 word_count 必须是 2（直连 DB 坏数据），按 BAD 上报："
                    + "device={} address={} word_count={}",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(point.getAddress()), expected);
            emitBad(point.getIdentifier(), value, "modbus.register.word-count-invalid");
            return;
        }
        int first = words[0];
        int second = words[1];
        if (isFloat(point)) {
            // float 形态：两半齐后按 IEEE754 解释（NaN/Inf 按 BAD 上报 + 计数，见 §4.4.6）
            float decoded = ModbusRegisterDecoder.decodeFloatBits(first, second, point.getByteOrder());
            if (!Float.isFinite(decoded)) {
                onNonFinite(point, value, "float");
                return;
            }
            // float32 的十进制表示取**最短可回读**形态（理由见 §4.4.7）
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

    /** 暂存组是否已陈旧（组内最早到达的字距本次超过一个采集周期；周期未知用兜底值）。 */
    private boolean isStale(PendingWords pending, Instant currentTs) {
        long tolerance = pollIntervalMs != null && pollIntervalMs > 0
            ? pollIntervalMs : DEFAULT_PAIR_TTL_MS;
        return currentTs.toEpochMilli() - pending.oldestTs().toEpochMilli() > tolerance;
    }

    /** N 个 word_index 槽是否全部到达。 */
    private static boolean allFilled(PendingWords pending) {
        for (Half half : pending.halves()) {
            if (half == null) {
                return false;
            }
        }
        return true;
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

    /** float 形态（register_format=float，IEEE754 单精度，2 寄存器）。 */
    private static boolean isFloat(AccessPointMappingDto point) {
        return "float".equals(registerFormatOf(point));
    }

    /** double 形态（register_format=double，IEEE754 双精度，4 寄存器）。 */
    private static boolean isDouble(AccessPointMappingDto point) {
        return "double".equals(registerFormatOf(point));
    }

    /** string 形态（register_format=string）。 */
    private static boolean isString(AccessPointMappingDto point) {
        return "string".equals(registerFormatOf(point));
    }

    /** 生效字数（null ⇒ 1，兼容 v1 规格）。 */
    private static int wordCountOf(AccessPointMappingDto point) {
        return point.getWordCount() == null ? 1 : point.getWordCount();
    }

    /** 归一化 register_format（null/blank ⇒ int，兼容 v1 规格）。 */
    private static String registerFormatOf(AccessPointMappingDto point) {
        String format = point.getRegisterFormat();
        return format == null || format.isBlank() ? "int" : format.trim();
    }

    /**
     * Modbus 浮点（float/double）解出 NaN/±Inf：按 BAD 上报 + 计数（复用 {@code non-finite} reason，
     * 不新增指标）。
     *
     * <p>理由见 {@code docs/VALUE-DECODE-DESIGN.md} §4.4.6：NaN/Inf 不是有效测量、且
     * {@code applyScale} 的 BigDecimal 不可表示。float32 与 float64 共用同一计数与 reason，
     * 用 {@code format} 区分日志/消息键（{@code modbus.float.non-finite} /
     * {@code modbus.double.non-finite}）。</p>
     *
     * @param point  点位
     * @param value  原始 PointValue（提供地址/时刻）
     * @param format 形态标签（{@code float} / {@code double}）
     */
    private void onNonFinite(AccessPointMappingDto point, PointValue value, String format) {
        nonFiniteFloatCount.increment();
        PointValueNormalizeMetrics.counter(meterRegistry, NormalizeFailure.NON_FINITE).increment();
        log.warn("[access] Modbus {} 解出 NaN/±Inf，按 BAD 上报（不落库；见 {} 指标）：device={} address={}",
            format, PointValueNormalizeMetrics.METRIC_NAME, LogSanitizer.sanitize(deviceId),
            LogSanitizer.sanitize(point.getAddress()));
        emitBad(point.getIdentifier(), value, "modbus." + format + ".non-finite");
    }

    /**
     * Modbus 多寄存器字符串不是合法 UTF-8：按 BAD 上报 + 计数（复用 {@code not-utf8} reason）。
     *
     * <p>严格解码、不替换字符、不静默：设备报的字节序列无法无歧义还原为文本时，
     * 把「解析失败」伪装成一个带 U+FFFD 的字符串比 BAD 更糟（后者会被当有效数据参与断档/曲线）。</p>
     */
    private void onNotUtf8(AccessPointMappingDto point, PointValue value) {
        normalizeFailureCount.increment();
        PointValueNormalizeMetrics.counter(meterRegistry, NormalizeFailure.NOT_UTF8).increment();
        log.warn("[access] Modbus 字符串不是合法 UTF-8，按 BAD 上报（不替换字符；见 {} 指标）："
                + "device={} address={} word_count={}",
            PointValueNormalizeMetrics.METRIC_NAME, LogSanitizer.sanitize(deviceId),
            LogSanitizer.sanitize(point.getAddress()), wordCountOf(point));
        emitBad(point.getIdentifier(), value, "modbus.string.not-utf8");
    }

    /**
     * 原始字节载荷的宿主可插解码路径（MQTT {@code binary} / OPC UA {@code ByteString} 等）。
     *
     * <p><b>行为矩阵（不静默降级）</b>：</p>
     * <ol>
     *   <li><b>没有解码器认领</b>（未注册 / 未配置 / 点位不匹配）⇒ 原样把 {@code byte[]} 交给出口，
     *       由出口闸门丢弃并计 {@code iot.access.egress.invalid}——与引入本 SPI 之前逐字一致；</li>
     *   <li><b>认领但解码失败</b>（长度不符、NaN/±Inf、实现抛异常）⇒ <b>BAD 质量</b> +
     *       {@code iot.access.payload.decode.failure{reason}} 计数 + WARN/ERROR，不落库；</li>
     *   <li><b>解码成功</b> ⇒ 仍走既有 {@link PointValueNormalizer}（物模型类型）与
     *       {@link AccessReading#applyScale}（线性缩放）链路，落列由 iot 侧按 {@code data_type} 决定。</li>
     * </ol>
     *
     * @param identifier 上报坐标（属性标识）
     * @param value      原始 PointValue（提供质量/时刻）
     * @param point      点位映射（提供物模型类型/地址/字序）
     * @param payload    原始字节载荷
     */
    private void onBytePayload(String identifier, PointValue value, AccessPointMappingDto point,
                               byte[] payload) {
        DecodeContext context = new DecodeContext(deviceId, protocol, point.getAddress(),
            point.getAddressType(), point.getDataType(), point.getByteOrder());
        Optional<BytePayloadDecoder> selected = decoderRegistry.find(context);
        if (selected.isEmpty()) {
            // 未认领：保持今天的行为，交给出口闸门（iot.access.egress.invalid）——不猜、不降级
            emit(identifier, value, AccessReading.applyScale(payload, point.getScaleFactor(),
                point.getOffsetValue()));
            return;
        }
        BytePayloadDecoder decoder = selected.get();
        DecodeOutcome outcome;
        try {
            outcome = decoder.decode(payload, context);
        } catch (RuntimeException ex) {
            // 解码器实现缺陷：捕获后显式暴露（BAD + 计数 + ERROR 全堆栈），绝不吞
            onDecodeFailure(decoder, point, value, payload.length, DecodeFailure.DECODER_ERROR, ex);
            return;
        }
        if (!outcome.success()) {
            onDecodeFailure(decoder, point, value, payload.length, outcome.failure(), null);
            return;
        }
        // 解码后仍走既有物模型类型规范化（只对 String 生效）与缩放；落列由 iot 侧决定
        PointValueNormalizer.Outcome normalized =
            PointValueNormalizer.normalize(outcome.value(), point.getDataType());
        if (!normalized.success()) {
            onNormalizeFailure(point, normalized.failure(), outcome.value());
            return;
        }
        emit(identifier, value, AccessReading.applyScale(normalized.value(),
            point.getScaleFactor(), point.getOffsetValue()));
    }

    /**
     * 记一次宿主字节载荷解码失败：**按 BAD 上报** + 计数（按原因）+ 脱敏日志（只记长度，不 dump 负载）。
     *
     * @param decoder     命中的解码器（日志/排障用）
     * @param point       点位映射
     * @param value       原始 PointValue（提供地址/时刻）
     * @param payloadSize 载荷字节长度
     * @param failure     失败原因
     * @param ex          解码器抛出的异常（无则 {@code null}）
     */
    private void onDecodeFailure(BytePayloadDecoder decoder, AccessPointMappingDto point,
                                 PointValue value, int payloadSize, DecodeFailure failure,
                                 RuntimeException ex) {
        decodeFailureCount.increment();
        ByteDecodeMetrics.counter(meterRegistry, failure).increment();
        if (ex == null) {
            log.warn("[access] 宿主字节载荷解码失败，按 BAD 上报（不落库；见 {} 指标）：device={} property={} "
                    + "address={} decoder={} 原因={}({}) 载荷长度={} 数据类型={}",
                ByteDecodeMetrics.METRIC_NAME, LogSanitizer.sanitize(deviceId),
                LogSanitizer.sanitize(point.getIdentifier()), LogSanitizer.sanitize(point.getAddress()),
                LogSanitizer.sanitize(decoder.id()), failure.getCode(), failure.getDesc(),
                payloadSize, LogSanitizer.sanitize(point.getDataType()));
        } else {
            log.error("[access] 宿主字节载荷解码器抛出异常（按 BAD 上报，不吞；见 {} 指标）：device={} "
                    + "property={} address={} decoder={} 原因={}({}) 载荷长度={}",
                ByteDecodeMetrics.METRIC_NAME, LogSanitizer.sanitize(deviceId),
                LogSanitizer.sanitize(point.getIdentifier()), LogSanitizer.sanitize(point.getAddress()),
                LogSanitizer.sanitize(decoder.id()), failure.getCode(), failure.getDesc(), payloadSize,
                ex);
        }
        emitBad(point.getIdentifier(), value, "decode." + failure.getCode());
    }

    /** 上报一条 BAD 读数（value=null；宿主侧解码失败用，不把垃圾写进库）。 */
    private void emitBad(String identifier, PointValue value, String reason) {
        emit(identifier, PointValue.bad(value.address(), Quality.BAD, reason, value.timestamp()), null);
    }

    /**
     * 是否多寄存器点位（word_count&gt;1：int/float=2、double=4、string 1..32）。
     *
     * <p>{@code word_count=1} 的 string 走单寄存器路径（无需配对）；传统 5 位编号的
     * {@code word_count=1} 也照常（向后兼容）。</p>
     */
    private static boolean isMultiRegister(AccessPointMappingDto point) {
        return wordCountOf(point) > 1;
    }

    /**
     * 上报一条读数（质量/时刻/周期随原始 PointValue 带出；BAD 的 value 可为 null）。
     *
     * <p>落列由 iot 侧按 {@code iot_property.data_type} 决定；本层不携带任何列/类型标记
     * （v3 {@code forceText} 通道已删除，见 docs/VALUE-DECODE-DESIGN.md §4.6）。</p>
     */
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

    /** 本设备因宿主字节载荷解码失败而按 BAD 上报的条数（供自检与测试断言）。 */
    public long decodeFailureCount() {
        return decodeFailureCount.sum();
    }

    /**
     * 寄存器地址 → 点位 + 第几个字。
     *
     * @param point     点位
     * @param wordIndex 0=base、1=base+1、…
     */
    private record RegisterTarget(AccessPointMappingDto point, int wordIndex) {
    }

    /**
     * 已到达的一个字。
     *
     * @param wordIndex 第几个字
     * @param value     16 位无符号值
     * @param ts        到达时刻（配对容差用）
     */
    private record Half(int wordIndex, int value, Instant ts) {
    }

    /**
     * 多寄存器点位已到达的字组。
     *
     * @param expected 期望字数（word_count）
     * @param halves   按 word_index 定位的槽（未到达为 null）
     * @param oldestTs 组内最早到达时刻（配对容差用）
     */
    private record PendingWords(int expected, Half[] halves, Instant oldestTs) {
    }
}
