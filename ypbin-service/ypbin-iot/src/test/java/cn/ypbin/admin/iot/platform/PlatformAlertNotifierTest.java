package cn.ypbin.admin.iot.platform;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.req.InboxMessageSendReq;
import cn.ypbin.admin.system.model.req.MailSendReq;
import cn.ypbin.starter.core.model.R;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 平台告警通知投递器用例（看板 #10 二批）。
 *
 * 锁四条安全判据：
 * ① 开关关闭 ⇒ 一律不投递（观察期无打扰）；
 * ② 开关开但收件人为空 ⇒ 不投递（WARN 留痕，非静默）；
 * ③ 单条失败/异常隔离（不拖累其它收件人、不抛出）；
 * ④ 站内信按用户逐个、邮件按地址逐个（分通道控制）。
 */
class PlatformAlertNotifierTest {

    private ObjectProvider<ISystemClient> provider;
    private ISystemClient client;
    private PlatformAlertProperties properties;
    private PlatformAlertNotifier notifier;

    @BeforeEach
    void setUp() {
        provider = mock(ObjectProvider.class);
        client = mock(ISystemClient.class);
        properties = new PlatformAlertProperties();
        when(provider.getIfAvailable()).thenReturn(client);
        notifier = new PlatformAlertNotifier(provider, properties);
    }

    @Test
    @DisplayName("开关关闭 ⇒ 一律不投递（默认行为）")
    void notifyDisabledMustNotSend() {
        properties.setNotifyEnabled(false);
        properties.setRecipientUserIds(List.of(1L));
        properties.setRecipientExtraEmails(List.of("a@b.com"));

        notifier.notifyFiring(PlatformHealthRule.EVALUATOR_STALLED, "s", "{}");

        verify(client, never()).sendInboxMessage(any(InboxMessageSendReq.class));
        verify(client, never()).sendMail(any(MailSendReq.class));
    }

    @Test
    @DisplayName("开关开 + 收件人为空 ⇒ 不投递（可审计）")
    void notifyEnabledButNoRecipientsMustNotSend() {
        properties.setNotifyEnabled(true);

        notifier.notifyFiring(PlatformHealthRule.EVALUATOR_STALLED, "s", "{}");

        verify(client, never()).sendInboxMessage(any(InboxMessageSendReq.class));
        verify(client, never()).sendMail(any(MailSendReq.class));
    }

    @Test
    @DisplayName("开关开 + 有收件人 ⇒ 站内信按用户逐个、邮件按地址逐个")
    void notifyEnabledWithRecipientsMustSendBothChannels() {
        properties.setNotifyEnabled(true);
        properties.setRecipientUserIds(List.of(1L, 2L));
        properties.setRecipientExtraEmails(List.of("a@b.com"));
        when(client.sendInboxMessage(any(InboxMessageSendReq.class))).thenReturn(R.ok(null));
        when(client.sendMail(any(MailSendReq.class))).thenReturn(R.ok(null));

        notifier.notifyFiring(PlatformHealthRule.NOTIFY_FAILING, "s", "{}");

        verify(client, times(2)).sendInboxMessage(any(InboxMessageSendReq.class));
        verify(client, times(1)).sendMail(any(MailSendReq.class));
    }

    @Test
    @DisplayName("单条投递抛异常 ⇒ 隔离（其它收件人继续、不抛出）")
    void singleFailureMustNotAbortOthers() {
        properties.setNotifyEnabled(true);
        properties.setRecipientUserIds(List.of(1L, 2L));
        when(client.sendInboxMessage(any(InboxMessageSendReq.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenReturn(R.ok(null));

        assertThatCode(() -> notifier.notifyResolved(PlatformHealthRule.INGEST_DROPPING, "r"))
            .doesNotThrowAnyException();
        verify(client, times(2)).sendInboxMessage(any(InboxMessageSendReq.class));
    }

    @Test
    @DisplayName("system 客户端未装配 ⇒ 不投递且不抛")
    void missingSystemClientMustNotThrow() {
        when(provider.getIfAvailable()).thenReturn(null);
        properties.setNotifyEnabled(true);
        properties.setRecipientUserIds(List.of(1L));

        assertThatCode(() -> notifier.notifyFiring(PlatformHealthRule.EVALUATOR_STALLED, "s", "{}"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FIRING 与 RESOLVED 都走同一开关（内容不同不代表通道不同）")
    void bothEventsFlowThroughSwitch() {
        properties.setNotifyEnabled(true);
        properties.setRecipientUserIds(List.of(1L));
        when(client.sendInboxMessage(any(InboxMessageSendReq.class))).thenReturn(R.ok(null));

        notifier.notifyFiring(PlatformHealthRule.EVALUATOR_STALLED, "s", "{}");
        notifier.notifyResolved(PlatformHealthRule.EVALUATOR_STALLED, "r");

        verify(client, times(2)).sendInboxMessage(any(InboxMessageSendReq.class));
    }
}
