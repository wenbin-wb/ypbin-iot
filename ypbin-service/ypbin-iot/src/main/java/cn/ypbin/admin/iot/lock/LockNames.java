/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lock;

/**
 * 后台任务的锁名常量（#220）。
 *
 * <p>集中定义而不是各任务里手写字符串：锁名是**运维观测的键**（Redis 里能看到谁持有什么锁、
 * 指标按它打 tag），散落的字面量迟早出现拼写漂移，而漂移会让「本应互斥的两个任务各锁各的」，
 * 表现为多副本下静默重复执行——最难发现的一类失效。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class LockNames {

    /** 告警评估器（{@code ypbin.alert.evaluate-interval-ms}，默认 15s）。 */
    public static final String ALERT_EVALUATE = "alert-evaluate";

    /** 断档 → 告警映射扫描（默认 15s）。 */
    public static final String ALERT_OUTAGE_SCAN = "alert-outage-scan";

    /** 告警通知投递（默认 10s）。 */
    public static final String ALERT_NOTIFY_DISPATCH = "alert-notify-dispatch";

    /** 告警保留清理（默认 24h）。 */
    public static final String ALERT_RETENTION_CLEANUP = "alert-retention-cleanup";

    /** 平台自告警判定（默认 30s；能力默认关闭）。 */
    public static final String PLATFORM_ALERT_EVALUATE = "platform-alert-evaluate";

    /** 断档事件 / 维护窗口保留清理（默认 24h）。 */
    public static final String RETENTION_CLEANUP = "retention-cleanup";

    private LockNames() {
        // 常量类：禁止实例化
    }
}
