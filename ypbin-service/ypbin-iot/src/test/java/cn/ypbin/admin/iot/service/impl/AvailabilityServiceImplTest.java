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
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        deviceMapper = mock(IotDeviceMapper.class);
        properties = new AvailabilityProperties();
        service = new AvailabilityServiceImpl(livenessMapper, outageMapper, deviceMapper, properties);
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device()));
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
    @DisplayName("★ 查询：按窗口现算可用率（10h 窗口内 1h 断档 ⇒ 0.9，不达标），并回填断档明细")
    void queryMustComputeAvailability() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(livenessMapper.selectOne(any())).thenReturn(liveness(T0, T0, null));
        when(outageMapper.selectList(any())).thenReturn(List.of(
            outage(1L, T0.plusHours(2), T0.plusHours(3), 3_600L)));

        AvailabilityResp resp = service.query(DEVICE, T0, T0.plusHours(10));

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
    @DisplayName("★ 查询：断档明细超过上限时置 truncated（可用率会偏低断档 ⇒ 客户端必须能看出截断）")
    void queryMustFlagTruncatedOutages() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0.plusHours(10));
        when(livenessMapper.selectOne(any())).thenReturn(liveness(T0, T0, null));
        List<OutageEvent> many = new ArrayList<>();
        for (int i = 0; i <= AvailabilityRules.MAX_OUTAGE_ROWS; i++) {
            many.add(outage((long) i, T0.plusMinutes(i), T0.plusMinutes(i).plusSeconds(30), 30L));
        }
        when(outageMapper.selectList(any())).thenReturn(many);

        AvailabilityResp resp = service.query(DEVICE, T0, T0.plusHours(10));

        assertThat(resp.getTruncated()).isTrue();
        assertThat(resp.getOutages()).hasSize(AvailabilityRules.MAX_OUTAGE_ROWS);
    }

    @Test
    @DisplayName("查询未给窗口：终点取数据库时钟、起点按默认窗口推算")
    void queryMustDeriveDefaultWindowFromDatabaseClock() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device());
        when(livenessMapper.selectNow()).thenReturn(T0);
        when(livenessMapper.selectOne(any())).thenReturn(null);
        when(outageMapper.selectList(any())).thenReturn(List.of());

        AvailabilityResp resp = service.query(DEVICE, null, null);

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

    private static ReadingIngestReq req(ReadingObservationDto... observations) {
        ReadingIngestReq req = new ReadingIngestReq();
        req.setItems(new ArrayList<>(List.of(observations)));
        return req;
    }
}
