/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台自告警判定器的 `@Scheduled` 壳（看板 #10）——与仓内既有 10 个同类扫描器**同范式**：
 * `fixedDelay`（不是 `fixedRate`：一轮没跑完不该再叠一轮）+ 具名可配常量 + 失败由调度器
 * 记完整堆栈（不吞异常、不做重试——下一轮自然重试）。</p>
 *
 * <p><b>周期默认 30s</b>：比告警评估器（15s）**慢一倍**。理由：平台健康是**慢变量**
 * （评估器停摆 45s 才算异常），判定比被监控对象还频繁没有意义，反而增加无谓的指标读取；
 * 而 30s 相对 45s 的停摆阈值仍有足够分辨率。</p>
 *
 * <p><b>默认关闭</b>（`ypbin.platform-alert.enabled=false`，`matchIfMissing=false`）：
 * 本能力一期是"观察期只落库"（设计 §2.1），**默认不参与生产运行**——
 * 需要显式开启并在观察期确认阈值合理后再谈通知。这与"新增后台任务必须可控"的运维原则一致。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Component
@ConditionalOnProperty(prefix = PlatformAlertProperties.PREFIX, name = "enabled",
    havingValue = "true")
public class PlatformAlertEvaluator {

    private final PlatformAlertService alertService;

    public PlatformAlertEvaluator(PlatformAlertService alertService) {
        this.alertService = alertService;
    }

    /** 周期判定（间隔由 `ypbin.platform-alert.evaluate-interval-ms` 控制，默认 30s）。 */
    @Scheduled(fixedDelayString = "${ypbin.platform-alert.evaluate-interval-ms:30000}",
        initialDelayString = "${ypbin.platform-alert.initial-delay-ms:20000}")
    public void evaluate() {
        alertService.evaluateOnce();
    }
}
