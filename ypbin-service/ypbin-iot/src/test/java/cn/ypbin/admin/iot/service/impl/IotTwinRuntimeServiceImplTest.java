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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.admin.iot.model.resp.TwinRuntimePointResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.admin.iot.values.LatestValueQueryService;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行态服务：**固定次数查询**、values 语义、stale 标记、告警能力未启用时的降级。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class IotTwinRuntimeServiceImplTest {

    private static final long SCENE_ID = 7_300_001L;

    private static final long POINT_ID = 7_300_002L;

    private static final long DEVICE_ID = 4_301L;

    private final IotTwinSceneMapper sceneMapper = mock(IotTwinSceneMapper.class);

    private final IotTwinPointMapper pointMapper = mock(IotTwinPointMapper.class);

    private final IotTwinPointBindingMapper bindingMapper = mock(IotTwinPointBindingMapper.class);

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    private final LatestValueQueryService latestValueQueryService =
        mock(LatestValueQueryService.class);

    private final AlertInstanceService alertInstanceService = mock(AlertInstanceService.class);

    private final TwinBindingResolver bindingResolver = mock(TwinBindingResolver.class);

    private IotTwinRuntimeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinPoint.class);
        TableInfoHelper.initTableInfo(assistant, IotTwinPointBinding.class);
    }

    @BeforeEach
    void setUp() {
        service = new IotTwinRuntimeServiceImpl(sceneMapper, pointMapper, bindingMapper,
            deviceMapper, latestValueQueryService, alertInstanceService, bindingResolver);
        IotTwinScene scene = new IotTwinScene();
        scene.setId(SCENE_ID);
        when(sceneMapper.selectById(SCENE_ID)).thenReturn(scene);
    }

    @Test
    @DisplayName("场景不存在 ⇒ 拒绝")
    void missingSceneRejected() {
        when(sceneMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.runtime(SCENE_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景不存在");
    }

    @Test
    @DisplayName("★ 无点位 ⇒ 空快照，不查绑定/设备/Redis/告警")
    void emptySceneShortCircuits() {
        when(pointMapper.selectList(any())).thenReturn(List.of());

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        assertThat(resp.getPointCount()).isZero();
        assertThat(resp.getPoints()).isEmpty();
        assertThat(resp.getTruncated()).isFalse();
        verify(bindingMapper, times(0)).selectList(any());
        verify(deviceMapper, times(0)).selectBatchIds(anyCollection());
        verify(latestValueQueryService, times(0)).listLatestBatch(anyList());
        verify(alertInstanceService, times(0)).activeCountsByDevice(anyList());
    }

    @Test
    @DisplayName("★ 一次点位 + 一次绑定 + 一次设备 + 一次 pipeline + 一次告警（不随点位增长）")
    void queryCountIsFixed() {
        when(pointMapper.selectList(any())).thenReturn(List.of(
            point(POINT_ID, DEVICE_ID), point(POINT_ID + 1, DEVICE_ID + 1)));
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of());
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device(DEVICE_ID)));
        when(latestValueQueryService.listLatestBatch(anyList())).thenReturn(Map.of());
        when(alertInstanceService.activeCountsByDevice(anyList())).thenReturn(Map.of());

        service.runtime(SCENE_ID);

        verify(pointMapper, times(1)).selectList(any());
        verify(bindingMapper, times(1)).selectList(any());
        verify(deviceMapper, times(1)).selectBatchIds(anyCollection());
        verify(latestValueQueryService, times(1)).listLatestBatch(anyList());
        verify(alertInstanceService, times(1)).activeCountsByDevice(anyList());
    }

    @Test
    @DisplayName("★ 有最新值 ⇒ values 带值与 ts；无最新值 ⇒ values 里该键不存在（不是 0）")
    void valuesSemantics() {
        when(pointMapper.selectList(any())).thenReturn(List.of(point(POINT_ID, DEVICE_ID)));
        IotTwinPointBinding binding = binding(POINT_ID, "Environment", "temperature");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        TwinBindingResolver.ResolvedBinding resolved = new TwinBindingResolver.ResolvedBinding();
        resolved.setBindingId(binding.getId());
        resolved.setStale(false);
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of(binding.getId(), resolved));
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device(DEVICE_ID)));
        LatestValueResp latest = new LatestValueResp();
        latest.setPropertyId("temperature");
        latest.setValue("26.5");
        latest.setQuality("good");
        latest.setTs(1000L);
        when(latestValueQueryService.listLatestBatch(anyList()))
            .thenReturn(Map.of(DEVICE_ID, List.of(latest)));
        when(alertInstanceService.activeCountsByDevice(anyList())).thenReturn(Map.of());

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        TwinRuntimePointResp pointResp = resp.getPoints().getFirst();
        assertThat(pointResp.getValues()).containsKey("temperature");
        assertThat(pointResp.getValues().get("temperature").getValue()).isEqualTo("26.5");
        assertThat(pointResp.getValues().get("temperature").getTs()).isEqualTo(1000L);
        assertThat(pointResp.getValues().get("temperature").getStale()).isFalse();
        assertThat(pointResp.getValues()).doesNotContainKey("humidity");
    }

    @Test
    @DisplayName("★ 失效绑定 ⇒ 对应 value 标 stale=true（页面要能提示重配）")
    void staleBindingMarksValue() {
        when(pointMapper.selectList(any())).thenReturn(List.of(point(POINT_ID, DEVICE_ID)));
        IotTwinPointBinding binding = binding(POINT_ID, "Environment", "gone");
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        TwinBindingResolver.ResolvedBinding resolved = new TwinBindingResolver.ResolvedBinding();
        resolved.setBindingId(binding.getId());
        resolved.setStale(true);
        resolved.setPropertyName("gone");
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of(binding.getId(), resolved));
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device(DEVICE_ID)));
        when(latestValueQueryService.listLatestBatch(anyList())).thenReturn(Map.of());
        when(alertInstanceService.activeCountsByDevice(anyList())).thenReturn(Map.of());

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        assertThat(resp.getPoints().getFirst().getValues().get("gone").getStale()).isTrue();
    }

    @Test
    @DisplayName("★ 设备已删除 ⇒ 点位仍在、设备字段为 null（不丢弃也不伪造）")
    void deletedDeviceKeptWithNullFields() {
        when(pointMapper.selectList(any())).thenReturn(List.of(point(POINT_ID, DEVICE_ID)));
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of());
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(latestValueQueryService.listLatestBatch(anyList())).thenReturn(Map.of());
        when(alertInstanceService.activeCountsByDevice(anyList())).thenReturn(Map.of());

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        assertThat(resp.getPoints()).hasSize(1);
        TwinRuntimePointResp pointResp = resp.getPoints().getFirst();
        assertThat(pointResp.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(pointResp.getDeviceName()).isNull();
        assertThat(pointResp.getOnlineStatus()).isNull();
        assertThat(pointResp.getAlertCount()).isZero();
    }

    @Test
    @DisplayName("★ 告警能力未启用（抛业务异常）⇒ 按 0 降级，不整页打不开")
    void alertDisabledDegradesToZero() {
        when(pointMapper.selectList(any())).thenReturn(List.of(point(POINT_ID, DEVICE_ID)));
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of());
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device(DEVICE_ID)));
        when(latestValueQueryService.listLatestBatch(anyList())).thenReturn(Map.of());
        when(alertInstanceService.activeCountsByDevice(anyList()))
            .thenThrow(new BusinessException("告警能力未启用"));

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        assertThat(resp.getPoints().getFirst().getAlertCount()).isZero();
    }

    @Test
    @DisplayName("设备快照字段（编码/名称/在线/启停/最后上报）被原样带出")
    void deviceSnapshotFieldsCopied() {
        when(pointMapper.selectList(any())).thenReturn(List.of(point(POINT_ID, DEVICE_ID)));
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of());
        IotDevice device = device(DEVICE_ID);
        device.setDeviceCode("PUMP-03");
        device.setDeviceName("3 号泵");
        device.setOnlineStatus("online");
        device.setStatus(1);
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device));
        when(latestValueQueryService.listLatestBatch(anyList())).thenReturn(Map.of());
        when(alertInstanceService.activeCountsByDevice(anyList()))
            .thenReturn(Map.of(DEVICE_ID, 3));

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        TwinRuntimePointResp pointResp = resp.getPoints().getFirst();
        assertThat(pointResp.getDeviceCode()).isEqualTo("PUMP-03");
        assertThat(pointResp.getDeviceName()).isEqualTo("3 号泵");
        assertThat(pointResp.getOnlineStatus()).isEqualTo("online");
        assertThat(pointResp.getStatus()).isEqualTo(1);
        assertThat(pointResp.getAlertCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("serviceTime 非空、pointCount 与 points 大小一致")
    void metadataConsistent() {
        when(pointMapper.selectList(any())).thenReturn(List.of(point(POINT_ID, DEVICE_ID)));
        when(bindingMapper.selectList(any())).thenReturn(List.of());
        when(bindingResolver.resolve(anyCollection())).thenReturn(Map.of());
        when(deviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(device(DEVICE_ID)));
        when(latestValueQueryService.listLatestBatch(anyList())).thenReturn(Map.of());
        when(alertInstanceService.activeCountsByDevice(anyList())).thenReturn(Map.of());

        IotTwinRuntimeResp resp = service.runtime(SCENE_ID);

        assertThat(resp.getServerTime()).isNotNull();
        assertThat(resp.getSceneId()).isEqualTo(SCENE_ID);
        assertThat(resp.getPointCount()).isEqualTo(resp.getPoints().size());
    }

    private static IotTwinPoint point(long id, long deviceId) {
        IotTwinPoint point = new IotTwinPoint();
        point.setId(id);
        point.setSceneId(SCENE_ID);
        point.setDeviceId(deviceId);
        point.setLocalX(BigDecimal.ZERO);
        point.setLocalY(BigDecimal.ZERO);
        point.setLocalZ(BigDecimal.ZERO);
        point.setSort(0);
        return point;
    }

    private static IotTwinPointBinding binding(long pointId, String serviceIdentifier,
                                               String propertyIdentifier) {
        IotTwinPointBinding binding = new IotTwinPointBinding();
        binding.setId(pointId * 10);
        binding.setPointId(pointId);
        binding.setServiceIdentifier(serviceIdentifier);
        binding.setPropertyIdentifier(propertyIdentifier);
        return binding;
    }

    private static IotDevice device(long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(1L);
        return device;
    }
}
