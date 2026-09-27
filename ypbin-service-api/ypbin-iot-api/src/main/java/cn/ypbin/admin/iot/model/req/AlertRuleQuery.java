/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import lombok.Getter;
import lombok.Setter;

/**
 * 告警规则分页查询条件。
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertRuleQuery {

    /** 页码（1 起）。 */
    private Integer page = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;

    /** 作用域码筛选（可空）。 */
    private String scopeType;

    /** 启用状态筛选（可空；{@code 0/1}）。 */
    private Integer enabled;

    /** 级别码筛选（可空）。 */
    private String severity;

    /** 关键词（规则名，模糊）。 */
    private String keyword;

    /** 作用域设备 ID 筛选（可空；用于「这台设备有哪些规则」）。 */
    private Long deviceId;

    /** 作用域产品 ID 筛选（可空）。 */
    private Long productId;
}
