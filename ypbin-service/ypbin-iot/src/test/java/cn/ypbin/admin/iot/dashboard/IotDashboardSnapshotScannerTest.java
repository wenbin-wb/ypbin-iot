/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import cn.ypbin.admin.iot.mapper.IotDashboardDailySnapshotMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.service.impl.IotDashboardSnapshotServiceImpl;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * 仪表盘每日快照定时扫描的纯逻辑单测（Mockito mock 两个 Mapper，不起 Spring/调度）。
 *
 * <p>通过真实 {@link IotDashboardSnapshotServiceImpl} 串起 scanner → service → mapper 三层，钉住：
 * ① {@code GROUP BY tenant_id} 聚合行到快照实体（含显式 tenant_id/id/审计列）的映射；
 * ② upsertBatch 只调一次（不逐租户往返）；③ 空数据短路不调 upsert；
 * ④ 底层异常不向调度器抛出但必须 ERROR 留痕（含堆栈）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotDashboardSnapshotScannerTest {

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    private final IotDashboardDailySnapshotMapper snapshotMapper = mock(IotDashboardDailySnapshotMapper.class);

    private final IotDashboardSnapshotServiceImpl snapshotService =
        new IotDashboardSnapshotServiceImpl(snapshotMapper, deviceMapper);

    private final IotDashboardSnapshotScanner scanner = new IotDashboardSnapshotScanner(snapshotService);

    private ListAppender<ILoggingEvent> logAppender;

    private Logger scannerLogger;

    @BeforeEach
    void attachLogCapture() {
        scannerLogger = (Logger) LoggerFactory.getLogger(IotDashboardSnapshotScanner.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        scannerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        scannerLogger.detachAppender(logAppender);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("扫描：一次 GROUP BY 聚合映射为全租户快照，upsertBatch 一次批量落库")
    void scanShouldMapGroupedStatsAndUpsertOnce() {
        when(deviceMapper.selectMaps(any())).thenAnswer(invocation -> {
            assertThat(TenantContext.isIgnored()).as("跨租户聚合必须在 executeIgnore 内").isTrue();
            return List.of(
                row("tenant_id", 1L, "device_total", 10L, "device_online", 7L,
                    "device_offline", 2L, "device_disabled", 1L),
                // 别名大小写/下划线形态由驱动决定，这里刻意用大写验证映射健壮
                row("TENANT_ID", 2L, "DEVICE_TOTAL", 20L, "DEVICE_ONLINE", 15L,
                    "DEVICE_OFFLINE", 3L, "DEVICE_DISABLED", 2L));
        });
        when(snapshotMapper.upsertBatch(anyList())).thenAnswer(invocation -> {
            assertThat(TenantContext.isIgnored()).as("自定义 SQL 批量写必须在 runIgnore 内").isTrue();
            return 2;
        });

        scanner.scan();

        ArgumentCaptor<List<IotDashboardDailySnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).upsertBatch(captor.capture());
        List<IotDashboardDailySnapshot> snapshots = captor.getValue();
        assertThat(snapshots).hasSize(2);

        IotDashboardDailySnapshot first = snapshots.get(0);
        assertThat(first.getId()).as("自定义 SQL 不会生成主键，必须显式预生成").isNotNull();
        assertThat(first.getTenantId()).isEqualTo(1L);
        assertThat(first.getSnapshotDate()).isEqualTo(LocalDate.now());
        assertThat(first.getDeviceTotal()).isEqualTo(10);
        assertThat(first.getDeviceOnline()).isEqualTo(7);
        assertThat(first.getDeviceOffline()).isEqualTo(2);
        assertThat(first.getDeviceDisabled()).isEqualTo(1);
        assertThat(first.getCreateTime()).isNotNull();
        assertThat(first.getUpdateTime()).isNotNull();
        assertThat(first.getStatus()).isEqualTo(EntityStatus.ENABLED.getCode());
        assertThat(first.getIsDeleted()).isZero();

        IotDashboardDailySnapshot second = snapshots.get(1);
        assertThat(second.getTenantId()).isEqualTo(2L);
        assertThat(second.getDeviceTotal()).isEqualTo(20);
        assertThat(second.getDeviceOnline()).isEqualTo(15);
        assertThat(second.getDeviceOffline()).isEqualTo(3);
        assertThat(second.getDeviceDisabled()).isEqualTo(2);

        verify(deviceMapper, times(1)).selectMaps(any());
        assertThat(TenantContext.isIgnored()).as("executeIgnore/runIgnore 必须恢复现场").isFalse();
    }

    @Test
    @DisplayName("扫描：无任何租户统计时空短路，不调 upsertBatch")
    void scanShouldShortCircuitOnEmptyStats() {
        when(deviceMapper.selectMaps(any())).thenReturn(List.of());

        scanner.scan();

        verifyNoInteractions(snapshotMapper);
        assertThat(TenantContext.isIgnored()).isFalse();
    }

    @Test
    @DisplayName("扫描：底层异常记 ERROR（含堆栈）且不向调度器抛出")
    void scanShouldLogErrorAndNotPropagate() {
        when(deviceMapper.selectMaps(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scanner::scan).doesNotThrowAnyException();

        List<ILoggingEvent> errors = logAppender.list.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains("仪表盘每日快照扫描失败");
        assertThat(errors.get(0).getThrowableProxy()).as("必须带完整异常，不能只留一行文案").isNotNull();
        assertThat(errors.get(0).getThrowableProxy().getClassName())
            .isEqualTo(IllegalStateException.class.getName());
        verify(snapshotMapper, never()).upsertBatch(anyList());
        assertThat(TenantContext.isIgnored()).as("异常路径也必须退出 ignore 作用域").isFalse();
    }

    /** 构造 selectMaps 返回行（键名大小写/下划线刻意由调用方决定）。 */
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            row.put((String) keyValues[index], keyValues[index + 1]);
        }
        return row;
    }
}
