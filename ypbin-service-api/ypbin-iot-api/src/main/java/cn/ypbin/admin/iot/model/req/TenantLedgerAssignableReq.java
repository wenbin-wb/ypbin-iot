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

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户台账「可分配」写请求。
 *
 * <p>是平台级运维动作（决定哪些租户可被 access 节点领取），不是租户内的业务配置——
 * 对应的权限码为平台管理员专属，见 {@code IotTenantLedgerController} 的说明。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
public class TenantLedgerAssignableReq {

    /** 是否可被分配：{@code true} 可分配、{@code false} 撤销可分配（不回收已分配租约）。 */
    @NotNull(message = "可分配标记不能为空")
    private Boolean assignable;
}
