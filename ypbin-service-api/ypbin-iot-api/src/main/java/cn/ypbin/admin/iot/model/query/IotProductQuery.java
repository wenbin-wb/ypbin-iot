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
 * IoT 产品分页查询条件。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotProductQuery extends PageQuery {

    /** 产品名称/编码模糊匹配（可空）。 */
    private String keyword;

    /** 接入协议过滤（可空）。 */
    private String protocol;

    /** 物模型状态过滤：draft | published（可空）。 */
    private String modelStatus;
}
