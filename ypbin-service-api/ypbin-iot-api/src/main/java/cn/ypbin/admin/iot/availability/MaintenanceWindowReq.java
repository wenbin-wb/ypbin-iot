/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import java.time.LocalDateTime;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 声明/关闭维护窗口的请求（内部端点用；租户从上下文取，**不来自请求体**）。
 *
 * @author wenbin
 * @since 2026-09-23
 */
@Getter
@Setter
public class MaintenanceWindowReq {

    /** 窗口 ID（关闭时必填）。 */
    private Long id;

    /** 设备 ID；{@code null}=该租户全部设备。 */
    private Long deviceId;

    /** 窗口开始；{@code null}=取服务端当前时间。 */
    private LocalDateTime startTs;

    /** 窗口结束；{@code null}=进行中（关闭时按服务端当前时间补上）。 */
    private LocalDateTime endTs;

    /** 说明。 */
    @Size(max = 255, message = "说明最长 255 字符")
    private String reason;
}
