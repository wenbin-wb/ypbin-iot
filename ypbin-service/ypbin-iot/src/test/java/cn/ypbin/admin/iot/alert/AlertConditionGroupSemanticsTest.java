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
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
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
import cn.ypbin.admin.iot.timeseries.TimeSeriesQueryService;
import cn.ypbin.starter.iot.availability.AvailabilityRules;
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
 * 【验收方临时用例 — verify-backend / task-10】真 AND 语义的**独立复核**。
 *
 * <p>本文件不是交付物，仅用于独立验收；与作者用例的差别：断言由验收方自行构造，且重点覆盖
 * ①「另一条仍在越界区却判定为恢复」的用户可感知后果；② 同设备不同规则；③ 单条件规则回归；
 * ④ 编辑 2→1 / 1→2；⑤ 断档实例不被本链路触碰。</p>
 *
 * @author verify-backend
 */
class AlertConditionGroupSemanticsTest {

    static {
        AlertMybatisTestSupport.initMetadata(IotDevice.class, DeviceLiveness.class, MaintenanceWindow.class,
            IotAlertInstance.class, IotAlertRule.class);
    }

    private static final long TENANT = 1L;
    private static final long DEVICE = 9L;
    private static final long OTHER_DEVICE = 10L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(T0);
    private final Map<Long, Map<String, AlertLatestValueReader.AlertSample>> latest = new LinkedHashMap<>();
    private final List<IotAlertInstance> instances = new ArrayList<>();
    private final List<IotAlertNotification> notifications = new ArrayList<>();

    private IotAlertInstanceMapper instanceMapper;
    private SimpleMeterRegistry registry;
    private AlertTenantEvaluator evaluator;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        AlertMetrics metrics = new AlertMetrics(registry);
        AlertProperties properties = new AlertProperties();

        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE), device(OTHER_DEVICE)));
        when(deviceMapper.selectPage(any(), any())).thenReturn(
            new Page<IotDevice>(1, 500).setRecords(List.of(device(DEVICE), device(OTHER_DEVICE)))
                .setTotal(2));

        DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);
        when(livenessMapper.selectNow()).thenAnswer(invocation -> clock.get());
        DeviceLiveness liveness = new DeviceLiveness();
        liveness.setDeviceId(DEVICE);
        liveness.setPollIntervalMs(15_000);
        when(livenessMapper.selectList(any())).thenReturn(List.of(liveness));

        MaintenanceWindowMapper windowMapper = mock(MaintenanceWindowMapper.class);
        when(windowMapper.selectList(any())).thenReturn(List.of());

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

        IotAlertNotificationMapper notificationMapper = mock(IotAlertNotificationMapper.class);
        when(notificationMapper.insertBatchIdempotent(anyList())).thenAnswer(invocation -> {
            List<IotAlertNotification> rows = invocation.getArgument(0);
            notifications.addAll(rows);
            return rows.size();
        });

        AlertLatestValueReader reader = mock(AlertLatestValueReader.class);
        when(reader.readLatest(anyLong(), anyList())).thenAnswer(invocation -> latest);

        AlertSilencePolicy silencePolicy = new AlertSilencePolicy(windowMapper);
        evaluator = new AlertTenantEvaluator(
            new AlertCandidateResolver(deviceMapper, new AlertDeviceCursor()),
            reader,
            new AlertNotifyScheduler(silencePolicy, new AlertNotifyPlanner(properties), metrics, properties),
            metrics, properties, instanceMapper, notificationMapper, livenessMapper, silencePolicy,
            mock(TimeSeriesQueryService.class));
    }

    // ------------------------------------------------------------------ 反例：四态

    @Test
    @DisplayName("反例①：全中 ⇒ 一条实例/组键 R:7:9；部分中 ⇒ 不触发；全不中 ⇒ 不触发；含不可判定 ⇒ 不触发")
    void andThreeStates() {
        IotAlertRule rule = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        List<IotAlertRulePoint> points = List.of(
            point(11L, "temperature", "GT", "30"),
            point(12L, "humidity", "GT", "80"));

        latestNow(Map.of("temperature", "35", "humidity", "90"));
        assertThat(evaluate(rule, points).fired()).as("全中").isEqualTo(1);
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getActiveDedupKey()).isEqualTo("R:7:9");
        instances.clear();
        notifications.clear();

        latestNow(Map.of("temperature", "35", "humidity", "50"));
        assertThat(evaluate(rule, points).fired()).as("部分中（关键反例：旧近似 OR 会触发）").isZero();
        assertThat(instances).as("部分中不得建实例").isEmpty();

        latestNow(Map.of("temperature", "10", "humidity", "50"));
        assertThat(evaluate(rule, points).fired()).as("全不中").isZero();
        assertThat(instances).isEmpty();

        latestNow(Map.of("temperature", "35"));
        assertThat(evaluate(rule, points).fired()).as("含不可判定（humidity 无值）").isZero();
        assertThat(instances).as("不可判定不得被当成越界").isEmpty();
    }

    @Test
    @DisplayName("反例②：一条回落、另一条仍在越界区 ⇒ 合取被证伪，告警收口（且收口快照仍显示那条越界）")
    void oneFallsBackWhileOtherStillOutOfRangeResolves() {
        IotAlertRule rule = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        List<IotAlertRulePoint> points = List.of(
            point(11L, "temperature", "GT", "30"),
            point(12L, "humidity", "GT", "80"));

        latestNow(Map.of("temperature", "35", "humidity", "90"));
        evaluate(rule, points);
        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());

        // 温度回落到 10（远低于阈值 30），湿度仍 90（仍在越界区）
        clock.set(T0.plusSeconds(30));
        latestNow(Map.of("temperature", "10", "humidity", "90"));
        AlertTenantEvaluator.TenantOutcome outcome = evaluate(rule, points);

        assertThat(outcome.resolved()).as("一条可判定条件已证伪合取 ⇒ 收口（AND 语义的定义）").isEqualTo(1);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.RESOLVED.getCode());
        assertThat(instances.get(0).getReason()).isEqualTo(AlertReason.RECOVERED.getCode());
        assertThat(instances.get(0).getActiveDedupKey()).isNull();
        // 关键可解释性：收口快照里那条仍越界的条件必须可见（不是静默消失）
        List<AlertConditionSnapshot.ConditionReading> readings =
            AlertConditionSnapshot.decode(instances.get(0).getConditionSnapshot());
        assertThat(readings).hasSize(2);
        assertThat(readings).extracting(AlertConditionSnapshot.ConditionReading::propertyId)
            .containsExactly("temperature", "humidity");
        assertThat(readings).extracting(AlertConditionSnapshot.ConditionReading::verdict)
            .containsExactly(AlertValueVerdict.IN_RANGE, AlertValueVerdict.OUT_OF_RANGE);
        System.out.println("[verify] 收口快照=" + instances.get(0).getConditionSnapshot()
            + " ; 通知事件=" + notifications.stream().map(IotAlertNotification::getEvent).toList());
    }

    @Test
    @DisplayName("反例③：回差挡住时，即使另一条越界也不得收口（不得提前恢复）")
    void deadbandPreventsPrematureResolve() {
        IotAlertRule rule = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        IotAlertRulePoint withDeadband = point(11L, "temperature", "GT", "30");
        withDeadband.setDeadband(new BigDecimal("10"));
        List<IotAlertRulePoint> points = List.of(withDeadband, point(12L, "humidity", "GT", "80"));

        latestNow(Map.of("temperature", "35", "humidity", "90"));
        evaluate(rule, points);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());

        // 温度 25（未越界，但未越过恢复门槛 20），湿度仍 90
        clock.set(T0.plusSeconds(30));
        latestNow(Map.of("temperature", "25", "humidity", "90"));
        assertThat(evaluate(rule, points).resolved()).as("回差挡住 ⇒ 不得收口").isZero();
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
    }

    // ------------------------------------------------------------------ 编辑 / 多规则

    @Test
    @DisplayName("反例④：编辑 2→1 与 1→2 条件后既有实例均能收敛（不留僵尸）")
    void editingConditionCountConverges() {
        IotAlertRule rule = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        latestNow(Map.of("temperature", "35", "humidity", "90"));
        evaluate(rule, List.of(point(11L, "temperature", "GT", "30"), point(12L, "humidity", "GT", "80")));
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());

        // 2 → 1：删掉 humidity，剩 temperature 越界 ⇒ 仍满足，不得误恢复
        clock.set(T0.plusSeconds(15));
        latestNow(Map.of("temperature", "35"));
        assertThat(evaluate(rule, List.of(point(11L, "temperature", "GT", "30"))).resolved()).isZero();
        assertThat(instances).as("编辑条件行不得另起实例（键不含条件内容）").hasSize(1);

        // 1 → 2：新增 humidity 且它未越界 ⇒ 合取不成立 ⇒ 收口
        clock.set(T0.plusSeconds(30));
        latestNow(Map.of("temperature", "35", "humidity", "50"));
        assertThat(evaluate(rule, List.of(point(11L, "temperature", "GT", "30"),
            point(12L, "humidity", "GT", "80"))).resolved()).isEqualTo(1);
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.RESOLVED.getCode());
    }

    @Test
    @DisplayName("反例⑤：同设备不同规则 ⇒ 各自一条实例，组键互不复用")
    void differentRulesSameDeviceAreIndependent() {
        IotAlertRule ruleA = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        IotAlertRule ruleB = rule(8L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        latestNow(Map.of("temperature", "35", "humidity", "90"));

        evaluator.evaluateTenant(TENANT, List.of(ruleA, ruleB), Map.of(
            ruleA.getId(), List.of(point(11L, "temperature", "GT", "30")),
            ruleB.getId(), List.of(point(12L, "humidity", "GT", "80"))));

        assertThat(instances).hasSize(2);
        assertThat(instances).extracting(IotAlertInstance::getActiveDedupKey)
            .containsExactlyInAnyOrder("R:7:9", "R:8:9");
    }

    @Test
    @DisplayName("反例⑥：单条件规则与旧语义逐字一致（文案分支走 legacy、键为 R:rule:device）")
    void singleConditionRuleKeepsLegacySemantics() {
        IotAlertRule rule = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        latestNow(Map.of("temperature", "35"));
        evaluate(rule, List.of(point(11L, "temperature", "GT", "30")));

        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).getActiveDedupKey()).isEqualTo("R:7:9");
        assertThat(instances.get(0).getPropertyId()).isEqualTo("temperature");
        assertThat(instances.get(0).getTriggerValue()).isEqualTo("35");
        assertThat(instances.get(0).getThresholdSnapshot()).isEqualTo("> 30");
        // 单条件规则的通知正文必须与原实现同形（composer 的 legacy 分支）
        AlertNotificationComposer composer = new AlertNotificationComposer();
        AlertNotificationComposer.AlertNotificationText text = composer.compose(instances.get(0), device(DEVICE), rule,
            cn.ypbin.admin.iot.enums.AlertNotifyEvent.FIRING,
            List.of(point(11L, "temperature", "GT", "30")));
        System.out.println("[verify] 单条件通知正文=\n" + text.content());
        assertThat(text.content()).contains("条件：> 30");
        assertThat(text.content()).contains("触发值：35");
        assertThat(text.content()).doesNotContain("条件组");
    }

    @Test
    @DisplayName("反例⑦：断档类实例（rule_id=0 / OUTAGE 键）不被点位评估链路触碰")
    void outageInstanceUntouched() {
        IotAlertInstance outage = new IotAlertInstance();
        outage.setId(500L);
        outage.setTenantId(TENANT);
        outage.setRuleId(AlertRules.RULE_ID_OUTAGE);
        outage.setDeviceId(DEVICE);
        outage.setDedupKey(AlertRules.outageDedupKey(DEVICE, 42L));
        outage.setActiveDedupKey(outage.getDedupKey());
        outage.setState(AlertState.FIRING.getCode());
        outage.setSeverity(AlertSeverity.CRITICAL.getCode());
        outage.setStartTs(T0.minusMinutes(5));
        instances.add(outage);

        IotAlertRule rule = rule(7L, AlertTriggerMode.IMMEDIATE.getCode(), 0);
        latestNow(Map.of("temperature", "35"));
        evaluate(rule, List.of(point(11L, "temperature", "GT", "30")));

        assertThat(outage.getState()).as("断档实例不得被点评估链路改写").isEqualTo(AlertState.FIRING.getCode());
        assertThat(outage.getActiveDedupKey()).isEqualTo("OUTAGE:9:42");
        assertThat(outage.getReason()).isNull();
    }

    // ------------------------------------------------------------------ 夹具

    private static IotDevice device(long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(TENANT);
        device.setProductId(77L);
        device.setDeviceName("演示设备" + id);
        device.setDeviceCode("demo-" + id);
        return device;
    }

    private static IotAlertRule rule(long id, String mode, int threshold) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(id);
        rule.setTenantId(TENANT);
        rule.setRuleName("规则" + id);
        rule.setScopeType(AlertScopeType.DEVICE.getCode());
        rule.setScopeDeviceId(DEVICE);
        rule.setSeverity(AlertSeverity.WARNING.getCode());
        rule.setEnabled(true);
        rule.setTriggerMode(mode);
        rule.setTriggerThreshold(threshold);
        rule.setPendingTtlSec(300);
        rule.setRepeatIntervalSec(1800);
        rule.setNotifyChannels("INBOX");
        rule.setNotifyTargets("1001");
        rule.setCreateUser(42L);
        return rule;
    }

    private static IotAlertRulePoint point(long id, String propertyId, String operator, String threshold) {
        IotAlertRulePoint point = new IotAlertRulePoint();
        point.setId(id);
        point.setRuleId(7L);
        point.setPropertyId(propertyId);
        point.setOperator(operator);
        point.setThreshold(new BigDecimal(threshold));
        point.setValueType(AlertValueType.NUMERIC.getCode());
        return point;
    }

    private void latestNow(Map<String, String> valuesByProperty) {
        Long ts = clock.get().atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli();
        Map<String, AlertLatestValueReader.AlertSample> samples = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : valuesByProperty.entrySet()) {
            samples.put(entry.getKey(), new AlertLatestValueReader.AlertSample(entry.getKey(),
                entry.getValue(), AlertRules.QUALITY_GOOD, ts));
        }
        latest.put(DEVICE, samples);
    }

    private AlertTenantEvaluator.TenantOutcome evaluate(IotAlertRule rule, List<IotAlertRulePoint> points) {
        return evaluator.evaluateTenant(TENANT, List.of(rule), Map.of(rule.getId(), points));
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
}
