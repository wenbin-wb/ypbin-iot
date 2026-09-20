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
 * 领取/续期响应：本节点当前的归属清单。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
public class LeaseAcquireResp {

    /** 节点标识。 */
    private String accessNode;

    /** 本节点当前持有的租户归属（永不为 null）。 */
    private List<LeaseAssignmentDto> assignments = new ArrayList<>();

    /**
     * 归属清单（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<LeaseAssignmentDto> getAssignments() {
        return assignments == null ? List.of() : assignments;
    }
}
