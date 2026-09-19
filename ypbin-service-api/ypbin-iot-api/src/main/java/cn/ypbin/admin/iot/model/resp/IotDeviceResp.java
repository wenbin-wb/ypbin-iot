/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备响应模型。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class IotDeviceResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 设备编码。 */
    private String deviceCode;

    /** 设备名称。 */
    private String deviceName;

    /** 接入协议。 */
    private String protocol;

    /** 端点 URI。 */
    private String endpoint;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
