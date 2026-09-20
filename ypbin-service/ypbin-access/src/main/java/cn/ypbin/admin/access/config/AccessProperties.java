/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * access 节点参数（前缀 {@code ypbin.access}）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AccessProperties.PREFIX)
public class AccessProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.access";

    /** 节点标识：租约归属的键，**必须全局唯一**（为空则拒绝启动）。 */
    private String nodeId = "";

    /** 最多可持有租户数；{@code null} = 不限（单节点全量）。 */
    private Integer capacity;

    /** 续约周期（毫秒）：必须 ≥ 一次调用最坏耗时的 2 倍（启动自检拒绝太小）。 */
    private long renewIntervalMs = 10_000L;

    /** 周期重领间隔（毫秒）：接管的执行入口（只在启动时领取会让待接管租户永远无人接手）。 */
    private long acquireIntervalMs = 15_000L;

    /** 是否在启动期执行握手（注册 + 领取）；关闭时节点零采集，仅供「验装配」的测试使用。 */
    private boolean startupHandshakeEnabled = true;
}
