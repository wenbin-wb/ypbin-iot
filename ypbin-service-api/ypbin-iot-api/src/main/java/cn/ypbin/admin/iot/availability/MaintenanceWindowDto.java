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
import lombok.Getter;
import lombok.Setter;

/**
 * 维护窗口（可用率响应里回显，便于解释「这段时间为什么不算断档」）。
 *
 * @author wenbin
 * @since 2026-09-23
 */
@Getter
@Setter
public class MaintenanceWindowDto {

    /** 窗口 ID。 */
    private Long id;

    /** 设备 ID；{@code null}=该租户全部设备。 */
    private Long deviceId;

    /** 窗口开始。 */
    private LocalDateTime startTs;

    /** 窗口结束；{@code null}=进行中。 */
    private LocalDateTime endTs;

    /** 来源码。 */
    private String source;

    /** 说明。 */
    private String reason;
}
