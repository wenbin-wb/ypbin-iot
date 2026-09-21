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
 * IoT 物模型事件（§3.6，平台在服务层的扩展）。
 *
 * <p>{@code identifier} 对齐属性 camelCase 风格，服务内唯一；
 * 事件为主动上报的告警/故障/状态（IoTDA 的事件仅存在于运行期 topic/payload，不在其 TSL 结构内）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_event")
public class IotEvent extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属服务 ID。 */
    private Long serviceId;

    /** 事件标识（camelCase，服务内唯一）。 */
    private String identifier;

    /** 事件名称。 */
    private String eventName;

    /** 数据类型（同属性）。 */
    private String dataType;

    /** 最大长度。 */
    private Integer maxLength;

    /** 单位。 */
    private String unit;

    /** 枚举取值（JSON 数组）。 */
    private String enumList;

    /** 排序。 */
    private Integer sort;
}
