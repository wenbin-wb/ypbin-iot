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

    /**
     * 本地台账版本号。
     *
     * <p><b>它不参与「是否拒绝续约」的判定</b>（见 {@code docs/LEASE.md} §4）：服务端只认「归属是否仍在本节点名下」，
     * 并把这<b>服务端</b> epoch 放进回执让节点自更新；把本地落后当吊销会把一次视图滞后放大成整租户断链。
     * 字段保留为必填是为了让节点显式声明自己的视图版本（对账与排障用），未来可演进为乐观锁。</p>
     */
    @NotNull(message = "台账版本号不能为空")
    private Long epoch;
}
