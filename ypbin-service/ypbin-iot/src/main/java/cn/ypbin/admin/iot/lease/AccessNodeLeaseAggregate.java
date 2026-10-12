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
import lombok.Getter;
import lombok.Setter;

/**
 * 按节点聚合的租约概览行（M7：一次 {@code GROUP BY access_node} 取代逐节点 N+1 查询）。
 *
 * <p>只承载 SQL 聚合出的三个字段；无在采租户的节点不会出现在结果里，调用方按「查不到 = 0 条/null」处理。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
@Getter
@Setter
public class AccessNodeLeaseAggregate {

    /** 节点标识。 */
    private String accessNode;

    /** 该节点当前 {@code active} 且未删除的租户数。 */
    private Long holdingCount;

    /** 该节点最早租约到期时刻（无在采租户不会出现在聚合结果中）。 */
    private LocalDateTime earliestExpireAt;
}
