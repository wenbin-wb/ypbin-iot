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
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 物模型属性（§3.4）。
 *
 * <p>{@code identifier} 对齐 IoTDA camelCase，服务内唯一；{@code dataType} 对齐 IoTDA 在线开发 9 类。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_property")
public class IotProperty extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属服务 ID。 */
    private Long serviceId;

    /** 属性标识（camelCase，服务内唯一）。 */
    private String identifier;

    /** 属性名称。 */
    private String propertyName;

    /** 数据类型（9 类之一）。 */
    private String dataType;

    /** 读写权限：R | W | RW。 */
    private String accessMode;

    /** 是否必选：1 是 0 否。 */
    private Boolean required;

    /** 最小值。 */
    private BigDecimal minValue;

    /** 最大值。 */
    private BigDecimal maxValue;

    /** 步长。 */
    private BigDecimal step;

    /** 最大长度（string 类型）。 */
    private Integer maxLength;

    /** 单位。 */
    private String unit;

    /** 枚举取值（JSON 数组，enum 类型）。 */
    private String enumList;

    /** 默认值。 */
    private String defaultValue;

    /** 扩展字段（JSON，预留）。 */
    private String expand;

    /** 排序。 */
    private Integer sort;
}
