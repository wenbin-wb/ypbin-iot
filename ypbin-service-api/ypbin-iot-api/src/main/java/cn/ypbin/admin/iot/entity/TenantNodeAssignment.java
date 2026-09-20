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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户节点归属（平台级表：**不继承 {@code TenantBaseEntity}**）。
 *
 * <p>为什么是平台表：它记录的是「哪个 access 节点在采哪个租户」，本身就是跨租户的平台元数据；
 * 若继承租户基类，租户插件会给它追加 {@code tenant_id} 条件，导致跨租户的对账/失效扫描查不到数据。
 * 因此必须在 {@code ypbin.tenant.ignore-tables} 里登记本表（见 {@code deploy/nacos/ypbin-iot.yaml}）。</p>
 *
 * <p>状态列存的是 {@code LeaseState} 的**稳定码**（{@code active}/{@code pending_takeover}/{@code released}），
 * 不存 ordinal。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
@TableName("tenant_node_assignment")
public class TenantNodeAssignment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 租户 ID（唯一：一个租户同一时刻只有一个归属）。 */
    private Long tenantId;

    /** 当前归属的 access 节点标识。 */
    private String accessNode;

    /** 台账版本号（归属每次变更都推进）。 */
    private Long epoch;

    /** 租约状态稳定码。 */
    private String state;

    /** 租约到期时间。 */
    private LocalDateTime leaseExpireAt;
}
