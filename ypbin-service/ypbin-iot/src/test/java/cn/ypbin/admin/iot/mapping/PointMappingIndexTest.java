/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备 → 点位坐标集合索引（P0-6c 的成员校验数据源）：批量、判空短路、两种坐标形态并存。
 *
 * <p>本类证明的是「取映射」这一半（SQL 形态与集合内容）；「用集合做判定」那一半在
 * {@code AvailabilityServiceImplTest}（单测）与 {@code IngestPointMappingIT}（真库）里。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
class PointMappingIndexTest {

    private static final long DEVICE_A = 9300001L;

    private static final long DEVICE_B = 9300002L;

    private static final long PROP_TEMPERATURE = 9130001L;

    private static final long PROP_HUMIDITY = 9130002L;

    private final IotPointMappingMapper pointMappingMapper = mock(IotPointMappingMapper.class);

    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);

    private final PointMappingIndex index = new PointMappingIndex(pointMappingMapper, propertyMapper);

    /**
     * 初始化 MyBatis-Plus 实体元信息。
     *
     * <p>为什么需要：{@code LambdaQueryWrapper} 靠方法引用解析列名，列名来自实体 TableInfo；纯单测不起 Spring、
     * 不扫 Mapper，没有这一步会抛 {@code can not find lambda cache for this entity}（与
     * {@code AvailabilityServiceImplTest} 同款处理）。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotPointMapping.class);
        TableInfoHelper.initTableInfo(assistant, IotProperty.class);
    }

    private static IotPointMapping mapping(Long deviceId, Long propertyId) {
        IotPointMapping row = new IotPointMapping();
        row.setDeviceId(deviceId);
        row.setPropertyId(propertyId);
        return row;
    }

    private static IotProperty property(Long id, String identifier) {
        IotProperty row = new IotProperty();
        row.setId(id);
        row.setIdentifier(identifier);
        return row;
    }

    @Test
    @DisplayName("★ 空设备集合 ⇒ 两张表都不查（先判空短路，不做 IN ()）")
    void emptyDeviceIdsMustNotTouchDatabase() {
        assertThat(index.loadByDeviceIds(Set.of())).isEmpty();

        verify(pointMappingMapper, never()).selectList(any());
        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("★ 批量：两张表各一次查询，与设备数/点位数无关；集合同时含主键形态与属性标识形态")
    void mustLoadInTwoRoundedTripsAndExposeBothCoordinateForms() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE),
            mapping(DEVICE_A, PROP_HUMIDITY),
            mapping(DEVICE_B, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "temperature"),
            property(PROP_HUMIDITY, "humidity")));

        Map<Long, Set<String>> mapped = index.loadByDeviceIds(Set.of(DEVICE_A, DEVICE_B));

        assertThat(mapped).containsOnlyKeys(DEVICE_A, DEVICE_B);
        assertThat(mapped.get(DEVICE_A)).as("access 上报的是属性主键字符串；MQTT/前端用的是属性标识")
            .containsExactlyInAnyOrder(String.valueOf(PROP_TEMPERATURE), "temperature",
                String.valueOf(PROP_HUMIDITY), "humidity");
        assertThat(mapped.get(DEVICE_B)).containsExactlyInAnyOrder(String.valueOf(PROP_TEMPERATURE), "temperature");
        verify(pointMappingMapper, times(1)).selectList(any());
        verify(propertyMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("★ 映射为空 ⇒ 直接返回空 Map，**不再查属性表**（且不抛）")
    void emptyMappingsMustShortCircuitBeforePropertyQuery() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of());

        assertThat(index.loadByDeviceIds(Set.of(DEVICE_A))).isEmpty();

        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("★ 属性行缺失/标识为空 ⇒ 该主键只剩主键字符串形态（不抛、不丢点位）")
    void missingOrBlankIdentifierMustKeepPrimaryKeyForm() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, null), property(PROP_HUMIDITY, "  ")));

        Map<Long, Set<String>> mapped = index.loadByDeviceIds(Set.of(DEVICE_A));

        assertThat(mapped.get(DEVICE_A)).containsExactlyInAnyOrder(
            String.valueOf(PROP_TEMPERATURE), String.valueOf(PROP_HUMIDITY));
    }

    @Test
    @DisplayName("★ 脏映射行（设备或属性为空）被跳过而不是让整批失败")
    void dirtyMappingsMustBeSkipped() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(null, PROP_TEMPERATURE), mapping(DEVICE_A, null), mapping(DEVICE_A, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(property(PROP_TEMPERATURE, "temperature")));

        Map<Long, Set<String>> mapped = index.loadByDeviceIds(Set.of(DEVICE_A));

        assertThat(mapped).containsOnlyKeys(DEVICE_A);
        assertThat(mapped.get(DEVICE_A)).containsExactlyInAnyOrder(String.valueOf(PROP_TEMPERATURE),
            "temperature");
    }

    @Test
    @DisplayName("★ 没有映射的设备**不出现**在结果里（调用方据此把它的读数全判为未映射）")
    void deviceWithoutMappingMustBeAbsentFromResult() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(mapping(DEVICE_A, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(property(PROP_TEMPERATURE, "temperature")));

        Map<Long, Set<String>> mapped = index.loadByDeviceIds(Set.of(DEVICE_A, DEVICE_B));

        assertThat(mapped).containsOnlyKeys(DEVICE_A);
        assertThat(mapped.getOrDefault(DEVICE_B, Set.of())).as("未映射设备 → 空集合 → 全部丢弃").isEmpty();
    }
}
