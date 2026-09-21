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
 * TSL 事件定义（平台在服务层的扩展，§3.6）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslEvent {

    /** 事件标识（camelCase）。 */
    @NotBlank(message = "事件标识不能为空")
    @Size(max = 64, message = "事件标识长度不能超过 64")
    private String eventName;

    /** 事件名称（展示用，可空）。 */
    @Size(max = 128, message = "事件名称长度不能超过 128")
    private String description;

    /** 数据类型（9 类之一）。 */
    @NotBlank(message = "事件数据类型不能为空")
    private String dataType;

    /** 最大长度。 */
    private String maxLength;

    /** 单位。 */
    private String unit;

    /** 枚举取值。 */
    private java.util.List<String> enumList;

    /** 排序。 */
    private Integer sort;
}
