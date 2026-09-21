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

import lombok.Getter;
import lombok.Setter;

/**
 * 单个租户的台账版本号。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class TenantEpochItem {

    /** 租户 ID。 */
    private Long tenantId;

    /** **归属/租约**版本号：归属每次转移递增（来自 {@code tenant_node_assignment.epoch}）。 */
    private Long epoch;

    /**
     * **配置/台账**版本号（M0b-3）：该租户的台账（是否可分配等）每次变更在**同一事务内** +1。
     *
     * <p>与 {@link #epoch} 的区别很重要：前者管「谁在采」，后者管「要采什么」。
     * 接入侧按「不一致才拉全量」对账时用的就是这个值——设备清单变了才重拉，避免每轮都打远端。</p>
     */
    private Long configEpoch;
}
