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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 通知的**全局限流**（设计 §2.4 投递模型第 3 条：每渠道每分钟令牌桶上限，可配，默认 60/min）。
 *
 * <p>它防的是「一次大面积故障触发上千条告警把邮件网关打挂」。因此限流必须是**全局**（跨租户、跨告警），
 * 单条告警的 {@code repeat_interval_sec} 只能限制自己，挡不住这种并发。</p>
 *
 * <p><b>超出时「延后不丢弃」</b>（设计原话）：被限流的通知保持原状态并把 {@code next_retry_ts} 推到下一个
 * 时间窗，仍会被后续轮次投递——不是丢弃，也不改成失败。这条语义必须由用例钉住（S7）。</p>
 *
 * <p><b>为什么是固定窗口而不是令牌桶</b>：本场景只需要「别把网关打挂」的粗粒度保护，固定窗口
 * 实现无状态依赖、可单测、不会因为长时间空闲而积累出突发令牌（令牌桶在空闲后会把一整桶一次性放出去，
 * 恰好是这里最不想要的形态）。代价是窗口边界可能出现瞬时 2× 速率，对邮件网关完全可接受。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertNotifyThrottle {

    /** 窗口长度（毫秒）：1 分钟。 */
    public static final long WINDOW_MS = 60_000L;

    private final AlertProperties properties;

    /** 渠道 → 当前窗口计数（只在投递线程里访问，故用轻量同步）。 */
    private final Map<String, Window> windows = new LinkedHashMap<>();

    public AlertNotifyThrottle(AlertProperties properties) {
        this.properties = properties;
    }

    /** 一个固定窗口的计数。 */
    private static final class Window {

        private long startMs;
        private final AtomicInteger count = new AtomicInteger();
    }

    /**
     * 尝试为某渠道取一个名额。
     *
     * @param channel 渠道码
     * @param nowMs   当前时刻（epoch 毫秒；由调用方传入，便于用例确定化）
     * @return 取到名额返回 {@code true}；超出上限返回 {@code false}（调用方应**延后**而不是丢弃）
     */
    public synchronized boolean tryAcquire(String channel, long nowMs) {
        int limit = properties.getNotifyThrottlePerMinute();
        if (limit <= 0) {
            // 配置为 0/负数视为「不限流」，但启动自检会拒绝该配置（此处只做防御，不会静默变成不限流）
            return true;
        }
        long windowStart = nowMs - Math.floorMod(nowMs, WINDOW_MS);
        Window window = windows.computeIfAbsent(channel, key -> new Window());
        if (window.startMs != windowStart) {
            window.startMs = windowStart;
            window.count.set(0);
        }
        if (window.count.get() >= limit) {
            return false;
        }
        window.count.incrementAndGet();
        return true;
    }

    /**
     * 下一个时间窗的起点（被限流时的延后目标）。
     *
     * @param nowMs 当前时刻（epoch 毫秒）
     * @return 下一个窗口起点的时刻（毫秒）
     */
    public static long nextWindowStartMs(long nowMs) {
        return nowMs - Math.floorMod(nowMs, WINDOW_MS) + WINDOW_MS;
    }

    /** 当前某渠道已用名额（用例与排障用，不参与判定）。 */
    public synchronized int used(String channel) {
        Window window = windows.get(channel);
        return window == null ? 0 : window.count.get();
    }
}
