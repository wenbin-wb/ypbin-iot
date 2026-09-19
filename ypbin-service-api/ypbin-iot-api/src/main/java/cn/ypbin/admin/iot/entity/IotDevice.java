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
 * IoT 设备台账。
 *
 * <p>继承 {@link TenantBaseEntity} ⇒ 落库自动带 {@code tenant_id}，查询由租户插件自动加条件
 * （不需要在业务代码里手写租户过滤，也不允许手写——见多租户安全底线）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
@TableName("iot_device")
public class IotDevice extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设备编码（租户内唯一，设备侧上报时用它定位）。 */
    private String deviceCode;

    /** 设备名称。 */
    private String deviceName;

    /** 接入协议：tcp | modbus | mqtt | opcua（与 ypbin-iot-starter 的协议码一致）。 */
    private String protocol;

    /** 端点 URI，例如 tcp://127.0.0.1:15002。 */
    private String endpoint;

    /** 备注。 */
    private String remark;
}
