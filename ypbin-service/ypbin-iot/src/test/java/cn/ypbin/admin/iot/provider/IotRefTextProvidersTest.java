/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceGroup;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IoT 引用翻译数据源测试（纯逻辑：mock 三个 Mapper，不起 Spring、不连库）。
 *
 * <p>覆盖最易出错的四点：设备/产品名称缺失时回退编码、分组无名称时跳过、
 * 入参 ID 可能是字符串（框架传入的是字段原值，JSON 里才是字符串）、
 * 以及脏主键（非数字/空/ null）跳过与空集合短路——不允许空集合进 SQL、不允许循环内查库。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
class IotRefTextProvidersTest {

    @Test
    @DisplayName("设备：一次批量查询，名称缺失/空白回退设备编码，无名无编码的行跳过")
    void deviceShouldTranslateBatchWithCodeFallback() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        when(mapper.selectByIds(any(Collection.class))).thenReturn(List.of(
            device(1L, "总表", "DEV-1"),
            device(2L, "  ", "DEV-2"),
            device(3L, null, "DEV-3"),
            device(4L, null, null)));
        IotRefTextProviders.DeviceName provider = new IotRefTextProviders.DeviceName(mapper);

        assertThat(provider.type()).isEqualTo("device");
        Map<Object, String> names = provider.getNames(List.of(1L, 2L, 3L, 4L));

        assertThat(names).containsEntry(1L, "总表").containsEntry(2L, "DEV-2").containsEntry(3L, "DEV-3");
        assertThat(names).doesNotContainKey(4L);
        // 一次批量查询，不在循环里查库
        verify(mapper).selectByIds(List.of(1L, 2L, 3L, 4L));
    }

    @Test
    @DisplayName("产品：一次批量查询，名称缺失/空白回退产品编码，无名无编码的行跳过")
    void productShouldTranslateBatchWithCodeFallback() {
        IotProductMapper mapper = mock(IotProductMapper.class);
        when(mapper.selectByIds(any(Collection.class))).thenReturn(List.of(
            product(1L, "智能水表", "P-1"),
            product(2L, " ", "P-2"),
            product(3L, null, "P-3"),
            product(4L, null, null)));
        IotRefTextProviders.ProductName provider = new IotRefTextProviders.ProductName(mapper);

        assertThat(provider.type()).isEqualTo("product");
        Map<Object, String> names = provider.getNames(List.of(1L, 2L, 3L, 4L));

        assertThat(names).containsEntry(1L, "智能水表").containsEntry(2L, "P-2").containsEntry(3L, "P-3");
        assertThat(names).doesNotContainKey(4L);
        verify(mapper).selectByIds(List.of(1L, 2L, 3L, 4L));
    }

    @Test
    @DisplayName("分组：一次批量查询，ID → 分组名称，无名称的分组跳过")
    void groupShouldTranslateBatchAndSkipBlankName() {
        IotDeviceGroupMapper mapper = mock(IotDeviceGroupMapper.class);
        when(mapper.selectByIds(any(Collection.class))).thenReturn(List.of(
            group(9L, "根分组"),
            group(10L, "  "),
            group(11L, null)));
        IotRefTextProviders.GroupName provider = new IotRefTextProviders.GroupName(mapper);

        assertThat(provider.type()).isEqualTo("group");
        Map<Object, String> names = provider.getNames(List.of(9L, 10L, 11L));

        assertThat(names).containsOnlyKeys(9L).containsEntry(9L, "根分组");
        verify(mapper).selectByIds(List.of(9L, 10L, 11L));
    }

    @Test
    @DisplayName("parseIds：合法主键（含字符串/空白）保留，空与非数字跳过")
    void parseIdsShouldKeepParsableAndSkipOthers() {
        assertThat(IotRefTextProviders.parseIds(Arrays.asList(1L, "2", " 3 ", "abc", "", null, 4)))
            .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("合法与非法混在一起时：只查合法主键，名称按可解析的 key 返回")
    void mixedIdsShouldQueryOnlyParsableOnes() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        when(mapper.selectByIds(any(Collection.class)))
            .thenReturn(List.of(device(7L, null, "DEV-7"), device(8L, "八号", "DEV-8")));

        Map<Object, String> names =
            new IotRefTextProviders.DeviceName(mapper).getNames(Arrays.asList("7", "bad", " 8 ", null));

        assertThat(names).containsEntry(7L, "DEV-7").containsEntry(8L, "八号");
        verify(mapper).selectByIds(List.of(7L, 8L));
    }

    @Test
    @DisplayName("空入参与全非法入参：返回空 Map，且不查库（空集合不得进 SQL）")
    void emptyAndAllInvalidIdsMustShortCircuitWithoutQuery() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        IotProductMapper productMapper = mock(IotProductMapper.class);
        IotDeviceGroupMapper groupMapper = mock(IotDeviceGroupMapper.class);

        assertThat(new IotRefTextProviders.DeviceName(deviceMapper).getNames(List.of())).isEmpty();
        assertThat(new IotRefTextProviders.DeviceName(deviceMapper).getNames(Arrays.asList(null, "abc", "")))
            .isEmpty();
        assertThat(new IotRefTextProviders.ProductName(productMapper).getNames(List.of())).isEmpty();
        assertThat(new IotRefTextProviders.ProductName(productMapper).getNames(Arrays.asList(null, "abc")))
            .isEmpty();
        assertThat(new IotRefTextProviders.GroupName(groupMapper).getNames(List.of())).isEmpty();
        assertThat(new IotRefTextProviders.GroupName(groupMapper).getNames(Arrays.asList(null, "abc")))
            .isEmpty();

        verify(deviceMapper, never()).selectByIds(any(Collection.class));
        verify(productMapper, never()).selectByIds(any(Collection.class));
        verify(groupMapper, never()).selectByIds(any(Collection.class));
    }

    private static IotDevice device(Long id, String deviceName, String deviceCode) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setDeviceName(deviceName);
        device.setDeviceCode(deviceCode);
        return device;
    }

    private static IotProduct product(Long id, String productName, String productCode) {
        IotProduct product = new IotProduct();
        product.setId(id);
        product.setProductName(productName);
        product.setProductCode(productCode);
        return product;
    }

    private static IotDeviceGroup group(Long id, String groupName) {
        IotDeviceGroup group = new IotDeviceGroup();
        group.setId(id);
        group.setGroupName(groupName);
        return group;
    }
}
