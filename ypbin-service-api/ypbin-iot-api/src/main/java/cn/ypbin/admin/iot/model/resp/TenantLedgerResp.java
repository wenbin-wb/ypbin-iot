/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户台账视图（M-2：可分配来源 + 配置版本号）。
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
public class TenantLedgerResp {

    /** 租户 ID。 */
    private Long tenantId;

    /** 是否可被分配。 */
    private Boolean assignable;

    /** 配置版本号：台账每次变更 +1（接入侧据此「不一致才拉全量」）。 */
    private Long configEpoch;

    /**
     * 设备数：该租户名下**未删除**设备的总数。
     *
     * <p>{@code 0} 表示「确实没有设备」，不是「未统计、待补」。查询接口（台账列表）会填充；
     * 写入口回读时也按同一口径填充。</p>
     */
    private Integer deviceCount;

    /**
     * 最近上报时刻：该租户名下未删除设备 {@code last_seen_at} 的最大值。
     *
     * <p>{@code null} 表示「没有设备」或「设备从未上报」，是事实而不是未统计。查询接口会填充；
     * 写入口回读时也按同一口径填充。</p>
     */
    private LocalDateTime lastReportAt;

    /**
     * 是否有有效凭据：该租户是否存在**未删除且 {@code password_hash} 非空**的设备凭据行
     * （吊销会把哈希置空，因此空哈希视作无有效凭据）。
     *
     * <p>{@code false} 表示「确实没有有效凭据」，不是「未统计、待补」。查询接口会填充；
     * 写入口回读时也按同一口径填充。</p>
     */
    private Boolean hasCredential;

    /**
     * 本次写操作的结果类型：{@code created} / {@code revived} / {@code updated}。
     *
     * <p>仅写入口回填；查询接口为 {@code null}（没有「本次变更」可言）。</p>
     */
    private String change;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
