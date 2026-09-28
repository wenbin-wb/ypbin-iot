/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.system.entity.SysMessage;
import cn.ypbin.admin.system.entity.SysUser;
import cn.ypbin.admin.system.mapper.SysConfigMapper;
import cn.ypbin.admin.system.mapper.SysLogMapper;
import cn.ypbin.admin.system.mapper.SysMessageMapper;
import cn.ypbin.admin.system.model.req.InboxMessageSendReq;
import cn.ypbin.admin.system.model.req.MailSendReq;
import cn.ypbin.admin.system.service.SocialBindService;
import cn.ypbin.admin.system.service.SysMenuService;
import cn.ypbin.admin.system.service.SysPermissionService;
import cn.ypbin.admin.system.service.SysUserService;
import cn.ypbin.admin.system.social.SocialConfigReader;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.dao.LogDao;
import cn.ypbin.starter.messaging.mail.MailService;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tracking.core.TrackRecorder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 内部通知端点的用例（独立复核 2026-10-03 M2：新增对外行为必须补验证）。
 *
 * <p>判据：① 站内信落库成功 ⇒ 返回成功 R，且**进入声明的租户上下文**；
 * ② 收件人不存在/不属于该租户 ⇒ 返回**失败** R（不能写进库而收件人看不到）；
 * ③ 邮件发送抛异常 ⇒ 返回**失败** R（不静默成功——否则调用方会记成「已发送」）。</p>
 */
class SystemInternalNotifyTest {

    private final SysUserService userService = mock(SysUserService.class);

    private final SysMessageMapper messageMapper = mock(SysMessageMapper.class);

    private final MailService mailService = mock(MailService.class);

    private SystemClientImpl controller() {
        return new SystemClientImpl(
            mock(SysPermissionService.class),
            userService,
            mock(SysConfigMapper.class),
            mock(SocialConfigReader.class),
            mock(SocialBindService.class),
            mock(SysMenuService.class),
            mock(LogDao.class),
            messageMapper,
            mailService,
            providerOfTrackRecorder());
    }

    private static ObjectProvider<TrackRecorder> providerOfTrackRecorder() {
        return mock(ObjectProvider.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static InboxMessageSendReq inbox(Long receiver) {
        InboxMessageSendReq req = new InboxMessageSendReq();
        req.setTenantId(1L);
        req.setReceiverUserId(receiver);
        req.setTitle("【严重】演示设备 temperature 越界");
        req.setContent("级别：严重");
        return req;
    }

    @Test
    @DisplayName("★ 站内信成功：进入声明的租户上下文写入，并返回成功 R")
    void inboxSuccess() {
        when(userService.getById(1001L)).thenReturn(new SysUser());
        AtomicReference<Long> tenantSeen = new AtomicReference<>();
        when(messageMapper.insertPlainMessage(any(SysMessage.class))).thenAnswer(invocation -> {
            tenantSeen.set(TenantContext.getTenantId().orElse(null));
            return 1;
        });

        R<Void> result = controller().sendInboxMessage(inbox(1001L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(tenantSeen.get()).as("必须进入请求声明的租户上下文").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 收件人不存在 ⇒ 失败 R 且**不写库**（否则投递记录标「已发送」而用户永远看不到）")
    void inboxMissingReceiverFails() {
        when(userService.getById(anyLong())).thenReturn(null);

        R<Void> result = controller().sendInboxMessage(inbox(9999L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("收件人不存在");
        verify(messageMapper, never()).insertPlainMessage(any(SysMessage.class));
    }

    @Test
    @DisplayName("站内信落库异常 ⇒ 失败 R（不静默成功）")
    void inboxInsertFailureReturnsFail() {
        when(userService.getById(anyLong())).thenReturn(new SysUser());
        when(messageMapper.insertPlainMessage(any(SysMessage.class)))
            .thenThrow(new IllegalStateException("模拟写库失败"));

        R<Void> result = controller().sendInboxMessage(inbox(1001L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("站内信写入失败");
    }

    @Test
    @DisplayName("★ 邮件发送抛异常 ⇒ 失败 R（调用方据此退避重试，而不是以为已发出）")
    void mailFailureReturnsFail() {
        doThrow(new IllegalStateException("SMTP 连接超时")).when(mailService)
            .sendText(anyString(), anyString(), anyString());
        MailSendReq req = new MailSendReq();
        req.setTo("ops@example.com");
        req.setSubject("【严重】演示设备 temperature 越界");
        req.setContent("级别：严重");

        R<Void> result = controller().sendMail(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("邮件发送失败");
    }

    @Test
    @DisplayName("邮件成功 ⇒ 成功 R")
    void mailSuccess() {
        MailSendReq req = new MailSendReq();
        req.setTo("ops@example.com");
        req.setSubject("主题");
        req.setContent("正文");
        assertThat(controller().sendMail(req).isSuccess()).isTrue();
        verify(mailService).sendText("ops@example.com", "主题", "正文");
    }
}
