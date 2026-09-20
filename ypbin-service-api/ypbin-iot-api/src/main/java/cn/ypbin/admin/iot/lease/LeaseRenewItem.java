/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约请求中的单个租户：带上本地 epoch，服务端据此识别过期视图。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseRenewItem {

    /** 租户 ID。 */
    @NotNull(message = "租户 ID 不能为空")
    private Long tenantId;

    /** 本地台账版本号（与服务端不一致说明本地视图过期，服务端会拒绝续约并回收）。 */
    @NotNull(message = "台账版本号不能为空")
    private Long epoch;
}
