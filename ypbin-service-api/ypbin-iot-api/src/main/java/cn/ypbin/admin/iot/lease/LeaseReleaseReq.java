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
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 主动释放请求（节点正常下线）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseReleaseReq {

    /** 节点标识。 */
    @NotBlank(message = "节点标识不能为空")
    @Size(max = 128, message = "节点标识长度不能超过 128")
    private String accessNode;

    /** 要释放的租户。 */
    @NotEmpty(message = "释放的租户不能为空")
    private List<Long> tenantIds = new ArrayList<>();
}
