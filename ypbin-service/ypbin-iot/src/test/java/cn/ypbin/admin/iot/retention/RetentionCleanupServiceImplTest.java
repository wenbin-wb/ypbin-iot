/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 保留清理的行为（D0.8）：护栏、数据库时钟、计数、跳过语义。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class RetentionCleanupServiceImplTest {

    private static final LocalDateTime DB_NOW = LocalDateTime.of(2026, 9, 24, 3, 0);

    private final OutageEventMapper outageEventMapper = mock(OutageEventMapper.class);

    private final MaintenanceWindowMapper maintenanceWindowMapper = mock(MaintenanceWindowMapper.class);

    private final DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RetentionCleanupServiceImpl service(RetentionProperties properties) {
        when(livenessMapper.selectNow()).thenReturn(DB_NOW);
        return new RetentionCleanupServiceImpl(outageEventMapper, maintenanceWindowMapper, livenessMapper,
            properties, registry);
    }

    private static RetentionProperties days(int outageDays, int windowDays) {
        RetentionProperties properties = new RetentionProperties();
        properties.setOutageEventDays(outageDays);
        properties.setMaintenanceWindowDays(windowDays);
        return properties;
    }

    @Test
    @DisplayName("★ 启用时：截止时刻按**数据库时钟**与配置天数计算，并分别清理两张表")
    void mustDeleteWithDatabaseClockCutoff() {
        when(outageEventMapper.deleteStartedBefore(any())).thenReturn(5);
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(3);

        RetentionCleanupResult result = service(days(396, 396)).cleanupOnce();

        ArgumentCaptor<LocalDateTime> outageCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outageEventMapper).deleteStartedBefore(outageCutoff.capture());
        assertThat(outageCutoff.getValue()).as("断档截止 = 数据库现在 − 396 天")
            .isEqualTo(DB_NOW.minusDays(396));
        ArgumentCaptor<LocalDateTime> windowCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(maintenanceWindowMapper).deleteStartedBefore(windowCutoff.capture());
        assertThat(windowCutoff.getValue()).isEqualTo(DB_NOW.minusDays(396));

        assertThat(result.outageEvents()).isEqualTo(5);
        assertThat(result.maintenanceWindows()).isEqualTo(3);
        assertThat(result.skipped()).isFalse();
        assertThat(result.total()).isEqualTo(8);
        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_DELETED).tag("table", "outage_event")
            .counter().count()).isEqualTo(5.0d);
        assertThat(registry.get(RetentionCleanupServiceImpl.METRIC_DELETED).tag("table", "maintenance_window")
            .counter().count()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("★ 关闭时：不删任何数据，返回 skipped")
    void mustSkipWhenDisabled() {
        RetentionProperties properties = days(396, 396);
        properties.setEnabled(false);

        RetentionCleanupResult result = service(properties).cleanupOnce();

        assertThat(result.skipped()).isTrue();
        assertThat(result.total()).isZero();
        verify(outageEventMapper, never()).deleteStartedBefore(any());
        verify(maintenanceWindowMapper, never()).deleteStartedBefore(any());
    }

    @Test
    @DisplayName("★ 护栏：天数配置成 0/负数（= 全部过期）时必须**拒绝执行**，绝不清空全表")
    void mustRefuseWhenRetentionNotPositive() {
        for (RetentionProperties properties : new RetentionProperties[] {days(0, 396), days(396, -1)}) {
            RetentionCleanupResult result = service(properties).cleanupOnce();
            assertThat(result.skipped()).as("非法天数必须跳过而不是照删").isTrue();
        }
        verify(outageEventMapper, never()).deleteStartedBefore(any());
        verify(maintenanceWindowMapper, never()).deleteStartedBefore(any());
    }

    @Test
    @DisplayName("★ 启动自检：天数非正 / 间隔非正时拒绝启动（不可逆动作不允许带着错误配置跑起来）")
    void mustFailFastOnInvalidConfiguration() {
        assertThatThrownBy(() -> new IotRetentionConfiguration(days(0, 396)).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("必须为正数");
        assertThatThrownBy(() -> new IotRetentionConfiguration(days(396, -1)).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class);

        RetentionProperties badInterval = days(396, 396);
        badInterval.setCleanupIntervalMs(0);
        assertThatThrownBy(() -> new IotRetentionConfiguration(badInterval).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cleanup-interval-ms");

        // 关闭时不做校验（不删数据就无所谓天数）
        RetentionProperties disabled = days(0, 0);
        disabled.setEnabled(false);
        new IotRetentionConfiguration(disabled).afterPropertiesSet();
    }

    @Test
    @DisplayName("无过期数据：不报错、计数为 0、skipped=false")
    void mustReturnEmptyResultWhenNothingExpired() {
        when(outageEventMapper.deleteStartedBefore(any())).thenReturn(0);
        when(maintenanceWindowMapper.deleteStartedBefore(any())).thenReturn(0);

        RetentionCleanupResult result = service(days(396, 396)).cleanupOnce();

        assertThat(result.skipped()).isFalse();
        assertThat(result.total()).isZero();
    }
}
