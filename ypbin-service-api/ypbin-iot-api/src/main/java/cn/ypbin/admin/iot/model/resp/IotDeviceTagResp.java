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
 * IoT 设备标签响应模型（§3.11）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotDeviceTagResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 设备 ID。 */
    private Long deviceId;

    /** 标签键。 */
    private String tagKey;

    /** 标签值。 */
    private String tagValue;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
