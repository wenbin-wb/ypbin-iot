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
 * IoT 设备分组-设备成员（§3.11 多对多）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_device_group_member")
public class IotDeviceGroupMember extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分组 ID。 */
    private Long groupId;

    /** 设备 ID。 */
    private Long deviceId;
}
