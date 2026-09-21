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
 * TSL 命令定义（对齐 IoTDA servicetype-capability.json 的 commands 数组元素）。
 *
 * <p>{@code commandName} 对齐 IoTDA UPPER_SNAKE；{@code paras} 入参、{@code responses} 出参。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslCommand {

    /** 命令标识（UPPER_SNAKE）。 */
    @NotBlank(message = "命令标识不能为空")
    @Size(max = 64, message = "命令标识长度不能超过 64")
    private String commandName;

    /** 命令名称（展示用，可空）。 */
    @Size(max = 128, message = "命令名称长度不能超过 128")
    private String description;

    /** 入参定义。 */
    @Valid
    private List<TslPara> paras;

    /** 出参定义。 */
    @Valid
    private List<TslPara> responses;

    /** 命令超时（毫秒；缺省用全局默认）。 */
    private Integer timeoutMs;

    /** 排序。 */
    private Integer sort;
}
