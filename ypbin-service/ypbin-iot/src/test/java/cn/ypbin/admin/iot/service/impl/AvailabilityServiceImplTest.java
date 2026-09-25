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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.availability.AvailabilityProperties;
import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.availability.OutageReason;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import cn.ypbin.admin.iot.shadow.ShadowReportedUpdate;
import cn.ypbin.admin.iot.shadow.ShadowReportedWriter;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPoint;
import cn.ypbin.admin.iot.timeseries.TimeSeriesProperties;
import cn.ypbin.admin.iot.timeseries.TimeSeriesWriter;
import cn.ypbin.admin.iot.values.LatestValue;
import cn.ypbin.admin.iot.values.LatestValueWriter;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 断档与可用率服务单测（M-2）：上报 / 扫描 / 查询三条链路的边界与失败语义。
 *
 * <p>重点不是「能跑通」，而是几处容易静默错的行为：时间只能**前进**（乱序/重放的上报不得把状态拉回去）、
 * 有效数据必须闭合断档、多副本并发只允许一个开断档、参数给反必须报错而不是给出一份看起来正常的报表。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
class AvailabilityServiceImplTest {

    private static final Long TENANT = 11L;
    private static final Long DEVICE = 100L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 22, 10, 0, 0);

    private DeviceLivenessMapper livenessMapper;
    private OutageEventMapper outageMapper;

    private MaintenanceWindowMapper maintenanceWindowMapper;

    /** 租户提供者桩：真实请求链路上租户来自 IdentityContext（见 MicroserviceTenantProvider），这里模拟之。 */
    private TenantProvider tenantProvider;

    /** 最新值写入器（Q8）：断言「带点位与值的读数才写」 */
    private LatestValueWriter latestValueWriter;

    /** 时序写入器（§5.2.1）：默认关闭时不应被调用 */
    private TimeSeriesWriter timeSeriesWriter;

    /** 影子 reported 写入器（G2）：断言「带点位与值的读数才合并进影子」与失败不回滚 */
    private ShadowReportedWriter shadowReportedWriter;

    private TimeSeriesProperties timeSeriesProperties;
    private IotDeviceMapper deviceMapper;
    private AvailabilityProperties properties;
    private AvailabilityServiceImpl service;

    /**
     * 初始化 MyBatis-Plus 的实体元信息。
     *
     * <p>为什么需要：{@code LambdaQueryWrapper} 用方法引用解析列名，列名来自实体 TableInfo，
     * 而它通常由 Mapper 扫描时注册——纯单测（不起 Spring、不扫 Mapper）里没有这一步，会抛
     * {@code MybatisPlus can not find lambda cache for this entity}。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DeviceLiveness.class);
        TableInfoHelper.initTableInfo(assistant, OutageEvent.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
    }

    @BeforeEach
    void setUp() {
        livenessMapper = mock(DeviceLivenessMapper.class);
        outageMapper = mock(OutageEventMapper.class);
        maintenanceWindowMapper = mock(MaintenanceWindowMapper.class);
        tenantProvider = mock(TenantProvider.class);
        latestValueWriter = mock(LatestValueWriter.class);
        timeSeriesWriter = mock(TimeSeriesWriter.class);
        shadowReportedWriter = mock(ShadowReportedWriter.class);
        timeSeriesProperties = new TimeSeriesProperties();
        lenient().when(tenantProvider.getCurrentTenantId()).thenReturn(java.util.Optional.empty());
        // 默认无维护窗口（既有用例的口径不受影响）
        lenient().when(maintenanceWindowMapper.sumMaintenanceSecondsInWindow(anyLong(), anyLong(), any(),
            any(), any())).thenReturn(0L);
        lenient().when(maintenanceWindowMapper.sumOutageInMaintenanceSeconds(anyLong(), anyLong(), any(),
            any(), any())).thenReturn(0L);
        lenient().when(maintenanceWindowMapper.listOverlappingInWindow(anyLong(), anyLong(), any(), any(),
            anyInt())).thenReturn(List.of());
        deviceMapper = mock(IotDeviceMapper.class);
        properties = new AvailabilityProperties();
        service = new AvailabilityServiceImpl(livenessMapper, outageMapper, maintenanceWindowMapper, deviceMapper,
            properties, tenantProvider, latestValueWriter, timeSeriesWriter, timeSeriesProperties,
            shadowReportedWriter);
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
    }

    @Test
    @DisplayName("★ Q8：带点位与值的读数必须写最新值；只报质量/时刻（无点位）的读数不写")
    void ingestMustWriteLatestValuesOnlyForPointReadings() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());

        ReadingObservationDto withPoint = new ReadingObservationDto();
        withPoint.setDeviceId(DEVICE);
        withPoint.setPropertyId("temperature");
        withPoint.setValue("23.5");
        withPoint.setQuality(AvailabilityRules.QUALITY_GOOD);
        withPoint.setTs(1_700_000_000_000L);

        ReadingObservationDto onlyQuality = new ReadingObservationDto();
        onlyQuality.setDeviceId(DEVICE);
        onlyQuality.setQuality(AvailabilityRules.QUALITY_GOOD);
        onlyQuality.setTs(1_700_000_000_100L);

        assertThat(service.ingest(req(withPoint, onlyQuality))).isEqualTo(2);

        ArgumentCaptor<List<LatestValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(latestValueWriter).writeAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(value -> {
            assertThat(value.tenantId()).as("租户必须来自设备台账（内部上报路径没有租户上下文）").isEqualTo(TENANT);
            assertThat(value.deviceId()).isEqualTo(DEVICE);
            assertThat(value.propertyId()).isEqualTo("temperature");
            assertThat(value.value()).isEqualTo("23.5");
        });
    }

    @Test
    @DisplayName("★ Q8：最新值必须在**事务提交后**才写（库回滚了但最新值已生效 = 不一致）")
    void latestValuesMustBeWrittenAfterCommitOnly() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());
        ReadingObservationDto withPoint = new ReadingObservationDto();
        withPoint.setDeviceId(DEVICE);
        withPoint.setPropertyId("temperature");
        withPoint.setValue("23.5");
        withPoint.setQuality(AvailabilityRules.QUALITY_GOOD);
        withPoint.setTs(1_700_000_000_000L);

        // 模拟「事务已开启」：单测默认没有事务，直接写；这里显式开一个同步器来验证延迟语义
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.ingest(req(withPoint));
            verify(latestValueWriter, never()).writeAll(any());
        } finally {
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(latestValueWriter).writeAll(any());
    }

    @Test
    @DisplayName("★ §5.2.1：默认关闭（enabled=false）时**不得**收集/写入时序")
    void mustNotWriteSeriesWhenDisabled() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());
        ReadingObservationDto withPoint = new ReadingObservationDto();
        withPoint.setDeviceId(DEVICE);
        withPoint.setPropertyId("temperature");
        withPoint.setValue("23.5");
        withPoint.setQuality(AvailabilityRules.QUALITY_GOOD);
        withPoint.setTs(1_700_000_000_000L);

        service.ingest(req(withPoint));

        verify(timeSeriesWriter, never()).writeAll(any());
    }

    @Test
    @DisplayName("★ §5.2.1：启用时序时按批写点位（含租户/设备/点位/值/质量/时刻），且仍在提交后")
    void mustWriteSeriesWhenEnabled() {
        timeSeriesProperties.setEnabled(true);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());
        ReadingObservationDto withPoint = new ReadingObservationDto();
        withPoint.setDeviceId(DEVICE);
        withPoint.setPropertyId("temperature");
        withPoint.setValue("23.5");
        withPoint.setQuality(AvailabilityRules.QUALITY_GOOD);
        withPoint.setTs(1_700_000_000_000L);

        service.ingest(req(withPoint));

        ArgumentCaptor<List<TimeSeriesPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(timeSeriesWriter).writeAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(point -> {
            assertThat(point.tenantId()).isEqualTo(TENANT);
            assertThat(point.deviceId()).isEqualTo(DEVICE);
            assertThat(point.propertyId()).isEqualTo("temperature");
            assertThat(point.value()).isEqualTo("23.5");
            assertThat(point.ts()).isEqualTo(1_700_000_000_000L);
        });
    }

    @Test
    @DisplayName("★ Q8：全部读数都不带点位时，最新值写入器不得被调用（空批短路）")
    void ingestMustNotWriteWhenNoPointReadings() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());
        ReadingObservationDto onlyQuality = new ReadingObservationDto();
        onlyQuality.setDeviceId(DEVICE);
        onlyQuality.setQuality(AvailabilityRules.QUALITY_GOOD);
        onlyQuality.setTs(1_700_000_000_000L);

        service.ingest(req(onlyQuality));

        verify(latestValueWriter, never()).writeAll(any());
        verify(shadowReportedWriter, never()).writeAll(any());
    }

    @Test
    @DisplayName("★ G2：上报读数必须驱动影子 reported —— 一批里同一设备的多个点位合并成一条增量（不是整体覆盖）")
    void ingestMustMergeReportedByPropertyId() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());

        ReadingObservationDto temperature = point(DEVICE, "temperature", "23.5", 1_700_000_000_000L);
        ReadingObservationDto humidity = point(DEVICE, "humidity", "61", 1_700_000_000_100L);
        // 同一点位在一批里重复上报：读数时刻更晚的那条必须赢（批内取最新）
        ReadingObservationDto temperatureNewer = point(DEVICE, "temperature", "24.1", 1_700_000_000_200L);

        service.ingest(req(temperature, humidity, temperatureNewer));

        ArgumentCaptor<List<ShadowReportedUpdate>> captor = ArgumentCaptor.forClass(List.class);
        verify(shadowReportedWriter).writeAll(captor.capture());
        assertThat(captor.getValue()).as("一台设备只发一条增量（不是每个点位一条，避免 N+1 写入）")
            .singleElement()
            .satisfies(update -> {
                assertThat(update.tenantId()).as("租户必须来自设备台账（上报路径没有租户上下文）")
                    .isEqualTo(TENANT);
                assertThat(update.deviceId()).isEqualTo(DEVICE);
                assertThat(update.reported()).containsOnlyKeys("temperature", "humidity");
                assertThat(update.reported()).containsEntry("temperature", "24.1");
                assertThat(update.reported()).containsEntry("humidity", "61");
                assertThat(update.reportTs())
                    .isEqualTo(AvailabilityRules.toLocalDateTime(1_700_000_000_200L));
            });
    }

    @Test
    @DisplayName("★ G2：影子 reported 必须在**事务提交后**才写（与最新值同一时机）")
    void shadowReportedMustBeWrittenAfterCommitOnly() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.ingest(req(point(DEVICE, "temperature", "23.5", 1_700_000_000_000L)));
            verify(shadowReportedWriter, never()).writeAll(any());
        } finally {
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(shadowReportedWriter).writeAll(any());
    }

    @Test
    @DisplayName("★ G2：影子写入器抛异常不得中断上报（上报已落库，接口不得报错）")
    void shadowWriterFailureMustNotBreakIngest() {
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(null);
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
        when(livenessMapper.selectList(any())).thenReturn(List.of());
        doThrow(new IllegalStateException("影子写入失败（模拟）")).when(shadowReportedWriter).writeAll(any());

        int processed = service.ingest(req(point(DEVICE, "temperature", "23.5", 1_700_000_000_000L)));

        assertThat(processed).as("上报处理条数不受影子写入失败影响").isEqualTo(1);
        verify(shadowReportedWriter).writeAll(any());
        // 影子的失败不得牵连同一批的其它派生写入
        verify(latestValueWriter).writeAll(any());
    }

    @Test
    @DisplayName("★ 有效数据上报：首次写入活性行（lastGoodAt=firstObservedAt=ts，周期落库）")
    void goodReadingMustCreateLiveness() {
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(null);

        int processed = service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, T0)));

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<DeviceLiveness> captor = ArgumentCaptor.forClass(DeviceLiveness.class);
        verify(livenessMapper).insert(captor.capture());
        DeviceLiveness row = captor.getValue();
        assertThat(row.getDeviceId()).isEqualTo(DEVICE);
        assertThat(row.getPollIntervalMs()).isEqualTo(5_000);
        assertThat(row.getLastGoodAt()).isEqualTo(T0);
        assertThat(row.getFirstObservedAt()).isEqualTo(T0);
        assertThat(row.getLastObservedAt()).isEqualTo(T0);
        assertThat(row.getOpenOutageId()).isNull();
        verify(outageMapper, never()).closeOutage(anyLong(), any(), any());
    }

    @Test
    @DisplayName("★ 乱序/重放的上报不得把状态拉回去：更早的有效数据只更新 firstObserved，lastGoodAt 保持")
    void staleReadingMustNotMoveStateBackwards() {
        DeviceLiveness existing = liveness(T0.plusMinutes(30), T0, null);
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(existing);

        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, T0)));

        ArgumentCaptor<DeviceLiveness> captor = ArgumentCaptor.forClass(DeviceLiveness.class);
        verify(livenessMapper).reviveAndUpdate(captor.capture());
        assertThat(captor.getValue().getLastGoodAt()).as("更早的数据不得覆盖较新的 lastGoodAt")
            .isEqualTo(T0.plusMinutes(30));
        assertThat(captor.getValue().getFirstObservedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("★ 有效数据到达必须闭合进行中的断档：用**更新前**的起点算时长，并用条件语句清空标记")
    void goodReadingMustCloseOpenOutage() {
        DeviceLiveness existing = liveness(T0, T0, 999L);
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(existing);
        LocalDateTime recovered = T0.plusMinutes(10);

        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, recovered)));

        verify(outageMapper).closeOutage(999L, recovered, 600L);
        // 清空走条件语句（只清「仍是那一条」时）：无条件整行回写会把扫描刚开的新断档抹成孤儿
        verify(livenessMapper).clearOpenOutage(1L, 999L);
        ArgumentCaptor<DeviceLiveness> captor = ArgumentCaptor.forClass(DeviceLiveness.class);
        verify(livenessMapper).reviveAndUpdate(captor.capture());
        assertThat(captor.getValue().getLastGoodAt()).isEqualTo(recovered);
    }

    @Test
    @DisplayName("★ 乱序/重放：有效数据**早于**断档起点时不得闭合断档（否则写出 start>end 的假恢复并清掉标记）")
    void staleGoodReadingMustNotCloseOutage() {
        DeviceLiveness existing = liveness(T0, T0, 999L);
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(existing);

        // 上报时刻比断档起点早 5 分钟：不可能是「恢复」
        service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, T0.minusMinutes(5))));

        verify(outageMapper, never()).closeOutage(anyLong(), any(), any());
        verify(livenessMapper, never()).clearOpenOutage(anyLong(), anyLong());
    }

    @Test
    @DisplayName("非有效数据（BAD/未知质量）只刷新观测时刻，不得推进 lastGoodAt")
    void badReadingMustNotAdvanceLastGood() {
        DeviceLiveness existing = liveness(T0, T0, null);
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(existing);
        LocalDateTime later = T0.plusMinutes(5);

        service.ingest(req(observation(DEVICE, 5_000, "BAD", later),
            observation(DEVICE, 5_000, "WHATEVER", later)));

        ArgumentCaptor<DeviceLiveness> captor = ArgumentCaptor.forClass(DeviceLiveness.class);
        verify(livenessMapper).reviveAndUpdate(captor.capture());
        assertThat(captor.getValue().getLastGoodAt()).isEqualTo(T0);
        assertThat(captor.getValue().getLastObservedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("同一批里同一设备的条数被聚合：只写一次活性行，条数按原始观察计")
    void sameDeviceObservationsMustBeAggregated() {
        when(livenessMapper.selectByDeviceIncludingDeleted(TENANT, DEVICE)).thenReturn(null);

        int processed = service.ingest(req(
            observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, T0),
            observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, T0.plusMinutes(1))));

        assertThat(processed).isEqualTo(2);
        verify(livenessMapper, times(1)).insert(any(DeviceLiveness.class));
        ArgumentCaptor<DeviceLiveness> captor = ArgumentCaptor.forClass(DeviceLiveness.class);
        verify(livenessMapper).insert(captor.capture());
        assertThat(captor.getValue().getLastGoodAt()).isEqualTo(T0.plusMinutes(1));
    }

    @Test
    @DisplayName("设备不存在的上报被丢弃且不计入处理条数（不静默当成有效上报）")
    void unknownDeviceMustBeDropped() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of());

        int processed = service.ingest(req(observation(DEVICE, 5_000, AvailabilityRules.QUALITY_GOOD, T0)));

        assertThat(processed).isZero();
        verify(livenessMapper, never()).insert(any(DeviceLiveness.class));
        verify(livenessMapper, never()).reviveAndUpdate(any(DeviceLiveness.class));
    }

    @Test
    @DisplayName("★ 扫描：为「超时无有效数据」的设备开断档（起点=lastGoodAt，原因码=NO_GOOD_DATA）")
    void scanMustOpenOutageForStaleDevice() {
        when(livenessMapper.selectList(any())).thenReturn(List.of(candidate(5L, T0)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(livenessMapper.markOpenOutage(eq(5L), anyLong())).thenReturn(1);

        int opened = service.scanAndOpenOutages();

        assertThat(opened).isEqualTo(1);
        ArgumentCaptor<OutageEvent> captor = ArgumentCaptor.forClass(OutageEvent.class);
        verify(outageMapper).insert(captor.capture());
        OutageEvent event = captor.getValue();
        assertThat(event.getDeviceId()).isEqualTo(DEVICE);
        assertThat(event.getStartTs()).as("断档起点必须是最后一次有效数据").isEqualTo(T0);
        assertThat(event.getEndTs()).isNull();
        assertThat(event.getDurationSec()).isNull();
        assertThat(event.getReason()).isEqualTo(OutageReason.NO_GOOD_DATA.getCode());
        verify(livenessMapper).markOpenOutage(eq(5L), anyLong());
    }

    @Test
    @DisplayName("★ 多副本并发：另一个副本已开断档（markOpenOutage=0）时撤销本次插入，避免同一段断档记两次")
    void scanMustRollBackWhenAnotherReplicaWonTheRace() {
        when(livenessMapper.selectList(any())).thenReturn(List.of(candidate(5L, T0)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(livenessMapper.markOpenOutage(eq(5L), anyLong())).thenReturn(0);

        int opened = service.scanAndOpenOutages();

        assertThat(opened).isZero();
        ArgumentCaptor<OutageEvent> inserted = ArgumentCaptor.forClass(OutageEvent.class);
        verify(outageMapper).insert(inserted.capture());
        verify(outageMapper).deleteById(inserted.getValue().getId());
    }

    @Test
    @DisplayName("★ 设备已删除/停用：活性行既不得算断档，也**必须被清理**（否则永远占住候选集前段 ⇒ 饥饿）")
    void scanMustCleanUpDevicesThatAreGoneOrDisabled() {
        when(livenessMapper.selectList(any())).thenReturn(List.of(candidate(5L, T0)));

        // 设备已被逻辑删除或停用 ⇒ 批量查设备时查不到（或查到的不是启用态）⇒ 不开断档
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.scanAndOpenOutages()).isZero();

        verify(outageMapper, never()).insert(any(OutageEvent.class));
        verify(livenessMapper, never()).markOpenOutage(anyLong(), anyLong());
        // 关键：清理掉（一次批量删，不是循环逐条），否则候选查询（id 升序 + LIMIT 批次上限）
        // 会被这类行永久占满 ⇒ 其它设备的断档再也不会被发现
        verify(livenessMapper).deleteBatchIds(List.of(5L));
    }

    @Test
    @DisplayName("扫描关闭（enabled=false）：不读库、不开断档")
    void disabledScanMustDoNothing() {
        properties.setEnabled(false);

        assertThat(service.scanAndOpenOutages()).isZero();

        verify(livenessMapper, never()).selectList(any());
        verify(livenessMapper, never()).selectNow();
    }

    @Test
    @DisplayName("查询：设备不存在直接报「不存在」（跨租户访问同样查不到，不泄露存在性）")
    void queryMustRejectMissingDevice() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(null);

        assertThatThrownBy(() -> service.query(DEVICE, T0, T0.plusHours(1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
    }

    @Test
    @DisplayName("★ 查询：窗口给反必须报错，不静默交换（否则参数笔误会得到一份看起来正常的报表）")
    void queryMustRejectReversedWindow() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());

        assertThatThrownBy(() -> service.query(DEVICE, T0.plusHours(1), T0))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("统计窗口起点必须早于终点");
    }

    @Test
    @DisplayName("★ 查询：可用率来自**精确聚合**（10h 窗口内 1h 断档 ⇒ 0.9，不达标），并回填断档明细")
    void queryMustComputeAvailability() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(livenessMapper.selectOne(any())).thenReturn(liveness(T0, T0, null));
        // 汇总来自聚合（SUM 在 MySQL 里可能是 BigDecimal ⇒ 顺便覆盖 toLong 的两种取值）
        when(outageMapper.summarizeInWindow(eq(TENANT), eq(DEVICE), any(), any(), any()))
            .thenReturn(Map.of("outageCount", 1L, "outageSeconds", new BigDecimal("3600"),
                "longestOutageSeconds", 3_600L));
        when(outageMapper.selectList(any())).thenReturn(List.of(
            outage(1L, T0.plusHours(2), T0.plusHours(3), 3_600L)));

        AvailabilityResp resp = TenantContext.executeWithTenant(TENANT,
            () -> service.query(DEVICE, T0, T0.plusHours(10)));

        assertThat(resp.getDeviceId()).isEqualTo(DEVICE);
        assertThat(resp.getWindowSeconds()).isEqualTo(10 * 3600L);
        assertThat(resp.getOutageSeconds()).isEqualTo(3_600L);
        assertThat(resp.getAvailability()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(resp.getMeetsTarget()).isFalse();
        assertThat(resp.getTargetAvailability()).isEqualByComparingTo(AvailabilityRules.TARGET_AVAILABILITY);
        assertThat(resp.getOutages()).hasSize(1);
        assertThat(resp.getOutages().getFirst().getOngoing()).isFalse();
        assertThat(resp.getTruncated()).isFalse();
    }

    @Test
    @DisplayName("★ 维护窗口：查询必须把维护时长与「维护内断档」都剔除，并回显窗口")
    void queryMustExcludeMaintenanceWindows() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(livenessMapper.selectOne(any())).thenReturn(liveness(T0, T0, null));
        // 10h 窗口里有 2h 维护；聚合里的断档已由 SQL 剔除维护内部分（计入 0s、剔除 3_600s）
        when(maintenanceWindowMapper.sumMaintenanceSecondsInWindow(eq(TENANT), eq(DEVICE), any(), any(),
            any())).thenReturn(7_200L);
        // 原始断档 3600s，其中 3600s 落在维护里 ⇒ Java 侧相减后计入 0s（剔除在 Java 做：两段 SQL 都保持简单可解析）
        when(outageMapper.summarizeInWindow(eq(TENANT), eq(DEVICE), any(), any(), any()))
            .thenReturn(Map.of("outageCount", 1L, "outageSeconds", 3_600L, "longestOutageSeconds", 3_600L));
        when(maintenanceWindowMapper.sumOutageInMaintenanceSeconds(eq(TENANT), eq(DEVICE), any(), any(),
            any())).thenReturn(3_600L);
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(7L);
        window.setStartTs(T0.plusHours(1));
        window.setEndTs(T0.plusHours(3));
        window.setSource("MANUAL");
        window.setReason("夜间停产");
        when(maintenanceWindowMapper.listOverlappingInWindow(eq(TENANT), eq(DEVICE), any(), any(),
            anyInt())).thenReturn(List.of(window));

        AvailabilityResp resp = TenantContext.executeWithTenant(TENANT,
            () -> service.query(DEVICE, T0, T0.plusHours(10)));

        assertThat(resp.getMaintenanceSeconds()).as("维护时长（分母排除）").isEqualTo(7_200L);
        assertThat(resp.getEffectiveWindowSeconds()).as("统计总时长 = 10h − 2h").isEqualTo(8 * 3_600L);
        assertThat(resp.getOutageInMaintenanceSeconds()).as("维护内断档（分子排除）").isEqualTo(3_600L);
        assertThat(resp.getOutageSeconds()).isZero();
        assertThat(resp.getAvailability()).isEqualByComparingTo(new BigDecimal("1.000000"));
        assertThat(resp.getMaintenanceWindows()).singleElement()
            .satisfies(dto -> assertThat(dto.getSource()).isEqualTo("MANUAL"));
    }

    @Test
    @DisplayName("★ A11：明细被截断（只返回上限条数）时，汇总仍取**精确聚合**值（次数不被截成 200）")
    void queryMustKeepExactSummaryWhenDetailsTruncated() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(livenessMapper.selectOne(any())).thenReturn(liveness(T0, T0, null));
        // 精确聚合：201 条、合计 6030 秒（明细只可能返回 200 条）
        when(outageMapper.summarizeInWindow(eq(TENANT), eq(DEVICE), any(), any(), any()))
            .thenReturn(Map.of("outageCount", 201L, "outageSeconds", 6_030L,
                "longestOutageSeconds", 30L));
        List<OutageEvent> details = new ArrayList<>();
        for (int i = 0; i < AvailabilityRules.MAX_OUTAGE_ROWS; i++) {
            details.add(outage((long) i, T0.plusMinutes(i), T0.plusMinutes(i).plusSeconds(30), 30L));
        }
        when(outageMapper.selectList(any())).thenReturn(details);

        AvailabilityResp resp = TenantContext.executeWithTenant(TENANT,
            () -> service.query(DEVICE, T0, T0.plusHours(10)));

        assertThat(resp.getOutages()).hasSize(AvailabilityRules.MAX_OUTAGE_ROWS);
        assertThat(resp.getTruncated()).isTrue();
        assertThat(resp.getOutageCount()).as("次数是精确值，不是明细条数").isEqualTo(201);
        assertThat(resp.getOutageSeconds()).as("断档合计来自精确聚合，不因截断变小").isEqualTo(6_030L);
        assertThat(resp.getAvailability()).isEqualByComparingTo(new BigDecimal("0.832500"));
    }

    @Test
    @DisplayName("★ 查询：租户来自 TenantProvider（真实请求链路：IdentityContext → provider，TenantContext 为空）")
    void queryMustResolveTenantFromProviderOnRealRequestPath() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(livenessMapper.selectOne(any())).thenReturn(liveness(T0, T0, null));
        when(outageMapper.summarizeInWindow(eq(TENANT), eq(DEVICE), any(), any(), any()))
            .thenReturn(Map.of("outageCount", 0L, "outageSeconds", 0L, "longestOutageSeconds", 0L));
        when(tenantProvider.getCurrentTenantId()).thenReturn(java.util.Optional.of(TENANT));

        // 刻意**不**用 executeWithTenant：这正是网关注入身份后的真实形状（TenantContext 为空）
        AvailabilityResp resp = service.query(DEVICE, T0, T0.plusHours(1));

        assertThat(resp.getDeviceId()).isEqualTo(DEVICE);
    }

    @Test
    @DisplayName("★ 查询：缺少租户上下文时直接报错（不猜租户、不查全表）")
    void queryMustRejectMissingTenantContext() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());

        assertThatThrownBy(() -> service.query(DEVICE, T0, T0.plusHours(1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("缺少租户上下文");
    }

    @Test
    @DisplayName("查询未给窗口：终点取数据库时钟、起点按默认窗口推算")
    void queryMustDeriveDefaultWindowFromDatabaseClock() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0);
        when(livenessMapper.selectOne(any())).thenReturn(null);
        when(outageMapper.selectList(any())).thenReturn(List.of());

        when(outageMapper.summarizeInWindow(eq(TENANT), eq(DEVICE), any(), any(), any()))
            .thenReturn(Map.of("outageCount", 0L, "outageSeconds", 0L, "longestOutageSeconds", 0L));
        AvailabilityResp resp = TenantContext.executeWithTenant(TENANT, () -> service.query(DEVICE, null, null));

        assertThat(resp.getTo()).isEqualTo(T0);
        assertThat(resp.getFrom()).isEqualTo(T0.minusHours(properties.getDefaultWindowHours()));
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE);
        device.setTenantId(TENANT);
        return device;
    }

    private static DeviceLiveness liveness(LocalDateTime lastGoodAt, LocalDateTime firstObservedAt,
                                           Long openOutageId) {
        DeviceLiveness row = new DeviceLiveness();
        row.setId(1L);
        row.setDeviceId(DEVICE);
        row.setPollIntervalMs(5_000);
        row.setLastGoodAt(lastGoodAt);
        row.setFirstObservedAt(firstObservedAt);
        row.setLastObservedAt(lastGoodAt);
        row.setOpenOutageId(openOutageId);
        row.setTenantId(TENANT);
        return row;
    }

    private static DeviceLiveness candidate(Long id, LocalDateTime lastGoodAt) {
        DeviceLiveness row = liveness(lastGoodAt, lastGoodAt, null);
        row.setId(id);
        return row;
    }

    private static OutageEvent outage(Long id, LocalDateTime start, LocalDateTime end, Long durationSec) {
        OutageEvent event = new OutageEvent();
        event.setId(id);
        event.setDeviceId(DEVICE);
        event.setStartTs(start);
        event.setEndTs(end);
        event.setDurationSec(durationSec);
        event.setReason(OutageReason.NO_GOOD_DATA.getCode());
        event.setTenantId(TENANT);
        return event;
    }

    private static ReadingObservationDto observation(Long deviceId, Integer pollIntervalMs, String quality,
                                                     LocalDateTime ts) {
        ReadingObservationDto observation = new ReadingObservationDto();
        observation.setDeviceId(deviceId);
        observation.setPollIntervalMs(pollIntervalMs);
        observation.setQuality(quality);
        observation.setTs(ts.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli());
        return observation;
    }

    /** 带点位与值的读数（影子/最新值都只认这种形态）。 */
    private static ReadingObservationDto point(Long deviceId, String propertyId, String value, long ts) {
        ReadingObservationDto observation = new ReadingObservationDto();
        observation.setDeviceId(deviceId);
        observation.setPropertyId(propertyId);
        observation.setValue(value);
        observation.setQuality(AvailabilityRules.QUALITY_GOOD);
        observation.setTs(ts);
        return observation;
    }

    private static ReadingIngestReq req(ReadingObservationDto... observations) {
        ReadingIngestReq req = new ReadingIngestReq();
        req.setItems(new ArrayList<>(List.of(observations)));
        return req;
    }
}
