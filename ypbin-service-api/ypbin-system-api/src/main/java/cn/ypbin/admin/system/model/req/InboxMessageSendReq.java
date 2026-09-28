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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 内部端点：写一条**普通站内信**（非公告来源）。
 *
 * <p><b>为什么显式带 {@code tenantId}</b>：调用方（如 IoT 告警投递器）运行在**调度线程**里，
 * 自身没有租户登录态，而 {@code sys_message} 是租户表（租户插件 fail-closed）。由调用方声明租户、
 * 服务端进入该租户上下文写入，与既有 {@code /internal/log-ingest} 等内部端点的信任模型一致
 * （{@code /internal/**} 由内部令牌守卫，只允许平台内网服务调用）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class InboxMessageSendReq {

    /** 租户 ID（必填；调用方声明，服务端据此进入租户上下文）。 */
    @NotNull(message = "租户 ID 不能为空")
    private Long tenantId;

    /** 接收人用户 ID（必填）。 */
    @NotNull(message = "接收人不能为空")
    private Long receiverUserId;

    /** 标题（必填）。 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题过长")
    private String title;

    /** 正文。 */
    @Size(max = 4000, message = "正文过长")
    private String content;
}
