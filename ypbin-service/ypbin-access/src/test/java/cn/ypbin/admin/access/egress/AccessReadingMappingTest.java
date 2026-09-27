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
import cn.ypbin.admin.access.decode.TextFrameValueDecoder;
import cn.ypbin.admin.access.decode.ValueDecoder;
import cn.ypbin.admin.access.link.PointMappingDataListener;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 点位映射、**原始值解码**与线性缩放的纯逻辑单测（采集数据的正确性关键面）。
 *
 * <p>这里最容易出错、且出错后**不会报错只会算错**的四件事：① 缩放/偏移该不该应用；② 地址对不上时
 * 是静默丢还是留痕；③ **上报的坐标形态**——必须是属性标识（规范坐标），不能退回属性主键字符串
 * （2026-09-26 坐标统一的落点，见 {@code docs/IOT-ROADMAP.md} 四点十七补充段）；
 * ④ **TCP 原始字节必须先解码**——不解码就会落成 {@code [B@<hash>}，曲线只能进文本列。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class AccessReadingMappingTest {

    private static final String PROTOCOL_TCP = "tcp";

    private static final String PROTOCOL_MODBUS = "modbus";

    private static final List<ValueDecoder> DECODERS = List.of(new TextFrameValueDecoder());

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

        listener.onData(new PointValue(PointAddress.of("holding:1"), 250, Quality.GOOD, now, null));

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
        assertThat(listener.decodeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ TCP 帧解码：`TEMP=23.5,SEQ=1` → 值 \"23.5\"（干净数值串 ⇒ 自动进 value_double，曲线可画）")
    void tcpFrameShouldBeDecodedIntoNumericValueForCurve() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("TEMP", "temperature", "decimal", null, null)), readings);

        listener.onData(new PointValue(PointAddress.of("TEMP"),
            "TEMP=23.5,SEQ=12\n".getBytes(StandardCharsets.UTF_8), Quality.GOOD, Instant.now(), null));

        assertThat(readings).hasSize(1);
        Object value = readings.getFirst().value();
        assertThat(value).as("值必须是解码后的数值，不能是原始字节").isNotInstanceOf(byte[].class);
        // 出口走 String.valueOf：形态必须是纯数值串，ReadingValueMapper 才会写 value_double。
        // 这是「曲线画不出来」这条缺陷的回归断言。
        assertThat(String.valueOf(value))
            .isEqualTo("23.5")
            .matches("-?\\d+(\\.\\d+)?");
        assertThat(listener.decodeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ TCP 文本点：serialNo 解码后仍是文本（含前导零不被吞），不进数值列")
    void tcpTextPointMustStayText() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("SN", "serialNo", "string", null, null)), readings);

        listener.onData(new PointValue(PointAddress.of("SN"),
            "SN=007".getBytes(StandardCharsets.UTF_8), Quality.GOOD, Instant.now(), null));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo("007"));
    }

    @Test
    @DisplayName("解码后仍应用缩放/偏移（缩放作用在解码值上，而不是原始字节上）")
    void scaleShouldApplyToDecodedValue() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("TEMP", "temperature", "decimal", new BigDecimal("2"), BigDecimal.ONE)),
            readings);

        listener.onData(new PointValue(PointAddress.of("TEMP"),
            "TEMP=23.5".getBytes(StandardCharsets.UTF_8), Quality.GOOD, Instant.now(), null));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(new BigDecimal("48.000000")));
    }

    @Test
    @DisplayName("★ 解码失败不静默：键不匹配/二进制帧 ⇒ 丢弃 + 计数（带 reason 标签）+ WARN，且不 dump 载荷")
    void decodeFailureMustBeCountedAndWarnedWithoutPayloadDump() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", PROTOCOL_TCP, 5_000,
            List.of(point("TEMP", "temperature", "decimal", null, null)), readings::add,
            DECODERS, meterRegistry);
        Logger listenerLogger = (Logger) LoggerFactory.getLogger(PointMappingDataListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
        try {
            // ① 键不匹配：帧里是 HUM，而该点位声明的是 TEMP
            listener.onData(new PointValue(PointAddress.of("TEMP"), frame("HUM=45"), Quality.GOOD,
                Instant.now(), null));
            // ② 二进制（大小端寄存器）帧：不是合法 UTF-8
            byte[] registerFrame = {(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x02};
            listener.onData(new PointValue(PointAddress.of("TEMP"), registerFrame, Quality.GOOD,
                Instant.now(), null));

            assertThat(readings).as("解不出来就不许进出口（更不许把 [B@… 写进库）").isEmpty();
            assertThat(listener.decodeFailureCount()).isEqualTo(2);
            assertThat(meterRegistry.get(PointMappingDataListener.METRIC_DECODE_FAILURE)
                .tag("reason", "key-not-found").counter().count()).isEqualTo(1.0d);
            assertThat(meterRegistry.get(PointMappingDataListener.METRIC_DECODE_FAILURE)
                .tag("reason", "not-utf8").counter().count()).isEqualTo(1.0d);

            List<String> warns = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
            assertThat(warns).hasSize(2);
            assertThat(warns.getFirst()).contains("device=100", "key-not-found", "载荷长度=6");
            assertThat(warns.get(1)).contains("not-utf8", "载荷长度=4");
            assertThat(String.join("\n", warns))
                .as("WARN 必须脱敏：不得把帧内容原样打进日志")
                .doesNotContain("HUM=45");
        } finally {
            listenerLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("其它协议不受影响：modbus/opcua 已解码的数值原样透传（本层不改取值路径）")
    void otherProtocolsMustPassThroughUnchanged() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_MODBUS,
            List.of(point("holding:1", "temperature", "decimal", null, null)), readings);

        listener.onData(new PointValue(PointAddress.of("holding:1"), 250, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(250));
        assertThat(listener.decodeFailureCount()).isZero();
    }

    @Test
    @DisplayName("★ 孤儿映射（映射行在、物模型属性行已被删除 ⇒ 没有属性标识）：丢弃 + 单独计数，绝不退回主键形态")
    void orphanMappingMustBeDroppedAndCounted() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("holding:1", null, null, null, null)), readings);

        listener.onData(new PointValue(PointAddress.of("holding:1"), 1, Quality.GOOD, Instant.now(), null));

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

        listener.onData(new PointValue(PointAddress.of("holding:99"), 1, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("质量非 GOOD 也要如实上报（断档判定依赖它，不能在采集侧吞掉）")
    void nonGoodQualityShouldBeReported() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(PROTOCOL_TCP,
            List.of(point("holding:1", "temperature", "decimal", null, null)), readings);

        listener.onData(new PointValue(PointAddress.of("holding:1"), null, Quality.BAD,
            Instant.now(), "timeout"));

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
        listener.onData(new PointValue(PointAddress.of("holding:2"), 1, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("byteOrder 对文本帧无影响（寄存器级大小端解码未实现，参数不足 ⇒ 不猜）")
    void byteOrderMustNotAffectTextFrameDecoding() {
        List<AccessReading> readings = new ArrayList<>();
        AccessPointMappingDto littleEndian = point("TEMP", "temperature", "decimal", null, null);
        littleEndian.setByteOrder("little");
        PointMappingDataListener listener = listener(PROTOCOL_TCP, List.of(littleEndian), readings);

        listener.onData(new PointValue(PointAddress.of("TEMP"), frame("TEMP=23.5"), Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).singleElement().satisfies(reading ->
            assertThat(String.valueOf(reading.value()))
                .as("文本帧没有字节序语义；寄存器帧的大小端解码需先补位宽/符号参数")
                .isEqualTo("23.5"));
    }

    private static byte[] frame(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static PointMappingDataListener listener(String protocol,
                                                     List<AccessPointMappingDto> points,
                                                     List<AccessReading> readings) {
        return new PointMappingDataListener("100", protocol, 5_000, points, readings::add, DECODERS,
            new SimpleMeterRegistry());
    }

    /**
     * 构造一个点位映射：{@code identifier} 是上报坐标，{@code propertyId} 只作定位（刻意与标识不同，
     * 以便断言「上报用的是标识而不是主键字符串」）。
     *
     * @param address    协议地址（TCP 文本帧下即 {@code KEY=VALUE} 的键）
     * @param identifier 属性标识（规范坐标；{@code null} 表示孤儿映射）
     * @param dataType   物模型数据类型（解码按它决定规范值形态）
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
