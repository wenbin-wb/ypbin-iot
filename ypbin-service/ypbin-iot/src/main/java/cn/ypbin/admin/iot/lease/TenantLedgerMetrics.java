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

/**
 * 租约台账域（iot 服务）的指标名**单一来源**。
 *
 * <p>为什么抽出来：平台自告警按**指标名**从 registry 取值，名字若与产出侧不一致，
 * 取值返回 `null` ⇒ 判定 `UNKNOWN` ⇒ **监控静默失效且无人发现**。故产出侧与判定侧
 * 必须引用同一个常量（一致性由 {@code PlatformMetricsNameTest} 断言）。</p>
 *
 * @author wenbin
 * @since 2026-10-10
 */
public final class TenantLedgerMetrics {

    /** 台账版本号推进失败次数（R8-8 方案 B：失败不回滚业务写入，靠周期安全网兜底）。 */
    public static final String METRIC_BUMP_FAILURE = "iot.ledger.bump.failure";

    private TenantLedgerMetrics() {
    }
}
