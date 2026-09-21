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
 * IoT 设备标签（§3.11，key/value）。
 *
 * <p>灵活检索与规则条件引用；同一设备同键唯一。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_device_tag")
public class IotDeviceTag extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备 ID。 */
    private Long deviceId;

    /** 标签键。 */
    private String tagKey;

    /** 标签值。 */
    private String tagValue;
}
