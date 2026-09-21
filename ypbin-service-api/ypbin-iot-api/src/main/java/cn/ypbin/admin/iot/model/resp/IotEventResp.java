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
 * IoT 物模型事件响应模型（§3.6）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotEventResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 所属服务 ID。 */
    private Long serviceId;

    /** 事件标识（camelCase）。 */
    private String identifier;

    /** 事件名称。 */
    private String eventName;

    /** 数据类型。 */
    private String dataType;

    /** 最大长度。 */
    private Integer maxLength;

    /** 单位。 */
    private String unit;

    /** 枚举取值（JSON 数组）。 */
    private String enumList;

    /** 排序。 */
    private Integer sort;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
