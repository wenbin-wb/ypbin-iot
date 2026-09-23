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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租约维护参数（前缀 {@code ypbin.lease}）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
@ConfigurationProperties(prefix = LeaseProperties.PREFIX)
public class LeaseProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.lease";

    /** 是否启用租约维护（关闭时内部端点与失效扫描都不装配）。 */
    private boolean enabled = true;

    /**
     * 租约交接窗口的**上界**（默认 1 小时）。
     *
     * <p>为什么必须给上界：交接窗口（{@code source=LEASE_HANDOVER}）会从可用率统计里排除，
     * 若它一直不关（例如租户被释放后**没有任何节点接管**），排除范围会随查询不断延展
     * ⇒ 该租户可用率恒 100% 且恒判达标（fail-open）。给上界后：超过它仍未接管，空档重新按**断档**计
     * ——「没人采集」本来就该算可用率损失，这才是保守方向。</p>
     */
    private Duration handoverWindowTtl = Duration.ofHours(1);

    /** 租约有效期：access 必须在这段时间内续约，否则可被判定失效并接管。 */
    private Duration ttl = Duration.ofSeconds(30);

    /** 失效扫描周期（毫秒）。 */
    private long scanIntervalMs = 15_000L;

    /** 预期续约周期：用于启动自检（ttl 必须明显大于它）。 */
    private Duration expectedRenewInterval = Duration.ofSeconds(10);

    /**
     * 本部署允许分配的租户（M0b 起应来自台账表；当前显式配置以免「什么都能分配」）。
     *
     * <p>为空表示不分配任何租户（而不是「分配全部」）——空配置静默分配全部是危险的默认值。</p>
     */
    private List<Long> assignableTenantIds = new ArrayList<>();
}
