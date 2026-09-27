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
 * 告警评估器的 {@code @Scheduled} 壳（设计 §2.2.2 路线 C）——**第 6 个同类扫描器**。
 *
 * <p>与既有 5 个扫描器（{@code OutageScanner} / {@code CommandTimeoutScanner} /
 * {@code IotDbRowCountProbe} / {@code RetentionCleanupServiceImpl} / {@code LeaseExpiryScanner}）
 * 同范式：{@code fixedDelay} + 具名常量可配 + 失败由调度器记完整堆栈（不吞异常、不做重试——
 * 下一轮自然重试）。</p>
 *
 * <p>周期**默认 15s**（用户已批准口径：触发到通知 15s 量级轮询）。{@code fixedDelay} 而不是
 * {@code fixedRate}：一轮没跑完就不该再叠一轮，否则慢查询会把线程池塞满。</p>
 *
 * <p>总开关关闭时本 Bean **不装配**（{@code ypbin.alert.enabled=false}）：不仅不评估，
 * 接口也会明确返回「未启用」而不是空列表（设计 §3.6.2）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
@ConditionalOnProperty(prefix = AlertProperties.PREFIX, name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class AlertEvaluator {

    private final AlertEvaluatorService evaluatorService;

    public AlertEvaluator(AlertEvaluatorService evaluatorService) {
        this.evaluatorService = evaluatorService;
    }

    /** 周期评估（间隔由 {@code ypbin.alert.evaluate-interval-ms} 控制，默认 15s）。 */
    @Scheduled(fixedDelayString = "${ypbin.alert.evaluate-interval-ms:15000}",
        initialDelayString = "${ypbin.alert.evaluate-initial-delay-ms:10000}")
    public void evaluate() {
        evaluatorService.evaluateOnce();
    }
}
