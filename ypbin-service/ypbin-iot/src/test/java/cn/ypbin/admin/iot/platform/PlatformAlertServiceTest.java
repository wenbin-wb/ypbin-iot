/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotPlatformAlert;
import cn.ypbin.admin.iot.mapper.IotPlatformAlertMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 平台自告警服务用例（看板 #10，设计 §8）。
 *
 * <p>重点锁死"做错了也看不出来"的几条：</p>
 * <ol>
 *   <li>**指标取不到 ⇒ 不改动告警状态**（既不开单也不收口）——若此时收口，
 *       会把真实告警因"指标抖动"误标为已恢复，那是比不告警更糟的错误信息；</li>
 *   <li>**恢复必须把 `active_dedup_key` 置 NULL**（靠 mapper 的 SQL 保证）——
 *       忘了置 NULL 会让"问题第二次发生"时**不再告警**（静默失效）；</li>
 *   <li>**未达连续轮次门槛时为 PENDING，达门槛才 FIRING**（抗抖动）；</li>
 *   <li>**只落库、不通知**（一期口径）——不new任何通知调用。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class PlatformAlertServiceTest {

    private MeterRegistry registry;

    private IotPlatformAlertMapper alertMapper;

    private PlatformAlertNotifier notifier;

    private PlatformAlertService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        alertMapper = mock(IotPlatformAlertMapper.class);
        notifier = mock(PlatformAlertNotifier.class);
        service = new PlatformAlertService(registry, alertMapper, notifier);
    }

    /** 注册一个"评估器停摆"的 gauge（lag 很大）。 */
    private void registerStalledLag(long lagMs) {
        Gauge.builder(PlatformMetrics.METRIC_EVALUATE_LAG, () -> (double) lagMs)
            .register(registry);
    }

    private void registerCounters(long roundFailed, long notifyFailed) {
        Counter.builder(PlatformMetrics.METRIC_ROUND_FAILED)
            .register(registry).increment(roundFailed);
        Counter.builder(PlatformMetrics.METRIC_NOTIFY_FAILED)
            .register(registry).increment(notifyFailed);
    }

    @Test
    @DisplayName("指标齐全且全部健康 ⇒ 不开单、不收口（本来就没有活动告警）")
    void healthySnapshotMustNotWriteAnything() {
        registerStalledLag(1_000L);
        registerCounters(0L, 0L);
        when(alertMapper.selectOne(any())).thenReturn(null);

        Map<PlatformHealthRule, PlatformHealthVerdict> verdicts = service.evaluateOnce();

        assertThat(verdicts.get(PlatformHealthRule.EVALUATOR_STALLED).state())
            .isEqualTo(PlatformHealthState.HEALTHY);
        verify(alertMapper, never()).upsertActive(any());
        verify(alertMapper, never()).resolve(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("🔴 指标缺失（gauge 未注册）⇒ 判 UNKNOWN，且**不改动库**（不收口真实告警）")
    void unknownMustNotTouchStorage() {
        // 刻意不注册任何指标
        Map<PlatformHealthRule, PlatformHealthVerdict> verdicts = service.evaluateOnce();

        assertThat(verdicts.get(PlatformHealthRule.EVALUATOR_STALLED).state())
            .isEqualTo(PlatformHealthState.UNKNOWN);
        verify(alertMapper, never()).upsertActive(any());
        verify(alertMapper, never())
            .resolve(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("评估器停摆（lag 超阈值）⇒ 开单；连续两轮后升 FIRING")
    void stalledMustOpenThenFiring() {
        registerStalledLag(120_000L);
        when(alertMapper.selectOne(any())).thenReturn(null);

        // 第 1 轮：连续 1 次 < 门槛(2) ⇒ PENDING（不发通知）
        service.evaluateOnce();
        verify(notifier, never()).notifyFiring(any(), anyString(), anyString());
        // 第 2 轮：连续 2 次 ⇒ FIRING（升 FIRING 当轮发一次）
        service.evaluateOnce();
        verify(notifier, times(1)).notifyFiring(
            eq(PlatformHealthRule.EVALUATOR_STALLED), anyString(), anyString());

        ArgumentCaptor<IotPlatformAlert> captor =
            ArgumentCaptor.forClass(IotPlatformAlert.class);
        verify(alertMapper, times(2)).upsertActive(captor.capture());
        assertThat(captor.getAllValues().get(0).getState())
            .as("首次异常先 PENDING，避免单轮抖动就报警").isEqualTo(PlatformAlertState.PENDING.getCode());
        assertThat(captor.getAllValues().get(1).getState())
            .isEqualTo(PlatformAlertState.FIRING.getCode());
    }

    @Test
    @DisplayName("🔴 开单时必须带 active_dedup_key（去重的库级保证；漏了会重复开单）")
    void openMustCarryActiveDedupKey() {
        registerStalledLag(120_000L);
        when(alertMapper.selectOne(any())).thenReturn(null);

        service.evaluateOnce();

        ArgumentCaptor<IotPlatformAlert> captor =
            ArgumentCaptor.forClass(IotPlatformAlert.class);
        verify(alertMapper).upsertActive(captor.capture());
        IotPlatformAlert saved = captor.getValue();
        assertThat(saved.getActiveDedupKey())
            .as("active_dedup_key 为空则该行不受唯一键约束 ⇒ 每轮都新开一条 ⇒ 刷屏")
            .isNotBlank();
        assertThat(saved.getRuleCode()).isEqualTo(PlatformHealthRule.EVALUATOR_STALLED.getCode());
        assertThat(saved.getSummary()).isNotBlank();
        assertThat(saved.getMetricSnapshot()).contains("observed");
    }

    @Test
    @DisplayName("🔴 开单必须带 tenant_id（表列 NOT NULL ⇒ 留空则整条 INSERT 被库拒绝，告警一条都落不了）")
    void openMustCarryTenantId() {
        registerStalledLag(120_000L);
        when(alertMapper.selectOne(any())).thenReturn(null);

        service.evaluateOnce();

        ArgumentCaptor<IotPlatformAlert> captor =
            ArgumentCaptor.forClass(IotPlatformAlert.class);
        verify(alertMapper).upsertActive(captor.capture());
        assertThat(captor.getValue().getTenantId())
            .as("tenant_id 留空 ⇒ MySQL 报 Column 'tenant_id' cannot be null"
                + "（2026-10-08 dev 真实 FIRING 时实测；runIgnore 下拦截器不补值）")
            .isEqualTo(PlatformAlertProperties.PLATFORM_TENANT_ID);
    }

    @Test
    @DisplayName("恢复正常且有活动告警 ⇒ 收口（且由 mapper 的 SQL 负责置 NULL 去重键）")
    void recoveredMustResolveActiveAlert() {
        registerStalledLag(1_000L);
        registerCounters(0L, 0L);
        IotPlatformAlert active = new IotPlatformAlert();
        active.setId(99L);
        when(alertMapper.selectOne(any())).thenReturn(active);

        service.evaluateOnce();

        verify(alertMapper).resolve(eq(99L),
            eq(PlatformAlertState.RESOLVED.getCode()),
            anyString(), any());
        // 恢复必发通知（设计点名）
        verify(notifier).notifyResolved(
            eq(PlatformHealthRule.EVALUATOR_STALLED), anyString());
    }

    @Test
    @DisplayName("恢复正常但没有活动告警 ⇒ 不调 resolve（避免无谓写库）")
    void recoveredWithoutActiveMustNotResolve() {
        registerStalledLag(1_000L);
        registerCounters(0L, 0L);
        when(alertMapper.selectOne(any())).thenReturn(null);

        service.evaluateOnce();

        verify(alertMapper, never()).resolve(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("计数增长被识别（round.failed 从 0 涨到 1）⇒ 开单")
    void counterGrowthMustOpen() {
        registerStalledLag(1_000L);
        registerCounters(0L, 0L);
        when(alertMapper.selectOne(any())).thenReturn(null);
        service.evaluateOnce(); // 建立基线

        // 第二个 registry 模拟计数增长：直接在同一 registry 上再加 1
        Counter.builder(PlatformMetrics.METRIC_ROUND_FAILED).register(registry).increment(1);

        service.evaluateOnce();

        ArgumentCaptor<IotPlatformAlert> captor =
            ArgumentCaptor.forClass(IotPlatformAlert.class);
        verify(alertMapper, times(1)).upsertActive(captor.capture());
        assertThat(captor.getValue().getRuleCode())
            .isEqualTo(PlatformHealthRule.EVALUATOR_ROUND_FAILED.getCode());
    }

    @Test
    @DisplayName("约定式断言：一期「只落库不通知」——服务不依赖任何通知组件")
    void mustNotNotifyInPhaseOne() {
        // 通过反射确认类上没有注入通知相关字段（设计 §2.1：一期只落库）
        for (Field field : PlatformAlertService.class.getDeclaredFields()) {
            String type = field.getType().getSimpleName();
            assertThat(type)
                .as("一期只落库不通知；出现通知组件（%s）说明口径被改，须先裁定收件人", type)
                .doesNotContain("Notify")
                .doesNotContain("Mail")
                .doesNotContain("Dispatcher");
        }
    }
}
