/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.tsl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * TSL 属性定义（对齐 IoTDA servicetype-capability.json 的 properties 数组元素）。
 *
 * <p>导入方向全字段校验（命名规范/类型枚举/引用完整性）；{@code method} 用 {@code R|W|RW}。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslProperty {

    /** 属性标识（camelCase）。 */
    @NotBlank(message = "属性标识不能为空")
    @Size(max = 64, message = "属性标识长度不能超过 64")
    private String propertyName;

    /** 数据类型（9 类之一）。 */
    @NotBlank(message = "属性数据类型不能为空")
    private String dataType;

    /** 读写权限：R | W | RW。 */
    @NotBlank(message = "属性读写权限不能为空")
    private String method;

    /** 最小值。 */
    private String min;

    /** 最大值。 */
    private String max;

    /** 步长。 */
    private String step;

    /** 最大长度（string 类型）。 */
    private String maxLength;

    /** 单位。 */
    private String unit;

    /** 是否必选。 */
    private Boolean required;

    /** 枚举取值（enum 类型）。 */
    private List<String> enumList;

    /** 默认值。 */
    private String defaultValue;

    /** 扩展（透传，导入不校验）。 */
    private Object expand;

    /** 排序。 */
    private Integer sort;
}
