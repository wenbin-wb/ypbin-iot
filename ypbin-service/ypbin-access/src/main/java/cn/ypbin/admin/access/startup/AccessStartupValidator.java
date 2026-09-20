/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.startup;

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.iot.lease.config.LeaseFeignConfiguration;
import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * access 启动自检（fail-fast）。
 *
 * <p>两条都只在启动瞬间可判、且都会让「租约归属」失效：node-id 为空会让所有副本注册成同一个节点；
 * 续约周期太短会让节点反复把自己 fencing（周期必须 ≥ 一次调用最坏耗时的 2 倍）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Component
public class AccessStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AccessStartupValidator.class);

    /** 安全倍数：续约周期必须 ≥ 一次调用最坏耗时的该倍数。 */
    public static final int SAFETY_FACTOR = 2;

    private final AccessProperties properties;

    /**
     * 构造自检。
     *
     * @param properties 节点参数
     */
    public AccessStartupValidator(AccessProperties properties) {
        this.properties = properties;
    }

    /** 一次调用的最坏耗时（毫秒）：connect + read（客户端不重试）。 */
    static int worstCaseMs() {
        return LeaseFeignConfiguration.CONNECT_TIMEOUT_MS + LeaseFeignConfiguration.READ_TIMEOUT_MS;
    }

    /**
     * 逐条列出问题（不早退，一次列全）。
     *
     * @return 问题清单
     */
    public List<String> errors() {
        List<String> issues = new ArrayList<>();
        if (properties.getNodeId() == null || properties.getNodeId().isBlank()) {
            issues.add(AccessProperties.PREFIX + ".node-id 不能为空：为空会让所有副本注册成同一个节点，租约归属直接失效");
        }
        if (properties.getRenewIntervalMs() < (long) worstCaseMs() * SAFETY_FACTOR) {
            issues.add(AccessProperties.PREFIX + ".renew-interval-ms（" + properties.getRenewIntervalMs()
                + "ms）必须 ≥ 一次调用最坏耗时（connect 1000ms + read 3000ms = " + worstCaseMs()
                + "ms）的 " + SAFETY_FACTOR + " 倍（即 ≥" + (worstCaseMs() * SAFETY_FACTOR)
                + "ms）：否则一次卡顿就会让节点把自己判成失效");
        }
        if (properties.getAcquireIntervalMs() <= 0) {
            issues.add(AccessProperties.PREFIX + ".acquire-interval-ms 必须为正数");
        }
        if (properties.getAcquireIntervalMs() > 0
            && properties.getAcquireIntervalMs() < worstCaseMs()) {
            issues.add(AccessProperties.PREFIX + ".acquire-interval-ms（" + properties.getAcquireIntervalMs()
                + "ms）必须 ≥ 一次调用最坏耗时(" + worstCaseMs() + "ms)");
        }
        return issues;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> issues = errors();
        if (!issues.isEmpty()) {
            throw new IllegalStateException("access 启动自检未通过：" + String.join("；", issues));
        }
        log.info("access 启动自检通过：node={} renewIntervalMs={} acquireIntervalMs={} 最坏耗时={}ms",
            LogSanitizer.sanitize(properties.getNodeId()), properties.getRenewIntervalMs(),
            properties.getAcquireIntervalMs(), worstCaseMs());
    }
}
