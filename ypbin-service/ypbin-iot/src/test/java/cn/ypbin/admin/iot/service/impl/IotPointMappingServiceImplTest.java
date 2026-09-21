/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.IotPointMappingReq;
import cn.ypbin.admin.iot.model.resp.IotPointMappingResp;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 点位映射服务的纯逻辑单测（§3.9）。
 *
 * <p>§3.9 是 MUST 约束（映射必须引用<b>已发布版本</b>的属性），此前零覆盖。这里钉死四条：
 * ① 属性必须经「服务的产品」可达（不得引用其它产品的属性）；
 * ② 设备所绑产品必须已发布物模型；③ rw 与 access_mode 联动；
 * ④ 映射归属由路径设备决定，禁止借请求体把映射迁到别的设备（否则可绕过 ② ）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotPointMappingServiceImplTest {

    private static final Long DEVICE_ID = 1L;
    private static final Long PRODUCT_ID = 10L;
    private static final Long SERVICE_ID = 100L;
    private static final Long PROPERTY_ID = 1000L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);
    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final IotServiceMapper serviceMapper = mock(IotServiceMapper.class);
    private final IotPointMappingMapper mappingMapper = mock(IotPointMappingMapper.class);
    private final IotPointMappingServiceImpl service = new IotPointMappingServiceImpl(
        deviceMapper, propertyMapper, productMapper, serviceMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotPointMapping.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", mappingMapper);
    }

    @Test
    @DisplayName("属性不属于设备所绑定产品（其它产品/其它租户属性）→ 拒绝")
    void propertyFromAnotherProductShouldBeRejected() {
        stubPublishedProduct();
        IotService foreignService = new IotService();
        foreignService.setId(SERVICE_ID);
        foreignService.setProductId(999L);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(foreignService);
        when(propertyMapper.selectById(PROPERTY_ID)).thenReturn(rwProperty("RW"));

        assertThatThrownBy(() -> service.create(request(DEVICE_ID, PROPERTY_ID, "RW")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不属于设备所绑定产品的物模型");
        verify(mappingMapper, never()).insert(any(IotPointMapping.class));
    }

    @Test
    @DisplayName("设备所绑产品未发布物模型 → 拒绝（§3.9 必须引用已发布版本）")
    void unpublishedProductShouldBeRejected() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        IotProduct product = new IotProduct();
        product.setId(PRODUCT_ID);
        product.setModelStatus("draft");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        when(propertyMapper.selectById(PROPERTY_ID)).thenReturn(rwProperty("RW"));

        assertThatThrownBy(() -> service.create(request(DEVICE_ID, PROPERTY_ID, "RW")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须引用已发布版本的属性");
    }

    @Test
    @DisplayName("rw 联动：属性 RW 时允许 R/W/RW；属性 R 时映射必须同为 R")
    void rwShouldFollowAccessMode() {
        stubPublishedProduct();
        when(propertyMapper.selectById(PROPERTY_ID)).thenReturn(rwProperty("RW"));
        service.create(request(DEVICE_ID, PROPERTY_ID, "W"));
        verify(mappingMapper).insert(any(IotPointMapping.class));

        when(propertyMapper.selectById(PROPERTY_ID)).thenReturn(rwProperty("R"));
        assertThatThrownBy(() -> service.create(request(DEVICE_ID, PROPERTY_ID, "W")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("读写权限与属性 access_mode 不一致");
    }

    @Test
    @DisplayName("update：请求体 deviceId 与映射归属不一致 → 拒绝（禁止跨设备迁移绕过校验）")
    void updateMustNotMigrateMappingToAnotherDevice() {
        IotPointMapping existing = new IotPointMapping();
        existing.setId(500L);
        existing.setDeviceId(DEVICE_ID);
        when(mappingMapper.selectById(500L)).thenReturn(existing);

        // 请求体声称属于设备 2，而映射实际属于设备 1
        assertThatThrownBy(() -> service.update(500L, request(2L, PROPERTY_ID, "RW")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("禁止跨设备迁移");
        verify(mappingMapper, never()).updateById(any(IotPointMapping.class));
    }

    @Test
    @DisplayName("remove：映射不属于路径设备 → 拒绝（否则可借他人设备路径删映射）")
    void removeMustCheckDeviceOwnership() {
        IotPointMapping existing = new IotPointMapping();
        existing.setId(600L);
        existing.setDeviceId(DEVICE_ID);
        when(mappingMapper.selectById(600L)).thenReturn(existing);

        assertThatThrownBy(() -> service.remove(2L, 600L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不属于该设备");
        verify(mappingMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("实体→响应：字段逐个搬运，不做改名")
    void toRespShouldMapEveryField() {
        IotPointMapping entity = new IotPointMapping();
        entity.setId(700L);
        entity.setDeviceId(DEVICE_ID);
        entity.setPropertyId(PROPERTY_ID);
        entity.setRefType("property");
        entity.setRawAddress("40001");
        entity.setAddressType("holding");
        entity.setPollIntervalMs(5000);
        entity.setScaleFactor(new BigDecimal("0.1"));
        entity.setOffsetValue(new BigDecimal("1.5"));
        entity.setByteOrder("big");
        entity.setRw("RW");
        entity.setEnabled(Boolean.TRUE);

        IotPointMappingResp resp = service.toResp(entity);

        assertThat(resp.getId()).isEqualTo(700L);
        assertThat(resp.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(resp.getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(resp.getRefType()).isEqualTo("property");
        assertThat(resp.getRawAddress()).isEqualTo("40001");
        assertThat(resp.getAddressType()).isEqualTo("holding");
        assertThat(resp.getPollIntervalMs()).isEqualTo(5000);
        assertThat(resp.getRw()).isEqualTo("RW");
        assertThat(resp.getEnabled()).isTrue();
    }

    private void stubPublishedProduct() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        IotProduct product = new IotProduct();
        product.setId(PRODUCT_ID);
        product.setModelStatus("published");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        IotService service = new IotService();
        service.setId(SERVICE_ID);
        service.setProductId(PRODUCT_ID);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(service);
    }

    private static IotProperty rwProperty(String accessMode) {
        IotProperty property = new IotProperty();
        property.setId(PROPERTY_ID);
        property.setServiceId(SERVICE_ID);
        property.setAccessMode(accessMode);
        return property;
    }

    private static IotPointMappingReq request(Long deviceId, Long propertyId, String rw) {
        IotPointMappingReq req = new IotPointMappingReq();
        req.setDeviceId(deviceId);
        req.setPropertyId(propertyId);
        req.setRefType("property");
        req.setRawAddress("40001");
        req.setAddressType("holding");
        req.setRw(rw);
        return req;
    }
}
