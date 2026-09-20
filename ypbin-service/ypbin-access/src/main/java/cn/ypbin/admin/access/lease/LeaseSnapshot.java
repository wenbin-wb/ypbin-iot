/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.lease;

import cn.ypbin.admin.iot.lease.LeaseEpochRules;
import cn.ypbin.admin.iot.lease.LeaseState;
import java.time.LocalDateTime;

/**
 * 本地租约快照（热路径只读它，不查库不调 RPC）。
 *
 * <p>到期判据**复用契约里的组合判据** {@link LeaseEpochRules#needsSelfFence}：
 * 快照按定义只存有效持有（状态恒为 ACTIVE），所以状态维度恒有效、判据退化成时间比较——
 * 但走同一个方法，契约才不会被实现另写一套。</p>
 *
 * @param leaseExpireAt 到期时间
 * @param epoch         台账版本号
 * @author wenbin
 * @since 2026-09-20
 */
public record LeaseSnapshot(LocalDateTime leaseExpireAt, Long epoch) {

    /**
     * 本地是否必须停采。
     *
     * @param now 当前时刻
     * @return 必须停采返回 {@code true}
     */
    boolean mustSelfFence(LocalDateTime now) {
        return LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, leaseExpireAt, now);
    }
}
