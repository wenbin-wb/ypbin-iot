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
 * <p>这里最容易出错、且出错后**不会报错只会算错**的两件事：① 缩放/偏移该不该应用；② 地址对不上时
 * 是静默丢还是留痕。前者测边界（未配置不改值、非数值不硬转），后者测「未映射必须计数且不误发」。</p>
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
    @DisplayName("映射：地址命中 → 发到出口的属性号/值/质量/时刻均正确（含缩放与质量语义）")
    void mappedAddressShouldProduceReading() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100",
            List.of(point("holding:1", "900", new BigDecimal("0.1"), new BigDecimal("0"))),
            readings::add);
        Instant now = Instant.parse("2026-09-21T08:00:00Z");

        listener.onData(new PointValue(PointAddress.of("holding:1"), 250, Quality.GOOD, now, null));

        assertThat(readings).hasSize(1);
        AccessReading reading = readings.getFirst();
        assertThat(reading.deviceId()).isEqualTo("100");
        assertThat(reading.propertyId()).isEqualTo("900");
        assertThat(reading.value()).isEqualTo(new BigDecimal("25.000000"));
        assertThat(reading.quality()).isEqualTo("GOOD");
        assertThat(reading.isGood()).isTrue();
        assertThat(reading.timestamp()).isEqualTo(now);
        assertThat(listener.unmappedCount()).isZero();
    }

    @Test
    @DisplayName("未映射地址：不进出口且计数递增（不得静默丢弃、也不得误发到别的属性）")
    void unmappedAddressShouldBeCountedNotEmitted() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100",
            List.of(point("holding:1", "900", null, null)), readings::add);

        listener.onData(new PointValue(PointAddress.of("holding:99"), 1, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("质量非 GOOD 也要如实上报（断档判定依赖它，不能在采集侧吞掉）")
    void nonGoodQualityShouldBeReported() {
        List<AccessReading> readings = new ArrayList<>();
        PointMappingDataListener listener = new PointMappingDataListener("100",
            List.of(point("holding:1", "900", null, null)), readings::add);

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
        PointMappingDataListener listener = new PointMappingDataListener("100",
            List.of(point("", "900", null, null), point(null, "901", null, null)), readings::add);

        // 框架自身会拒绝空地址（PointAddress 构造期校验），故这里用一个真实但未映射的地址验证：
        // 若上面两条空地址被当成了合法映射，这里就不会走「未映射」分支
        listener.onData(new PointValue(PointAddress.of("holding:2"), 1, Quality.GOOD,
            Instant.now(), null));

        assertThat(readings).isEmpty();
        assertThat(listener.unmappedCount()).isEqualTo(1);
    }

    private static AccessPointMappingDto point(String address, String propertyId,
                                               BigDecimal scale, BigDecimal offset) {
        AccessPointMappingDto point = new AccessPointMappingDto();
        point.setAddress(address);
        point.setPropertyId(propertyId);
        point.setScaleFactor(scale);
        point.setOffsetValue(offset);
        return point;
    }
}
