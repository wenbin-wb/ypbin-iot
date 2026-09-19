/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.query;

import cn.ypbin.starter.crud.model.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备分页查询条件。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class IotDeviceQuery extends PageQuery {

    /** 设备名称/编码模糊匹配（可空）。 */
    private String keyword;

    /** 协议过滤（可空）。 */
    private String protocol;
}
