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
 * IoT 产品响应模型（§3.2）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotProductResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 产品编码。 */
    private String productCode;

    /** 产品名称。 */
    private String productName;

    /** 接入协议码。 */
    private String protocol;

    /** 数据格式。 */
    private String dataFormat;

    /** 设备类型描述。 */
    private String deviceType;

    /** 厂商 ID。 */
    private String manufacturerId;

    /** 厂商名称。 */
    private String manufacturerName;

    /** 物模型状态：draft | published。 */
    private String modelStatus;

    /** 当前物模型版本号（如 v1.0；未发布时为当前草稿版本号）。 */
    private String currentVersion;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
