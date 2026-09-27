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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyStatus;
import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertScopeType;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.enums.AlertTriggerMode;
import cn.ypbin.admin.iot.enums.AlertValueType;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPointResp;
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 评估器的**端到端单轮/多轮用例**（设计 §3.1 T1–T10、§3.2 D1–D8、§3.3 U1/U3/U4、§3.4 S1/S2/S4/S5、§3.5 N6）。
 *
 * <p>做法：把 6 个 Mapper 换成**内存假库**（实例集合 + 通知集合 + 假时钟 + 假 Redis 读数），
 * 其余全部是**生产实现**（候选解析、判定、状态机、静默、通知意图、批量写）。因此本用例覆盖的是
 * 「评估器在给定数据下真的做了那件事」，而不是「某个方法被调用过」。</p>
 *
 * <p><b>为什么假库而不是真库</b>：真库 IT 需要容器（本机低配不允许跑容器 IT，以 CI 为准），
 * 而状态机与抖动抑制的判据是**序列语义**，用假库可以把「第几轮读什么值」写得完全确定——
 * 这恰恰是真库最难稳定复现的部分。真库侧由 CI 的 {@code -Pit} 与生产演示承担。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertTenantEvaluatorTest {

    static {
        AlertMybatisTestSupport.initMetadata(IotDevice.class, DeviceLiveness.class, MaintenanceWindow.class,
            IotAlertInstance.class, IotAlertRule.class);
    }

    private static final long TENANT = 1L;

    private static final long DEVICE = 9L;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    /** 采集周期（毫秒）：陈旧判定 = 3 × 15000 = 45000。 */
    private static final int POLL_INTERVAL_MS = 15_000;

    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(T0);

    private final Map<Long, Map<String, AlertLatestValueReader.AlertSample>> latest = new LinkedHashMap<>();

    private final List<IotAlertInstance> instances = new ArrayList<>();

    private final List<IotAlertNotification> notifications = new ArrayList<>();

    private final List<MaintenanceWindow> windows = new ArrayList<>();

    private boolean redisDown;

    private AlertProperties properties;

    private SimpleMeterRegistry registry;

    private IotAlertInstanceMapper instanceMapper;

    private IotAlertNotificationMapper notificationMapper;

    private DeviceLivenessMapper livenessMapper;

    private IotDeviceMapper deviceMapper;

    private MaintenanceWindowMapper windowMapper;

    private AlertLatestValueReader reader;

    private TimeSeriesQueryService timeSeriesQueryService;

    private AlertTenantEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new AlertProperties();
        registry = new SimpleMeterRegistry();
        AlertMetrics metrics = new AlertMetrics(registry);

        deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(deviceMapper.selectPage(any(), any())).thenReturn(
            new Page<IotDevice>(1, 500).setRecords(List.of(device())).setTotal(1));

        livenessMapper = mock(DeviceLivenessMapper.class);
        when(livenessMapper.selectNow()).thenAnswer(invocation -> clock.get());
        when(livenessMapper.selectList(any())).thenReturn(List.of(liveness()));

        windowMapper = mock(MaintenanceWindowMapper.class);
        when(windowMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(windows));

        instanceMapper = mock(IotAlertInstanceMapper.class);
        when(instanceMapper.selectActiveInTenant()).thenAnswer(invocation -> activeInstances());
        when(instanceMapper.insertBatch(anyList())).thenAnswer(invocation -> {
            List<IotAlertInstance> inserted = invocation.getArgument(0);
            instances.addAll(inserted);
            return inserted.size();
        });
        when(instanceMapper.batchUpdate(anyList())).thenAnswer(invocation -> {
            List<IotAlertInstance> updated = invocation.getArgument(0);
            for (IotAlertInstance item : updated) {
                replace(item);
            }
            return updated.size();
        });
        when(instanceMapper.deletePendingByIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            instances.removeIf(item -> ids.contains(item.getId()));
            return ids.size();
        });

        notificationMapper = mock(IotAlertNotificationMapper.class);
        when(notificationMapper.insertBatchIgnore(anyList())).thenAnswer(invocation -> {
            List<IotAlertNotification> rows = invocation.getArgument(0);
            notifications.addAll(rows);
            return rows.size();
        });

        reader = mock(AlertLatestValueReader.class);
        when(reader.readLatest(anyLong(), anyList())).thenAnswer(invocation -> {
            if (redisDown) {
                throw new AlertEvaluationException("模拟 Redis 不可用");
            }
            return latest;
        });

        timeSeriesQueryService = mock(TimeSeriesQueryService.class);

        evaluator = new AlertTenantEvaluator(new AlertCandidateResolver(deviceMapper), reader,
            new AlertNotifyPlanner(properties), metrics, properties, instanceMapper, notificationMapper,
            livenessMapper, new AlertSilencePolicy(windowMapper), timeSeriesQueryService);
    }

    // ---------------------------------------------------------------- 夹具

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE);
        device.setTenantId(TENANT);
        device.setProductId(77L);
        device.setDeviceName("演示设备");
        device.setDeviceCode("demo-dev-curve");
        return device;
    }

    private static DeviceLiveness liveness() {
        DeviceLiveness liveness = new DeviceLiveness();
        liveness.setDeviceId(DEVICE);
        liveness.setPollIntervalMs(POLL_INTERVAL_MS);
        return liveness;
    }

    /** 一台设备级规则（默认「连续 3 次」，渠道站内信+邮件，收件人 1001 / ops@example.com）。 */
    private static IotAlertRule rule(String mode, int threshold) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(7L);
        rule.setTenantId(TENANT);
        rule.setRuleName("演示规则");
        rule.setScopeType(AlertScopeType.DEVICE.getCode());
        rule.setScopeDeviceId(DEVICE);
        rule.setSeverity(AlertSeverity.WARNING.getCode());
        rule.setEnabled(true);
        rule.setTriggerMode(mode);
        rule.setTriggerThreshold(threshold);
        rule.setPendingTtlSec(300);
        rule.setRepeatIntervalSec(1800);
        rule.setNotifyChannels("INBOX,EMAIL");
        rule.setNotifyTargets("1001,ops@example.com");
        rule.setCreateUser(42L);
        return rule;
    }

    private static IotAlertRulePoint point(String operator, String threshold) {
        IotAlertRulePoint point = new IotAlertRulePoint();
        point.setId(11L);
        point.setPropertyId("temperature");
        point.setOperator(operator);
        point.setThreshold(new BigDecimal(threshold));
        point.setValueType(AlertValueType.NUMERIC.getCode());
        return point;
    }

    private Map<Long, List<IotAlertRulePoint>> pointsOf(IotAlertRule rule, IotAlertRulePoint point) {
        return Map.of(rule.getId(), List.of(point));
    }

    /** 写入「当前最新值」。 */
    private void latest(String value, String quality, Long ts) {
        latest.put(DEVICE, Map.of("temperature",
            new AlertLatestValueReader.AlertSample("temperature", value, quality, ts)));
    }

    private void latestNow(String value) {
        latest(value, AlertRules.QUALITY_GOOD, clock.get().atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli());
    }

    private List<IotAlertInstance> activeInstances() {
        List<IotAlertInstance> active = new ArrayList<>();
        for (IotAlertInstance item : instances) {
            if (item.getActiveDedupKey() != null) {
                active.add(item);
            }
        }
        return active;
    }

    private void replace(IotAlertInstance updated) {
        for (int index = 0; index < instances.size(); index++) {
            if (instances.get(index).getId().equals(updated.getId())) {
                instances.set(index, updated);
                return;
            }
        }
        instances.add(updated);
    }

    private void seedActive(AlertState state) {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(900L);
        instance.setTenantId(TENANT);
        instance.setRuleId(7L);
        instance.setDeviceId(DEVICE);
        instance.setPropertyId("temperature");
        instance.setDedupKey(AlertRules.dedupKey(7L, DEVICE, "temperature"));
        instance.setActiveDedupKey(instance.getDedupKey());
        instance.setSeverity(AlertSeverity.WARNING.getCode());
        instance.setState(state.getCode());
        instance.setConsecutiveCount(3);
        instance.setTriggerValue("35");
        instance.setThresholdSnapshot("> 30");
        instance.setStartTs(T0.minusMinutes(10));
        instance.setFiringTs(T0.minusMinutes(9));
        instance.setNotifyCount(0);
        instances.add(instance);
    }

    private AlertTenantEvaluator.TenantOutcome evaluate(IotAlertRule rule, IotAlertRulePoint point) {
        return evaluator.evaluateTenant(TENANT, List.of(rule), pointsOf(rule, point));
    }

    private double counter(String name) {
        return registry.find(name).counter() == null ? 0d : registry.find(name).counter().count();
    }

    // ---------------------------------------------------------------- T 系列

    @Test
    @DisplayName("T1 IMMEDIATE + GT 30 读到 35 ⇒ 建 FIRING 实例并生成首次通知")
    void t1ImmediateFiresAndNotifies() {
        latestNow("35");
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0),
            point("GT", "30"));

        assertThat(outcome.fired()).isEqualTo(1);
        assertThat(instances).hasSize(1);
        IotAlertInstance created = instances.get(0);
        assertThat(created.getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(created.getActiveDedupKey()).isEqualTo("7:9:temperature");
        assertThat(created.getTriggerValue()).isEqualTo("35");
        assertThat(created.getThresholdSnapshot()).isEqualTo("> 30");
        assertThat(created.getSeverity()).isEqualTo(AlertSeverity.WARNING.getCode());
        assertThat(created.getFiringTs()).isNotNull();
        assertThat(created.getCreateUser()).isEqualTo(42L);
        // 通知：站内信 + 邮件各一条，事件为 FIRING
        assertThat(notifications).hasSize(2);
        assertThat(notifications).extracting(IotAlertNotification::getChannel)
            .containsExactlyInAnyOrder(AlertChannel.INBOX.getCode(), AlertChannel.EMAIL.getCode());
        assertThat(notifications).allSatisfy(row -> {
            assertThat(row.getEvent()).isEqualTo(AlertNotifyEvent.FIRING.getCode());
            assertThat(row.getNotifyStatus()).isEqualTo(AlertNotifyStatus.PENDING.getCode());
            assertThat(row.getTenantId()).isEqualTo(TENANT);
        });
        assertThat(counter(AlertMetrics.METRIC_TRIGGERED)).isEqualTo(1d);
        assertThat(created.getNotifyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 GT 30 读到 30 ⇒ 不触发（边界值）")
    void t2BoundaryDoesNotFire() {
        latestNow("30");
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0),
            point("GT", "30"));
        assertThat(outcome.fired()).isZero();
        assertThat(instances).isEmpty();
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("T4 读到 abc ⇒ 不可判定：不触发、不恢复、计入 skipped_non_numeric")
    void t4NonNumeric() {
        latestNow("abc");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(instances).isEmpty();
        assertThat(counter("iot.alert.evaluate.skipped_non_numeric")).isEqualTo(1d);
    }

    @Test
    @DisplayName("T5 质量位非 GOOD ⇒ 不可判定（skipped_bad_quality）")
    void t5BadQuality() {
        latest("35", "BAD", null);
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(instances).isEmpty();
        assertThat(counter("iot.alert.evaluate.skipped_bad_quality")).isEqualTo(1d);
    }

    @Test
    @DisplayName("T6 读数陈旧 ⇒ 不可判定（skipped_stale），且**已有的 FIRING 不得被改成 RESOLVED**")
    void t6StaleDoesNotResolveFiring() {
        seedActive(AlertState.FIRING);
        Long staleTs = clock.get().minusMinutes(10)
            .atZone(AvailabilityRules.PLATFORM_ZONE).toInstant()
            .toEpochMilli();
        latest("35", AlertRules.QUALITY_GOOD, staleTs);
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0),
            point("GT", "30"));
        assertThat(outcome.resolved()).isZero();
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(instances.get(0).getActiveDedupKey()).isNotNull();
        assertThat(counter("iot.alert.evaluate.skipped_stale")).isEqualTo(1d);
    }

    @Test
    @DisplayName("T7 FIRING 中读到合格的未越界值 25 ⇒ RESOLVED（去重键释放）+ 恢复通知一条")
    void t7InRangeResolves() {
        seedActive(AlertState.FIRING);
        clock.set(T0.plusMinutes(5));
        latestNow("25");
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0),
            point("GT", "30"));

        assertThat(outcome.resolved()).isEqualTo(1);
        IotAlertInstance resolved = instances.get(0);
        assertThat(resolved.getState()).isEqualTo(AlertState.RESOLVED.getCode());
        assertThat(resolved.getResolvedTs()).isNotNull();
        assertThat(resolved.getReason()).isEqualTo(AlertReason.RECOVERED.getCode());
        // 去重键释放：同一键此后可以再开一条（「清除 ≠ 删除」的前提）
        assertThat(resolved.getActiveDedupKey()).isNull();
        assertThat(resolved.getDedupKey()).isEqualTo("7:9:temperature");
        assertThat(notifications).extracting(IotAlertNotification::getEvent)
            .containsOnly(AlertNotifyEvent.RESOLVED.getCode());
        assertThat(counter(AlertMetrics.METRIC_RESOLVED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("T8 Redis 读失败 ⇒ 本轮跳过判定：保持 FIRING、不发通知、不产生恢复")
    void t8RedisFailureKeepsFiring() {
        seedActive(AlertState.FIRING);
        redisDown = true;
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0),
            point("GT", "30"));

        assertThat(outcome.redisFailed()).isTrue();
        assertThat(outcome.resolved()).isZero();
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(instances.get(0).getActiveDedupKey()).isNotNull();
        assertThat(notifications).isEmpty();
        assertThat(counter(AlertMetrics.METRIC_REDIS_FAILED)).isEqualTo(1d);
        // 关键：一次状态写都没发生（没有把「读不到」当成「不越界」）
        verify(instanceMapper, times(0)).batchUpdate(anyList());
    }

    @Test
    @DisplayName("T9 时序库查询失败（持续模式窗口校验）⇒ 保持 PENDING，计入 timeseries_failed")
    void t9TimeseriesFailureKeepsPending() {
        IotAlertRule rule = rule(AlertTriggerMode.DURATION.getCode(), 60);
        IotAlertRulePoint point = point("GT", "30");
        latestNow("35");
        evaluate(rule, point);
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.PENDING.getCode());

        // 到点后查窗口：时序库不可用（抛业务异常）
        clock.set(T0.plusSeconds(75));
        latestNow("35");
        when(timeSeriesQueryService.query(anyLong(), any()))
            .thenThrow(new RuntimeException("模拟时序库未启用"));
        evaluate(rule, point);

        assertThat(instances.get(0).getState()).isEqualTo(AlertState.PENDING.getCode());
        assertThat(counter(AlertMetrics.METRIC_TIMESERIES_FAILED)).isEqualTo(1d);
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("D7 持续模式：窗口内每个点都越界 ⇒ 触发，且触发时刻取「满足 T 秒那一刻」")
    void durationFiresWithWindowVerification() {
        IotAlertRule rule = rule(AlertTriggerMode.DURATION.getCode(), 60);
        IotAlertRulePoint point = point("GT", "30");
        latestNow("35");
        evaluate(rule, point);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.PENDING.getCode());

        clock.set(T0.plusSeconds(75));
        latestNow("35");
        long startMs = T0.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
        when(timeSeriesQueryService.query(anyLong(), any())).thenReturn(List.of(
            new TimeSeriesPointResp(startMs + 1_000, "35", AlertRules.QUALITY_GOOD),
            new TimeSeriesPointResp(startMs + 30_000, "36", AlertRules.QUALITY_GOOD),
            new TimeSeriesPointResp(startMs + 60_000, "40", AlertRules.QUALITY_GOOD)));
        evaluate(rule, point);

        IotAlertInstance promoted = instances.get(0);
        assertThat(promoted.getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(promoted.getFiringTs()).isEqualTo(T0.plusSeconds(60));
        assertThat(notifications).isNotEmpty();
    }

    @Test
    @DisplayName("D7-补充 持续模式：窗口内存在未越界点 ⇒ 候选被丢弃，不触发")
    void durationDropsWhenWindowHasInRangePoint() {
        IotAlertRule rule = rule(AlertTriggerMode.DURATION.getCode(), 60);
        IotAlertRulePoint point = point("GT", "30");
        latestNow("35");
        evaluate(rule, point);

        clock.set(T0.plusSeconds(75));
        latestNow("35");
        long startMs = T0.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
        when(timeSeriesQueryService.query(anyLong(), any())).thenReturn(List.of(
            new TimeSeriesPointResp(startMs + 1_000, "35", AlertRules.QUALITY_GOOD),
            new TimeSeriesPointResp(startMs + 30_000, "20", AlertRules.QUALITY_GOOD)));
        evaluate(rule, point);

        assertThat(instances).isEmpty();
        assertThat(counter(AlertMetrics.METRIC_FLAPPED)).isEqualTo(1d);
    }

    // ---------------------------------------------------------------- D 系列

    @Test
    @DisplayName("D1+D2+D4 连续 3 次：35,35,abc,35 ⇒ 第 4 轮触发（不可判定那轮计数不变）")
    void consecutiveCountSequence() {
        IotAlertRule rule = rule(AlertTriggerMode.CONSECUTIVE_COUNT.getCode(), 3);
        IotAlertRulePoint point = point("GT", "30");

        latestNow("35");
        evaluate(rule, point);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.PENDING.getCode());
        assertThat(instances.get(0).getConsecutiveCount()).isEqualTo(1);

        clock.set(T0.plusSeconds(15));
        latestNow("35");
        evaluate(rule, point);
        assertThat(instances.get(0).getConsecutiveCount()).isEqualTo(2);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.PENDING.getCode());

        clock.set(T0.plusSeconds(30));
        latestNow("abc");
        evaluate(rule, point);
        assertThat(instances.get(0).getConsecutiveCount()).as("不可判定不得改变计数").isEqualTo(2);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.PENDING.getCode());
        assertThat(notifications).as("PENDING 阶段不得产生任何通知").isEmpty();

        clock.set(T0.plusSeconds(45));
        latestNow("35");
        evaluate(rule, point);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(instances.get(0).getConsecutiveCount()).isEqualTo(3);
        assertThat(notifications).isNotEmpty();
    }

    @Test
    @DisplayName("D3+D8 序列 35,25 ⇒ 抖动被吸收：候选被丢弃、无通知、计入 flapped")
    void flapIsAbsorbedInvisibly() {
        IotAlertRule rule = rule(AlertTriggerMode.CONSECUTIVE_COUNT.getCode(), 3);
        IotAlertRulePoint point = point("GT", "30");
        latestNow("35");
        evaluate(rule, point);
        assertThat(instances).hasSize(1);

        clock.set(T0.plusSeconds(15));
        latestNow("25");
        evaluate(rule, point);
        assertThat(instances).as("PENDING 回落 ⇒ 候选被丢弃").isEmpty();
        assertThat(notifications).isEmpty();
        assertThat(counter(AlertMetrics.METRIC_FLAPPED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("D5 首轮越界后停发数据：超 pending TTL 后候选被放弃（计入 pending_expired）")
    void pendingTtlExpiry() {
        IotAlertRule rule = rule(AlertTriggerMode.CONSECUTIVE_COUNT.getCode(), 3);
        IotAlertRulePoint point = point("GT", "30");
        latestNow("35");
        evaluate(rule, point);
        assertThat(instances).hasSize(1);

        // 停发数据：读数时刻停在很久以前（陈旧）且已超过 300s 的 pending TTL
        clock.set(T0.plusSeconds(400));
        latest("35", AlertRules.QUALITY_GOOD, T0.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli());
        evaluate(rule, point);
        assertThat(instances).isEmpty();
        assertThat(counter(AlertMetrics.METRIC_PENDING_EXPIRED)).isEqualTo(1d);
        assertThat(notifications).isEmpty();
    }

    // ---------------------------------------------------------------- U 系列

    @Test
    @DisplayName("U1 已 FIRING 再次判定越界 ⇒ 不新建实例，只更新当前值")
    void u1NoDuplicateInstance() {
        seedActive(AlertState.FIRING);
        latestNow("40");
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0),
            point("GT", "30"));
        assertThat(outcome.fired()).isZero();
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getTriggerValue()).isEqualTo("40");
    }

    @Test
    @DisplayName("U3 已 RESOLVED 后再次越界 ⇒ 允许新建一条实例（恢复后可重开）")
    void u3ResolvedCanReopen() {
        seedActive(AlertState.FIRING);
        clock.set(T0.plusMinutes(1));
        latestNow("25");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.RESOLVED.getCode());

        clock.set(T0.plusMinutes(2));
        latestNow("40");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(instances).hasSize(2);
        assertThat(activeInstances()).hasSize(1);
        assertThat(activeInstances().get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
    }

    @Test
    @DisplayName("U4 规则改了阈值 ⇒ 已产生实例的阈值快照不被改写")
    void u4ThresholdSnapshotIsImmutable() {
        seedActive(AlertState.FIRING);
        latestNow("40");
        // 规则阈值改成 100（实例上的快照仍是 > 30）
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "100"));
        assertThat(instances.get(0).getThresholdSnapshot()).isEqualTo("> 30");
        // 新阈值下 40 不再算越界 ⇒ 因为读到 40 对 GT 100 是「未越界」，活动告警应恢复
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.RESOLVED.getCode());
    }

    // ---------------------------------------------------------------- S 系列

    @Test
    @DisplayName("S1 规则静默窗口内触发 ⇒ 实例照常 FIRING，但**不发任何通知**")
    void s1RuleSilenceSuppressesNotify() {
        IotAlertRule rule = rule(AlertTriggerMode.IMMEDIATE.getCode(), 0);
        rule.setSilenceStart(T0.minusMinutes(10));
        rule.setSilenceEnd(T0.plusMinutes(10));
        latestNow("35");
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule, point("GT", "30"));

        assertThat(outcome.fired()).isEqualTo(1);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(notifications).isEmpty();
        assertThat(instances.get(0).getLastNotifiedTs()).isNull();
        assertThat(counter(AlertMetrics.METRIC_NOTIFY_SILENCED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("S2 维护窗口覆盖该设备 ⇒ 同样静默（复用既有 maintenance_window，不新造概念）")
    void s2MaintenanceWindowSuppressesNotify() {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setDeviceId(DEVICE);
        window.setStartTs(T0.minusHours(1));
        window.setEndTs(T0.plusHours(1));
        windows.add(window);
        latestNow("35");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(notifications).isEmpty();
        assertThat(counter(AlertMetrics.METRIC_NOTIFY_SILENCED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("S3 静默结束后：不补发历史通知，但距上次通知已超 repeat_interval ⇒ 本轮可以通知一次")
    void s3AfterSilenceNotifiesOnceMore() {
        IotAlertRule rule = rule(AlertTriggerMode.IMMEDIATE.getCode(), 0);
        rule.setSilenceStart(T0.minusMinutes(10));
        rule.setSilenceEnd(T0.plusMinutes(10));
        latestNow("35");
        evaluate(rule, point("GT", "30"));
        assertThat(notifications).isEmpty();

        // 静默结束、且规则被改回不静默：下一轮触发 REPEAT（lastNotifiedTs 一直是 null，故立即到期）
        IotAlertRule active = rule(AlertTriggerMode.IMMEDIATE.getCode(), 0);
        clock.set(T0.plusMinutes(20));
        latestNow("36");
        evaluate(active, point("GT", "30"));
        assertThat(notifications).hasSize(2);
        assertThat(notifications).extracting(IotAlertNotification::getEvent)
            .containsOnly(AlertNotifyEvent.REPEAT.getCode());
    }

    @Test
    @DisplayName("S4 活动告警持续超过 repeat_interval ⇒ 产生 REPEAT 通知（不是永远只喊一次）")
    void s4RepeatNotification() {
        seedActive(AlertState.FIRING);
        instances.get(0).setLastNotifiedTs(T0.minusMinutes(31));
        instances.get(0).setNotifyCount(1);
        latestNow("40");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(notifications).extracting(IotAlertNotification::getEvent)
            .containsOnly(AlertNotifyEvent.REPEAT.getCode());
        assertThat(instances.get(0).getNotifyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("S4-补充 未到 repeat_interval ⇒ 不重复打扰")
    void s4NoRepeatBeforeInterval() {
        seedActive(AlertState.FIRING);
        instances.get(0).setLastNotifiedTs(T0.minusMinutes(5));
        instances.get(0).setNotifyCount(1);
        latestNow("40");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(notifications).isEmpty();
        assertThat(instances.get(0).getNotifyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("S5 ACKED 之后不再按 repeat_interval 重复通知（只在恢复时通知一次）")
    void s5AckedDoesNotRepeat() {
        seedActive(AlertState.ACKED);
        instances.get(0).setLastNotifiedTs(T0.minusHours(2));
        instances.get(0).setNotifyCount(1);
        latestNow("40");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(notifications).isEmpty();
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.ACKED.getCode());

        // 恢复时仍要通知一次（「问题没了」必须让人知道）
        clock.set(T0.plusSeconds(30));
        latestNow("25");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(notifications).extracting(IotAlertNotification::getEvent)
            .containsOnly(AlertNotifyEvent.RESOLVED.getCode());
    }

    @Test
    @DisplayName("实例级静默（一键静默 1 小时）：只推迟通知，状态仍是 FIRING，判定完全不受影响")
    void instanceSilenceOnlyDefersNotification() {
        seedActive(AlertState.FIRING);
        instances.get(0).setSilenceUntil(T0.plusHours(1));
        instances.get(0).setLastNotifiedTs(T0.minusHours(1));
        latestNow("40");
        evaluate(rule(AlertTriggerMode.IMMEDIATE.getCode(), 0), point("GT", "30"));
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(notifications).isEmpty();
        assertThat(counter(AlertMetrics.METRIC_NOTIFY_SILENCED)).isEqualTo(1d);
    }

    // ---------------------------------------------------------------- N 系列

    @Test
    @DisplayName("N6 无 N+1：一轮评估的设备查询是常数次（与候选数无关），活动实例只查一次")
    void n6NoNPlusOne() {
        latestNow("35");
        IotAlertRule rule = rule(AlertTriggerMode.IMMEDIATE.getCode(), 0);
        Map<Long, List<IotAlertRulePoint>> points = new LinkedHashMap<>();
        points.put(rule.getId(), List.of(point("GT", "30"), point("LT", "5"), point("GTE", "1")));
        evaluator.evaluateTenant(TENANT, List.of(rule), points);

        verify(deviceMapper, times(1)).selectList(any());
        verify(instanceMapper, times(1)).selectActiveInTenant();
        verify(livenessMapper, times(1)).selectList(any());
        verify(windowMapper, times(1)).selectList(any());
        // 三个点位条件全部在**一轮内**判定完（不是每个点位一次查询）：
        // GT 30 → 越界、GTE 1 → 越界、LT 5 → 未越界 ⇒ 两条实例（第三条确实不该建）
        assertThat(instances).hasSize(2);
        assertThat(instances).extracting(IotAlertInstance::getPropertyId).containsOnly("temperature");
    }

    @Test
    @DisplayName("N5 租户级规则也能解析到设备（经 selectPage 一次批量取），且不影响按设备查的路径")
    void tenantScopeResolvesDevices() {
        IotAlertRule rule = rule(AlertTriggerMode.IMMEDIATE.getCode(), 0);
        rule.setScopeType(AlertScopeType.TENANT.getCode());
        rule.setScopeDeviceId(null);
        latestNow("35");
        evaluator.evaluateTenant(TENANT, List.of(rule), pointsOf(rule, point("GT", "30")));
        verify(deviceMapper, times(1)).selectPage(any(), any());
        assertThat(instances).hasSize(1);
    }
}
