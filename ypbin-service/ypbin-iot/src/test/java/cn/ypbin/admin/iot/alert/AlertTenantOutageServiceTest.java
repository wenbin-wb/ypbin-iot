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

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertScopeType;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * 断档 → 告警映射的用例（用户口径 4：**只做映射、不新造判定**；同一事件不重复建实例、恢复时同步 RESOLVED）。
 *
 * <p>用内存假库驱动生产实现（设备解析器真实跑、状态与通知全部走生产代码），
 * 从而把「事件 → 实例 → 通知」的因果链钉住。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertTenantOutageServiceTest {

    static {
        // 不依赖其它测试类的静态初始化顺序（此前是隐式依赖，用例顺序一变就红）
        AlertMybatisTestSupport.initMetadata(IotDevice.class, IotAlertInstance.class,
            MaintenanceWindow.class);
    }

    private static final long DEVICE = 9L;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(T0);

    private final List<IotAlertInstance> instances = new ArrayList<>();

    private final List<IotAlertNotification> notifications = new ArrayList<>();

    private IotAlertInstanceMapper instanceMapper;

    private OutageEventMapper outageEventMapper;

    private AlertTenantOutageService service;

    @BeforeEach
    void setUp() {
        AlertProperties properties = new AlertProperties();
        AlertMetrics metrics = new AlertMetrics(new SimpleMeterRegistry());
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(deviceMapper.selectPage(any(), any())).thenReturn(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<IotDevice>(1, 500)
                .setRecords(List.of(device())).setTotal(1));
        outageEventMapper = mock(OutageEventMapper.class);
        instanceMapper = mock(IotAlertInstanceMapper.class);
        IotAlertNotificationMapper notificationMapper = mock(IotAlertNotificationMapper.class);
        DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);
        MaintenanceWindowMapper windowMapper = mock(MaintenanceWindowMapper.class);
        when(livenessMapper.selectNow()).thenAnswer(invocation -> clock.get());
        when(windowMapper.selectList(any())).thenReturn(List.of());
        when(instanceMapper.selectActiveOutageAlerts()).thenAnswer(invocation -> activeOutages());
        when(instanceMapper.insertBatch(anyList())).thenAnswer(invocation -> {
            List<IotAlertInstance> rows = invocation.getArgument(0);
            instances.addAll(rows);
            return rows.size();
        });
        when(instanceMapper.batchUpdate(anyList())).thenAnswer(invocation -> {
            List<IotAlertInstance> rows = invocation.getArgument(0);
            for (IotAlertInstance row : rows) {
                replace(row);
            }
            return rows.size();
        });
        when(notificationMapper.insertBatchIdempotent(anyList())).thenAnswer(invocation -> {
            List<IotAlertNotification> rows = invocation.getArgument(0);
            notifications.addAll(rows);
            return rows.size();
        });
        AlertSilencePolicy silencePolicy = new AlertSilencePolicy(windowMapper);
        AlertNotifyScheduler scheduler = new AlertNotifyScheduler(silencePolicy,
            new AlertNotifyPlanner(properties), metrics, properties);
        AlertCandidateResolver resolver = new AlertCandidateResolver(deviceMapper, new AlertDeviceCursor());
        service = new AlertTenantOutageService(resolver, scheduler, silencePolicy, metrics, properties,
            outageEventMapper, instanceMapper, notificationMapper, livenessMapper);
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE);
        device.setTenantId(1L);
        device.setProductId(77L);
        device.setDeviceName("演示设备");
        return device;
    }

    private static IotAlertRule offlineRule(Long id) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(id);
        rule.setTenantId(1L);
        rule.setRuleName("设备离线");
        rule.setScopeType(AlertScopeType.DEVICE.getCode());
        rule.setScopeDeviceId(DEVICE);
        rule.setSeverity(AlertSeverity.CRITICAL.getCode());
        rule.setEnabled(true);
        rule.setTriggerMode("IMMEDIATE");
        rule.setTriggerThreshold(0);
        rule.setRepeatIntervalSec(1800);
        rule.setNotifyChannels("INBOX");
        rule.setNotifyTargets("1001");
        rule.setCreateUser(42L);
        return rule;
    }

    private static OutageEvent outage(Long id, Long deviceId, LocalDateTime start, LocalDateTime end) {
        OutageEvent event = new OutageEvent();
        event.setId(id);
        event.setTenantId(1L);
        event.setDeviceId(deviceId);
        event.setStartTs(start);
        event.setEndTs(end);
        event.setReason("NO_GOOD_DATA");
        return event;
    }

    private List<IotAlertInstance> activeOutages() {
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

    private static Map<Long, OutageEvent> byId(List<OutageEvent> events) {
        Map<Long, OutageEvent> map = new LinkedHashMap<>();
        for (OutageEvent event : events) {
            map.put(event.getId(), event);
        }
        return map;
    }

    @Test
    @DisplayName("进行中的断档 + 覆盖规则 ⇒ 建一条 FIRING 实例（rule_id=0、去重键带事件 ID）并通知一次")
    void openOutageCreatesAlert() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));

        AlertTenantOutageService.TenantOutageOutcome outcome =
            service.mapTenant(List.of(offlineRule(7L)));

        assertThat(outcome.created()).isEqualTo(1);
        assertThat(instances).hasSize(1);
        IotAlertInstance created = instances.get(0);
        assertThat(created.getRuleId()).isEqualTo(AlertRules.RULE_ID_OUTAGE);
        assertThat(created.getState()).isEqualTo(AlertState.FIRING.getCode());
        assertThat(created.getDedupKey()).isEqualTo("OUTAGE:9:42");
        assertThat(created.getSeverity()).isEqualTo(AlertSeverity.CRITICAL.getCode());
        assertThat(created.getStartTs()).isEqualTo(T0.minusMinutes(5));
        assertThat(created.getTriggerValue()).isEqualTo("NO_GOOD_DATA");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getEvent()).isEqualTo(AlertNotifyEvent.FIRING.getCode());
    }

    @Test
    @DisplayName("同一断档事件已有活动实例 ⇒ **不重复建**（依赖去重键里的事件 ID）")
    void sameEventDoesNotCreateTwice() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        service.mapTenant(List.of(offlineRule(7L)));
        assertThat(instances).hasSize(1);

        // 第二轮：事件仍在进行、实例已存在 ⇒ 只可能产生重复提醒（repeat_interval 未到 ⇒ 无通知）
        clock.set(T0.plusSeconds(15));
        AlertTenantOutageService.TenantOutageOutcome second =
            service.mapTenant(List.of(offlineRule(7L)));
        assertThat(second.created()).isZero();
        assertThat(instances).hasSize(1);
        assertThat(notifications).hasSize(1);
    }

    @Test
    @DisplayName("没有覆盖该设备的离线规则 ⇒ **不产生任何告警**（opt-in；不是错误）")
    void noCoveringRuleMeansNoAlert() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        AlertTenantOutageService.TenantOutageOutcome outcome = service.mapTenant(List.of());

        assertThat(outcome.devices()).isZero();
        assertThat(outcome.created()).isZero();
        assertThat(instances).isEmpty();
        assertThat(notifications).isEmpty();
        // 没有覆盖设备时连断档都不查（省一次查询，且避免把无关断档拉进来）
        verify(outageEventMapper, never()).selectOpenOutages(anyList(), anyInt());
    }

    @Test
    @DisplayName("断档事件已闭合 ⇒ 同步 RESOLVED（reason=OUTAGE_RECOVERED、释放去重键）并通知恢复")
    void closedOutageResolvesAlert() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        service.mapTenant(List.of(offlineRule(7L)));
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());

        // 事件闭合：不再出现在「进行中」列表里，但能按 ID 查回来（带 end_ts）
        LocalDateTime endTs = T0.plusMinutes(3);
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt())).thenReturn(List.of());
        when(outageEventMapper.selectByIds(anyList()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), endTs)));

        clock.set(T0.plusMinutes(5));
        AlertTenantOutageService.TenantOutageOutcome outcome =
            service.mapTenant(List.of(offlineRule(7L)));

        assertThat(outcome.resolved()).isEqualTo(1);
        IotAlertInstance resolved = instances.get(0);
        assertThat(resolved.getState()).isEqualTo(AlertState.RESOLVED.getCode());
        assertThat(resolved.getReason()).isEqualTo(AlertReason.OUTAGE_RECOVERED.getCode());
        assertThat(resolved.getResolvedTs()).isEqualTo(endTs);
        assertThat(resolved.getActiveDedupKey()).isNull();
        assertThat(notifications).extracting(IotAlertNotification::getEvent)
            .contains(AlertNotifyEvent.RESOLVED.getCode());
    }

    @Test
    @DisplayName("恢复后可重开：同一设备的新断档事件建**新实例**（去重键带新事件 ID）")
    void newOutageAfterRecoveryCreatesNewAlert() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        service.mapTenant(List.of(offlineRule(7L)));
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt())).thenReturn(List.of());
        when(outageEventMapper.selectByIds(anyList()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), T0.plusMinutes(3))));
        clock.set(T0.plusMinutes(5));
        service.mapTenant(List.of(offlineRule(7L)));

        // 新事件
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(43L, DEVICE, T0.plusMinutes(10), null)));
        clock.set(T0.plusMinutes(11));
        AlertTenantOutageService.TenantOutageOutcome outcome =
            service.mapTenant(List.of(offlineRule(7L)));

        assertThat(outcome.created()).isEqualTo(1);
        assertThat(instances).hasSize(2);
        assertThat(activeOutages()).hasSize(1);
        assertThat(activeOutages().get(0).getDedupKey()).isEqualTo("OUTAGE:9:43");
    }

    @Test
    @DisplayName("事件行已不存在 ⇒ 计为孤立并保持活动（**不伪造恢复**），需人工处置")
    void missingEventKeepsAlertAndCountsOrphan() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        service.mapTenant(List.of(offlineRule(7L)));

        when(outageEventMapper.selectOpenOutages(anyList(), anyInt())).thenReturn(List.of());
        when(outageEventMapper.selectByIds(anyList())).thenReturn(List.of());
        clock.set(T0.plusMinutes(6));
        AlertTenantOutageService.TenantOutageOutcome outcome =
            service.mapTenant(List.of(offlineRule(7L)));

        assertThat(outcome.orphan()).isEqualTo(1);
        assertThat(outcome.resolved()).isZero();
        assertThat(instances.get(0).getState()).isEqualTo(AlertState.FIRING.getCode());
    }

    @Test
    @DisplayName("批读次数是常数级：设备/断档/活动实例/事件各一次（无 N+1）")
    void queriesAreBatched() {
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        service.mapTenant(List.of(offlineRule(7L)));
        verify(instanceMapper, times(1)).selectActiveOutageAlerts();
        verify(outageEventMapper, times(1)).selectOpenOutages(anyList(), anyInt());
        verify(instanceMapper, times(1)).insertBatch(anyList());
    }

    @Test
    @DisplayName("维护窗口覆盖设备时：实例照建但不通知（静默只影响通知）")
    void maintenanceWindowSuppressesNotification() {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setDeviceId(DEVICE);
        window.setStartTs(T0.minusHours(1));
        window.setEndTs(T0.plusHours(1));
        AlertProperties properties = new AlertProperties();
        AlertMetrics metrics = new AlertMetrics(new SimpleMeterRegistry());
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(deviceMapper.selectPage(any(), any())).thenReturn(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<IotDevice>(1, 500)
                .setRecords(List.of(device())).setTotal(1));
        MaintenanceWindowMapper windowMapper = mock(MaintenanceWindowMapper.class);
        when(windowMapper.selectList(any())).thenReturn(List.of(window));
        DeviceLivenessMapper livenessMapper = mock(DeviceLivenessMapper.class);
        when(livenessMapper.selectNow()).thenReturn(T0);
        when(instanceMapper.selectActiveOutageAlerts()).thenReturn(List.of());
        when(outageEventMapper.selectOpenOutages(anyList(), anyInt()))
            .thenReturn(List.of(outage(42L, DEVICE, T0.minusMinutes(5), null)));
        AlertSilencePolicy silencePolicy = new AlertSilencePolicy(windowMapper);
        AlertTenantOutageService silenced = new AlertTenantOutageService(
            new AlertCandidateResolver(deviceMapper, new AlertDeviceCursor()),
            new AlertNotifyScheduler(silencePolicy, new AlertNotifyPlanner(properties), metrics, properties),
            silencePolicy, metrics, properties, outageEventMapper, instanceMapper,
            mock(IotAlertNotificationMapper.class), livenessMapper);

        silenced.mapTenant(List.of(offlineRule(7L)));
        assertThat(instances).hasSize(1);
        assertThat(notifications).isEmpty();
    }
}
