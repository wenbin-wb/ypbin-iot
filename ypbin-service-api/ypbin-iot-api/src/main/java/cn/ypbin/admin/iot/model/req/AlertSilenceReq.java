/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 一键静默请求（**静默不是状态**：只推迟通知，判定与状态机完全不受影响，设计 §2.3）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertSilenceReq {

    /** 实例 ID。 */
    @NotEmpty(message = "请选择要静默的告警")
    private List<Long> ids;

    /** 静默时长（分钟；默认 60 = 用户口径里的「一键静默 1 小时」）。 */
    @Min(value = 1, message = "静默时长至少 1 分钟")
    @Max(value = 10_080, message = "静默时长最多 7 天")
    private Integer minutes = 60;
}
