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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.device.AccessDeviceSpecResp;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备采集规格服务单测（access 采集的取数入口）。
 *
 * <p>守三件事：① 无设备/无点位时**短路**、绝不发空 IN；② 设备级周期取各点位周期的**最小值**；
 * ③ 查询跑在**调用方传入的租户上下文**里（无此绑定则租户插件 fail-closed，整个采集取数不可用）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class DeviceSpecServiceImplTest {

    private static final Long TENANT = 11L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotPointMappingMapper mappingMapper = mock(IotPointMappingMapper.class);
    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);
    private final DeviceSpecServiceImpl service =
        new DeviceSpecServiceImpl(deviceMapper, mappingMapper, propertyMapper);

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(IotDevice.class, IotPointMapping.class, IotProperty.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                entity);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("tenantId 为空：直接返回空集合，不查任何表")
    void nullTenantShouldShortCircuit() {
        assertThat(service.listByTenant(null)).isEmpty();
        verify(deviceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("租户无设备：返回空集合，且不再去查点位（不发空 IN）")
    void noDeviceShouldShortCircuit() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listByTenant(TENANT)).isEmpty();
        verify(mappingMapper, never()).selectList(any());
        verify(propertyMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("设备无点位：仍返回设备（点位为空列表），且不查属性标识")
    void deviceWithoutMappingShouldNotQueryProperties() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(100L)));
        when(mappingMapper.selectList(any())).thenReturn(List.of());

        List<AccessDeviceSpecResp> specs = service.listByTenant(TENANT);

        assertThat(specs).hasSize(1);
        assertThat(specs.getFirst().getPoints()).isEmpty();
        assertThat(specs.getFirst().getPollIntervalMs()).isNull();
        verify(propertyMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("★ 孤儿映射（属性行缺失 ⇒ 没有属性标识）不下发：采集侧因此不会采一个必被丢弃的点位")
    void orphanMappingMustNotBeDeliveredToAccess() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(100L)));
        // 900 有属性行（temperature），901 的属性行已被物模型重导入物理删除
        when(mappingMapper.selectList(any())).thenReturn(List.of(
            mapping(100L, 900L, "holding:1", 1000), mapping(100L, 901L, "holding:2", 5000)));
        when(propertyMapper.selectBatchIds(any())).thenReturn(List.of(property(900L, "temperature")));

        AccessDeviceSpecResp spec = service.listByTenant(TENANT).getFirst();

        assertThat(spec.getPoints()).singleElement()
            .satisfies(point -> assertThat(point.getIdentifier()).isEqualTo("temperature"));
    }

    @Test
    @DisplayName("装配：connectionId 自带租户、点位带属性标识、设备级周期取点位最小值")
    void shouldAssembleSpecWithPoints() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(100L)));
        IotPointMapping fast = mapping(100L, 900L, "holding:1", 1000);
        IotPointMapping slow = mapping(100L, 901L, "holding:2", 5000);
        when(mappingMapper.selectList(any())).thenReturn(List.of(fast, slow));
        when(propertyMapper.selectBatchIds(any())).thenReturn(List.of(property(900L, "temperature"),
            property(901L, "humidity")));

        AccessDeviceSpecResp spec = service.listByTenant(TENANT).getFirst();

        assertThat(spec.getConnectionId()).isEqualTo("t" + TENANT + "-d100");
        assertThat(spec.getDeviceId()).isEqualTo("100");
        assertThat(spec.getProtocol()).isEqualTo("modbus");
        assertThat(spec.getPollIntervalMs()).as("取点位周期最小值").isEqualTo(1000);
        assertThat(spec.getPoints()).hasSize(2);
        assertThat(spec.getPoints().get(0).getIdentifier()).isEqualTo("temperature");
        assertThat(spec.getPoints().get(0).getAddress()).isEqualTo("holding:1");
        assertThat(spec.getPoints().get(0).getPropertyId()).isEqualTo("900");
        assertThat(spec.getPoints().get(0).getRw()).isEqualTo("RW");
        assertThat(spec.getPoints().get(0).getDataType())
            .as("数据类型必须随点位下发（采集侧解码据此产出数值/布尔/文本）").isEqualTo("decimal");
    }

    @Test
    @DisplayName("★ 查询必须跑在调用方传入的租户上下文里（否则租户插件 fail-closed，取数整块不可用）")
    void queryMustRunInsideGivenTenantContext() {
        AtomicReference<Optional<Long>> seenTenant = new AtomicReference<>();
        when(deviceMapper.selectList(any())).thenAnswer(invocation -> {
            seenTenant.set(TenantContext.getTenantId());
            return List.of(device(100L));
        });
        when(mappingMapper.selectList(any())).thenReturn(List.of());

        service.listByTenant(TENANT);

        assertThat(seenTenant.get()).as("查设备时租户上下文必须是入参 tenantId")
            .contains(TENANT);
    }

    @Test
    @DisplayName("★ 停用设备不进规格下发：查询条件必须带 status=1（去掉该条件 ⇒ 本用例转红）")
    void disabledDeviceMustBeFilteredOutOfSpecDelivery() {
        // 为什么必须断言「查询条件」而不是「返回值」：本测试的 mapper 是 mock，返回什么由 stub 决定——
        // 若只 stub 一个空列表再断言结果为空，那条用例**永远绿**，删掉 status 过滤也照样通过（假绿）。
        // 唯一咬得住变异的判据是**发给 DB 的 WHERE 子句本身**：它必须含 status = 1。
        AtomicReference<String> sqlSegment = new AtomicReference<>();
        when(deviceMapper.selectList(any())).thenAnswer(invocation -> {
            sqlSegment.set(invocation.<LambdaQueryWrapper<IotDevice>>getArgument(0).getTargetSql());
            return List.of();
        });

        assertThat(service.listByTenant(TENANT)).isEmpty();

        assertThat(sqlSegment.get())
            .as("设备取数必须只取启停位为「启用」的设备（G7′ 停用即停采的落地点）")
            .contains("status")
            .contains("=");
        assertThat(service.listByTenant(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("★ 启停位过滤用的是 EntityStatus.ENABLED 的码值 1（裸数字/取值写错 ⇒ 本用例转红）")
    void specQueryMustFilterByEnabledStatusCode() {
        AtomicReference<LambdaQueryWrapper<IotDevice>> captured = new AtomicReference<>();
        when(deviceMapper.selectList(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return List.of();
        });

        service.listByTenant(TENANT);

        // ⚠️ 必须先取一次 `getSqlSegment()`：MyBatis-Plus 是**在渲染 SQL 段时才把参数登记进**
        // `paramNameValuePairs` 的（`eq(...)` 只排好「列 + #{} 占位符」）。不先渲染，
        // 这里读到的永远是空 Map ⇒ 断言以「空 map 不含 1」的形式**误红**；
        // 而删掉 status 过滤它反而变绿——那就成了一个方向反了的哨兵。
        String segment = captured.get().getSqlSegment();
        assertThat(segment).as("设备取数条件必须引用 status 列").contains("status");

        // 用参数对（列 → 值）而不是整串 SQL 断言：不依赖 MyBatis-Plus 的 SQL 拼写细节，
        // 但**取值**必须逐字等于枚举码 —— 改成 0（把「只取启用」写成「只取停用」）立刻转红。
        assertThat(captured.get().getParamNameValuePairs())
            .as("过滤值必须是 EntityStatus.ENABLED.getCode() = 1")
            .containsValue(EntityStatus.ENABLED.getCode());
        assertThat(captured.get().getParamNameValuePairs())
            .as("绝不能出现停用码 0（那等于整体反转语义）")
            .doesNotContainValue(EntityStatus.DISABLED.getCode());
    }

    @Test
    @DisplayName("停用设备的点位映射不会被顺带查出：设备为空即短路，不发点位查询")
    void disabledDeviceMustNotTriggerMappingQuery() {
        // 与上面两条互补：即便过滤条件在，若设备列表为空却仍去查点位，就说明短路被破坏
        // （而且「全部停用」的租户会每轮发一次空 IN）。
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listByTenant(TENANT)).isEmpty();
        verify(mappingMapper, never()).selectList(any());
        verify(propertyMapper, never()).selectBatchIds(any());
    }

    private static IotDevice device(Long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setDeviceName("水表-" + id);
        device.setProtocol("modbus");
        device.setEndpoint("tcp://127.0.0.1:15002");
        device.setStatus(1);
        return device;
    }

    private static IotPointMapping mapping(Long deviceId, Long propertyId, String address, int interval) {
        IotPointMapping mapping = new IotPointMapping();
        mapping.setDeviceId(deviceId);
        mapping.setPropertyId(propertyId);
        mapping.setRawAddress(address);
        mapping.setAddressType("holding");
        mapping.setPollIntervalMs(interval);
        mapping.setEnabled(Boolean.TRUE);
        mapping.setRw("RW");
        mapping.setScaleFactor(new BigDecimal("0.1"));
        return mapping;
    }

    @Test
    @DisplayName("★ 物模型数据类型随点位一起下发：采集侧解码靠它决定规范值形态（不许靠帧内容猜类型）")
    void dataTypeMustBeDeliveredWithPoints() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(100L)));
        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping(100L, 900L, "TEMP", 1000)));
        when(propertyMapper.selectBatchIds(any())).thenReturn(List.of(property(900L, "temperature", "decimal")));

        AccessDeviceSpecResp spec = service.listByTenant(TENANT).getFirst();

        assertThat(spec.getPoints()).singleElement()
            .satisfies(point -> assertThat(point.getDataType()).isEqualTo("decimal"));
    }

    private static IotProperty property(Long id, String identifier) {
        return property(id, identifier, "decimal");
    }

    private static IotProperty property(Long id, String identifier, String dataType) {
        IotProperty property = new IotProperty();
        property.setId(id);
        property.setIdentifier(identifier);
        property.setDataType(dataType);
        return property;
    }
}
