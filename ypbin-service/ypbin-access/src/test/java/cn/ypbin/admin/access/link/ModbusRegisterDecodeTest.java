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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.access.decode.BytePayloadDecoderRegistry;
import cn.ypbin.admin.access.egress.AccessReading;
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

/**
 * Modbus 寄存器解码 v1：8 组矩阵 + 宿主侧半字配对/失败语义 + 向后兼容。
 *
 * <p>SDK 对每个寄存器**逐值回调**（16 位无符号 Integer），32 位点位必须由宿主按点位配对两个寄存器后
 * 合成一条读数；任一寄存器 BAD ⇒ 点位 BAD，绝不用陈旧一半/0 拼值。这里把「切换前=切换后」
 * （word_count=1/signed=false 原样透传）与 8 组矩阵逐条钉住。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ModbusRegisterDecodeTest {

    private static final String PROTOCOL = "modbus";

    private static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    @DisplayName("★ 解码矩阵：16 位 signed/unsigned + 32 位 big/little × signed/unsigned（8 组）")
    void decoderMatrixMustBeExact() {
        // word_count=1：unsigned 原样；signed 16 位符号扩展
        assertThat(ModbusRegisterDecoder.decodeSingle(0xFFFF, false)).isEqualTo(65535);
        assertThat(ModbusRegisterDecoder.decodeSingle(0xFFFF, true)).isEqualTo(-1);
        assertThat(ModbusRegisterDecoder.decodeSingle(0x8000, true)).isEqualTo(-32768);
        assertThat(ModbusRegisterDecoder.decodeSingle(0x7FFF, true)).isEqualTo(32767);

        // word_count=2：big=高字在前(ABCD)、little=低字在前(CDAB)
        assertThat(ModbusRegisterDecoder.decodePair(0x1234, 0x5678, "big", false))
            .isEqualTo(0x12345678L);
        assertThat(ModbusRegisterDecoder.decodePair(0x1234, 0x5678, "little", false))
            .isEqualTo(0x56781234L);
        assertThat(ModbusRegisterDecoder.decodePair(0x1234, 0x5678, null, false))
            .as("byte_order 为 null ⇒ 按 big").isEqualTo(0x12345678L);
        assertThat(ModbusRegisterDecoder.decodePair(0xFFFF, 0xFFFF, "big", false))
            .as("32 位无符号上界 2^32-1 不截断").isEqualTo(4294967295L);
        assertThat(ModbusRegisterDecoder.decodePair(0xFFFF, 0xFFFF, "big", true)).isEqualTo(-1L);
        assertThat(ModbusRegisterDecoder.decodePair(0x8000, 0x0000, "big", true))
            .as("32 位有符号下界").isEqualTo(-2147483648L);
        assertThat(ModbusRegisterDecoder.decodePair(0x0001, 0xFFFF, "little", true))
            .as("little：raw32=0xFFFF0001 ⇒ -65535").isEqualTo(-65535L);
    }

    @Test
    @DisplayName("★ v4 解码矩阵：IEEE754 双精度 4 寄存器 big=ABCD / little=DCBA（整体翻转）")
    void doubleDecoderMatrixMustBeExact() {
        long bits = Double.doubleToRawLongBits(1.5);
        int[] big = {(int) (bits >>> 48) & 0xFFFF, (int) (bits >>> 32) & 0xFFFF,
            (int) (bits >>> 16) & 0xFFFF, (int) bits & 0xFFFF};
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(big, "big")).isEqualTo(1.5d);

        int[] little = {big[3], big[2], big[1], big[0]};
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(little, "little")).isEqualTo(1.5d);

        // 字序必须真的生效：同一 4 字序列在 big/little 下是不同的 double
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(big, "little")).isNotEqualTo(1.5d);
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(big, null))
            .as("byte_order 为 null ⇒ 按 big").isEqualTo(1.5d);

        // 0.1 的位模式 round-trip；用 BigDecimal.valueOf 直接能取最短表示（在 listener 用例里断言）
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(
            wordsOf(Double.doubleToRawLongBits(0.1), "big"), "big")).isEqualTo(0.1d);

        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeDoubleBits(new int[] {1, 2, 3}, "big"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("必须恰好 4 个寄存器");
    }

    private static int[] wordsOf(long bits, String byteOrder) {
        int[] big = {(int) (bits >>> 48) & 0xFFFF, (int) (bits >>> 32) & 0xFFFF,
            (int) (bits >>> 16) & 0xFFFF, (int) bits & 0xFFFF};
        return "little".equals(byteOrder) ? new int[] {big[3], big[2], big[1], big[0]} : big;
    }

    @Test
    @DisplayName("★ 向后兼容：word_count=1/signed=false 原样透传（Integer 与值都不变）")
    void wordCountOneUnsignedMustPassThroughUnchanged() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "temperature", "int", 1, false, null)), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 250, T0));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.value()).as("默认路径必须与切换前逐字节一致").isEqualTo(250);
            assertThat(reading.propertyId()).isEqualTo("temperature");
        });
        assertThat(listener.pairStaleCount()).isZero();
    }

    @Test
    @DisplayName("★ word_count=1 + signed=true：16 位符号扩展（0xFFFF ⇒ -1）")
    void wordCountOneSignedMustSignExtend() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "temperature", "int", 1, true, null)), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0xFFFF, T0));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(-1));
    }

    @Test
    @DisplayName("★ 32 位 big 无符号：两半齐才产出一条读数，值 0x12345678")
    void wordCountTwoBigUnsignedMustPairToOneReading() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "counter", "long", 2, false, "big")), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x1234, T0));
        assertThat(readings).as("只到一半不得产出读数").isEmpty();

        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x5678, T0.plusMillis(5)));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.value()).isEqualTo(0x12345678L);
            assertThat(reading.quality()).isEqualTo("GOOD");
        });
        assertThat(listener.pairStaleCount()).isZero();
    }

    @Test
    @DisplayName("★ 32 位 little 有符号：base=1、base+1=0xFFFF ⇒ -65535（字序生效）")
    void wordCountTwoLittleSignedMustPair() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "delta", "long", 2, true, "little")), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, T0));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0xFFFF, T0.plusMillis(5)));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(-65535L));
    }

    @Test
    @DisplayName("★ 缩放作用在合成的 32 位值上（不是某一半）")
    void wordCountTwoScaleAppliedToCombinedValue() {
        AccessPointMappingDto point = point("holding:0", "counter", "long", 2, false, "big");
        point.setScaleFactor(new BigDecimal("0.1"));
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(point), readings, new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0000, T0));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x04D2, T0.plusMillis(5)));

        // 0x04D2 = 1234 ⇒ 1234 * 0.1 = 123.400000
        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(new BigDecimal("123.400000")));
    }

    @Test
    @DisplayName("★ 任一寄存器 BAD ⇒ 该点位 BAD 并清空暂存（绝不用陈旧一半拼值）")
    void wordCountTwoBadRegisterMustEmitBadAndClear() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "counter", "long", 2, false, "big")), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x1234, T0));
        listener.onData(PointValue.bad(PointAddress.of("holding:1"), Quality.BAD, "timeout",
            T0.plusMillis(5)));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).as("BAD 不得用陈旧一半拼出值").isNull();
        });

        // 暂存已清空：下一个周期的两半仍能正常配对（不会与 BAD 前的一半混）
        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, T0.plusSeconds(6)));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x0002, T0.plusSeconds(6)));

        assertThat(readings).hasSize(2);
        assertThat(readings.get(1).value()).isEqualTo(0x00010002L);
        assertThat(listener.pairStaleCount()).as("BAD 清空不算陈旧").isZero();
    }

    @Test
    @DisplayName("★ 半字陈旧：同一 half 隔一个周期再来 ⇒ 丢弃旧的 + 计数，再与新 half 配对")
    void wordCountTwoStaleHalfMustBeDiscardedAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "counter", "long", 2, false, "big")), readings, registry);

        // 第 1 个周期只到了 base（另一半没到）
        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, T0));
        // 第 2 个周期 base 又到了：旧的一半超过一个采集周期（5s）⇒ 丢弃 + 计数
        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0002, T0.plusSeconds(6)));
        // 第 2 个周期的 base+1 到了 ⇒ 与新的 base 配对
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x0003, T0.plusSeconds(6)));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(0x00020003L));
        assertThat(listener.pairStaleCount()).isEqualTo(1);
        assertThat(registry.get(ModbusRegisterMetrics.METRIC_PAIR_STALE).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 同角色 half 在一个周期内重复到达 ⇒ 视为陈旧丢弃 + 计数（不把两个同角色值拼成假 32 位）")
    void wordCountTwoSameRoleWithinToleranceMustBeDiscarded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "counter", "long", 2, false, "big")), readings, registry);

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, T0));
        // 同一 half 在容差内重复到达：丢弃旧的 + 计数，避免把两个 base 值拼成假 32 位
        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0002, T0.plusMillis(100)));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x0003, T0.plusMillis(100)));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(0x00020003L));
        assertThat(listener.pairStaleCount()).isEqualTo(1);
        assertThat(registry.get(ModbusRegisterMetrics.METRIC_PAIR_STALE).counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 32 位点位收到非数值寄存器值 ⇒ 按 BAD 上报（不把垃圾写进库）")
    void wordCountTwoNonNumericMustEmitBad() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("holding:0", "counter", "long", 2, false, "big")), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), "not-a-number", T0));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });
    }

    @Test
    @DisplayName("★ 向后兼容：word_count=1 的传统 5 位编号照常可用（不在宿主做地址解析）")
    void wordCountOneTraditionalAddressStillWorks() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(point("40001", "temperature", "int", 1, false, null)), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("40001"), 250, T0));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(250));
    }

    private static PointMappingDataListener listener(List<AccessPointMappingDto> points,
                                                     List<AccessReading> readings,
                                                     SimpleMeterRegistry registry) {
        return new PointMappingDataListener("100", PROTOCOL, 5_000, points, readings::add, registry,
            BytePayloadDecoderRegistry.empty());
    }

    private static AccessPointMappingDto point(String address, String identifier, String dataType,
                                               Integer wordCount, Boolean signed, String byteOrder) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(address);
        point.setIdentifier(identifier);
        point.setDataType(dataType);
        point.setPropertyId("pk-" + identifier);
        point.setWordCount(wordCount);
        point.setSigned(signed);
        point.setByteOrder(byteOrder);
        return point;
    }
}
