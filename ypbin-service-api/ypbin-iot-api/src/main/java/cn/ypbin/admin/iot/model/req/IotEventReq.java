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
 * IoT 物模型事件新增/编辑请求（§3.6，平台在服务层的扩展）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotEventReq {

    /** 所属服务 ID（新增时必填；编辑时可空）。 */
    private Long serviceId;

    /** 事件标识（camelCase，服务内唯一）。 */
    @NotBlank(message = "事件标识不能为空")
    @Pattern(regexp = "[a-z][A-Za-z0-9]*", message = "事件标识格式非法（应为小写字母开头，仅含字母与数字）")
    @Size(max = 64, message = "事件标识长度不能超过 64")
    private String identifier;

    /** 事件名称。 */
    @NotBlank(message = "事件名称不能为空")
    @Size(max = 128, message = "事件名称长度不能超过 128")
    private String eventName;

    /** 数据类型（9 类之一）。 */
    @NotBlank(message = "数据类型不能为空")
    @Pattern(regexp = "int|long|decimal|string|bool|enum|date_time|json_object|array",
            message = "数据类型非法（应为 int|long|decimal|string|bool|enum|date_time|json_object|array）")
    private String dataType;

    /** 最大长度。 */
    private Integer maxLength;

    /** 单位。 */
    @Size(max = 32, message = "单位长度不能超过 32")
    private String unit;

    /** 枚举取值（JSON 数组）。 */
    @Size(max = 2000, message = "枚举定义长度不能超过 2000")
    private String enumList;

    /** 排序。 */
    private Integer sort;
}
