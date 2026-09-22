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

import java.time.LocalDateTime;
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
     * 服务端时间（**数据库时钟**，非接入节点时钟）。
     *
     * <p>为什么必须带：接入侧要用 {@code leaseExpireAt} 判「本地租约是否已过期」。若它拿**自己的**时钟去比，
     * 节点钟快就会提前自行停采（数据凭空变少）、钟慢就会在服务端已判定接管后仍多采一段（双采）。
     * 带上服务端时间后，接入侧用「服务端时间 + 本地单调流逝」判定，节点时钟漂移不再影响语义。</p>
     */
    private LocalDateTime serverTime;

    /**
     * 归属清单（防御 null）。
     *
     * @return 清单，非 null
     */
    public List<LeaseAssignmentDto> getAssignments() {
        return assignments == null ? List.of() : assignments;
    }
}
