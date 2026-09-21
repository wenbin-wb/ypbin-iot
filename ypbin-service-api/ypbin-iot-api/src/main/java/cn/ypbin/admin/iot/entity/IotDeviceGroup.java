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
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备分组（§3.11，树形）。
 *
 * <p>租户内分组，{@code groupName} 租户内唯一；{@code parentId} 为空表示根分组。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_device_group")
public class IotDeviceGroup extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分组名称（租户内唯一）。 */
    private String groupName;

    /** 父分组 ID（null=根）。 */
    private Long parentId;

    /** 排序。 */
    private Integer sort;

    /** 备注。 */
    private String remark;
}
