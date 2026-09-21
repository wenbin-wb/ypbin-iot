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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 物模型命令新增/编辑请求（§3.5）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotCommandReq {

    /** 所属服务 ID（新增时必填；编辑时可空）。 */
    private Long serviceId;

    /** 命令标识（UPPER_SNAKE，服务内唯一）。 */
    @NotBlank(message = "命令标识不能为空")
    @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "命令标识格式非法（应为大写字母开头，仅含大写字母、数字与下划线）")
    @Size(max = 64, message = "命令标识长度不能超过 64")
    private String identifier;

    /** 命令名称。 */
    @NotBlank(message = "命令名称不能为空")
    @Size(max = 128, message = "命令名称长度不能超过 128")
    private String commandName;

    /** 入参定义（JSON：字段/类型/必填/范围/枚举）。 */
    @Size(max = 4000, message = "入参定义长度不能超过 4000")
    private String inputParams;

    /** 出参定义（JSON）。 */
    @Size(max = 4000, message = "出参定义长度不能超过 4000")
    private String outputParams;

    /** 命令超时（毫秒；缺省用全局默认）。 */
    private Integer timeoutMs;

    /** 排序。 */
    private Integer sort;
}
