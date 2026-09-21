/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 物模型属性新增/编辑请求（§3.4）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotPropertyReq {

    /** 所属服务 ID（新增时必填；编辑时可空）。 */
    private Long serviceId;

    /** 属性标识（camelCase，服务内唯一）。 */
    @NotBlank(message = "属性标识不能为空")
    @Pattern(regexp = "[a-z][A-Za-z0-9]*", message = "属性标识格式非法（应为小写字母开头，仅含字母与数字）")
    @Size(max = 64, message = "属性标识长度不能超过 64")
    private String identifier;

    /** 属性名称。 */
    @NotBlank(message = "属性名称不能为空")
    @Size(max = 128, message = "属性名称长度不能超过 128")
    private String propertyName;

    /** 数据类型（9 类之一）。 */
    @NotBlank(message = "数据类型不能为空")
    @Pattern(regexp = "int|long|decimal|string|bool|enum|date_time|json_object|array",
            message = "数据类型非法（应为 int|long|decimal|string|bool|enum|date_time|json_object|array）")
    private String dataType;

    /** 读写权限：R | W | RW。 */
    @NotBlank(message = "读写权限不能为空")
    @Pattern(regexp = "R|W|RW", message = "读写权限非法（应为 R|W|RW）")
    private String accessMode;

    /** 是否必选。 */
    private Boolean required;

    /** 最小值。 */
    @DecimalMin(value = "0", message = "最小值不能为负")
    @Digits(integer = 14, fraction = 6, message = "最小值超出精度范围")
    private BigDecimal minValue;

    /** 最大值。 */
    @Digits(integer = 14, fraction = 6, message = "最大值超出精度范围")
    private BigDecimal maxValue;

    /** 步长。 */
    @Digits(integer = 14, fraction = 6, message = "步长超出精度范围")
    private BigDecimal step;

    /** 最大长度（string 类型）。 */
    private Integer maxLength;

    /** 单位。 */
    @Size(max = 32, message = "单位长度不能超过 32")
    private String unit;

    /** 枚举取值（JSON 数组，enum 类型）。 */
    @Size(max = 2000, message = "枚举定义长度不能超过 2000")
    private String enumList;

    /** 默认值。 */
    @Size(max = 255, message = "默认值长度不能超过 255")
    private String defaultValue;

    /** 扩展字段（JSON，预留）。 */
    @Size(max = 4000, message = "扩展字段长度不能超过 4000")
    private String expand;

    /** 排序。 */
    private Integer sort;
}
