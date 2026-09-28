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

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.req.InboxMessageSendReq;
import cn.ypbin.admin.system.model.req.MailSendReq;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 通过 system 服务的内部端点投递通知（**复用既有能力**：站内信写 {@code sys_message}、邮件走既有 JavaMail）。
 *
 * <p><b>为什么经 Feign 而不是直连库/直连 SMTP</b>：{@code ypbin-iot} 不直连共享库也不持有邮件配置
 * （仓内纪律：各服务只碰自己的库）。站内信与邮件的能力都在 system 侧，因此这里是**远程调用**——
 * 而所有远程调用都必须有超时与失败语义（仓内铁律）：超时由 Feign 客户端的既有配置控制
 * （{@code ypbin.cloud.feign.*-timeout}），失败由调用方记为 {@code FAILED} 并按退避重试。</p>
 *
 * <p><b>失败必须原样带回</b>：{@code R.code != 200} 时把 {@code R.message} 记进
 * {@code last_error}，页面上能回答「为什么这条通知没送到」；**绝不**把失败当成功。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class SystemAlertNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SystemAlertNotificationSender.class);

    /** system 客户端（可空：单测/裁剪部署下可能没有 Feign）。 */
    private final ObjectProvider<ISystemClient> systemClientProvider;

    private final AlertNotificationComposer composer;

    public SystemAlertNotificationSender(ObjectProvider<ISystemClient> systemClientProvider,
                                         AlertNotificationComposer composer) {
        this.systemClientProvider = systemClientProvider;
        this.composer = composer;
    }

    /**
     * 一次投递的结果。
     *
     * @param success 是否成功
     * @param error   失败原因（面向人，原样记入 {@code last_error}）
     */
    public record SendResult(boolean success, String error) {

        /** 成功。 */
        public static SendResult ok() {
            return new SendResult(true, null);
        }

        /** 失败。 */
        public static SendResult fail(String error) {
            return new SendResult(false, error);
        }
    }

    /**
     * 投递一条通知。
     *
     * @param notification 投递记录（渠道 + 收件人）
     * @param instance     告警实例
     * @param device       设备（可空）
     * @param rule         规则（可空）
     * @param event        通知事件
     * @return 结果（**不抛异常**：失败也要落库成可查的 FAILED/GIVEN_UP，见设计 §2.1 表 D）
     */
    public SendResult send(IotAlertNotification notification, IotAlertInstance instance, IotDevice device,
                           IotAlertRule rule, AlertNotifyEvent event) {
        ISystemClient systemClient = systemClientProvider.getIfAvailable();
        if (systemClient == null) {
            return SendResult.fail("系统服务客户端未装配（无法投递站内信/邮件）：请检查服务依赖与注册中心");
        }
        AlertNotificationComposer.AlertNotificationText text =
            composer.compose(instance, device, rule, event);
        try {
            R<Void> result;
            if (AlertChannel.INBOX.getCode().equals(notification.getChannel())) {
                result = systemClient.sendInboxMessage(
                    inboxRequest(notification, instance, text));
            } else if (AlertChannel.EMAIL.getCode().equals(notification.getChannel())) {
                result = systemClient.sendMail(mailRequest(notification, text));
            } else {
                // 未知渠道：**不静默**（回到这里说明渠道校验漏了，必须能被看见）
                return SendResult.fail("未知的通知渠道：" + notification.getChannel());
            }
            if (result == null) {
                return SendResult.fail("系统服务返回空响应（投递结果不可判定）");
            }
            if (!result.isSuccess()) {
                return SendResult.fail("系统服务返回失败：" + result.getMessage());
            }
            return SendResult.ok();
        } catch (RuntimeException ex) {
            // 远程调用异常（超时/连接失败/序列化）：记完整堆栈，并把可读原因带给投递记录
            log.error("[iot] 告警通知投递异常：channel={} target={} instanceId={}",
                notification.getChannel(), LogSanitizer.sanitize(notification.getTarget()),
                notification.getInstanceId(), ex);
            return SendResult.fail(ex.getClass().getSimpleName() + "：" + ex.getMessage());
        }
    }

    /** 站内信请求：收件人是用户 ID（投递计划阶段已按形态分流）。 */
    private static InboxMessageSendReq inboxRequest(IotAlertNotification notification,
                                                    IotAlertInstance instance,
                                                    AlertNotificationComposer.AlertNotificationText text) {
        InboxMessageSendReq req = new InboxMessageSendReq();
        req.setTenantId(instance.getTenantId());
        req.setReceiverUserId(parseUserId(notification.getTarget()));
        req.setTitle(text.title());
        req.setContent(text.content());
        return req;
    }

    /** 邮件请求。 */
    private static MailSendReq mailRequest(IotAlertNotification notification,
                                           AlertNotificationComposer.AlertNotificationText text) {
        MailSendReq req = new MailSendReq();
        req.setTo(notification.getTarget());
        req.setSubject(text.title());
        req.setContent(text.content());
        return req;
    }

    /**
     * 收件人用户 ID 解析。
     *
     * <p>投递计划阶段已按「纯数字 ⇒ 站内信」分流，因此这里解析失败只可能是数据被人工改过；
     * 抛 {@link IllegalArgumentException} 由上层转成 FAILED 原因（**不静默丢弃**）。</p>
     */
    private static Long parseUserId(String target) {
        try {
            return Long.valueOf(target);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("站内信收件人不是合法的用户 ID：" + target, ex);
        }
    }
}
