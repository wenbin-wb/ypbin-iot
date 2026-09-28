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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 全局限流的用例（设计 §3.4-S7：超出部分置 {@code PENDING} **延后**，不丢弃）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertNotifyThrottleTest {

    private static final long BASE = 1_700_000_000_000L;

    @Test
    @DisplayName("同一分钟内超出上限 ⇒ 拒绝；跨到下一分钟 ⇒ 名额重置")
    void limitAppliesPerWindowAndResets() {
        AlertProperties properties = new AlertProperties();
        properties.setNotifyThrottlePerMinute(2);
        AlertNotifyThrottle throttle = new AlertNotifyThrottle(properties);

        assertThat(throttle.tryAcquire("EMAIL", BASE)).isTrue();
        assertThat(throttle.tryAcquire("EMAIL", BASE + 1)).isTrue();
        assertThat(throttle.tryAcquire("EMAIL", BASE + 2)).isFalse();
        assertThat(throttle.used("EMAIL")).isEqualTo(2);

        // 下一个时间窗（+60s）名额重置
        assertThat(throttle.tryAcquire("EMAIL", BASE + AlertNotifyThrottle.WINDOW_MS)).isTrue();
        assertThat(throttle.used("EMAIL")).isEqualTo(1);
    }

    @Test
    @DisplayName("渠道各自独立计数（邮件被打满不该连站内信也一起延后）")
    void channelsAreIndependent() {
        AlertProperties properties = new AlertProperties();
        properties.setNotifyThrottlePerMinute(1);
        AlertNotifyThrottle throttle = new AlertNotifyThrottle(properties);
        assertThat(throttle.tryAcquire("EMAIL", BASE)).isTrue();
        assertThat(throttle.tryAcquire("EMAIL", BASE)).isFalse();
        assertThat(throttle.tryAcquire("INBOX", BASE)).isTrue();
    }

    @Test
    @DisplayName("延后目标是下一个时间窗起点（口径可复现，不是「当前时刻 + 1 分钟」）")
    void nextWindowStartIsAligned() {
        assertThat(AlertNotifyThrottle.nextWindowStartMs(BASE)).isEqualTo(
            BASE - Math.floorMod(BASE, AlertNotifyThrottle.WINDOW_MS) + AlertNotifyThrottle.WINDOW_MS);
        // 正好落在窗口起点时，下一个窗口就是 +60s（不是同一窗口）
        long aligned = BASE - Math.floorMod(BASE, AlertNotifyThrottle.WINDOW_MS);
        assertThat(AlertNotifyThrottle.nextWindowStartMs(aligned)).isEqualTo(aligned + 60_000L);
    }
}
