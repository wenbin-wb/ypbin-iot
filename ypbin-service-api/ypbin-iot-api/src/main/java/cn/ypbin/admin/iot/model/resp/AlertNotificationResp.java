/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 通知投递记录视图（页面上回答「到底发出去没有、发到谁、失败原因是什么」）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertNotificationResp {

    /** 记录 ID。 */
    private Long id;

    /** 渠道码：INBOX / EMAIL。 */
    private String channel;

    /** 收件人（用户 ID 或邮箱）。 */
    private String target;

    /** 通知事件码：FIRING / RESOLVED / REPEAT / ACKED。 */
    private String event;

    /** 投递状态码：PENDING / SENT / FAILED / GIVEN_UP。 */
    private String notifyStatus;

    /** 已尝试次数。 */
    private Integer attempt;

    /** 下次重试时刻（可空）。 */
    private LocalDateTime nextRetryTs;

    /** 最近一次错误（**原样**，不美化）。 */
    private String lastError;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
