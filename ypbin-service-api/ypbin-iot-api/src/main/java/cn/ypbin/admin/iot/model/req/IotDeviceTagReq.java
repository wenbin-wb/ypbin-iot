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
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备标签新增/编辑请求（§3.11，key/value）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotDeviceTagReq {

    /** 标签键。 */
    @NotBlank(message = "标签键不能为空")
    @Size(max = 64, message = "标签键长度不能超过 64")
    private String tagKey;

    /** 标签值。 */
    @NotBlank(message = "标签值不能为空")
    @Size(max = 255, message = "标签值长度不能超过 255")
    private String tagValue;
}
