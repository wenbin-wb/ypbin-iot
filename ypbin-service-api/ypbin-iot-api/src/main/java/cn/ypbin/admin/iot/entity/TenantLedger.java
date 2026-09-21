/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.data.core.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户台账（M0b-2 可分配来源 + M0b-3 配置版本号）。
 *
 * <p>{@link #assignable} 取代原先「读配置 {@code ypbin.lease.assignable-tenant-ids}」的分配来源，
 * 使「哪些租户可被接入」成为可运维的数据而不是重启才生效的配置；
 * {@link #configEpoch} 在台账变更时**同事务**递增，供变更推送侧做「不一致才拉全量」的对账。</p>
 *
 * <p>本表是**平台表**（它本身描述租户，不能被租户条件过滤）⇒ 必须登记在
 * {@code ypbin.tenant.ignore-tables}。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
@TableName("tenant_ledger")
public class TenantLedger extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 租户 ID（唯一）。 */
    private Long tenantId;

    /** 是否可分配：{@code true} 可、{@code false} 不可。 */
    private Boolean assignable;

    /** 配置版本号：台账变更同事务 +1。 */
    private Long configEpoch;
}
