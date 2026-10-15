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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import cn.ypbin.admin.iot.enums.OnlineStatus;
import cn.ypbin.admin.iot.mapper.IotDashboardDailySnapshotMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 仪表盘每日在线快照的纯逻辑单测（Mockito mock Mapper，不起 Spring、不连库）。
 *
 * <p>钉住四处：当天无快照插入、已有快照更新、并发首写撞唯一键回退更新、近 N 天查询窗口/排序
 * 与空集合短路。租户条件由 MyBatis-Plus 租户插件在真实 SQL 上追加，本测试不重复验证。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotDashboardSnapshotServiceImplTest {

    private final IotDashboardDailySnapshotMapper snapshotMapper = mock(IotDashboardDailySnapshotMapper.class);

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    private final IotDashboardSnapshotServiceImpl service =
        new IotDashboardSnapshotServiceImpl(snapshotMapper, deviceMapper);

    @Test
    @DisplayName("capture：当天无快照时插入，四个计数按当前租户口径写入")
    void captureShouldInsertWhenNoSnapshotForToday() {
        stubDeviceCounts(2148L, 1976L, 135L, 37L);
        when(snapshotMapper.selectOne(any())).thenReturn(null);

        service.captureCurrentTenant();

        ArgumentCaptor<IotDashboardDailySnapshot> captor =
            ArgumentCaptor.forClass(IotDashboardDailySnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        IotDashboardDailySnapshot inserted = captor.getValue();
        assertThat(inserted.getSnapshotDate()).isEqualTo(LocalDate.now());
        assertThat(inserted.getDeviceTotal()).isEqualTo(2148);
        assertThat(inserted.getDeviceOnline()).isEqualTo(1976);
        assertThat(inserted.getDeviceOffline()).isEqualTo(135);
        assertThat(inserted.getDeviceDisabled()).isEqualTo(37);
        verify(snapshotMapper, never()).updateById(any(IotDashboardDailySnapshot.class));
    }

    @Test
    @DisplayName("capture：当天已有快照时更新已有行（同 id），不新增")
    void captureShouldUpdateWhenSnapshotExists() {
        stubDeviceCounts(2200L, 2000L, 170L, 30L);
        IotDashboardDailySnapshot existing = snapshot(LocalDate.now(), 1, 1);
        existing.setId(7001L);
        existing.setDeviceTotal(1);
        existing.setDeviceDisabled(1);
        when(snapshotMapper.selectOne(any())).thenReturn(existing);

        service.captureCurrentTenant();

        assertThat(existing.getDeviceTotal()).isEqualTo(2200);
        assertThat(existing.getDeviceOnline()).isEqualTo(2000);
        assertThat(existing.getDeviceOffline()).isEqualTo(170);
        assertThat(existing.getDeviceDisabled()).isEqualTo(30);
        verify(snapshotMapper).updateById(existing);
        verify(snapshotMapper, never()).insert(any(IotDashboardDailySnapshot.class));
    }

    @Test
    @DisplayName("capture：并发首写撞唯一键时回退为更新，不向上抛错")
    void captureShouldFallBackToUpdateOnDuplicateKey() {
        stubDeviceCounts(100L, 80L, 15L, 5L);
        IotDashboardDailySnapshot concurrent = snapshot(LocalDate.now(), 1, 1);
        concurrent.setId(7002L);
        when(snapshotMapper.selectOne(any())).thenReturn(null, concurrent);
        when(snapshotMapper.insert(any(IotDashboardDailySnapshot.class)))
            .thenThrow(new DuplicateKeyException("uk_iot_dashboard_daily_snapshot"));

        service.captureCurrentTenant();

        verify(snapshotMapper, times(2)).selectOne(any());
        verify(snapshotMapper).insert(any(IotDashboardDailySnapshot.class));
        assertThat(concurrent.getDeviceTotal()).isEqualTo(100);
        assertThat(concurrent.getDeviceOnline()).isEqualTo(80);
        assertThat(concurrent.getDeviceOffline()).isEqualTo(15);
        assertThat(concurrent.getDeviceDisabled()).isEqualTo(5);
        verify(snapshotMapper).updateById(concurrent);
    }

    @Test
    @DisplayName("capture：撞唯一键后仍查不到当天行时原样抛出，不静默吞异常")
    void captureShouldRethrowWhenDuplicateButNoRowFound() {
        stubDeviceCounts(1L, 1L, 0L, 0L);
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(snapshotMapper.insert(any(IotDashboardDailySnapshot.class)))
            .thenThrow(new DuplicateKeyException("uk_iot_dashboard_daily_snapshot"));

        assertThatThrownBy(service::captureCurrentTenant)
            .isInstanceOf(DuplicateKeyException.class)
            .hasMessageContaining("uk_iot_dashboard_daily_snapshot");
        verify(snapshotMapper, never()).updateById(any(IotDashboardDailySnapshot.class));
    }

    @Test
    @DisplayName("loadRecent：days<=0 直接返回空集合且不查库")
    void loadRecentShouldShortCircuitOnNonPositiveDays() {
        assertThat(service.loadRecent(0)).isEmpty();
        assertThat(service.loadRecent(-3)).isEmpty();

        verifyNoInteractions(snapshotMapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("loadRecent：查询 [today-days+1, today] 并按 snapshot_date 升序，结果原样返回")
    void loadRecentShouldQueryAscendingWindow() {
        LocalDate today = LocalDate.now();
        IotDashboardDailySnapshot older = snapshot(today.minusDays(2), 10, 1);
        IotDashboardDailySnapshot newer = snapshot(today, 12, 2);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(older, newer));

        List<IotDashboardDailySnapshot> rows = service.loadRecent(3);

        assertThat(rows).containsExactly(older, newer);
        ArgumentCaptor<QueryWrapper<IotDashboardDailySnapshot>> captor =
            ArgumentCaptor.forClass(QueryWrapper.class);
        verify(snapshotMapper).selectList(captor.capture());
        QueryWrapper<IotDashboardDailySnapshot> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertThat(sql).as("查询窗口 + 升序排序").contains("snapshot_date").contains("ORDER BY").contains("ASC");
        assertThat(wrapper.getParamNameValuePairs().values())
            .contains(today.minusDays(2), today);
    }

    /**
     * 按 SQL 条件片段区分四个设备计数查询：总数 / 在线 / 离线 / 停用。
     */
    private void stubDeviceCounts(long total, long online, long offline, long disabled) {
        when(deviceMapper.selectCount(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            String segment = wrapper.getSqlSegment();
            if (segment.contains("online_status")) {
                return wrapper.getParamNameValuePairs().containsValue(OnlineStatus.ONLINE.getCode())
                    ? online : offline;
            }
            if (segment.contains("status")) {
                return disabled;
            }
            return total;
        });
    }

    private static IotDashboardDailySnapshot snapshot(LocalDate date, int online, int offline) {
        IotDashboardDailySnapshot snapshot = new IotDashboardDailySnapshot();
        snapshot.setSnapshotDate(date);
        snapshot.setDeviceOnline(online);
        snapshot.setDeviceOffline(offline);
        return snapshot;
    }
}
