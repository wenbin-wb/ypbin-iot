/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 告警保留清理的用例（用户口径 7：**活动告警永久保留** + 已恢复 180 天可配）。
 *
 * <p>这里最该被咬住的是「不可逆动作的护栏」：天数配成 0/负数必须**拒绝执行**而不是清空全表；
 * 以及「只删已恢复」——把活动告警删掉是不可逆的事故。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertRetentionCleanupServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private IotAlertInstanceMapper instanceMapper;

    private IotAlertNotificationMapper notificationMapper;

    private AlertProperties properties;

    private AlertRetentionCleanupServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new AlertProperties();
        instanceMapper = mock(IotAlertInstanceMapper.class);
        notificationMapper = mock(IotAlertNotificationMapper.class);
        DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);
        when(livenessMapper.selectNow()).thenReturn(NOW);
        service = new AlertRetentionCleanupServiceImpl(instanceMapper, notificationMapper, livenessMapper,
            properties, new AlertMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("★ 保留天数非法（0/负数）⇒ 拒绝执行，绝不清空全表")
    void illegalRetentionDaysRefusesToDelete() {
        properties.setRetentionResolvedDays(0);
        AlertRetentionCleanupService.CleanupResult result = service.cleanupOnce();
        assertThat(result.skipped()).isTrue();
        assertThat(result.deleted()).isZero();
        verify(instanceMapper, never()).selectResolvedIdsBefore(any(), anyInt());
        verify(instanceMapper, never()).deleteResolvedByIds(anyList());
    }

    @Test
    @DisplayName("总开关关闭 ⇒ 跳过（不删任何数据）")
    void disabledSkips() {
        properties.setEnabled(false);
        assertThat(service.cleanupOnce().skipped()).isTrue();
        verify(instanceMapper, never()).deleteResolvedByIds(anyList());
    }

    @Test
    @DisplayName("清理按「已恢复 + 早于截止时刻」取 ID，先删投递记录再删实例；截止时刻由保留天数算出")
    void deletesResolvedInstanceAndItsNotifications() {
        when(instanceMapper.selectResolvedIdsBefore(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(notificationMapper.deleteByInstanceIds(anyList())).thenReturn(3);
        when(instanceMapper.deleteResolvedByIds(anyList())).thenReturn(2);

        AlertRetentionCleanupService.CleanupResult result = service.cleanupOnce();

        assertThat(result.deleted()).isEqualTo(2);
        verify(instanceMapper, times(1)).selectResolvedIdsBefore(NOW.minusDays(180), 1000);
        verify(notificationMapper, times(1)).deleteByInstanceIds(List.of(1L, 2L));
        verify(instanceMapper, times(1)).deleteResolvedByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("分批：满一批就继续下一批，直到不足一批为止（单批删除都是单条语句）")
    void loopsUntilBatchNotFull() {
        properties.setRetentionBatchSize(2);
        when(instanceMapper.selectResolvedIdsBefore(any(), anyInt()))
            .thenReturn(List.of(1L, 2L), List.of(3L), List.of());
        when(instanceMapper.deleteResolvedByIds(anyList())).thenReturn(2, 1);

        AlertRetentionCleanupService.CleanupResult result = service.cleanupOnce();

        assertThat(result.deleted()).isEqualTo(3);
        verify(instanceMapper, times(2)).selectResolvedIdsBefore(any(), anyInt());
    }

    @Test
    @DisplayName("没有过期数据 ⇒ 一次查询即结束（不空转）")
    void emptyRoundEndsImmediately() {
        when(instanceMapper.selectResolvedIdsBefore(any(), anyInt())).thenReturn(List.of());
        assertThat(service.cleanupOnce().deleted()).isZero();
        verify(instanceMapper, times(1)).selectResolvedIdsBefore(any(), anyInt());
        verify(notificationMapper, never()).deleteByInstanceIds(anyList());
    }
}
