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
 * IoT 物模型版本响应模型（§3.8）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotProductVersionResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 所属产品 ID。 */
    private Long productId;

    /** 版本号（语义化 v1.0）。 */
    private String versionNo;

    /** 版本状态：draft | published。 */
    private String modelStatus;

    /** 发布时间。 */
    private LocalDateTime publishedAt;

    /** 备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
