package cn.ypbin.admin.iot.platform;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.req.InboxMessageSendReq;
import cn.ypbin.admin.system.model.req.MailSendReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 平台告警通知投递器（看板 #10 二批「开通通知」）。
 *
 * <p>复用既有通知通道的底层调用（system 侧站内信/邮件，与 SystemAlertNotificationSender
 * 同一套 Req），但不经过 iot_alert_notification 待发队列 —— 平台告警没有设备实例，
 * 塞设备告警表会污染语义（设计 §1.3）；平台告警频率天然低频（FIRING 升发一次、RESOLVED 恢复发一次）。</p>
 *
 * <p>开关：notifyEnabled=false（默认）一律不投递；收件人为空同样不投递但记 WARN
 * （让「开关开了但没人收到」可审计）。单条投递失败隔离（不拖累其它收件人），完整堆栈留痕。</p>
 */
@Component
public class PlatformAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(PlatformAlertNotifier.class);

    private final ObjectProvider<ISystemClient> systemClientProvider;
    private final PlatformAlertProperties properties;

    public PlatformAlertNotifier(ObjectProvider<ISystemClient> systemClientProvider,
                                 PlatformAlertProperties properties) {
        this.systemClientProvider = systemClientProvider;
        this.properties = properties;
    }

    /** FIRING 通知。 */
    public void notifyFiring(PlatformHealthRule rule, String summary, String snapshot) {
        send(rule, "平台告警持续触发", buildFiringText(rule, summary, snapshot));
    }

    /** RESOLVED 通知（恢复必发，设计点名）。 */
    public void notifyResolved(PlatformHealthRule rule, String summary) {
        send(rule, "平台告警已恢复", buildResolvedText(rule, summary));
    }

    private void send(PlatformHealthRule rule, String kind, String content) {
        if (!properties.isNotifyEnabled()) {
            log.debug("[iot] 平台告警通知开关关闭，跳过：rule={}, kind={}", rule.getCode(), kind);
            return;
        }
        boolean noInbox = properties.getRecipientUserIds().isEmpty();
        boolean noMail = properties.getRecipientExtraEmails().isEmpty();
        if (noInbox && noMail) {
            log.warn("[iot] 平台告警通知开关已开但收件人为空，未投递：rule={}, kind={}",
                rule.getCode(), kind);
            return;
        }
        ISystemClient systemClient = systemClientProvider.getIfAvailable();
        if (systemClient == null) {
            log.error("[iot] 平台告警通知无法投递：system 客户端未装配（rule={}, kind={}）",
                rule.getCode(), kind);
            return;
        }
        for (Long userId : properties.getRecipientUserIds()) {
            InboxMessageSendReq req = new InboxMessageSendReq();
            // 平台告警调度线程无租户上下文，按主租户 1（多租户部署需显式收件租户，登记见看板）
            req.setTenantId(1L);
            req.setReceiverUserId(userId);
            req.setTitle(rule.getTitle() + "（" + kind + "）");
            req.setContent(content);
            try {
                sendInbox(systemClient, req, rule);
            } catch (RuntimeException ex) {
                log.error("[iot] 平台告警站内信投递异常：userId={}, rule={}", userId,
                    rule.getCode(), ex);
            }
        }
        for (String email : properties.getRecipientExtraEmails()) {
            MailSendReq req = new MailSendReq();
            req.setTo(email);
            req.setSubject(rule.getTitle() + "（" + kind + "）");
            req.setContent(content);
            try {
                sendMail(systemClient, req, rule);
            } catch (RuntimeException ex) {
                log.error("[iot] 平台告警邮件投递异常：to={}, rule={}", email,
                    rule.getCode(), ex);
            }
        }
    }

    private static void sendInbox(ISystemClient client, InboxMessageSendReq req,
                                  PlatformHealthRule rule) {
        var result = client.sendInboxMessage(req);
        if (result == null || !result.isSuccess()) {
            log.warn("[iot] 平台告警站内信发送失败：rule={}, respMsg={}", rule.getCode(),
                result == null ? "null" : result.getMessage());
        }
    }

    private static void sendMail(ISystemClient client, MailSendReq req,
                                 PlatformHealthRule rule) {
        var result = client.sendMail(req);
        if (result == null || !result.isSuccess()) {
            log.warn("[iot] 平台告警邮件发送失败：rule={}, respMsg={}", rule.getCode(),
                result == null ? "null" : result.getMessage());
        }
    }

    private static String buildFiringText(PlatformHealthRule rule, String summary, String snapshot) {
        return "规则：" + rule.getTitle() + "；级别：" + rule.getSeverity().getCode()
            + "；概要：" + summary + "；指标快照：" + (snapshot == null ? "-" : snapshot);
    }

    private static String buildResolvedText(PlatformHealthRule rule, String summary) {
        return "规则：" + rule.getTitle() + "；已恢复。概要：" + summary;
    }
}
