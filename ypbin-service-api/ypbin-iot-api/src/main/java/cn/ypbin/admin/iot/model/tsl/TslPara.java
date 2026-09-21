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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * TSL 命令参数定义（对齐 IoTDA 命令的 paras / responses 数组元素）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslPara {

    /** 参数名（camelCase）。 */
    @NotBlank(message = "参数名不能为空")
    @Size(max = 64, message = "参数名长度不能超过 64")
    private String paraName;

    /** 数据类型（9 类之一）。 */
    @NotBlank(message = "参数数据类型不能为空")
    private String dataType;

    /** 是否必填。 */
    private Boolean required;

    /** 最小值。 */
    private String min;

    /** 最大值。 */
    private String max;

    /** 步长。 */
    private String step;

    /** 最大长度。 */
    private String maxLength;

    /** 单位。 */
    private String unit;

    /** 枚举取值。 */
    private java.util.List<String> enumList;
}
