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
 * IoT 设备新增/编辑请求。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class IotDeviceReq {

    /** 设备编码：租户内唯一。 */
    @NotBlank(message = "设备编码不能为空")
    @Size(max = 64, message = "设备编码长度不能超过 64")
    private String deviceCode;

    /** 设备名称。 */
    @NotBlank(message = "设备名称不能为空")
    @Size(max = 100, message = "设备名称长度不能超过 100")
    private String deviceName;

    /** 接入协议码（与 ypbin-iot-starter 的协议码一致：小写字母/数字/连字符）。 */
    @NotBlank(message = "接入协议不能为空")
    @Pattern(regexp = "[a-z][a-z0-9-]*", message = "协议码格式非法（应为小写字母开头，仅含小写字母、数字与连字符）")
    private String protocol;

    /** 端点 URI（必须带 scheme，例如 tcp://host:port）。 */
    @NotBlank(message = "端点不能为空")
    @Size(max = 300, message = "端点长度不能超过 300")
    private String endpoint;

    /** 绑定产品 ID（M-1，§4.1；可选，草稿设备可不绑）。 */
    private Long productId;

    /** 绑定物模型版本（如 v1.0，§3.8；可选）。 */
    @Size(max = 32, message = "物模型版本长度不能超过 32")
    private String productVersion;

    /** 备注。 */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;
}
