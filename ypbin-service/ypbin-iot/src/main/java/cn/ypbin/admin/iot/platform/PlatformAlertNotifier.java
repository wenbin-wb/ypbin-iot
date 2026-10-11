package cn.ypbin.admin.iot.platform;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.req.InboxMessageSendReq;
import cn.ypbin.admin.system.model.req.MailSendReq;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    /**
     * 通知尝试总数（**含被开关/收件人拦下的**）——部署验收实测发现：
     * 全部异常路径只有日志、没有指标，「到底有没有尝试发」在线上不可观测。
     */
    static final String METRIC_SEND = "iot.platform_alert.notify.send";

    /** 投递成功次数（按站内信/邮件分别计）。 */
    static final String METRIC_SUCCEEDED = "iot.platform_alert.notify.succeeded";

    /** 投递失败次数（异常 + 对端返回失败）。 */
    static final String METRIC_FAILED = "iot.platform_alert.notify.failed";

    private final ObjectProvider<ISystemClient> systemClientProvider;
    private final PlatformAlertProperties properties;

    private final Counter sendCounter;
    private final Counter succeededCounter;
    private final Counter failedCounter;

    public PlatformAlertNotifier(ObjectProvider<ISystemClient> systemClientProvider,
                                 PlatformAlertProperties properties, MeterRegistry meterRegistry) {
        this.systemClientProvider = systemClientProvider;
        this.properties = properties;
        this.sendCounter = Counter.builder(METRIC_SEND)
            .description("平台告警通知尝试次数（含被开关/空收件人拦下的）")
            .register(meterRegistry);
        this.succeededCounter = Counter.builder(METRIC_SUCCEEDED)
            .description("平台告警投递成功次数")
            .register(meterRegistry);
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("平台告警投递失败次数（异常 + 对端返回失败）")
            .register(meterRegistry);
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
        sendCounter.increment();
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
            // 平台告警调度线程无租户上下文 ⇒ 与告警实例同口径取主租户（多租户部署需显式收件租户，登记见看板）
            req.setTenantId(PlatformAlertProperties.PLATFORM_TENANT_ID);
            req.setReceiverUserId(userId);
            req.setTitle(rule.getTitle() + "（" + kind + "）");
            req.setContent(content);
            try {
                sendInbox(systemClient, req, rule);
            } catch (RuntimeException ex) {
                failedCounter.increment();
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
                failedCounter.increment();
                log.error("[iot] 平台告警邮件投递异常：to={}, rule={}", email,
                    rule.getCode(), ex);
            }
        }
    }

    private void sendInbox(ISystemClient client, InboxMessageSendReq req,
                           PlatformHealthRule rule) {
        var result = client.sendInboxMessage(req);
        if (result == null || !result.isSuccess()) {
            log.warn("[iot] 平台告警站内信发送失败：rule={}, respMsg={}", rule.getCode(),
                result == null ? "null" : result.getMessage());
            failedCounter.increment();
            return;
        }
        succeededCounter.increment();
    }

    private void sendMail(ISystemClient client, MailSendReq req,
                          PlatformHealthRule rule) {
        var result = client.sendMail(req);
        if (result == null || !result.isSuccess()) {
            log.warn("[iot] 平台告警邮件发送失败：rule={}, respMsg={}", rule.getCode(),
                result == null ? "null" : result.getMessage());
            failedCounter.increment();
            return;
        }
        succeededCounter.increment();
    }

    private static String buildFiringText(PlatformHealthRule rule, String summary, String snapshot) {
        return "规则：" + rule.getTitle() + "；级别：" + rule.getSeverity().getCode()
            + "；概要：" + summary + "；指标快照：" + (snapshot == null ? "-" : snapshot);
    }

    private static String buildResolvedText(PlatformHealthRule rule, String summary) {
        return "规则：" + rule.getTitle() + "；已恢复。概要：" + summary;
    }
}
