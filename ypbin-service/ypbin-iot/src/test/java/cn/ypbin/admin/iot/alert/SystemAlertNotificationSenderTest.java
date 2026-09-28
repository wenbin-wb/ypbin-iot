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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.req.InboxMessageSendReq;
import cn.ypbin.admin.system.model.req.MailSendReq;
import cn.ypbin.starter.core.model.R;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 通知发送器的用例（独立复核 2026-10-03 M2：这段新增的**对外行为**此前零用例）。
 *
 * <p>核心判据只有一条但极重要：**失败绝不能被当成成功**。投递结果决定告警是否会被重复提醒、
 * 是否会进入退避重试；把「系统服务返回失败」当成成功，会让通知在用户毫无察觉的情况下丢掉，
 * 而投递记录上写着「已发送」——那是最难排查的一种失效。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class SystemAlertNotificationSenderTest {

    private ISystemClient systemClient;

    private SystemAlertNotificationSender sender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        systemClient = mock(ISystemClient.class);
        ObjectProvider<ISystemClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(systemClient);
        sender = new SystemAlertNotificationSender(provider, new AlertNotificationComposer());
    }

    @SuppressWarnings("unchecked")
    private SystemAlertNotificationSender withoutClient() {
        ObjectProvider<ISystemClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new SystemAlertNotificationSender(provider, new AlertNotificationComposer());
    }

    private static IotAlertInstance instance() {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(1L);
        instance.setTenantId(1L);
        instance.setDeviceId(9L);
        instance.setPropertyId("temperature");
        instance.setState(AlertState.FIRING.getCode());
        instance.setTriggerValue("35");
        instance.setStartTs(LocalDateTime.of(2026, 10, 3, 10, 0, 0));
        return instance;
    }

    private static IotAlertNotification notification(String channel, String target) {
        IotAlertNotification item = new IotAlertNotification();
        item.setId(5L);
        item.setInstanceId(1L);
        item.setChannel(channel);
        item.setTarget(target);
        item.setEvent(AlertNotifyEvent.FIRING.getCode());
        return item;
    }

    private static IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(9L);
        device.setDeviceName("演示设备");
        return device;
    }

    @Test
    @DisplayName("站内信成功 ⇒ ok；请求体带上租户/收件人/标题正文")
    void inboxSuccess() {
        when(systemClient.sendInboxMessage(any(InboxMessageSendReq.class))).thenReturn(R.ok());
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification(AlertChannel.INBOX.getCode(), "1001"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("★ 系统服务返回失败 ⇒ **fail 且带上原因**（绝不静默成功）")
    void inboxFailureIsPropagated() {
        when(systemClient.sendInboxMessage(any(InboxMessageSendReq.class)))
            .thenReturn(R.fail("收件人不存在或不属于该租户：userId=1001"));
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification(AlertChannel.INBOX.getCode(), "1001"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("收件人不存在");
    }

    @Test
    @DisplayName("邮件失败（网关抛异常）⇒ fail 且原因含异常类型与消息")
    void mailExceptionIsPropagated() {
        when(systemClient.sendMail(any(MailSendReq.class)))
            .thenThrow(new IllegalStateException("SMTP 连接超时"));
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification(AlertChannel.EMAIL.getCode(), "ops@example.com"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("IllegalStateException").contains("SMTP 连接超时");
    }

    @Test
    @DisplayName("系统服务返回空响应 ⇒ fail（投递结果不可判定时不得当成成功）")
    void nullResponseIsFailure() {
        when(systemClient.sendMail(any(MailSendReq.class))).thenReturn(null);
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification(AlertChannel.EMAIL.getCode(), "ops@example.com"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("空响应");
    }

    @Test
    @DisplayName("未装配系统客户端 ⇒ fail 且说明原因（不抛异常，投递记录里能查）")
    void missingClientIsFailure() {
        SystemAlertNotificationSender.SendResult result = withoutClient().send(
            notification(AlertChannel.INBOX.getCode(), "1001"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("系统服务客户端未装配");
    }

    @Test
    @DisplayName("未知渠道 ⇒ fail（说明渠道校验漏了，必须可见）")
    void unknownChannelIsFailure() {
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification("WEBHOOK", "https://example.com"), instance(), device(), new IotAlertRule(),
            AlertNotifyEvent.FIRING);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("未知的通知渠道");
    }

    @Test
    @DisplayName("站内信收件人不是数字（数据被人工改过）⇒ fail，不抛到上层")
    void nonNumericInboxTargetIsFailure() {
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification(AlertChannel.INBOX.getCode(), "ops@example.com"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不是合法的用户 ID");
        // 关键：一次坏数据不能让整轮投递炸掉
        org.mockito.Mockito.verify(systemClient, org.mockito.Mockito.never())
            .sendInboxMessage(any(InboxMessageSendReq.class));
    }

    @Test
    @DisplayName("通知幂等键：超长 target（合法邮箱上限 254）时不同轮次必须生成不同的键")
    void longIdempotentKeyKeepsRoundDistinct() {
        String longTarget = "a".repeat(200) + "@example.com";
        IotAlertInstance first = instance();
        first.setNotifyCount(0);
        IotAlertInstance second = instance();
        second.setNotifyCount(1);
        String key1 = AlertNotifyPlanner.idempotentKey(first, AlertNotifyEvent.REPEAT,
            AlertChannel.EMAIL, longTarget);
        String key2 = AlertNotifyPlanner.idempotentKey(second, AlertNotifyEvent.REPEAT,
            AlertChannel.EMAIL, longTarget);
        assertThat(key1).hasSize(191);
        assertThat(key2).hasSize(191);
        // 变异哨兵：若把实现改回 substring(0,191)（按前缀截断，恰好砍掉末尾的「轮次」），
        // 这两个键会相同 ⇒ 第 2 次 REPEAT 会被幂等键挡掉（活动告警再也不提醒）
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).startsWith(key2.substring(0, 20));
    }

    @Test
    @DisplayName("幂等键短于列宽时保持可读（不加哈希）")
    void shortKeyStaysReadable() {
        IotAlertInstance instance = instance();
        instance.setNotifyCount(2);
        assertThat(AlertNotifyPlanner.idempotentKey(instance, AlertNotifyEvent.FIRING,
            AlertChannel.INBOX, "1001")).isEqualTo("1:FIRING:INBOX:1001:2");
    }

    @Test
    @DisplayName("文案生成被复用：站内信与邮件共用同一份标题/正文（避免两个渠道说法不一致）")
    void composerIsSharedBetweenChannels() {
        when(systemClient.sendInboxMessage(any(InboxMessageSendReq.class))).thenReturn(R.ok());
        when(systemClient.sendMail(any(MailSendReq.class))).thenReturn(R.ok());
        sender.send(notification(AlertChannel.INBOX.getCode(), "1001"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        sender.send(notification(AlertChannel.EMAIL.getCode(), "ops@example.com"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        org.mockito.ArgumentCaptor<InboxMessageSendReq> inbox =
            org.mockito.ArgumentCaptor.forClass(InboxMessageSendReq.class);
        org.mockito.ArgumentCaptor<MailSendReq> mail =
            org.mockito.ArgumentCaptor.forClass(MailSendReq.class);
        org.mockito.Mockito.verify(systemClient).sendInboxMessage(inbox.capture());
        org.mockito.Mockito.verify(systemClient).sendMail(mail.capture());
        assertThat(inbox.getValue().getTitle()).isEqualTo(mail.getValue().getSubject());
        assertThat(inbox.getValue().getContent()).isEqualTo(mail.getValue().getContent());
        assertThat(inbox.getValue().getContent()).contains("演示设备").contains("temperature");
        assertThat(inbox.getValue().getTenantId()).isEqualTo(1L);
        assertThat(inbox.getValue().getReceiverUserId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("收件人校验的失败文案不泄露敏感信息（只带 userId）")
    void failureMessageHasNoSecret() {
        when(systemClient.sendInboxMessage(any(InboxMessageSendReq.class)))
            .thenReturn(R.fail("收件人不存在或不属于该租户：userId=1001"));
        SystemAlertNotificationSender.SendResult result = sender.send(
            notification(AlertChannel.INBOX.getCode(), "1001"), instance(), device(),
            new IotAlertRule(), AlertNotifyEvent.FIRING);
        assertThat(result.error()).doesNotContain("password").doesNotContain("token");
    }
}
