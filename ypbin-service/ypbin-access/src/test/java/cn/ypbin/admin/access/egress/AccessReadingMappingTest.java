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

import cn.ypbin.admin.access.link.PointMappingDataListener;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 点位映射与线性缩放的纯逻辑单测（采集数据的正确性关键面）。
 *
 * <p>这里最容易出错、且出错后**不会报错只会算错**的三件事：① 缩放/偏移该不该应用；② 地址对不上时
 * 是静默丢还是留痕；③ **上报的坐标形态**——必须是属性标识（规范坐标），不能退回属性主键字符串
 * （2026-09-26 坐标统一的落点，见 {@code docs/IOT-ROADMAP.md} 四点十七补充段）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class AccessReadingMappingTest {

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
        PointMappingDataListener listener = new PointMappingDataListener("100", 5_000,
            List.of(point("holding:1", "temperature", new BigDecimal("0.1"), new BigDecimal("0"))),
            readings::add);
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
    }

    @Test
    @DisplayName("★ 孤儿映射（映射行在、物模型属性行已被删除 ⇒ 没有属性标识）：丢弃 + 单独计数，绝不退回主键形态")
    void orphanMappingMustBeDroppedAndCounted() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", 5_000,
            List.of(point("holding:1", null, null, null)), readings::add);

        listener.onData(new PointValue(PointAddress.of("holding:1"), 1, Quality.GOOD, Instant.now(), null));

        assertThat(readings).as("拿不到属性标识就不能发（否则等于把统一掉的形态又写回去）").isEmpty();
        assertThat(listener.orphanCount()).isEqualTo(1);
        assertThat(listener.unmappedCount()).as("孤儿与「地址未映射」是两类原因，不许混计").isZero();
    }

    @Test
    @DisplayName("未映射地址：不进出口且计数递增（不得静默丢弃、也不得误发到别的属性）")
    void unmappedAddressShouldBeCountedNotEmitted() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", 5_000,
            List.of(point("holding:1", "temperature", null, null)), readings::add);

        listener.onData(new PointValue(PointAddress.of("holding:99"), 1, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("质量非 GOOD 也要如实上报（断档判定依赖它，不能在采集侧吞掉）")
    void nonGoodQualityShouldBeReported() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100", 5_000,
            List.of(point("holding:1", "temperature", null, null)), readings::add);

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
        PointMappingDataListener listener = new PointMappingDataListener("100", 5_000,
            List.of(point("", "temperature", null, null), point(null, "humidity", null, null)), readings::add);

        // 框架自身会拒绝空地址（PointAddress 构造期校验），故这里用一个真实但未映射的地址验证：
        // 若上面两条空地址被当成了合法映射，这里就不会走「未映射」分支
        listener.onData(new PointValue(PointAddress.of("holding:2"), 1, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    /**
     * 构造一个点位映射：{@code identifier} 是上报坐标，{@code propertyId} 只作定位（刻意与标识不同，
     * 以便断言「上报用的是标识而不是主键字符串」）。
     *
     * @param address    协议地址
     * @param identifier 属性标识（规范坐标；{@code null} 表示孤儿映射）
     * @param scale      缩放系数
     * @param offset     偏移
     * @return 点位映射
     */
    private static AccessPointMappingDto point(String address, String identifier,
                                               BigDecimal scale, BigDecimal offset) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(address);
        point.setIdentifier(identifier);
        point.setPropertyId(identifier == null ? null : "pk-" + identifier);
        point.setScaleFactor(scale);
        point.setOffsetValue(offset);
        return point;
    }
}
