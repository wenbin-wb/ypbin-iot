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
import cn.ypbin.admin.access.egress.PointValueNormalizeMetrics;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.admin.iot.modbus.ModbusRegisterAddress;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Modbus 寄存器解码 v3：多寄存器拼严格 UTF-8（{@code register_format=string}）。
 *
 * <p>覆盖冻结契约的填充/解码口径（高字节在前、去尾 NUL、不去空格、中间 NUL 保留、全 NUL ⇒ 空串、
 * 非法 UTF-8 ⇒ BAD + {@code not-utf8}）与泛化后的 N 字配对（乱序到达、同角色重复、超容差、BAD 清空）；
 * 并用一条 int 用例证明泛化后的路径对 v1/v2 行为无回归。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ModbusStringDecodeTest {

    private static final String PROTOCOL = "modbus";

    private static final Instant T0 = Instant.parse("2026-10-13T00:00:00Z");

    // ==================== 纯解码：填充与编码 ====================

    @Test
    @DisplayName("★ 中文+ASCII 混合：按寄存器顺序、高字节在前拼字节后严格 UTF-8 解码")
    void chineseAndAsciiMixed() throws CharacterCodingException {
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(words("序列号SN-001")))
            .isEqualTo("序列号SN-001");
    }

    @Test
    @DisplayName("★ 跨字边界：多字节字符可跨两个寄存器（奇偶混排）")
    void multiByteCrossesWordBoundary() throws CharacterCodingException {
        // "中A" = E4 B8 AD 41 ⇒ 0xE4B8, 0xAD41（第二个字的低字节是下一个字符）
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(words("中A"))).isEqualTo("中A");
    }

    @Test
    @DisplayName("★ 去尾部连续 NUL（声明 N 大于实际内容），但不去空格、中间 NUL 保留")
    void trailingNulStrippedMiddleNulKept() throws CharacterCodingException {
        // "AB" 声明 4 个寄存器：尾部全是 0x00 ⇒ 去掉后仍是 "AB"
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[] {0x4142, 0, 0, 0}))
            .isEqualTo("AB");
        // "A\0B" ⇒ 中间 NUL 必须保留
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[] {0x4100, 0x4200}))
            .isEqualTo("A\u0000B");
        // 空格是数据，不去
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(words(" A B "))).isEqualTo(" A B ");
    }

    @Test
    @DisplayName("★ 全 NUL ⇒ GOOD + 空串（不是 BAD：设备确实报了「未填」）")
    void allNulIsEmptyString() throws CharacterCodingException {
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[] {0, 0})).isEmpty();
    }

    @Test
    @DisplayName("★ 单寄存器字符串：一个字也能解（ASCII 两字节）")
    void singleRegisterString() throws CharacterCodingException {
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[] {0x4142})).isEqualTo("AB");
    }

    @Test
    @DisplayName("🔴 非法 UTF-8 ⇒ 抛 CharacterCodingException（严格解码，不替换 U+FFFD）")
    void invalidUtf8MustFailStrictly() {
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeStringUtf8(new int[] {0xC328}))
            .isInstanceOf(CharacterCodingException.class);
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeStringUtf8(new int[] {0xFFFF}))
            .isInstanceOf(CharacterCodingException.class);
    }

    // ==================== listener 集成：配对/落列/失败语义 ====================

    @Test
    @DisplayName("★ N=3 乱序到达 ⇒ 按 word_index 收齐后按序拼接（同一套泛化配对）")
    void outOfOrderWordsAssembleByWordIndex() {
        List<AccessReading> readings = new ArrayList<>();
        int[] words = words("AB中文"); // 2 + 6 = 8 bytes ⇒ 4 words
        assertThat(words.length).isEqualTo(4);
        PointMappingDataListener four = listener(List.of(stringPoint("holding:0", 4)), readings,
            new SimpleMeterRegistry());
        feed(four, "holding:0", 3, words[3], T0.plusMillis(3));
        feed(four, "holding:0", 0, words[0], T0);
        feed(four, "holding:0", 2, words[2], T0.plusMillis(2));
        feed(four, "holding:0", 1, words[1], T0.plusMillis(1));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).isEqualTo("AB中文");
        });
    }

    @Test
    @DisplayName("★ 声明 N 大于实际内容：尾部 NUL 被去掉后产出短串")
    void declaredMoreThanContentTrimsTrailingNul() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(stringPoint("holding:0", 4)), readings,
            new SimpleMeterRegistry());

        feed(listener, "holding:0", 0, 0x4142, T0);
        feed(listener, "holding:0", 1, 0, T0.plusMillis(1));
        feed(listener, "holding:0", 2, 0, T0.plusMillis(2));
        feed(listener, "holding:0", 3, 0, T0.plusMillis(3));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).isEqualTo("AB");
        });
    }

    @Test
    @DisplayName("★ 全 NUL ⇒ GOOD + 空串（不是 BAD）")
    void allNulReadingIsGoodEmpty() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(stringPoint("holding:0", 2)), readings,
            new SimpleMeterRegistry());

        feed(listener, "holding:0", 0, 0, T0);
        feed(listener, "holding:0", 1, 0, T0.plusMillis(1));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).isEqualTo("");
        });
    }

    @Test
    @DisplayName("🔴 非法 UTF-8 ⇒ 点位 BAD + not-utf8 计数（复用 iot.access.value.normalize.failure）")
    void invalidUtf8ReadingIsBadAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(stringPoint("holding:0", 2)), readings,
            registry);

        feed(listener, "holding:0", 0, 0xC328, T0);
        feed(listener, "holding:0", 1, 0x0000, T0.plusMillis(1));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });
        assertThat(listener.normalizeFailureCount()).isEqualTo(1);
        assertThat(registry.get(PointValueNormalizeMetrics.METRIC_NAME)
            .tag(PointValueNormalizeMetrics.TAG_REASON, "not-utf8").counter().count())
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 单寄存器 string 走单寄存器路径（GOOD + 字符串值）")
    void singleRegisterStringReading() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(stringPoint("holding:0", 1)), readings,
            new SimpleMeterRegistry());

        feed(listener, "holding:0", 0, 0x4142, T0);

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("GOOD");
            assertThat(reading.value()).isEqualTo("AB");
        });
    }

    @Test
    @DisplayName("★ byte_order 对 string 忽略（little 也按 Modbus 本体序高字节在前）")
    void byteOrderIgnoredForString() {
        List<AccessReading> readings = new ArrayList<>();
        AccessPointMappingDto point = stringPoint("holding:0", 2);
        point.setByteOrder("little");
        PointMappingDataListener listener = listener(List.of(point), readings,
            new SimpleMeterRegistry());

        feed(listener, "holding:0", 0, 0x4142, T0);
        feed(listener, "holding:0", 1, 0x4344, T0.plusMillis(1));

        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo("ABCD"));
    }

    @Test
    @DisplayName("🔴 同角色重复到达 ⇒ 整组丢弃 + pair.stale；随后完整一组仍能正常拼出")
    void duplicateSlotDropsGroupAndCountsStale() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(stringPoint("holding:0", 2)), readings,
            registry);

        feed(listener, "holding:0", 0, 0x4142, T0);
        feed(listener, "holding:0", 0, 0x4142, T0.plusMillis(10)); // 同 slot 重复 ⇒ 整组丢弃
        assertThat(readings).as("未齐不得产出读数").isEmpty();
        assertThat(listener.pairStaleCount()).isEqualTo(1);

        feed(listener, "holding:0", 0, 0x4142, T0.plusMillis(20));
        feed(listener, "holding:0", 1, 0x4344, T0.plusMillis(21));
        assertThat(readings).singleElement()
            .satisfies(reading -> assertThat(reading.value()).isEqualTo("ABCD"));
    }

    @Test
    @DisplayName("🔴 组内最早到达超容差 ⇒ 整组丢弃 + pair.stale（不与新值混）")
    void staleGroupDropsAndCounts() {
        List<AccessReading> readings = new ArrayList<>();
        // pollInterval=100ms ⇒ 容差 100ms；第二字晚到 1s 视为陈旧
        PointMappingDataListener listener = new PointMappingDataListener("100", PROTOCOL, 100,
            List.of(stringPoint("holding:0", 2)), readings::add, new SimpleMeterRegistry(),
            BytePayloadDecoderRegistry.empty());

        feed(listener, "holding:0", 0, 0x4142, T0);
        feed(listener, "holding:0", 1, 0x4344, T0.plusSeconds(1));
        assertThat(readings).isEmpty();
        assertThat(listener.pairStaleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 任一寄存器 BAD ⇒ 点位 BAD + 清空暂存（不用陈旧字拼值）")
    void badRegisterEmitsBadAndClears() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = listener(List.of(stringPoint("holding:0", 2)), readings,
            new SimpleMeterRegistry());

        feed(listener, "holding:0", 0, 0x4142, T0);
        listener.onData(PointValue.bad(PointAddress.of("holding:1"), Quality.BAD, "timeout",
            T0.plusMillis(5)));

        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.quality()).isEqualTo("BAD");
            assertThat(reading.value()).isNull();
        });

        // 清空后再来完整一组仍能拼出
        feed(listener, "holding:0", 0, 0x4142, T0.plusMillis(10));
        feed(listener, "holding:0", 1, 0x4344, T0.plusMillis(11));
        assertThat(readings).hasSize(2);
        assertThat(readings.get(1).value()).isEqualTo("ABCD");
    }

    @Test
    @DisplayName("★ 回归：word_count=2 的 int 走泛化后的配对路径，值逐字节不变")
    void intWordCountTwoRegressionThroughGeneralizedPairing() {
        List<AccessReading> readings = new ArrayList<>();
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress("holding:0");
        point.setIdentifier("counter");
        point.setDataType("int");
        point.setPropertyId("pk-counter");
        point.setWordCount(2);
        point.setSigned(Boolean.FALSE);
        point.setRegisterFormat("int");
        PointMappingDataListener listener = listener(List.of(point), readings,
            new SimpleMeterRegistry());

        feed(listener, "holding:0", 0, 0x0001, T0);
        feed(listener, "holding:0", 1, 0x0002, T0.plusMillis(1));

        assertThat(readings).singleElement().satisfies(reading ->
            assertThat(reading.value()).isEqualTo(65538L));
    }

    // ==================== helpers ====================

    private static PointMappingDataListener listener(List<AccessPointMappingDto> points,
                                                     List<AccessReading> readings,
                                                     SimpleMeterRegistry registry) {
        return new PointMappingDataListener("100", PROTOCOL, 5_000, points, readings::add, registry,
            BytePayloadDecoderRegistry.empty());
    }

    private static AccessPointMappingDto stringPoint(String base, int wordCount) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(base);
        point.setIdentifier("serial");
        point.setDataType("string");
        point.setPropertyId("pk-serial");
        point.setWordCount(wordCount);
        point.setSigned(Boolean.FALSE);
        point.setRegisterFormat("string");
        return point;
    }

    private static void feed(PointMappingDataListener listener, String base, int index, int word,
                             Instant ts) {
        listener.onData(PointValue.good(PointAddress.of(ModbusRegisterAddress.plus(base, index)), word,
            ts));
    }

    /** 把文本按 UTF-8 编码后拆成「每寄存器高字节在前」的 16 位字（偶数长度，不额外补 NUL）。 */
    private static int[] words(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int count = (bytes.length + 1) / 2;
        int[] words = new int[count];
        for (int i = 0; i < count; i++) {
            int high = i * 2 < bytes.length ? bytes[i * 2] & 0xFF : 0;
            int low = i * 2 + 1 < bytes.length ? bytes[i * 2 + 1] & 0xFF : 0;
            words[i] = (high << 8) | low;
        }
        return words;
    }
}
