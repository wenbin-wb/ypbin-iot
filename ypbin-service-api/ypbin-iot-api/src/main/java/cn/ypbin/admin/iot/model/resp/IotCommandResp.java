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
 * IoT 物模型命令响应模型（§3.5）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotCommandResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 所属服务 ID。 */
    private Long serviceId;

    /** 命令标识（UPPER_SNAKE）。 */
    private String identifier;

    /** 命令名称。 */
    private String commandName;

    /** 入参定义（JSON）。 */
    private String inputParams;

    /** 出参定义（JSON）。 */
    private String outputParams;

    /** 命令超时（毫秒）。 */
    private Integer timeoutMs;

    /** 排序。 */
    private Integer sort;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
