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
 * 通知投递器的 {@code @Scheduled} 壳（设计 §2.4）——与评估器同范式（{@code fixedDelay} + 具名常量可配）。
 *
 * <p>周期默认 10s（比评估周期略慢即可：投递的时效由「评估触发 → 立即入队 → 本轮投递」的链路决定，
 * 不需要比评估更频繁）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
@ConditionalOnProperty(prefix = AlertProperties.PREFIX, name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class AlertNotifyDispatcher {

    private final AlertNotifyDispatchService dispatchService;

    public AlertNotifyDispatcher(AlertNotifyDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 周期投递（间隔由 {@code ypbin.alert.notify-dispatch-interval-ms} 控制，默认 10s）。 */
    @Scheduled(fixedDelayString = "${ypbin.alert.notify-dispatch-interval-ms:10000}",
        initialDelayString = "${ypbin.alert.notify-initial-delay-ms:15000}")
    public void dispatch() {
        dispatchService.dispatchOnce();
    }
}
