/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 断档 → 告警映射的 {@code @Scheduled} 壳（与 {@code OutageScanner} 同量级：15s）。
 *
 * <p>为什么映射也要周期扫描而不是在 {@code OutageScanner} 里直接挂钩子：{@code OutageScanner} 与
 * 断档判定是**既有链路**，本能力对它的纪律是「只读、不改」（设计 §3.6 回滚面）。周期扫描的代价是
 * 延迟一个周期（15s），换来的是「本能力整体关闭/回滚不会影响既有断档判定」——这个取舍写在设计里。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
@ConditionalOnProperty(prefix = AlertProperties.PREFIX, name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class AlertOutageScanner {

    private final AlertOutageMappingService outletMappingService;

    public AlertOutageScanner(AlertOutageMappingService outletMappingService) {
        this.outletMappingService = outletMappingService;
    }

    /** 周期映射（间隔由 {@code ypbin.alert.outage-scan-interval-ms} 控制，默认 15s）。 */
    @Scheduled(fixedDelayString = "${ypbin.alert.outage-scan-interval-ms:15000}",
        initialDelayString = "${ypbin.alert.outage-initial-delay-ms:20000}")
    public void scan() {
        outletMappingService.mapOnce();
    }
}
