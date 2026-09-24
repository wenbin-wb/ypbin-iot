/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 查询服务的参数校验、租户解析与「不可用即报错」语义（§5.2.1 查询路径）。
 *
 * <p>这里最关键的一条不是参数校验，而是：<b>存储不可用时必须报错，不能返回空列表</b>——
 * 空列表会被前端与运维读成「这段时间没数据」，与「时序库根本没启用」是两回事（禁静默降级）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class TimeSeriesQueryServiceTest {

    private static final Long DEVICE_ID = 100L;

    private static final Long TENANT_ID = 9L;

    private static TimeSeriesQueryReq req(String propertyId) {
        TimeSeriesQueryReq req = new TimeSeriesQueryReq();
        req.setPropertyId(propertyId);
        return req;
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setTenantId(TENANT_ID);
        return device;
    }

    private static TimeSeriesPointResp point() {
        return new TimeSeriesPointResp(1_700_000_000_000L, "23.5", "GOOD");
    }

    @Test
    @DisplayName("★ 设备 ID 为空即拒绝")
    void mustRejectNullDeviceId() {
        TimeSeriesQueryService service =
            new TimeSeriesQueryService(mock(TimeSeriesStore.class), mock(IotDeviceMapper.class));
        assertThatThrownBy(() -> service.query(null, req("temp")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备 ID");
    }

    @Test
    @DisplayName("★ 点位为空/空白即拒绝（不按点位查会把设备的全部点位混在一起）")
    void mustRejectBlankPropertyId() {
        TimeSeriesQueryService service =
            new TimeSeriesQueryService(mock(TimeSeriesStore.class), mock(IotDeviceMapper.class));
        assertThatThrownBy(() -> service.query(DEVICE_ID, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("点位");
        assertThatThrownBy(() -> service.query(DEVICE_ID, req("  ")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("点位");
    }

    @Test
    @DisplayName("★ 区间给反即拒绝（不静默交换：静默交换会让前端拿到看似正常的结果）")
    void mustRejectInvertedRange() {
        TimeSeriesQueryService service =
            new TimeSeriesQueryService(mock(TimeSeriesStore.class), mock(IotDeviceMapper.class));
        TimeSeriesQueryReq req = req("temp");
        req.setFrom(2_000L);
        req.setTo(1_000L);

        assertThatThrownBy(() -> service.query(DEVICE_ID, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("起始时刻");
    }

    @Test
    @DisplayName("★ limit 越界即拒绝（1~5000）")
    void mustRejectOutOfRangeLimit() {
        TimeSeriesQueryService service =
            new TimeSeriesQueryService(mock(TimeSeriesStore.class), mock(IotDeviceMapper.class));

        TimeSeriesQueryReq zero = req("temp");
        zero.setLimit(0);
        assertThatThrownBy(() -> service.query(DEVICE_ID, zero))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("limit");

        TimeSeriesQueryReq tooLarge = req("temp");
        tooLarge.setLimit(TimeSeriesQueryReq.MAX_LIMIT + 1);
        assertThatThrownBy(() -> service.query(DEVICE_ID, tooLarge))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("limit");

        TimeSeriesQueryReq negative = req("temp");
        negative.setLimit(-1);
        assertThatThrownBy(() -> service.query(DEVICE_ID, negative))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("★ 存储不可用时报错而不是空列表（空列表会被读成「这段时间没数据」）")
    void mustFailWhenStoreUnavailable() {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        when(store.available()).thenReturn(false);
        TimeSeriesQueryService service = new TimeSeriesQueryService(store, mock(IotDeviceMapper.class));

        assertThatThrownBy(() -> service.query(DEVICE_ID, req("temp")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未启用");
        verify(store, never()).query(anyLong(), anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("★ 设备不存在（或不属于当前租户）即拒绝")
    void mustRejectUnknownDevice() {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        when(store.available()).thenReturn(true);
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        TimeSeriesQueryService service = new TimeSeriesQueryService(store, deviceMapper);

        assertThatThrownBy(() -> service.query(DEVICE_ID, req("temp")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        verify(store, never()).query(anyLong(), anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("★ 正常路径：按解析出的租户查询，条件与 limit 原样透传")
    void mustQueryWithResolvedTenant() {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        when(store.available()).thenReturn(true);
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device());
        when(store.query(TENANT_ID, DEVICE_ID, "temp", 1_000L, 2_000L, 50))
            .thenReturn(List.of(point()));
        TimeSeriesQueryService service = new TimeSeriesQueryService(store, deviceMapper);

        TimeSeriesQueryReq req = req("temp");
        req.setFrom(1_000L);
        req.setTo(2_000L);
        req.setLimit(50);

        List<TimeSeriesPointResp> points = service.query(DEVICE_ID, req);

        assertThat(points).containsExactly(point());
        verify(store).query(TENANT_ID, DEVICE_ID, "temp", 1_000L, 2_000L, 50);
    }

    @Test
    @DisplayName("★ limit 未给时用默认值（不把 null 传给存储）")
    void mustUseDefaultLimitWhenAbsent() {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        when(store.available()).thenReturn(true);
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device());
        when(store.query(TENANT_ID, DEVICE_ID, "temp", null, null, TimeSeriesQueryReq.DEFAULT_LIMIT))
            .thenReturn(List.of());
        TimeSeriesQueryService service = new TimeSeriesQueryService(store, deviceMapper);

        TimeSeriesQueryReq req = req("temp");
        req.setLimit(null);

        assertThat(service.query(DEVICE_ID, req)).isEmpty();
        verify(store).query(TENANT_ID, DEVICE_ID, "temp", null, null, TimeSeriesQueryReq.DEFAULT_LIMIT);
    }

    @Test
    @DisplayName("★ 存储返回 null 时收敛为空列表（契约：集合不返回 null）")
    void mustCoerceNullStoreResultToEmptyList() {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        when(store.available()).thenReturn(true);
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device());
        when(store.query(anyLong(), anyLong(), anyString(), any(), any(), anyInt())).thenReturn(null);
        TimeSeriesQueryService service = new TimeSeriesQueryService(store, deviceMapper);

        assertThat(service.query(DEVICE_ID, req("temp"))).isEmpty();
    }

    @Test
    @DisplayName("storeAvailable 透传存储可用性（装配自检用，不触发查询）")
    void storeAvailableMustDelegate() {
        TimeSeriesStore store = mock(TimeSeriesStore.class);
        when(store.available()).thenReturn(false);
        TimeSeriesQueryService service = new TimeSeriesQueryService(store, mock(IotDeviceMapper.class));

        assertThat(service.storeAvailable()).isFalse();
        verify(store, never()).query(anyLong(), anyLong(), anyString(), any(), any(), anyInt());
    }
}
