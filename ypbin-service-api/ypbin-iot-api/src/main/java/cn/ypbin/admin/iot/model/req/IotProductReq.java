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
 * IoT 产品新增/编辑请求（§3.2）。
 *
 * <p>{@code modelStatus} 由发布流程控制，不在编辑请求中透传；协议码对齐 iot-starter。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotProductReq {

    /** 产品编码（租户内唯一，小写字母/数字/连字符）。 */
    @NotBlank(message = "产品编码不能为空")
    @Pattern(regexp = "[a-z][a-z0-9-]*", message = "产品编码格式非法（应小写字母开头，仅含小写字母、数字与连字符）")
    @Size(max = 64, message = "产品编码长度不能超过 64")
    private String productCode;

    /** 产品名称。 */
    @NotBlank(message = "产品名称不能为空")
    @Size(max = 128, message = "产品名称长度不能超过 128")
    private String productName;

    /** 接入协议码（tcp | modbus | mqtt | opcua）。 */
    @NotBlank(message = "接入协议不能为空")
    @Pattern(regexp = "[a-z][a-z0-9-]*", message = "协议码格式非法（应为小写字母开头，仅含小写字母、数字与连字符）")
    private String protocol;

    /** 数据格式：json（默认）| binary（预留）。 */
    @Size(max = 16, message = "数据格式长度不能超过 16")
    private String dataFormat;

    /** 设备类型描述。 */
    @Size(max = 64, message = "设备类型长度不能超过 64")
    private String deviceType;

    /** 厂商 ID（可选）。 */
    @Size(max = 128, message = "厂商 ID 长度不能超过 128")
    private String manufacturerId;

    /** 厂商名称（可选）。 */
    @Size(max = 128, message = "厂商名称长度不能超过 128")
    private String manufacturerName;

    /** 备注。 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
