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

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备影子（§3.10）。
 *
 * <p>M-1 先落库（{@code reported}/{@code desired} JSON + 时刻）；查询返回 reported 优先、
 * 无则回退 desired 的合并视图。Redis 化与最新值一起在 M-2（§5.3）落地，落库键仍按
 * {@code (tenant_id, device_id)} 唯一。影子只存最新值，历史走时序库。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_shadow")
public class IotShadow extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID。 */
    private Long deviceId;

    /** 设备上报值（JSON）。 */
    private String reported;

    /** 平台期望值（JSON）。 */
    private String desired;

    /** 最近上报时刻。 */
    private LocalDateTime reportTs;

    /** 最近期望时刻。 */
    private LocalDateTime desiredTs;
}
