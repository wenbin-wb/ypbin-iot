/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.ypbin.admin.access.decode.BytePayloadDecoderRegistry;
import cn.ypbin.admin.access.link.PointMappingDataListener;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 点位映射、**宿主物模型类型规范化**与线性缩放的纯逻辑单测（采集数据的正确性关键面）。
 *
 * <p><b>切换背景</b>（2026-10-13）：接入侧已切到 starter 0.3.0 的 {@code payload-format=key-value}，
 * SDK 完成帧解析与字段选择后交付 {@code String}（<b>不做类型转换</b>，其类注释明确类型属于宿主
 * 物模型语义）。因此本测试的输入从「原始 {@code byte[]} 帧」改为「SDK 已选好的 {@code String}」，
 * 并逐条断言「切换前是什么、切换后还是什么」。</p>
 *
 * <p>这里最容易出错、且出错后**不会报错只会算错**的四件事：① 缩放/偏移该不该应用（依赖类型规范化
 * 先把文本变成 {@code Number}/{@code BigDecimal}）；② 地址对不上时是静默丢还是留痕；
 * ③ **上报的坐标形态**——必须是属性标识（规范坐标），不能退回属性主键字符串（2026-09-26 坐标统一的落点）；
 * ④ **类型规范化失败必须丢弃 + 计数**，不许猜、不许把失败画成有效值。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class AccessReadingMappingTest {

    private static final String PROTOCOL_TCP = "tcp";

    private static final String PROTOCOL_MODBUS = "modbus";

    @Test
    @DisplayName("缩放：未配置缩放/偏移时原样返回；配了就 value*scale+offset")
    void applyScaleShouldOnlyTransformWhenConfigured() {
        assertThat(AccessReading.applyScale(42, null, null)).isEqualTo(42);
        assertThat(AccessReading.applyScale(42, BigDecimal.ONE, null))
            .isEqualTo(new BigDecimal("42.000000"));
        assertThat(AccessReading.applyScale(10, new BigDecimal("0.1"), new BigDecimal("1.5")))
            .isEqualTo(new BigDecimal("2.500000"));
    }

    @Test
    @DisplayName("缩放：非数值原值不得被硬转（不猜测、不做兜底转换）")
    void applyScaleShouldKeepNonNumericAsIs() {
        assertThat(AccessReading.applyScale("ON", new BigDecimal("2"), null)).isEqualTo("ON");
        assertThat(AccessReading.applyScale(null, new BigDecimal("2"), BigDecimal.ONE)).isNull();
    }

    @Test
    @DisplayName("映射：地址命中 → 上报的是**属性标识**（规范坐标）/值/质量/时刻均正确（含缩放与质量语义）")
    void mappedAddressShouldProduceReadingWithIdentifierCoordinate() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("holding:1", "temperature", "decimal", new BigDecimal("0.1"),
                new BigDecimal("0"))),
            readings);
        Instant now = Instant.parse("2026-09-21T08:00:00Z");

        // SDK key-value 交付的是 String（不做类型转换）；缩放生效依赖宿主类型规范化
        listener.onData(PointValue.good(PointAddress.of("holding:1"), "250", now));

        assertThat(readings).hasSize(1);
        AccessReading reading = readings.getFirst();
        assertThat(reading.deviceId()).isEqualTo("100");
        assertThat(reading.propertyId())
            .as("上报坐标必须是属性标识（规范坐标），不是属性主键字符串 pk-temperature——"
                + "退回主键会让读侧按标识查不到这条数据")
            .isEqualTo("temperature")
            .isNotEqualTo("pk-temperature");
        assertThat(reading.value()).isEqualTo(new BigDecimal("25.000000"));
        assertThat(reading.quality()).isEqualTo("GOOD");
        assertThat(reading.pollIntervalMs()).as("设备级周期必须随读数带出（断档判定用真周期）")
            .isEqualTo(5_000);
        assertThat(reading.isGood()).isTrue();
        assertThat(reading.timestamp()).isEqualTo(now);
        assertThat(listener.unmappedCount()).isZero();
        assertThat(listener.orphanCount()).isZero();
        assertThat(listener.normalizeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ key-value 数值点：SDK 交付 \"23.5\" ⇒ 规范化为 BigDecimal，形态仍是干净数值串（曲线可画）")
    void keyValueShouldNormalizeNumericValueForCurve() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("TEMP", "temperature", "decimal", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("TEMP"), "23.5", Instant.now()));

        assertThat(readings).hasSize(1);
        Object value = readings.getFirst().value();
        assertThat(value).as("切换后值应为 BigDecimal（切换前 TextFrameValueDecoder 也产出 BigDecimal）")
            .isInstanceOf(BigDecimal.class);
        // 出口走 String.valueOf：形态必须是纯数值串，ReadingValueMapper 才会写 value_double。
        // 这是「曲线画不出来」这条缺陷的回归断言。
        assertThat(String.valueOf(value))
            .isEqualTo("23.5")
            .matches("-?\\d+(\\.\\d+)?");
        assertThat(listener.normalizeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ key-value 文本点：serialNo 规范化后仍是文本（含前导零不被吞），不进数值列")
    void keyValueTextPointMustStayText() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("SN", "serialNo", "string", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("SN"), "007", Instant.now()));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo("007"));
    }

    @Test
    @DisplayName("★ 数值等价（切换前=切换后）：\"23.5\" + scale=2 + offset=1 ⇒ 48.000000")
    void scaleShouldApplyToNormalizedValue() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("TEMP", "temperature", "decimal", new BigDecimal("2"), BigDecimal.ONE)),
            readings);

        listener.onData(PointValue.good(PointAddress.of("TEMP"), "23.5", Instant.now()));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value())
                .as("若类型规范化缺失，applyScale 对 String 原样返回 ⇒ 这里会是 \"23.5\"（读数错值）")
                .isEqualTo(new BigDecimal("48.000000")));
    }

    @Test
    @DisplayName("★ bool 等价：\"1\"/\"0\"/\"TRUE\" ⇒ true/false 文本（与切换前的落列形态一致）")
    void boolShouldNormalizeToTrueFalseText() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("SW1", "switch1", "bool", null, null),
                point("SW2", "switch2", "bool", null, null),
                point("SW3", "switch3", "bool", null, null)),
            readings);

        listener.onData(PointValue.good(PointAddress.of("SW1"), "1", Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("SW2"), "0", Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("SW3"), "TRUE", Instant.now()));

        assertThat(readings).extracting(AccessReading::value)
            .as("bool 1/0 必须规范成 true/false，否则 ReadingValueMapper 会按词法把 \"1\" 落 value_double")
            .containsExactly("true", "false", "true");
        assertThat(listener.normalizeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ int/long 等价：纯十进制 → Long（越界不截断）；前导零整数按切换前口径转数值")
    void intShouldNormalizeToLong() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("HUM", "humidity", "int", null, null),
                point("CNT", "count", "long", null, null)),
            readings);

        listener.onData(PointValue.good(PointAddress.of("HUM"), "45", Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("CNT"), "007", Instant.now()));

        assertThat(readings).extracting(AccessReading::value)
            .as("与切换前 TextFrameValueDecoder 逐字一致：Long.valueOf 会去掉前导零")
            .containsExactly(45L, 7L);
        assertThat(listener.normalizeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ 类型规范化失败不静默：decimal 收到非数值 / long 越界 ⇒ 丢弃 + 计数 + WARN（不 dump 值）")
    void normalizeFailureMustBeCountedAndWarnedWithoutValueDump() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", PROTOCOL_TCP, 5_000,
            List.of(point("TEMP", "temperature", "decimal", null, null),
                point("BIG", "big", "long", null, null)),
            readings::add, meterRegistry,
            BytePayloadDecoderRegistry.empty());
        Logger listenerLogger = (Logger) LoggerFactory.getLogger(PointMappingDataListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
        try {
            listener.onData(PointValue.good(PointAddress.of("TEMP"), "not-a-number", Instant.now()));
            listener.onData(PointValue.good(PointAddress.of("BIG"), "99999999999999999999", Instant.now()));

            assertThat(readings).as("规范化失败不许进出口（更不许把非法文本写进库）").isEmpty();
            assertThat(listener.normalizeFailureCount()).isEqualTo(2);
            assertThat(meterRegistry.get(PointValueNormalizeMetrics.METRIC_NAME)
                .tag(PointValueNormalizeMetrics.TAG_REASON, "not-numeric").counter().count())
                .isEqualTo(2.0d);

            List<String> warns = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(warns).hasSize(2);
            assertThat(warns.getFirst()).contains("device=100", "not-numeric", "值长度=12");
            assertThat(String.join("\n", warns))
                .as("WARN 必须脱敏：不得把值内容原样打进日志")
                .doesNotContain("not-a-number", "99999999999999999999");
        } finally {
            listenerLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("★ dataType 缺失/未知 ⇒ UNKNOWN_DATA_TYPE 丢弃 + 计数（不猜数值还是文本）")
    void unknownDataTypeMustFailAndCount() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", PROTOCOL_TCP, 5_000,
            List.of(point("TEMP", "temperature", null, null, null),
                point("HUM", "humidity", "not-a-type", null, null)),
            readings::add, meterRegistry,
            BytePayloadDecoderRegistry.empty());

        listener.onData(PointValue.good(PointAddress.of("TEMP"), "23.5", Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("HUM"), "45", Instant.now()));

        assertThat(readings).isEmpty();
        assertThat(listener.normalizeFailureCount()).isEqualTo(2);
        assertThat(meterRegistry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "unknown-data-type").counter().count())
            .isEqualTo(2.0d);
    }

    @Test
    @DisplayName("★ bool 非法形态 ⇒ NOT_BOOLEAN 丢弃 + 计数")
    void invalidBoolMustFailAndCount() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", PROTOCOL_TCP, 5_000,
            List.of(point("SW", "switch1", "bool", null, null)), readings::add, meterRegistry,
            BytePayloadDecoderRegistry.empty());

        listener.onData(PointValue.good(PointAddress.of("SW"), "maybe", Instant.now()));

        assertThat(readings).isEmpty();
        assertThat(listener.normalizeFailureCount()).isEqualTo(1);
        assertThat(meterRegistry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "not-boolean").counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ SDK 的 BAD 原样穿透：不规范化、不计规范化失败（失败不得被洗白）")
    void badQualityMustPassThroughWithoutNormalizeFailure() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", PROTOCOL_TCP, 5_000,
            List.of(point("TEMP", "temperature", "decimal", null, null)), readings::add, meterRegistry,
            BytePayloadDecoderRegistry.empty());

        listener.onData(PointValue.bad(PointAddress.of("TEMP"), Quality.BAD,
            "iot.tcp.selector.not-found", Instant.now()));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).as("BAD 必须仍是 BAD，绝不能被规范化成 GOOD").isEqualTo("BAD");
            assertThat(reading.isGood()).isFalse();
            assertThat(reading.value()).as("SDK 的 BAD 不带值").isNull();
        });
        assertThat(listener.normalizeFailureCount())
            .as("SDK 侧失败不计入宿主规范化失败（否则双计）").isZero();
        assertThat(meterRegistry.find(PointValueNormalizeMetrics.METRIC_NAME).counters())
            .as("BAD 穿透不得创建任何规范化失败计数器").isEmpty();
    }

    @Test
    @DisplayName("其它协议不受影响：modbus/opcua 已解码的数值原样透传（宿主不改取值路径）")
    void otherProtocolsMustPassThroughUnchanged() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_MODBUS,
            List.of(point("holding:1", "temperature", "decimal", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("holding:1"), 250, Instant.now()));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(250));
        assertThat(listener.normalizeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ 孤儿映射（映射行在、物模型属性行已被删除 ⇒ 没有属性标识）：丢弃 + 单独计数，绝不退回主键形态")
    void orphanMappingMustBeDroppedAndCounted() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("holding:1", null, null, null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("holding:1"), "1", Instant.now()));

        assertThat(readings).as("拿不到属性标识就不能发（否则等于把统一掉的形态又写回去）").isEmpty();
        assertThat(listener.orphanCount()).isEqualTo(1);
        assertThat(listener.unmappedCount()).as("孤儿与「地址未映射」是两类原因，不许混计").isZero();
    }

    @Test
    @DisplayName("未映射地址：不进出口且计数递增（不得静默丢弃、也不得误发到别的属性）")
    void unmappedAddressShouldBeCountedNotEmitted() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("holding:1", "temperature", "decimal", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("holding:99"), "1", Instant.now()));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("质量非 GOOD 也要如实上报（断档判定依赖它，不能在采集侧吞掉）")
    void nonGoodQualityShouldBeReported() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("holding:1", "temperature", "decimal", null, null)), readings);

        listener.onData(PointValue.bad(PointAddress.of("holding:1"), Quality.BAD,
            "timeout", Instant.now()));

        assertThat(readings).hasSize(1);
        assertThat(readings.getFirst().isGood()).isFalse();
        assertThat(readings.getFirst().quality()).isEqualTo("BAD");
    }

    @Test
    @DisplayName("点位清单里的空/缺失地址被忽略：不会变成可用映射（其他地址仍按未映射计数）")
    void blankAddressInPointListShouldBeIgnored() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("", "temperature", "decimal", null, null),
                point(null, "humidity", "int", null, null)),
            readings);

        // 框架自身会拒绝空地址（PointAddress 构造期校验），故这里用一个真实但未映射的地址验证：
        // 若上面两条空地址被当成了合法映射，这里就不会走「未映射」分支
        listener.onData(PointValue.good(PointAddress.of("holding:2"), "1", Instant.now()));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("byteOrder 对 key-value 文本值无影响（字序只对 Modbus 寄存器 word_count=2 生效）")
    void byteOrderMustNotAffectKeyValueNormalization() {
        List<AccessReading> readings = new ArrayList<>();
        AccessPointMappingDto littleEndian = point("TEMP", "temperature", "decimal", null, null);
        littleEndian.setByteOrder("little");
        PointMappingDataListener listener = listener(PROTOCOL_TCP, List.of(littleEndian), readings);

        listener.onData(PointValue.good(PointAddress.of("TEMP"), "23.5", Instant.now()));

        assertThat(readings).singleElement().satisfies(reading ->
            assertThat(String.valueOf(reading.value()))
                .as("key-value 文本值没有字序语义；Modbus 寄存器帧的字序由 word_count/byte_order 承载")
                .isEqualTo("23.5"));
    }

    @Test
    @DisplayName("★ Modbus string：多寄存器拼出纯数字串 ⇒ 值原样（落列由 iot 侧按物模型类型决定）")
    void modbusStringMustKeepDecodedValue() {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress("holding:0");
        point.setIdentifier("serialNo");
        point.setDataType("string");
        point.setPropertyId("pk-serialNo");
        point.setRegisterFormat("string");
        point.setWordCount(2);
        point.setSigned(Boolean.FALSE);
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_MODBUS, List.of(point), readings);

        // 0x3132 = "12"，0x3334 = "34" ⇒ 两个字拼出 "1234"（纯数字序列号）
        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x3132, Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x3334, Instant.now()));

        assertThat(readings).singleElement().satisfies(reading ->
            assertThat(reading.value()).isEqualTo("1234"));
    }

    @Test
    @DisplayName("★ 对照①：TCP data_type=string + 纯数字 \"123456\" ⇒ 值原样（落列交给 iot 按类型决定）")
    void tcpStringNumericMustKeepRawValue() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("SN", "serialNo", "string", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("SN"), "123456", Instant.now()));

        assertThat(readings).singleElement().satisfies(reading ->
            assertThat(reading.value()).isEqualTo("123456"));
    }

    @Test
    @DisplayName("★ 对照②：TCP string 的典型序列号（007/SN-0001）值原样保留")
    void tcpStringSerialFormsMustKeepRawValue() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("SN1", "serial1", "string", null, null),
                point("SN2", "serial2", "string", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("SN1"), "007", Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("SN2"), "SN-0001", Instant.now()));

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).value()).isEqualTo("007");
        assertThat(readings.get(1).value()).isEqualTo("SN-0001");
    }

    @Test
    @DisplayName("★ 对照③：TCP data_type=long + 123456 ⇒ 规范化为 Long（落列交给 iot 按类型决定）")
    void tcpLongMustNormalizeToLong() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("CNT", "counter", "long", null, null)), readings);

        listener.onData(PointValue.good(PointAddress.of("CNT"), "123456", Instant.now()));

        assertThat(readings).singleElement().satisfies(reading ->
            assertThat(reading.value()).isEqualTo(123456L));
    }

    @Test
    @DisplayName("★ 对照④：Modbus int（非 string）两字合成数值（落列交给 iot 按类型决定）")
    void modbusIntMustDecodePair() {
        AccessPointMappingDto modbusInt = new AccessPointMappingDto();
        modbusInt.setAddress("holding:0");
        modbusInt.setIdentifier("counter");
        modbusInt.setDataType("int");
        modbusInt.setPropertyId("pk-counter");
        modbusInt.setRegisterFormat("int");
        modbusInt.setWordCount(2);
        modbusInt.setSigned(Boolean.FALSE);
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_MODBUS, List.of(modbusInt), readings);

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, Instant.now()));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x0002, Instant.now()));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isInstanceOf(Number.class));
    }

    private static PointMappingDataListener listener(String protocol,
                                                     List<AccessPointMappingDto> points,
                                                     List<AccessReading> readings) {
        return new PointMappingDataListener("100", protocol, 5_000, points, readings::add,
            new SimpleMeterRegistry(), BytePayloadDecoderRegistry.empty());
    }

    /**
     * 构造一个点位映射：{@code identifier} 是上报坐标，{@code propertyId} 只作定位（刻意与标识不同，
     * 以便断言「上报用的是标识而不是主键字符串」）。
     *
     * @param address    协议地址（TCP key-value 下即 selector）
     * @param identifier 属性标识（规范坐标；{@code null} 表示孤儿映射）
     * @param dataType   物模型数据类型（宿主按它决定规范值形态）
     * @param scale      缩放系数
     * @param offset     偏移
     * @return 点位映射
     */
    private static AccessPointMappingDto point(String address, String identifier, String dataType,
                                               BigDecimal scale, BigDecimal offset) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(address);
        point.setIdentifier(identifier);
        point.setDataType(dataType);
        point.setPropertyId(identifier == null ? null : "pk-" + identifier);
        point.setScaleFactor(scale);
        point.setOffsetValue(offset);
        return point;
    }
}
