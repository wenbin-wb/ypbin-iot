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

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 数字孪生属性绑定的新增/编辑条目（**展示哪几个属性**）。
 *
 * <p>权威键是 {@code (serviceIdentifier, propertyIdentifier)} 两个业务标识；
 * 不接收 {@code propertyId}——属性主键会被 TSL 重导入销毁，把它放进请求会让调用方误以为
 * 主键是权威引用。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinBindingReq {

    /** 物模型服务标识。 */
    @NotBlank(message = "服务标识不能为空")
    @Size(max = 64, message = "服务标识长度不能超过 64")
    private String serviceIdentifier;

    /** 物模型属性标识。 */
    @NotBlank(message = "属性标识不能为空")
    @Size(max = 64, message = "属性标识长度不能超过 64")
    private String propertyIdentifier;

    /** 展示形态：value | gauge | switch | color | sparkline（service 侧用枚举白名单校验）。 */
    @NotBlank(message = "展示形态不能为空")
    private String displayType;

    /** 展示角色：primary | secondary（null 视为 secondary，service 侧统一归一）。 */
    private String displayRole;

    /** 小数位（0~6；null=原样展示）。 */
    @Min(value = 0, message = "小数位不能小于 0")
    @Max(value = TwinLimits.MAX_DECIMALS, message = "小数位不能大于 6")
    private Integer decimals;

    /** 单位覆盖（null=用物模型属性自带单位）。 */
    @Size(max = TwinLimits.MAX_UNIT_LENGTH, message = "单位长度不能超过 32")
    private String unitOverride;

    /** 排序。 */
    private Integer sort;
}
