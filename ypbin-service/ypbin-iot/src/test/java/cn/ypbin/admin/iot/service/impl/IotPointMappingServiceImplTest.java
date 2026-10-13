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
    @DisplayName("★ byteOrder 统一严格：非 Modbus / int+word_count=1 / bits / string 上非空即拒绝")
    void byteOrderInInapplicableScenesRejected() {
        // 非 Modbus（tcp int，word_count=1 默认）
        stubProduct("tcp", "int");
        IotPointMappingReq nonModbus = request(DEVICE_ID, PROPERTY_ID, "RW");
        nonModbus.setByteOrder("big");
        assertThatThrownBy(() -> service.create(nonModbus))
            .isInstanceOf(BusinessException.class).hasMessageContaining("byte_order");

        // Modbus int + word_count=1
        stubProduct("modbus", "int");
        IotPointMappingReq wordCountOne = request(DEVICE_ID, PROPERTY_ID, "RW");
        wordCountOne.setWordCount(1);
        wordCountOne.setByteOrder("big");
        assertThatThrownBy(() -> service.create(wordCountOne))
            .isInstanceOf(BusinessException.class).hasMessageContaining("byte_order");

        // bits
        stubProduct("modbus", "bool");
        IotPointMappingReq bits = request(DEVICE_ID, PROPERTY_ID, "RW");
        bits.setRegisterFormat("bits");
        bits.setWordCount(1);
        bits.setBitOffset(0);
        bits.setBitLength(1);
        bits.setByteOrder("big");
        assertThatThrownBy(() -> service.create(bits))
            .isInstanceOf(BusinessException.class).hasMessageContaining("byte_order");

        // string
        stubProduct("modbus", "string");
        IotPointMappingReq string = request(DEVICE_ID, PROPERTY_ID, "RW");
        string.setRegisterFormat("string");
        string.setWordCount(2);
        string.setRawAddress("holding:0");
        string.setByteOrder("big");
        assertThatThrownBy(() -> service.create(string))
            .isInstanceOf(BusinessException.class).hasMessageContaining("byte_order");
    }

    @Test
    @DisplayName("★ byteOrder 在生效场景仍允许：Modbus int+word_count=2 非空、float 为空均可")
    void byteOrderInApplicableScenesAccepted() {
        stubProduct("modbus", "int");
        IotPointMappingReq intReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        intReq.setRawAddress("holding:0");
        intReq.setWordCount(2);
        intReq.setSigned(Boolean.FALSE);
        intReq.setByteOrder("little");
        service.create(intReq);

        stubProduct("modbus", "decimal");
        IotPointMappingReq floatReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        floatReq.setRawAddress("holding:0");
        floatReq.setRegisterFormat("float");
        floatReq.setWordCount(2);
        floatReq.setSigned(Boolean.FALSE);
        floatReq.setByteOrder(null);
        service.create(floatReq);

        verify(mappingMapper, times(2)).insert(any(IotPointMapping.class));
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
    @Test
    @DisplayName("★ v2 float：word_count=2 + signed=false + bit_* 空 + decimal + 显式地址 ⇒ 接受")
    void floatFormatAccepted() {
        stubProduct("modbus", "decimal");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("holding:0");
        req.setAddressType("holding");
        req.setRegisterFormat("float");
        req.setWordCount(2);
        req.setSigned(Boolean.FALSE);
        req.setByteOrder("big");

        service.create(req);

        ArgumentCaptor<IotPointMapping> captor = ArgumentCaptor.forClass(IotPointMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getRegisterFormat()).isEqualTo("float");
        assertThat(captor.getValue().getWordCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ v2 float：word_count≠2 ⇒ 拒绝（不静默改成 2）")
    void floatWordCountMustBeTwo() {
        stubProduct("modbus", "decimal");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRegisterFormat("float");
        req.setWordCount(1);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("要求 word_count=2");
    }

    @Test
    @DisplayName("★ v2 float：signed=true ⇒ 拒绝；bit_* 非空 ⇒ 拒绝；data_type≠decimal ⇒ 拒绝")
    void floatIncompatibleFieldsRejected() {
        stubProduct("modbus", "decimal");
        IotPointMappingReq signedReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        signedReq.setRawAddress("holding:0");
        signedReq.setRegisterFormat("float");
        signedReq.setWordCount(2);
        signedReq.setSigned(Boolean.TRUE);
        assertThatThrownBy(() -> service.create(signedReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("signed 必须为 false");

        IotPointMappingReq bitsReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        bitsReq.setRawAddress("holding:0");
        bitsReq.setRegisterFormat("float");
        bitsReq.setWordCount(2);
        bitsReq.setBitOffset(0);
        bitsReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(bitsReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 bit_offset/bit_length");

        stubProduct("modbus", "int");
        IotPointMappingReq typeReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        typeReq.setRawAddress("holding:0");
        typeReq.setRegisterFormat("float");
        typeReq.setWordCount(2);
        assertThatThrownBy(() -> service.create(typeReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("要求属性类型是 decimal");
    }

    @Test
    @DisplayName("★ v2 float：非 Modbus ⇒ 拒绝")
    void floatNonModbusRejected() {
        stubProduct("tcp", "decimal");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRegisterFormat("float");
        req.setWordCount(2);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅 Modbus 寄存器点位支持 register_format=float");
    }

    @Test
    @DisplayName("★ v4 double：word_count=4 + signed=false + bit_* 空 + decimal + 显式地址 + 字序 ⇒ 接受并搬运")
    void doubleFormatAccepted() {
        stubProduct("modbus", "decimal");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("holding:0");
        req.setRegisterFormat("double");
        req.setWordCount(4);
        req.setSigned(Boolean.FALSE);
        req.setByteOrder("little");

        service.create(req);

        ArgumentCaptor<IotPointMapping> captor = ArgumentCaptor.forClass(IotPointMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getRegisterFormat()).isEqualTo("double");
        assertThat(captor.getValue().getWordCount()).isEqualTo(4);
        assertThat(captor.getValue().getSigned()).isFalse();
        assertThat(captor.getValue().getByteOrder()).isEqualTo("little");
    }

    @Test
    @DisplayName("★ v4 double：word_count≠4 ⇒ 拒绝（不静默改成 4）")
    void doubleWordCountMustBeFour() {
        stubProduct("modbus", "decimal");
        for (int wrong : new int[] {1, 2, 3, 5, 32}) {
            IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
            req.setRawAddress("holding:0");
            req.setRegisterFormat("double");
            req.setWordCount(wrong);
            assertThatThrownBy(() -> service.create(req))
                .as("word_count=%s 必须拒绝", wrong)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("register_format=double 要求 word_count=4");
        }
    }

    @Test
    @DisplayName("★ v4 double：signed=true / bit_* 非空 / data_type≠decimal / 非 Modbus / 位类型 ⇒ 拒绝")
    void doubleIncompatibleFieldsRejected() {
        stubProduct("modbus", "decimal");
        IotPointMappingReq signedReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        signedReq.setRawAddress("holding:0");
        signedReq.setRegisterFormat("double");
        signedReq.setWordCount(4);
        signedReq.setSigned(Boolean.TRUE);
        assertThatThrownBy(() -> service.create(signedReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("signed 必须为 false");

        IotPointMappingReq bitReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        bitReq.setRawAddress("holding:0");
        bitReq.setRegisterFormat("double");
        bitReq.setWordCount(4);
        bitReq.setBitOffset(0);
        bitReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(bitReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 bit_offset/bit_length");

        stubProduct("modbus", "int");
        IotPointMappingReq typeReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        typeReq.setRawAddress("holding:0");
        typeReq.setRegisterFormat("double");
        typeReq.setWordCount(4);
        assertThatThrownBy(() -> service.create(typeReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("要求属性类型是 decimal");

        stubProduct("tcp", "decimal");
        IotPointMappingReq nonModbusReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        nonModbusReq.setRegisterFormat("double");
        nonModbusReq.setWordCount(4);
        assertThatThrownBy(() -> service.create(nonModbusReq))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅 Modbus 寄存器点位支持 register_format=double");

        stubProduct("modbus", "decimal");
        IotPointMappingReq bitTypeReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        bitTypeReq.setRawAddress("coil:0");
        bitTypeReq.setAddressType("coil");
        bitTypeReq.setRegisterFormat("double");
        bitTypeReq.setWordCount(4);
        assertThatThrownBy(() -> service.create(bitTypeReq))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("位类型（coil）不支持 register_format=double");
    }

    @Test
    @DisplayName("★ v4 double：非显式多寄存器地址（传统 5 位编号）⇒ 拒绝")
    void doubleTraditionalAddressRejected() {
        stubProduct("modbus", "decimal");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRegisterFormat("double");
        req.setWordCount(4);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("word_count>1 需要显式 type:offset 形式");
    }

    @Test
    @DisplayName("★ v2 bits：word_count=1 + 合法 bit 区间 + data_type 匹配 ⇒ 接受（传统 5 位编号也可）")
    void bitsFormatAccepted() {
        stubProduct("modbus", "bool");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("40001");
        req.setAddressType("holding");
        req.setRegisterFormat("bits");
        req.setWordCount(1);
        req.setSigned(Boolean.FALSE);
        req.setBitOffset(0);
        req.setBitLength(1);

        service.create(req);

        ArgumentCaptor<IotPointMapping> captor = ArgumentCaptor.forClass(IotPointMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getRegisterFormat()).isEqualTo("bits");
        assertThat(captor.getValue().getBitOffset()).isZero();
        assertThat(captor.getValue().getBitLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ v2 bits：word_count≠1 / signed=true / 缺 bit_* ⇒ 拒绝")
    void bitsIncompatibleFieldsRejected() {
        stubProduct("modbus", "bool");
        IotPointMappingReq wcReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        wcReq.setRegisterFormat("bits");
        wcReq.setWordCount(2);
        wcReq.setBitOffset(0);
        wcReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(wcReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("要求 word_count=1");

        IotPointMappingReq signedReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        signedReq.setRegisterFormat("bits");
        signedReq.setSigned(Boolean.TRUE);
        signedReq.setBitOffset(0);
        signedReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(signedReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("无符号位域");

        IotPointMappingReq missingReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        missingReq.setRegisterFormat("bits");
        assertThatThrownBy(() -> service.create(missingReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("必须同时给出 bit_offset 与 bit_length");
    }

    @Test
    @DisplayName("★ v2 bits：bit_offset/bit_length 越界 ⇒ 拒绝")
    void bitsRangeRejected() {
        stubProduct("modbus", "bool");
        IotPointMappingReq offsetReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        offsetReq.setRegisterFormat("bits");
        offsetReq.setBitOffset(16);
        offsetReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(offsetReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("bit_offset 必须在 0..15");

        IotPointMappingReq lengthReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        lengthReq.setRegisterFormat("bits");
        lengthReq.setBitOffset(15);
        lengthReq.setBitLength(2);
        assertThatThrownBy(() -> service.create(lengthReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("bit_length 必须在 1..1");
    }

    @Test
    @DisplayName("★ v2 bits：data_type 与 bit_length 不匹配 ⇒ 拒绝")
    void bitsDataTypeMismatchRejected() {
        stubProduct("modbus", "int");
        IotPointMappingReq boolReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        boolReq.setRegisterFormat("bits");
        boolReq.setBitOffset(0);
        boolReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(boolReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("要求属性类型是 bool");

        stubProduct("modbus", "bool");
        IotPointMappingReq intReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        intReq.setRegisterFormat("bits");
        intReq.setBitOffset(0);
        intReq.setBitLength(4);
        assertThatThrownBy(() -> service.create(intReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("要求属性类型是 int/long");
    }

    @Test
    @DisplayName("★ v2：register_format=int 携带 bit_* ⇒ 拒绝（新字段严格）")
    void intFormatWithBitFieldsRejected() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRegisterFormat("int");
        req.setBitOffset(0);
        req.setBitLength(1);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("register_format=int 不支持 bit_offset/bit_length");
    }

    @Test
    @DisplayName("★ v3 string：word_count=3 + 显式 holding:0 + data_type=string ⇒ 接受并搬运")
    void stringFormatAccepted() {
        stubProduct("modbus", "string");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("holding:0");
        req.setRegisterFormat("string");
        req.setWordCount(3);
        req.setSigned(Boolean.FALSE);

        service.create(req);

        ArgumentCaptor<IotPointMapping> captor = ArgumentCaptor.forClass(IotPointMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getRegisterFormat()).isEqualTo("string");
        assertThat(captor.getValue().getWordCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ v3 string：word_count=1 + 传统 5 位编号照常（向后兼容，无需扩地址）")
    void stringWordCountOneTraditionalAccepted() {
        stubProduct("modbus", "string");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRegisterFormat("string");
        req.setWordCount(1);
        req.setSigned(Boolean.FALSE);

        service.create(req);

        verify(mappingMapper).insert(any(IotPointMapping.class));
    }

    @Test
    @DisplayName("★ v3 string：word_count=32 接受、33 拒绝（边界说清）")
    void stringWordCountRangeEnforced() {
        stubProduct("modbus", "string");
        IotPointMappingReq max = request(DEVICE_ID, PROPERTY_ID, "RW");
        max.setRawAddress("holding:0");
        max.setRegisterFormat("string");
        max.setWordCount(32);
        max.setSigned(Boolean.FALSE);
        service.create(max);
        verify(mappingMapper).insert(any(IotPointMapping.class));

        IotPointMappingReq tooMany = request(DEVICE_ID, PROPERTY_ID, "RW");
        tooMany.setRawAddress("holding:0");
        tooMany.setRegisterFormat("string");
        tooMany.setWordCount(33);
        tooMany.setSigned(Boolean.FALSE);
        assertThatThrownBy(() -> service.create(tooMany))
            .isInstanceOf(BusinessException.class).hasMessageContaining("1..32");
    }

    @Test
    @DisplayName("★ v3 string：signed=true / bit_* 非空 / data_type≠string ⇒ 拒绝")
    void stringIncompatibleFieldsRejected() {
        stubProduct("modbus", "string");
        IotPointMappingReq signedReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        signedReq.setRawAddress("holding:0");
        signedReq.setRegisterFormat("string");
        signedReq.setWordCount(2);
        signedReq.setSigned(Boolean.TRUE);
        assertThatThrownBy(() -> service.create(signedReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("signed 必须为 false");

        IotPointMappingReq bitsReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        bitsReq.setRawAddress("holding:0");
        bitsReq.setRegisterFormat("string");
        bitsReq.setWordCount(2);
        bitsReq.setBitOffset(0);
        bitsReq.setBitLength(1);
        assertThatThrownBy(() -> service.create(bitsReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 bit_offset/bit_length");

        stubProduct("modbus", "int");
        IotPointMappingReq typeReq = request(DEVICE_ID, PROPERTY_ID, "RW");
        typeReq.setRawAddress("holding:0");
        typeReq.setRegisterFormat("string");
        typeReq.setWordCount(2);
        typeReq.setSigned(Boolean.FALSE);
        assertThatThrownBy(() -> service.create(typeReq))
            .isInstanceOf(BusinessException.class).hasMessageContaining("要求属性类型是 string");
    }

    @Test
    @DisplayName("★ v3 string：word_count>1 必须显式 type:offset；非 Modbus/位类型 ⇒ 拒绝")
    void stringAddressAndApplicabilityRejected() {
        stubProduct("modbus", "string");
        IotPointMappingReq traditional = request(DEVICE_ID, PROPERTY_ID, "RW");
        traditional.setRegisterFormat("string");
        traditional.setWordCount(2);
        traditional.setSigned(Boolean.FALSE);
        assertThatThrownBy(() -> service.create(traditional))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("传统 5 位编号不支持多寄存器");

        IotPointMappingReq nonModbus = request(DEVICE_ID, PROPERTY_ID, "RW");
        stubProduct("tcp", "string");
        nonModbus.setRegisterFormat("string");
        nonModbus.setWordCount(2);
        nonModbus.setSigned(Boolean.FALSE);
        assertThatThrownBy(() -> service.create(nonModbus))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅 Modbus 寄存器点位支持 register_format=string");

        stubProduct("modbus", "bool");
        IotPointMappingReq coil = request(DEVICE_ID, PROPERTY_ID, "RW");
        coil.setAddressType("coil");
        coil.setRawAddress("coil:0");
        coil.setRegisterFormat("string");
        coil.setWordCount(2);
        coil.setSigned(Boolean.FALSE);
        assertThatThrownBy(() -> service.create(coil))
            .isInstanceOf(BusinessException.class).hasMessageContaining("位类型");
    }

    @Test
    @DisplayName("★ v3 回归：int 的 word_count 仍只允许 1|2（3 必须拒绝）")
    void intWordCountThreeStillRejected() {
        stubProduct("modbus", "int");
        IotPointMappingReq req = request(DEVICE_ID, PROPERTY_ID, "RW");
        req.setRawAddress("holding:0");
        req.setRegisterFormat("int");
        req.setWordCount(3);
        req.setSigned(Boolean.FALSE);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class).hasMessageContaining("只能是 1 或 2");
    }

    @Test
    @DisplayName("实体→响应：registerFormat/bitOffset/bitLength 必须搬运（前端表单契约）")
    void toRespShouldMapRegisterFormatAndBits() {
        IotPointMapping entity = new IotPointMapping();
        entity.setRegisterFormat("bits");
        entity.setBitOffset(3);
        entity.setBitLength(5);

        IotPointMappingResp resp = service.toResp(entity);

        assertThat(resp.getRegisterFormat()).isEqualTo("bits");
        assertThat(resp.getBitOffset()).isEqualTo(3);
        assertThat(resp.getBitLength()).isEqualTo(5);
    }
}
