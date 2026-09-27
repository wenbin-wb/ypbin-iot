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

import cn.ypbin.starter.crud.model.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 命令实例分页查询条件（按设备倒序；排序固定，不接前端排序字段——避免 ORDER BY 注入面）。
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Getter
@Setter
public class CommandQuery extends PageQuery {

    /** 状态码过滤（可空；未知状态码由服务层显式报错，不静默当"全部"）。 */
    private String statusCode;
}
