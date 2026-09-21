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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 点位映射响应模型（§3.9）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotPointMappingResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 设备 ID。 */
    private Long deviceId;

    /** 关联属性（或命令）ID。 */
    private Long propertyId;

    /** 关联类型：property | command。 */
    private String refType;

    /** 协议内地址。 */
    private String rawAddress;

    /** 地址类型。 */
    private String addressType;

    /** 采集周期（毫秒）。 */
    private Integer pollIntervalMs;

    /** 缩放系数。 */
    private BigDecimal scaleFactor;

    /** 缩放偏移。 */
    private BigDecimal offsetValue;

    /** 字节序。 */
    private String byteOrder;

    /** 读写权限。 */
    private String rw;

    /** 启用/停采。 */
    private Boolean enabled;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
