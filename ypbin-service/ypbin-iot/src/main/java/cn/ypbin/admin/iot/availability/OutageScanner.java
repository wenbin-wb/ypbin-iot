/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import cn.ypbin.admin.iot.service.AvailabilityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 断档周期扫描（M-2）：静默设备不会带来任何请求，只能靠它发现。
 *
 * <p>间隔由 {@code ypbin.availability.scan-interval-ms} 控制；失败由调度器记完整堆栈（这里不吞异常，
 * 也不做重试——下一轮自然重试）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Component
@ConditionalOnProperty(prefix = AvailabilityProperties.PREFIX, name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class OutageScanner {

    private final AvailabilityService availabilityService;

    public OutageScanner(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    /** 周期扫描（间隔由 {@code ypbin.availability.scan-interval-ms} 控制）。 */
    @Scheduled(fixedDelayString = "${ypbin.availability.scan-interval-ms:15000}")
    public void scan() {
        availabilityService.scanAndOpenOutages();
    }
}
