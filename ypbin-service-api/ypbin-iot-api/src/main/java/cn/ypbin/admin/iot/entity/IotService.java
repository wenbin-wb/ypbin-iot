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
 * IoT 物模型服务（§3.3，能力域）。
 *
 * <p>{@code serviceId} 对齐 IoTDA PascalCase，产品内唯一；一个产品可有多个服务。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_service")
public class IotService extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属产品 ID。 */
    private Long productId;

    /** 服务标识（PascalCase，产品内唯一）。 */
    private String serviceId;

    /** 服务名称。 */
    private String serviceName;

    /** 服务选项：master | mandatory | optional。 */
    private String option;

    /** 排序。 */
    private Integer sort;

    /** 描述。 */
    private String description;
}
