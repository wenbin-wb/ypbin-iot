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

import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
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
 * Modbus 寄存器解码 v4：IEEE754 双精度（{@code register_format=double}，4 寄存器）。
 *
 * <p>强制覆盖：两种字序（big=ABCD / little=DCBA 整体翻转）的数值精确性、最短可回读 BigDecimal、
 * NaN/±Inf 与有限值分开断言、四段中任一 BAD/缺失、同角色重复/超容差、乱序到达、word_count≠4，
 * 以及 float32 与 double 同监听器互不污染。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ModbusDoubleDecodeTest {

    private static final String PROTOCOL = "modbus";

    private static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    @DisplayName("★ double 有限值矩阵：big/little × 正负 × {0.0,-0.0,1.5,1e-300,1e300,最大/最小正规数} ⇒ GOOD + BigDecimal 数值相等")
    void doubleFiniteMatrixMustBeExact() {
        double[] values = {0.0d, -0.0d, 1.5d, -1.5d, 0.1d, 1e-300d, 1e300d,
            Double.MAX_VALUE, Double.MIN_NORMAL};
        for (double value : values) {
            for (String order : new String[] {"big", "little"}) {
                BigDecimal decoded = (BigDecimal) doubleReading(Double.doubleToRawLongBits(value), order)
                    .value();
                assertThat(decoded)
                    .as("value=%s order=%s 必须按 BigDecimal.valueOf 物化", value, order)
                    .isEqualByComparingTo(BigDecimal.valueOf(value));
                assertThat(decoded.doubleValue())
                    .as("round-trip 恒等：value=%s order=%s", value, order).isEqualTo(value);
            }
        }
    }

    @Test
    @DisplayName("★ double 精度口径：BigDecimal.valueOf(Double.toString) 最短可回读；不得 new BigDecimal(double) 的 17 位噪声")
    void doubleDecimalMustBeShortestRoundTrip() {
        BigDecimal pointOne = (BigDecimal) doubleReading(Double.doubleToRawLongBits(0.1d), "big").value();
        assertThat(pointOne.toString()).as("0.1 必须是最短表示").isEqualTo("0.1");
        assertThat(pointOne).isNotEqualByComparingTo(new BigDecimal(0.1d));

        BigDecimal max = (BigDecimal) doubleReading(Double.doubleToRawLongBits(Double.MAX_VALUE), "little")
            .value();
        assertThat(max).isEqualByComparingTo(new BigDecimal(Double.toString(Double.MAX_VALUE)));
        assertThat(max.doubleValue()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("★ double NaN/±Inf（big 与 little 两种字序）⇒ BAD + non-finite 计数，且与有限值分开断言")
    void doubleNonFiniteMustBeBadAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        long[] nonFiniteBits = {Double.doubleToRawLongBits(Double.NaN),
            Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
            Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)};
        for (String order : new String[] {"big", "little"}) {
            List<AccessReading> readings = new ArrayList<>();
            PointMappingDataListener listener = listener(List.of(doublePoint(order)), readings, registry);
            for (int i = 0; i < nonFiniteBits.length; i++) {
                feedDouble(listener, 0, nonFiniteBits[i], order, T0.plusSeconds(i));
            }
            assertThat(readings).as("order=%s 三条非有限值都必须 BAD", order).hasSize(3)
                .allSatisfy(reading -> {
                    assertThat(reading.quality()).isEqualTo("BAD");
                    assertThat(reading.value()).isNull();
                });
            assertThat(listener.nonFiniteFloatCount()).isEqualTo(3);
        }
        assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "non-finite").counter().count())
            .as("两种字序共 6 条").isEqualTo(6.0d);

        // 有限值必须 GOOD（与上面分开断言）：同一 0.1 位模式不因上面的 NaN 计数而被污染
        assertThat(doubleReading(Double.doubleToRawLongBits(0.1d), "little").quality()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("★ double 四段任一 BAD ⇒ 点位 BAD 并清空暂存；随后完整 4 字仍能正常合成")
    void doubleBadRegisterMustBeBadAndClearBuffer() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(doublePoint("big")), readings,
            new SimpleMeterRegistry());

        feedWord(listener, 0, 0x3FF8, T0);
        feedWord(listener, 1, 0x0000, T0.plusMillis(5));
        listener.onData(PointValue.bad(PointAddress.of("holding:2"), Quality.BAD, "timeout",
            T0.plusMillis(10)));
        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });

        // 暂存已清空：完整 4 字重新到达 ⇒ GOOD（若未清空会被同角色/陈旧判据丢弃）
        readings.clear();
        feedDouble(listener, 0, Double.doubleToRawLongBits(1.5d), "big", T0.plusSeconds(1));
        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).isEqualTo(new BigDecimal("1.5"));
        });
    }

    @Test
    @DisplayName("★ double 同角色重复 ⇒ 整组丢弃 + pair.stale 计数，随后新组仍能合成")
    void doubleSameRoleDuplicateMustDiscardGroupAndCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(doublePoint("big")), readings, registry);

        feedWord(listener, 0, 0x3FF8, T0);
        feedWord(listener, 0, 0x3FF8, T0.plusMillis(5));
        feedWord(listener, 1, 0x0000, T0.plusMillis(10));
        feedWord(listener, 2, 0x0000, T0.plusMillis(15));
        feedWord(listener, 3, 0x0000, T0.plusMillis(20));

        assertThat(readings).as("重复同角色后仍以新组合成一条 GOOD").singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(new BigDecimal("1.5")));
        assertThat(registry.get(ModbusRegisterMetrics.METRIC_PAIR_STALE).counter().count())
            .as("同角色重复必须计 pair.stale").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ double 超容差 ⇒ 整组丢弃 + pair.stale 计数，随后新组仍能合成")
    void doubleStaleGroupMustDiscardAndCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(doublePoint("big")), readings, registry);

        feedWord(listener, 0, 0x3FF8, T0);
        // pollIntervalMs=5000 ⇒ 超过一个周期视为组陈旧
        feedWord(listener, 1, 0x0000, T0.plusMillis(6_000));
        feedWord(listener, 0, 0x3FF8, T0.plusMillis(6_005));
        feedWord(listener, 2, 0x0000, T0.plusMillis(6_010));
        feedWord(listener, 3, 0x0000, T0.plusMillis(6_015));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo(new BigDecimal("1.5")));
        assertThat(registry.get(ModbusRegisterMetrics.METRIC_PAIR_STALE).counter().count())
            .as("超容差必须计 pair.stale").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ double 乱序到达（按 word_index 收齐）⇒ GOOD")
    void doubleOutOfOrderMustAssemble() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(doublePoint("little")), readings,
            new SimpleMeterRegistry());

        long bits = Double.doubleToRawLongBits(-23.5d);
        int[] words = wordsOf(bits, "little");
        feedWord(listener, 3, words[3], T0.plusMillis(0));
        feedWord(listener, 1, words[1], T0.plusMillis(5));
        feedWord(listener, 0, words[0], T0.plusMillis(10));
        feedWord(listener, 2, words[2], T0.plusMillis(15));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).isEqualTo(new BigDecimal("-23.5"));
        });
    }

    @Test
    @DisplayName("★ double 直连 DB 坏数据 word_count≠4 ⇒ BAD + word-count-invalid（不静默只用前 4 个）")
    void doubleWrongWordCountMustBeBad() {
        for (int wrongCount : new int[] {2, 3, 5}) {
            List<AccessReading> readings = new ArrayList<>();
            AccessPointMappingDto bad = doublePoint("big");
            bad.setWordCount(wrongCount);
            PointMappingDataListener listener = listener(List.of(bad), readings, new SimpleMeterRegistry());

            for (int i = 0; i < wrongCount; i++) {
                feedWord(listener, i, 0x0000, T0.plusMillis(i * 5L));
            }

            assertThat(readings).as("word_count=%s 必须 BAD", wrongCount).singleElement()
                .satisfies(reading -> {
                    assertThat(reading.quality()).isEqualTo("BAD");
                    assertThat(reading.value()).isNull();
                });
        }
    }

    @Test
    @DisplayName("★ float32 与 double 同监听器互不污染（各自 word_index 收齐，值/计数独立）")
    void float32AndDoubleMustNotPolluteEachOther() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(
            List.of(floatPoint(0, "float-temp", "float"), doublePoint(10, "double-temp", "double")),
            readings, new SimpleMeterRegistry());

        long doubleBits = Double.doubleToRawLongBits(1.5d);
        int[] doubleWords = wordsOf(doubleBits, "big");
        int[] floatWords = wordsOf32(Float.floatToRawIntBits(23.4f), "big");

        // 交错到达：先 double 的 0/2，再 float 的 0/1，再 double 的 1/3
        feedWord(listener, 10, doubleWords[0], T0.plusMillis(0));
        feedWord(listener, 12, doubleWords[2], T0.plusMillis(1));
        feedWord(listener, 0, floatWords[0], T0.plusMillis(2));
        feedWord(listener, 1, floatWords[1], T0.plusMillis(3));
        feedWord(listener, 11, doubleWords[1], T0.plusMillis(4));
        feedWord(listener, 13, doubleWords[3], T0.plusMillis(5));

        assertThat(readings).hasSize(2);
        assertThat(readings).anySatisfy(reading -> {
            assertThat(reading.propertyId()).isEqualTo("float-temp");
            assertThat(reading.value()).isEqualTo(new BigDecimal("23.4"));
        }).anySatisfy(reading -> {
            assertThat(reading.propertyId()).isEqualTo("double-temp");
            assertThat(reading.value()).isEqualTo(new BigDecimal("1.5"));
        });
        assertThat(listener.nonFiniteFloatCount()).isZero();
    }

    // ==================== helpers ====================

    private static AccessReading doubleReading(long bits, String byteOrder) {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(doublePoint(byteOrder)), readings,
            new SimpleMeterRegistry());
        feedDouble(listener, 0, bits, byteOrder, T0);
        assertThat(readings).as("有限值必须产出一条 GOOD 读数").hasSize(1);
        assertThat(readings.getFirst().quality()).isEqualTo("GOOD");
        return readings.getFirst();
    }

    private static void feedDouble(PointMappingDataListener listener, int base, long bits,
                                   String byteOrder, Instant ts) {
        int[] words = wordsOf(bits, byteOrder);
        for (int i = 0; i < 4; i++) {
            feedWord(listener, base + i, words[i], ts.plusMillis(i * 5L));
        }
    }

    private static void feedWord(PointMappingDataListener listener, int offset, int word, Instant ts) {
        listener.onData(PointValue.good(PointAddress.of("holding:" + offset), word, ts));
    }

    private static int[] wordsOf(long bits, String byteOrder) {
        int[] big = {(int) (bits >>> 48) & 0xFFFF, (int) (bits >>> 32) & 0xFFFF,
            (int) (bits >>> 16) & 0xFFFF, (int) bits & 0xFFFF};
        return "little".equals(byteOrder) ? new int[] {big[3], big[2], big[1], big[0]} : big;
    }

    private static int[] wordsOf32(int bits, String byteOrder) {
        int high = (bits >>> 16) & 0xFFFF;
        int low = bits & 0xFFFF;
        return "little".equals(byteOrder) ? new int[] {low, high} : new int[] {high, low};
    }

    private static PointMappingDataListener listener(List<AccessPointMappingDto> points,
                                                     List<AccessReading> readings,
                                                     SimpleMeterRegistry registry) {
        return new PointMappingDataListener("100", PROTOCOL, 5_000, points, readings::add, registry,
            BytePayloadDecoderRegistry.empty());
    }

    private static AccessPointMappingDto doublePoint(String byteOrder) {
        return doublePoint(0, "temperature", byteOrder);
    }

    private static AccessPointMappingDto doublePoint(int base, String identifier, String byteOrder) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress("holding:" + base);
        point.setIdentifier(identifier);
        point.setDataType("decimal");
        point.setPropertyId("pk-" + identifier);
        point.setWordCount(4);
        point.setSigned(Boolean.FALSE);
        point.setRegisterFormat("double");
        point.setByteOrder(byteOrder);
        return point;
    }

    private static AccessPointMappingDto floatPoint(int base, String identifier, String byteOrder) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress("holding:" + base);
        point.setIdentifier(identifier);
        point.setDataType("decimal");
        point.setPropertyId("pk-" + identifier);
        point.setWordCount(2);
        point.setSigned(Boolean.FALSE);
        point.setRegisterFormat("float");
        point.setByteOrder(byteOrder);
        return point;
    }
}
