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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约请求（节点周期性上报自己持有的租户）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseRenewReq {

    /** 节点标识。 */
    @NotBlank(message = "节点标识不能为空")
    private String accessNode;

    /** 要续约的租户清单。 */
    @NotEmpty(message = "续约清单不能为空")
    @Valid
    private List<LeaseRenewItem> leases = new ArrayList<>();
}
