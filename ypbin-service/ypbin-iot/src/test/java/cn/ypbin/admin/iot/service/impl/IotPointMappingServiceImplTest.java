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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
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
import org.mockito.ArgumentCaptor;
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
    private final TenantLedgerService ledgerService = mock(TenantLedgerService.class);
    private final IotPointMappingServiceImpl service = new IotPointMappingServiceImpl(
        deviceMapper, propertyMapper, productMapper, serviceMapper, ledgerService);

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

    @Test
    @DisplayName("★ 点位映射增删改都必须推进台账配置版本号（否则接入侧永远不知道点位变了 ⇒ G7 静默零更新）")
    void pointMappingMutationsMustBumpConfigEpoch() {
        stubPublishedProduct();
        when(propertyMapper.selectById(PROPERTY_ID)).thenReturn(rwProperty("RW"));
        IotPointMapping existing = new IotPointMapping();
        existing.setId(800L);
        existing.setDeviceId(DEVICE_ID);
        when(mappingMapper.selectById(800L)).thenReturn(existing);

        service.create(request(DEVICE_ID, PROPERTY_ID, "RW"));
        service.update(800L, request(DEVICE_ID, PROPERTY_ID, "RW"));
        service.remove(DEVICE_ID, 800L);

        verify(ledgerService, times(3)).bumpConfigEpochOfCurrentTenant();
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
    @Test
    @DisplayName("★ 寄存器解码：word_count=2 + 显式 holding:0 被接受，且实体搬运 wordCount/signed/byteOrder")
    void modbusWordCountTwoExplicitFormAccepted() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("holding:0");
        req.setAddressType("holding");
        req.setWordCount(2);
        req.setSigned(Boolean.FALSE);
        req.setByteOrder("big");

        service.create(req);

        ArgumentCaptor<IotPointMapping> captor = ArgumentCaptor.forClass(IotPointMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getWordCount()).isEqualTo(2);
        assertThat(captor.getValue().getSigned()).isFalse();
        assertThat(captor.getValue().getByteOrder()).isEqualTo("big");
    }

    @Test
    @DisplayName("★ 寄存器解码：word_count=2 拒绝传统 5 位编号（报错说清怎么改）")
    void modbusWordCountTwoTraditionalAddressRejected() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setWordCount(2);
        req.setRawAddress("40001");

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("传统 5 位编号不支持多寄存器");
        verify(mappingMapper, never()).insert(any(IotPointMapping.class));
    }

    @Test
    @DisplayName("★ 寄存器解码：word_count=2 的 raw_address 寄存器区必须与 address_type 一致")
    void modbusWordCountTwoAddressTypeMismatchRejected() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setWordCount(2);
        req.setRawAddress("holding:0");
        req.setAddressType("input");

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须与 address_type 一致");
    }

    @Test
    @DisplayName("★ 寄存器解码：位类型（coil）不支持 word_count/signed")
    void modbusWordCountOnCoilRejected() {
        stubProduct("modbus", "bool");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setAddressType("coil");
        req.setRawAddress("coil:0");
        req.setWordCount(2);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("位类型");
    }

    @Test
    @DisplayName("★ 寄存器解码：非 Modbus 点位使用 word_count/signed ⇒ 拒绝（新字段严格）")
    void wordCountOnNonModbusRejected() {
        stubProduct("tcp", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setWordCount(2);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅 Modbus 寄存器点位支持");
    }

    @Test
    @DisplayName("★ 寄存器解码：signed=true 要求 data_type ∈ {int,long}")
    void signedOnNonIntegerDataTypeRejected() {
        stubProduct("modbus", "string");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setSigned(Boolean.TRUE);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅支持 int/long");
    }

    @Test
    @DisplayName("★ 向后兼容：word_count=1（默认）+ 传统 5 位编号照常可用")
    void wordCountOneTraditionalAddressAccepted() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("40001");
        req.setWordCount(1);
        req.setSigned(Boolean.FALSE);

        service.create(req);

        verify(mappingMapper).insert(any(IotPointMapping.class));
    }

    @Test
    @DisplayName("★ 旧字段宽容：非 Modbus 点位上的 byteOrder 被忽略（不拒绝）")
    void byteOrderOnNonModbusIgnored() {
        stubProduct("tcp", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setByteOrder("big");

        service.create(req);

        verify(mappingMapper).insert(any(IotPointMapping.class));
    }

    @Test
    @DisplayName("★ word_count 非法值（3）⇒ 拒绝（防御服务层直调）")
    void invalidWordCountRejected() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setWordCount(3);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能是 1 或 2");
    }

    @Test
    @DisplayName("实体→响应：wordCount/signed 必须搬运（前端表单契约）")
    void toRespShouldMapWordCountAndSigned() {
        IotPointMapping entity = new IotPointMapping();
        entity.setWordCount(2);
        entity.setSigned(Boolean.TRUE);

        IotPointMappingResp resp = service.toResp(entity);

        assertThat(resp.getWordCount()).isEqualTo(2);
        assertThat(resp.getSigned()).isTrue();
    }

    /** 桩：设备协议 + 属性 dataType + 已发布产品（寄存器解码校验用）。 */
    private void stubProduct(String protocol, String dataType) {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        device.setProtocol(protocol);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        IotProduct product = new IotProduct();
        product.setId(PRODUCT_ID);
        product.setModelStatus("published");
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
        IotService service = new IotService();
        service.setId(SERVICE_ID);
        service.setProductId(PRODUCT_ID);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(service);
        IotProperty property = new IotProperty();
        property.setId(PROPERTY_ID);
        property.setServiceId(SERVICE_ID);
        property.setAccessMode("RW");
        property.setDataType(dataType);
        when(propertyMapper.selectById(PROPERTY_ID)).thenReturn(property);
    }
}
