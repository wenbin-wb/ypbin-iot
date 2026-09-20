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

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * access 节点注册请求。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class AccessNodeRegisterReq {

    /** 节点标识（租约归属的键，须全局唯一）。 */
    @NotBlank(message = "节点标识不能为空")
    private String accessNode;

    /** 最多可持有多少租户；{@code null} = 不限（单节点全量）。 */
    private Integer maxTenants;
}
