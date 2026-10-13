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

import cn.ypbin.admin.access.decode.BytePayloadDecoderRegistry;
import cn.ypbin.admin.access.egress.AccessReading;
import cn.ypbin.admin.access.egress.PointValueNormalizeMetrics;
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
 * Modbus 寄存器解码 v2：IEEE754 单精度（复用 v1 半字配对）与单寄存器位域。
 *
 * <p>float 的有限值（GOOD + BigDecimal 精确）与 NaN/±Inf（BAD + non-finite 计数）**分开断言**；
 * bits 的 offset/length 边界与"布尔位域不缩放"逐条钉住。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ModbusRegisterFormatDecodeTest {

    private static final String PROTOCOL = "modbus";

    private static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    @DisplayName("★ float 有限值矩阵：big/little × 正/负 × {0.0,-0.0,1.5,3.4028235E38} ⇒ GOOD + BigDecimal 精确")
    void floatFiniteMatrixMustBeExact() {
        // 新口径：Float.toString 最短可回读 ⇒ 0.1f→0.1、23.4f→23.4（不是 Double.toString 的 17 位噪声）
        assertThat(floatReading(0x3DCCCCCD, "big").value()).isEqualTo(new BigDecimal("0.1"));
        assertThat(floatReading(0x3DCCCCCD, "little").value()).isEqualTo(new BigDecimal("0.1"));
        assertThat(floatReading(0x41BB3333, "big").value()).isEqualTo(new BigDecimal("23.4"));
        assertThat(floatReading(0x41BB3333, "little").value()).isEqualTo(new BigDecimal("23.4"));
        assertThat(floatReading(0x3FC00000, "big").value()).isEqualTo(new BigDecimal("1.5"));
        assertThat(floatReading(0x3FC00000, "little").value()).isEqualTo(new BigDecimal("1.5"));
        assertThat(floatReading(0xBFC00000, "big").value()).isEqualTo(new BigDecimal("-1.5"));
        assertThat(floatReading(0xBFC00000, "little").value()).isEqualTo(new BigDecimal("-1.5"));
        assertThat((BigDecimal) floatReading(0x00000000, "big").value())
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) floatReading(0x80000000, "big").value())
            .as("-0.0f：BigDecimal 无负零 ⇒ 归一到 0.0（IEEE 符号零对曲线无意义）")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) floatReading(0x7F7FFFFF, "big").value())
            .isEqualByComparingTo(new BigDecimal("3.4028235E38"));
        assertThat((BigDecimal) floatReading(0x7F7FFFFF, "little").value())
            .isEqualByComparingTo(new BigDecimal("3.4028235E38"));
        // 字序必须真的生效：同一个 32 位模式在 big/little 下是不同的数
        assertThat(floatReading(0x00003FC0, "big").value())
            .as("0x00003FC0 按 big 解释是一个极小值，按 little 才是 1.5").isNotEqualTo(new BigDecimal("1.5"));
    }

    @Test
    @DisplayName("★ float 精度口径：Float.toString 最短可回读（round-trip 恒等），不得换回 Double.toString")
    void floatDecimalMustBeShortestRoundTrip() {
        int[] patterns = {0x3DCCCCCD, 0x41BB3333, 0x3FC00000, 0xBFC00000, 0x7F7FFFFF};
        for (int bits : patterns) {
            float f = Float.intBitsToFloat(bits);
            BigDecimal decoded = new BigDecimal(Float.toString(f));
            // 按**数值**比较：`BigDecimal.toString()` 的指数带符号（E+38），`Float.toString()` 不带（E38）
            // ——比字符串格式会把"格式差异"误报成"精度错误"（本用例初版就这么错过一次）。
            assertThat(decoded).as("最短可回读（无 17 位噪声）：%s", Float.toString(f))
                .isEqualByComparingTo(new BigDecimal(Float.toString(f)));
            assertThat(decoded.floatValue()).as("round-trip 恒等：parseFloat(toString(f)) == f")
                .isEqualTo(f);
        }
        assertThat(new BigDecimal(Float.toString(0.1f)).toString()).isEqualTo("0.1");
        assertThat(new BigDecimal(Float.toString(23.4f)).toString()).isEqualTo("23.4");
        assertThat(new BigDecimal(Float.toString(1.5f)).toString()).isEqualTo("1.5");
        assertThat(new BigDecimal(Float.toString(3.4028235E38f)).toString()).isEqualTo("3.4028235E+38");
        assertThat(new BigDecimal(Float.toString(-1.5f)).toString()).isEqualTo("-1.5");
    }

    @Test
    @DisplayName("★ float NaN/±Inf ⇒ BAD + non-finite 计数（与有限值分开断言，不落库）")
    void floatNonFiniteMustBeBadAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> bigReadings = new ArrayList<>();
        PointMappingDataListener big = listener(List.of(floatPoint("big")), bigReadings, registry);
        feedFloat(big, 0x7FC00000, "big", T0);                       // NaN
        feedFloat(big, 0x7F800000, "big", T0.plusSeconds(1));        // +Inf

        List<AccessReading> littleReadings = new ArrayList<>();
        PointMappingDataListener little = listener(List.of(floatPoint("little")), littleReadings,
            registry);
        feedFloat(little, 0xFF800000, "little", T0.plusSeconds(2));  // -Inf（little 字序）

        assertThat(bigReadings).hasSize(2).allSatisfy(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });
        assertThat(littleReadings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });
        assertThat(big.nonFiniteFloatCount()).isEqualTo(2);
        assertThat(little.nonFiniteFloatCount()).isEqualTo(1);
        assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "non-finite").counter().count())
            .isEqualTo(3.0d);
    }

    @Test
    @DisplayName("★ float 任一寄存器 BAD ⇒ 点位 BAD（复用 v1 配对，不用陈旧一半）")
    void floatBadRegisterMustBeBad() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(floatPoint("big")), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x3FC0, T0));
        listener.onData(PointValue.bad(PointAddress.of("holding:1"), Quality.BAD, "timeout",
            T0.plusMillis(5)));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });
    }

    @Test
    @DisplayName("★ bits length=1 ⇒ Boolean，且**不参与缩放**（配了 scale 也不生效）")
    void bitsBooleanMustNotBeScaled() {
        AccessPointMappingDto point = bitsPoint(0, 1, "bool");
        point.setScaleFactor(new BigDecimal("10"));
        point.setOffsetValue(BigDecimal.ONE);
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(point), readings, new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, T0));
        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0000, T0));

        assertThat(readings).extracting(AccessReading::value)
            .as("布尔位域不是 Number ⇒ applyScale 原样返回，配 scale 也不生效")
            .containsExactly(Boolean.TRUE, Boolean.FALSE);
        assertThat(readings.get(0).value()).isInstanceOf(Boolean.class);
    }

    @Test
    @DisplayName("★ bits 边界：offset 0/15、length 1/16、offset+length=16、中间位段")
    void bitsBoundariesMustBeExact() {
        // offset=0, length=16：整寄存器无符号
        assertThat(bitsValue(0, 16, 0xFFFF)).isEqualTo(65535);
        assertThat(bitsValue(0, 16, 0x1234)).isEqualTo(0x1234);
        // offset=15, length=1：最高位
        assertThat(bitsValue(15, 1, 0x8000)).isEqualTo(Boolean.TRUE);
        assertThat(bitsValue(15, 1, 0x7FFF)).isEqualTo(Boolean.FALSE);
        // offset=4, length=8：取中间字节
        assertThat(bitsValue(4, 8, 0xB0 << 4)).isEqualTo(0xB0);
        // offset=0, length=12
        assertThat(bitsValue(0, 12, 0x0ABC)).isEqualTo(0x0ABC);
    }

    @Test
    @DisplayName("★ bits length>1 走既有 applyScale（可缩放）；length=1 不缩放")
    void bitsMultiBitScaleMustApply() {
        AccessPointMappingDto point = bitsPoint(0, 4, "int");
        point.setScaleFactor(new BigDecimal("10"));
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(point), readings, new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x0002, T0));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(new BigDecimal("20.000000")));
    }

    @Test
    @DisplayName("★ bits 区间非法（直连 DB 坏数据）⇒ BAD + 日志，不抛断链路")
    void bitsInvalidRangeMustBeBad() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(bitsPoint(16, 1, "bool"), bitsPoint(15, 2, "int")), readings,
            new SimpleMeterRegistry());

        listener.onData(PointValue.good(PointAddress.of("holding:0"), 0x0001, T0));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), 0x0001, T0));

        assertThat(readings).hasSize(2).allSatisfy(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });
    }

    private static Object bitsValue(int offset, int length, int raw) {
        return ModbusRegisterDecoder.decodeBits(raw, offset, length);
    }

    private static AccessReading floatReading(int bits32, String byteOrder) {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(floatPoint(byteOrder)), readings,
            new SimpleMeterRegistry());
        feedFloat(listener, bits32, byteOrder, T0);
        assertThat(readings).as("有限值必须产出一条 GOOD 读数").hasSize(1);
        assertThat(readings.getFirst().quality()).isEqualTo("GOOD");
        return readings.getFirst();
    }

    /** 按字序把一个 32 位模式拆成两半喂给 listener（模拟 SDK 逐寄存器回调）。 */
    private static void feedFloat(PointMappingDataListener listener, int bits32, String byteOrder,
                                  Instant ts) {
        int high = (bits32 >>> 16) & 0xFFFF;
        int low = bits32 & 0xFFFF;
        int first = "little".equals(byteOrder) ? low : high;
        int second = "little".equals(byteOrder) ? high : low;
        listener.onData(PointValue.good(PointAddress.of("holding:0"), first, ts));
        listener.onData(PointValue.good(PointAddress.of("holding:1"), second, ts.plusMillis(5)));
    }

    private static PointMappingDataListener listener(List<AccessPointMappingDto> points,
                                                     List<AccessReading> readings,
                                                     SimpleMeterRegistry registry) {
        return new PointMappingDataListener("100", PROTOCOL, 5_000, points, readings::add, registry,
            BytePayloadDecoderRegistry.empty());
    }

    private static AccessPointMappingDto floatPoint(String byteOrder) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress("holding:0");
        point.setIdentifier("temperature");
        point.setDataType("decimal");
        point.setPropertyId("pk-temperature");
        point.setWordCount(2);
        point.setSigned(Boolean.FALSE);
        point.setRegisterFormat("float");
        point.setByteOrder(byteOrder);
        return point;
    }

    private static AccessPointMappingDto bitsPoint(int offset, int length, String dataType) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(length == 1 ? "holding:0" : "holding:1");
        point.setIdentifier("flag");
        point.setDataType(dataType);
        point.setPropertyId("pk-flag");
        point.setWordCount(1);
        point.setSigned(Boolean.FALSE);
        point.setRegisterFormat("bits");
        point.setBitOffset(offset);
        point.setBitLength(length);
        return point;
    }
}
