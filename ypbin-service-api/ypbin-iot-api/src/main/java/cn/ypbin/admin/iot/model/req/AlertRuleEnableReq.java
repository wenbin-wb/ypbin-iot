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

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量启用/停用规则请求（页面上是「批量启用 / 批量停用」两个按钮）。
 *
 * <p><b>停用不删除</b>：停用会把该规则的活动实例收口为 {@code RESOLVED/RULE_DISABLED}
 * 并通知一次——否则会留下永不消解的幽灵告警（规则不再被评估，没人能把它恢复）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertRuleEnableReq {

    /** 规则 ID。 */
    @NotEmpty(message = "请选择要操作的规则")
    private List<Long> ids;

    /** 目标启用状态。 */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}
