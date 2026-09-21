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
 * TSL 服务定义（对齐 IoTDA servicetype-capability.json 的 services 数组元素）。
 *
 * <p>{@code serviceType} 为服务标识（PascalCase）；属性/命令/事件挂在服务下。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslService {

    /** 服务标识（PascalCase）。 */
    @NotBlank(message = "服务标识不能为空")
    @Size(max = 64, message = "服务标识长度不能超过 64")
    private String serviceType;

    /** 服务名称。 */
    @NotBlank(message = "服务名称不能为空")
    @Size(max = 128, message = "服务名称长度不能超过 128")
    private String description;

    /** 服务选项：master | mandatory | optional。 */
    @NotBlank(message = "服务选项不能为空")
    private String option;

    /** 排序。 */
    private Integer sort;

    /** 属性定义。 */
    @Valid
    private List<TslProperty> properties;

    /** 命令定义。 */
    @Valid
    private List<TslCommand> commands;

    /** 事件定义。 */
    @Valid
    private List<TslEvent> events;
}
