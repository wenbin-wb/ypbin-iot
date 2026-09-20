/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 归属查询请求。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class AssignmentQueryReq {

    /** 租户 ID。 */
    @NotNull(message = "租户 ID 不能为空")
    private Long tenantId;
}
