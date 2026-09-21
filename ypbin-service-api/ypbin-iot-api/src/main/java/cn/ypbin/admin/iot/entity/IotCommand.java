/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 物模型命令（§3.5）。
 *
 * <p>{@code identifier} 对齐 IoTDA UPPER_SNAKE，服务内唯一；入参/出参以 JSON 定义。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@TableName("iot_command")
public class IotCommand extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 所属服务 ID。 */
    private Long serviceId;

    /** 命令标识（UPPER_SNAKE，服务内唯一）。 */
    private String identifier;

    /** 命令名称。 */
    private String commandName;

    /** 入参定义（JSON：字段/类型/必填/范围/枚举）。 */
    private String inputParams;

    /** 出参定义（JSON）。 */
    private String outputParams;

    /** 命令超时（毫秒；缺省用全局默认，Q3）。 */
    private Integer timeoutMs;

    /** 排序。 */
    private Integer sort;
}
