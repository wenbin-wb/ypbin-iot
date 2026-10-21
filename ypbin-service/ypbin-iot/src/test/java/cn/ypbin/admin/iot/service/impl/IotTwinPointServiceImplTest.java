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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.req.TwinBindingReq;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 点位服务的绑定校验（枚举白名单 / 重复 / primary 唯一 / 设备唯一 / 上限 / null 语义）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class IotTwinPointServiceImplTest {

    private static final long SCENE_ID = 7_100_001L;

    private static final long DEVICE_ID = 4_101L;

    private static final long POINT_ID = 7_100_002L;

    private static final long PRODUCT_ID = 9_001L;

    private static final long TENANT_ID = 1L;

    private final IotTwinSceneMapper sceneMapper = mock(IotTwinSceneMapper.class);

    private final IotTwinPointBindingMapper bindingMapper = mock(IotTwinPointBindingMapper.class);

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    private final IotProductMapper productMapper = mock(IotProductMapper.class);

    private final IotServiceMapper serviceMapper = mock(IotServiceMapper.class);

    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);

    private final IotPointMappingMapper pointMappingMapper = mock(IotPointMappingMapper.class);

    private final TwinBindingResolver bindingResolver = mock(TwinBindingResolver.class);

    private final IotTwinPointMapper pointMapper = mock(IotTwinPointMapper.class);

    private IotTwinPointServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinPoint.class);
        TableInfoHelper.initTableInfo(assistant, IotTwinScene.class);
    }

    @BeforeEach
    void setUp() {
        service = new IotTwinPointServiceImpl(sceneMapper, bindingMapper, deviceMapper,
            productMapper, serviceMapper, propertyMapper, pointMappingMapper, bindingResolver);
        ReflectionTestUtils.setField(service, "baseMapper", pointMapper);
        IotTwinScene scene = new IotTwinScene();
        scene.setId(SCENE_ID);
        when(sceneMapper.selectById(SCENE_ID)).thenReturn(scene);
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        when(pointMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("★ bindings 为 null ⇒ 业务异常（漏字段不能被当成「清空绑定」）")
    void nullBindingsRejected() {
        IotTwinPointReq req = req();
        req.setBindings(null);
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("属性绑定列表不能为空");
    }

    @Test
    @DisplayName("★ bindings 传空数组 ⇒ 允许（用户明确的「暂不展示属性」）")
    void emptyBindingsAllowed() {
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        service.create(SCENE_ID, req);
        // 空列表仍会走一次 validateBindings（其内部对空集合直接 return），
        // 这是刻意的：调用点不需要为「空/非空」写两套分支。
        // 这里断言的是「没有属性被真正校验」——即传入的是空集合。
        verify(bindingResolver).validateBindings(eq(PRODUCT_ID), eq(List.of()));
        verify(pointMapper).insert(any(IotTwinPoint.class));
    }

    @Test
    @DisplayName("展示形态非法 ⇒ 拒绝并列出允许值")
    void invalidDisplayTypeRejected() {
        TwinBindingReq binding = binding("Environment", "temperature");
        binding.setDisplayType("pie");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("展示形态不合法")
            .hasMessageContaining("gauge");
    }

    @Test
    @DisplayName("展示角色非空但不认识 ⇒ 拒绝")
    void invalidDisplayRoleRejected() {
        TwinBindingReq binding = binding("Environment", "temperature");
        binding.setDisplayRole("boss");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("展示角色不合法");
    }

    @Test
    @DisplayName("展示角色为空 ⇒ 归一为 secondary（不拒绝）")
    void blankDisplayRoleNormalized() {
        TwinBindingReq binding = binding("Environment", "temperature");
        binding.setDisplayRole(null);
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        when(bindingResolver.resolvePropertyIds(anyLong(), any())).thenReturn(Map.of());
        // 原生批量 INSERT 需显式 tenant_id ⇒ 必须在租户上下文内执行（fail-closed 是刻意设计）
        TenantContext.executeWithTenant(TENANT_ID, () -> service.create(SCENE_ID, req));
        verify(bindingResolver).validateBindings(eq(PRODUCT_ID), any());
    }

    @Test
    @DisplayName("★ 同一属性绑定两次 ⇒ 拒绝")
    void duplicateBindingRejected() {
        TwinBindingReq first = binding("Environment", "temperature");
        TwinBindingReq second = binding("Environment", "temperature");
        IotTwinPointReq req = req();
        req.setBindings(List.of(first, second));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("属性绑定重复");
    }

    @Test
    @DisplayName("同属性标识但不同服务 ⇒ 不算重复（允许）")
    void sameIdentifierDifferentServiceAllowed() {
        TwinBindingReq first = binding("Environment", "temperature");
        TwinBindingReq second = binding("Device", "temperature");
        IotTwinPointReq req = req();
        req.setBindings(List.of(first, second));
        when(bindingResolver.resolvePropertyIds(anyLong(), any())).thenReturn(Map.of());
        TenantContext.executeWithTenant(TENANT_ID, () -> service.create(SCENE_ID, req));
        verify(bindingResolver).validateBindings(eq(PRODUCT_ID), any());
    }

    @Test
    @DisplayName("★ primary 多于一条 ⇒ 拒绝")
    void multiplePrimaryRejected() {
        TwinBindingReq first = binding("Environment", "temperature");
        first.setDisplayRole("primary");
        TwinBindingReq second = binding("Environment", "humidity");
        second.setDisplayRole("primary");
        IotTwinPointReq req = req();
        req.setBindings(List.of(first, second));
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最多只能有 1 个主展示属性");
    }

    @Test
    @DisplayName("★ 属性在物模型里不存在 ⇒ 拒绝（由 TwinBindingResolver 抛出）")
    void unknownPropertyRejected() {
        TwinBindingReq binding = binding("Environment", "notExists");
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding));
        doThrow(new BusinessException("以下属性在设备绑定的产品物模型里不存在"))
            .when(bindingResolver).validateBindings(any(), any());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("★ 设备未绑定产品 + 有绑定 ⇒ 由 resolver 拒绝并提示先绑产品")
    void deviceWithoutProductRejected() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(null);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding("Environment", "temperature")));
        doThrow(new BusinessException("该设备未绑定产品，无法配置属性绑定"))
            .when(bindingResolver).validateBindings(any(), any());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未绑定产品");
    }

    @Test
    @DisplayName("★ 同一场景同一设备已有锚点 ⇒ 拒绝")
    void duplicateDeviceInSceneRejected() {
        when(pointMapper.selectCount(any())).thenReturn(1L);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同一场景内一台设备只能有一个锚点");
    }

    @Test
    @DisplayName("★ 场景点位数达上限 ⇒ 拒绝并建议拆场景")
    void pointLimitRejected() {
        when(pointMapper.selectCount(any())).thenReturn(
            (long) TwinLimits.MAX_POINTS_PER_SCENE);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最多 " + TwinLimits.MAX_POINTS_PER_SCENE)
            .hasMessageContaining("拆成下级场景");
    }

    @Test
    @DisplayName("场景不存在 ⇒ 拒绝")
    void missingSceneRejected() {
        when(sceneMapper.selectById(anyLong())).thenReturn(null);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景不存在");
    }

    @Test
    @DisplayName("设备不存在（或跨租户）⇒ 拒绝")
    void missingDeviceRejected() {
        when(deviceMapper.selectById(anyLong())).thenReturn(null);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
    }

    @Test
    @DisplayName("编辑：点位不属于该场景 ⇒ 拒绝")
    void pointNotInSceneRejected() {
        IotTwinPoint other = new IotTwinPoint();
        other.setId(POINT_ID);
        other.setSceneId(9_999L);
        when(pointMapper.selectById(POINT_ID)).thenReturn(other);
        IotTwinPointReq req = req();
        req.setBindings(List.of());
        assertThatThrownBy(() -> service.update(SCENE_ID, POINT_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不属于该场景");
    }

    @Test
    @DisplayName("编辑：整体替换绑定 ⇒ 先物理删除旧绑定再插入")
    void updateReplacesBindings() {
        IotTwinPoint existing = new IotTwinPoint();
        existing.setId(POINT_ID);
        existing.setSceneId(SCENE_ID);
        existing.setDeviceId(DEVICE_ID);
        when(pointMapper.selectById(POINT_ID)).thenReturn(existing);
        when(bindingResolver.resolvePropertyIds(anyLong(), any())).thenReturn(Map.of());
        IotTwinPointReq req = req();
        req.setBindings(List.of(binding("Environment", "temperature")));
        TenantContext.runWithTenant(TENANT_ID,
            () -> service.update(SCENE_ID, POINT_ID, req));
        verify(bindingMapper).physicalDeleteByPointIds(List.of(POINT_ID));
        verify(bindingMapper).insertBatch(anyList());
    }

    @Test
    @DisplayName("删除点位 ⇒ 先删绑定再删点位")
    void removeDeletesBindingsThenPoint() {
        IotTwinPoint existing = new IotTwinPoint();
        existing.setId(POINT_ID);
        existing.setSceneId(SCENE_ID);
        when(pointMapper.selectById(POINT_ID)).thenReturn(existing);
        service.remove(SCENE_ID, POINT_ID);
        verify(bindingMapper).physicalDeleteByPointIds(List.of(POINT_ID));
        verify(pointMapper).deleteById(POINT_ID);
    }

    @Test
    @DisplayName("设备属性列表：设备未绑产品 ⇒ 空列表（正常空态，不抛错）")
    void deviceWithoutProductReturnsEmpty() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setProductId(null);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        assertThat(service.listDeviceProperties(DEVICE_ID)).isEmpty();
        verify(serviceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("设备属性列表：产品已不存在 ⇒ 空列表")
    void missingProductReturnsEmpty() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);
        assertThat(service.listDeviceProperties(DEVICE_ID)).isEmpty();
    }

    @Test
    @DisplayName("设备属性列表：设备不存在 ⇒ 抛业务异常")
    void missingDeviceForPropertiesRejected() {
        when(deviceMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.listDeviceProperties(DEVICE_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
    }

    @Test
    @DisplayName("列表：场景无点位 ⇒ 空列表，不查绑定")
    void emptyPointListShortCircuits() {
        when(pointMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listByScene(SCENE_ID)).isEmpty();
        verify(bindingMapper, never()).selectList(any());
    }

    private static IotTwinPointReq req() {
        IotTwinPointReq req = new IotTwinPointReq();
        req.setDeviceId(DEVICE_ID);
        req.setLocalX(BigDecimal.ONE);
        req.setLocalY(BigDecimal.ZERO);
        req.setLocalZ(BigDecimal.ZERO);
        return req;
    }

    private static TwinBindingReq binding(String serviceIdentifier, String propertyIdentifier) {
        TwinBindingReq binding = new TwinBindingReq();
        binding.setServiceIdentifier(serviceIdentifier);
        binding.setPropertyIdentifier(propertyIdentifier);
        binding.setDisplayType("value");
        return binding;
    }

    @Test
    @DisplayName("★ 13 条绑定被服务侧拒绝（不依赖 MVC 的 @Valid，#309）")
    void rejectsTooManyBindingsAtServiceLayer() {
        // 绕过 MVC 校验：直接调 service（内部调用/批量导入/定时任务都走这条路）
        List<TwinBindingReq> tooMany = new ArrayList<>();
        for (int index = 0; index < TwinLimits.MAX_BINDINGS_PER_POINT + 1; index++) {
            tooMany.add(binding("Environment", "prop" + index));
        }
        IotTwinPointReq req = req();
        req.setBindings(tooMany);

        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最多绑定 " + TwinLimits.MAX_BINDINGS_PER_POINT);
    }

    @Test
    @DisplayName("★ 恰好 12 条绑定被接受（边界值不误杀）")
    void acceptsExactlyMaxBindings() {
        List<TwinBindingReq> exact = new ArrayList<>();
        for (int index = 0; index < TwinLimits.MAX_BINDINGS_PER_POINT; index++) {
            TwinBindingReq item = binding("Environment", "prop" + index);
            // 只有第一条是 primary，其余 secondary
            item.setDisplayRole(index == 0 ? "primary" : "secondary");
            exact.add(item);
        }
        IotTwinPointReq req = req();
        req.setBindings(exact);
        when(bindingResolver.resolvePropertyIds(anyLong(), any())).thenReturn(Map.of());

        TenantContext.executeWithTenant(TENANT_ID, () -> service.create(SCENE_ID, req));
        verify(bindingResolver).validateBindings(eq(PRODUCT_ID), any());
    }

    @Test
    @DisplayName("★ decimals 越界被服务侧拒绝（@Size 管不了取值范围，#309）")
    void rejectsOutOfRangeDecimals() {
        TwinBindingReq bad = binding("Environment", "temperature");
        bad.setDecimals(TwinLimits.MAX_DECIMALS + 1);
        IotTwinPointReq req = req();
        req.setBindings(List.of(bad));

        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("小数位必须在 0~" + TwinLimits.MAX_DECIMALS);

        TwinBindingReq negative = binding("Environment", "temperature");
        negative.setDecimals(-1);
        IotTwinPointReq req2 = req();
        req2.setBindings(List.of(negative));
        assertThatThrownBy(() -> service.create(SCENE_ID, req2))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("小数位必须在 0~" + TwinLimits.MAX_DECIMALS);
    }

    @Test
    @DisplayName("★ 单位超长被服务侧拒绝（#309）")
    void rejectsTooLongUnitOverride() {
        TwinBindingReq bad = binding("Environment", "temperature");
        bad.setUnitOverride("x".repeat(TwinLimits.MAX_UNIT_LENGTH + 1));
        IotTwinPointReq req = req();
        req.setBindings(List.of(bad));

        assertThatThrownBy(() -> service.create(SCENE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("单位长度不能超过 " + TwinLimits.MAX_UNIT_LENGTH);
    }
}
