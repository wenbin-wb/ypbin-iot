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
 * access 读数出口参数（M-2：把协议栈采到的读数上报给 iot，供断档/可用率判定）。
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AccessEgressProperties.PREFIX)
public class AccessEgressProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.access.egress";

    /**
     * 是否启用读数上报。
     *
     * <p>关掉时退化为日志占位（会打 WARN）——那种部署下断档/可用率**收不到任何数据**，
     * 报表会一直显示「无断档」，属刻意保留的调试档位，不是可用配置。</p>
     */
    private boolean enabled = true;

    /** 有界队列容量：满了**丢弃并计数**，绝不阻塞协议回调线程。 */
    private int queueCapacity = 10_000;

    /** 微批大小（单次上报条数；服务端单批上限 500，启动自检会拒绝更大的值）。 */
    private int batchSize = 200;

    /** 微批周期（毫秒）。 */
    private long flushIntervalMs = 1_000L;
}
