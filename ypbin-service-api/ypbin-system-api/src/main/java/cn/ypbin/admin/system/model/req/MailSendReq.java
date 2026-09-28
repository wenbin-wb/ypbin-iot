/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.model.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 内部端点：发一封**纯文本邮件**（复用 system 侧已有的 JavaMail 能力，不新造邮件发送链路）。
 *
 * <p>超时由既有 {@code MailService} 的 {@code MailConfig.timeout} 统一控制（仓内铁律：所有远程调用必须
 * 显式配超时），本请求不重复暴露超时参数。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class MailSendReq {

    /** 收件人邮箱（必填）。 */
    @NotBlank(message = "收件人不能为空")
    @Email(message = "收件人必须是合法邮箱")
    @Size(max = 191, message = "收件人过长")
    private String to;

    /** 主题（必填）。 */
    @NotBlank(message = "主题不能为空")
    @Size(max = 255, message = "主题过长")
    private String subject;

    /** 正文（纯文本）。 */
    @Size(max = 20000, message = "正文过长")
    private String content;
}
