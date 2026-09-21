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
 * IoT 物模型服务响应模型（§3.3）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotServiceResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 所属产品 ID。 */
    private Long productId;

    /** 服务标识（PascalCase）。 */
    private String serviceId;

    /** 服务名称。 */
    private String serviceName;

    /** 服务选项。 */
    private String option;

    /** 排序。 */
    private Integer sort;

    /** 描述。 */
    private String description;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
