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

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约响应：成功回执 + 被回收的租户 + 节点级失效标记。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseRenewResp {

    /** 续约成功的租户。 */
    private List<LeaseRenewAck> renewedLeases = new ArrayList<>();

    /** 服务端已不认为属于本节点的租户（节点必须立即断链停采）。 */
    private List<Long> revokedTenantIds = new ArrayList<>();

    /** 节点级失效（未注册/已被移除）：节点应整体停采并重新注册。 */
    private boolean nodeFenced;

    /**
     * 成功回执（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<LeaseRenewAck> getRenewedLeases() {
        return renewedLeases == null ? List.of() : renewedLeases;
    }

    /**
     * 被回收的租户（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<Long> getRevokedTenantIds() {
        return revokedTenantIds == null ? List.of() : revokedTenantIds;
    }
}
