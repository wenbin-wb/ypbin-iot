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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyStatus;
import cn.ypbin.admin.iot.enums.AlertSeverity;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通知投递的用例（设计 §3.4-S6/S7：退避重试 3 次、耗尽放弃、限流延后不丢弃）。
 *
 * <p>投递器是「会出网、会失败、会重试」的那一环，所以这里逐条钉死三种终局（成功 / 失败待重试 / 放弃）
 * 与一种中间态（限流延后），并验证**状态推进与投递记录是同一条批量语句**（不在循环里逐条写）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertTenantNotifierTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private IotAlertNotificationMapper notificationMapper;

    private IotAlertInstanceMapper instanceMapper;

    private SystemAlertNotificationSender sender;

    private AlertProperties properties;

    private AlertTenantNotifier notifier;

    private final AtomicReference<List<IotAlertNotification>> saved = new AtomicReference<>(List.of());

    @BeforeEach
    void setUp() {
        properties = new AlertProperties();
        notificationMapper = mock(IotAlertNotificationMapper.class);
        instanceMapper = mock(IotAlertInstanceMapper.class);
        IotAlertRuleMapper ruleMapper = mock(IotAlertRuleMapper.class);
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        sender = mock(SystemAlertNotificationSender.class);
        when(notificationMapper.claim(any(), any(), any())).thenReturn(1);
        when(notificationMapper.batchUpdate(anyList())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get().size();
        });
        when(instanceMapper.selectByIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(AlertTenantNotifierTest::instance).toList();
        });
        when(deviceMapper.selectList(any())).thenReturn(List.of(device()));
        when(ruleMapper.selectByIds(anyList())).thenReturn(List.of());
        notifier = new AlertTenantNotifier(notificationMapper, instanceMapper, ruleMapper, deviceMapper,
            sender, new AlertNotifyThrottle(properties), new AlertMetrics(new SimpleMeterRegistry()),
            properties);
    }

    private static IotAlertInstance instance(Long id) {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(id);
        instance.setTenantId(1L);
        instance.setRuleId(0L);
        instance.setDeviceId(9L);
        instance.setState(AlertState.FIRING.getCode());
        instance.setSeverity(AlertSeverity.WARNING.getCode());
        instance.setStartTs(NOW.minusMinutes(10));
        return instance;
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(9L);
        device.setDeviceName("演示设备");
        device.setDeviceCode("demo-dev-curve");
        return device;
    }

    private static IotAlertNotification notification(Long id, int attempt, String status) {
        IotAlertNotification item = new IotAlertNotification();
        item.setId(id);
        item.setTenantId(1L);
        item.setInstanceId(id);
        item.setChannel(AlertChannel.INBOX.getCode());
        item.setTarget("1001");
        item.setEvent(AlertNotifyEvent.FIRING.getCode());
        item.setNotifyStatus(status);
        item.setAttempt(attempt);
        item.setNextRetryTs(NOW.minusMinutes(1));
        return item;
    }

    @Test
    @DisplayName("成功：置 SENT、清空 next_retry_ts 与 last_error，并累加尝试次数")
    void successMarksSent() {
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(
            SystemAlertNotificationSender.SendResult.ok());
        IotAlertNotification item = notification(1L, 0, AlertNotifyStatus.PENDING.getCode());

        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(item), NOW);

        assertThat(outcome.sent()).isEqualTo(1);
        assertThat(item.getNotifyStatus()).isEqualTo(AlertNotifyStatus.SENT.getCode());
        assertThat(item.getNextRetryTs()).isNull();
        assertThat(item.getLastError()).isNull();
        assertThat(item.getAttempt()).isEqualTo(1);
        verify(notificationMapper, times(1)).batchUpdate(anyList());
    }

    @Test
    @DisplayName("S6 失败但未耗尽 ⇒ FAILED + 退避（首次 30s），错误原因**原样**记入 last_error")
    void failureSchedulesBackoff() {
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(
            SystemAlertNotificationSender.SendResult.fail("系统服务返回失败：邮件网关 500"));
        IotAlertNotification item = notification(1L, 0, AlertNotifyStatus.PENDING.getCode());

        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(item), NOW);

        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(item.getNotifyStatus()).isEqualTo(AlertNotifyStatus.FAILED.getCode());
        assertThat(item.getNextRetryTs()).isEqualTo(NOW.plusSeconds(30));
        assertThat(item.getLastError()).contains("邮件网关 500");
    }

    @Test
    @DisplayName("S6 退避按阶梯推进：第 2 次失败退避 2 分钟、第 3 次失败退避 10 分钟")
    void backoffFollowsLadder() {
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(
            SystemAlertNotificationSender.SendResult.fail("boom"));
        IotAlertNotification second = notification(1L, 1, AlertNotifyStatus.FAILED.getCode());
        notifier.dispatchTenant(List.of(second), NOW);
        assertThat(second.getNextRetryTs()).isEqualTo(NOW.plusSeconds(120));

        IotAlertNotification third = notification(2L, 2, AlertNotifyStatus.FAILED.getCode());
        notifier.dispatchTenant(List.of(third), NOW);
        assertThat(third.getNextRetryTs()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    @DisplayName("S6 重试耗尽（尝试次数达到上限）⇒ GIVEN_UP 且不再排重试，last_error 保留原因")
    void exhaustedRetriesGiveUp() {
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(
            SystemAlertNotificationSender.SendResult.fail("SMTP 连接超时"));
        IotAlertNotification item = notification(1L, 3, AlertNotifyStatus.FAILED.getCode());

        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(item), NOW);

        assertThat(outcome.givenUp()).isEqualTo(1);
        assertThat(item.getNotifyStatus()).isEqualTo(AlertNotifyStatus.GIVEN_UP.getCode());
        assertThat(item.getNextRetryTs()).isNull();
        assertThat(item.getLastError()).contains("SMTP 连接超时");
    }

    @Test
    @DisplayName("★ S7 全局限流：超出的通知**延后**（保持原状态、推到下一个时间窗），且不消耗尝试次数")
    void throttledIsDeferredNotDropped() {
        properties.setNotifyThrottlePerMinute(1);
        when(sender.send(any(), any(), any(), any(), any())).thenReturn(
            SystemAlertNotificationSender.SendResult.ok());
        IotAlertNotification first = notification(1L, 0, AlertNotifyStatus.PENDING.getCode());
        IotAlertNotification second = notification(2L, 0, AlertNotifyStatus.PENDING.getCode());

        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(first, second),
            NOW);

        assertThat(outcome.sent()).isEqualTo(1);
        assertThat(outcome.throttled()).isEqualTo(1);
        assertThat(second.getNotifyStatus()).as("延后不是丢弃：状态保持原样").isEqualTo(
            AlertNotifyStatus.PENDING.getCode());
        assertThat(second.getNextRetryTs()).isAfter(NOW);
        assertThat(second.getAttempt()).as("被限流不该算作一次失败尝试").isZero();
        // 被限流的那条**没有被认领**（认领会消耗尝试次数）
        verify(notificationMapper, times(1)).claim(any(), any(), any());
    }

    @Test
    @DisplayName("认领失败（另一轮已认领）⇒ 本次不发送、不改状态")
    void claimFailureSkips() {
        when(notificationMapper.claim(any(), any(), any())).thenReturn(0);
        IotAlertNotification item = notification(1L, 0, AlertNotifyStatus.PENDING.getCode());

        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(item), NOW);

        assertThat(outcome.sent()).isZero();
        assertThat(outcome.failed()).isZero();
        verify(sender, never()).send(any(), any(), any(), any(), any());
        verify(notificationMapper, never()).batchUpdate(anyList());
    }

    @Test
    @DisplayName("实例已不存在（保留清理并发）⇒ 投递记录收口为 GIVEN_UP 并写明原因，不留永远到期的孤儿")
    void missingInstanceGivesUp() {
        when(instanceMapper.selectByIds(anyList())).thenReturn(List.of());
        IotAlertNotification item = notification(1L, 0, AlertNotifyStatus.PENDING.getCode());

        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(item), NOW);

        assertThat(outcome.givenUp()).isEqualTo(1);
        assertThat(item.getNotifyStatus()).isEqualTo(AlertNotifyStatus.GIVEN_UP.getCode());
        assertThat(item.getLastError()).contains("告警实例已不存在");
    }

    @Test
    @DisplayName("空输入直接返回（不查库）")
    void emptyInputShortCircuits() {
        AlertTenantNotifier.TenantDispatchOutcome outcome = notifier.dispatchTenant(List.of(), NOW);
        assertThat(outcome.sent()).isZero();
        verify(instanceMapper, never()).selectByIds(anyList());
    }
}
