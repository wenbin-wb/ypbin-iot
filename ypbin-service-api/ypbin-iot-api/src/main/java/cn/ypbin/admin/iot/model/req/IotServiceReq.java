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
 * IoT 物模型服务新增/编辑请求（§3.3）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotServiceReq {

    /** 所属产品 ID（新增时必填；编辑时可空）。 */
    private Long productId;

    /** 服务标识（PascalCase，产品内唯一）。 */
    @NotBlank(message = "服务标识不能为空")
    @Pattern(regexp = "[A-Z][A-Za-z0-9]*", message = "服务标识格式非法（应为大写字母开头，仅含字母与数字）")
    @Size(max = 64, message = "服务标识长度不能超过 64")
    private String serviceId;

    /** 服务名称。 */
    @NotBlank(message = "服务名称不能为空")
    @Size(max = 128, message = "服务名称长度不能超过 128")
    private String serviceName;

    /** 服务选项：master | mandatory | optional。 */
    @NotBlank(message = "服务选项不能为空")
    @Pattern(regexp = "master|mandatory|optional", message = "服务选项非法（应为 master|mandatory|optional）")
    private String option;

    /** 排序。 */
    private Integer sort;

    /** 描述。 */
    @Size(max = 255, message = "描述长度不能超过 255")
    private String description;
}
