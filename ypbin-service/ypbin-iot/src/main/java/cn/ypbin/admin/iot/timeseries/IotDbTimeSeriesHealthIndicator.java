/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 时序库可达性的健康指示器（{@code /actuator/health} 的一个组件）。
 *
 * <p><b>解决什么</b>：{@link TimeSeriesStore#available()} 只回答「是否已启用/已接入」（不连库），
 * 无法区分「启用了但连不上」与「真的可用」。本指示器调用 {@link TimeSeriesStore#reachable()} 做
 * **真实探测**（建立连接即视为可达，见 {@link IotDbConnectionFactory#probe()}），把「配置了却连不上」
 * 暴露成健康状态 DOWN，而不是等到用户查曲线时才报错。</p>
 *
 * <p><b>结果缓存（{@value #DEFAULT_CACHE_TTL_MS} ms）</b>：探测要建一条 TCP 连接，而健康端点可能被
 * 高频轮询/编排器反复调用。缓存把探测频率压到「每 TTL 至多一次」，避免健康检查本身成为对 IoTDB 的
 * 压测。缓存只用于健康端点，**不影响业务查询路径**（查询仍按自己的连接执行）。</p>
 *
 * <p><b>装配边界</b>：仅在 {@code ypbin.timeseries.enabled=true} 时注册（见
 * {@code IotTimeSeriesConfiguration}）。关闭时不注册 ⇒ 不会让整个应用健康状态因「未启用」而 DOWN。</p>
 *
 * @author wenbin
 * @since 2026-10-12
 */
public class IotDbTimeSeriesHealthIndicator implements HealthIndicator {

    /** 默认探测结果缓存时长（毫秒）：15 秒。 */
    public static final long DEFAULT_CACHE_TTL_MS = 15_000L;

    /** 健康详情里的组件名（不含任何凭据/地址，只给状态）。 */
    private static final String DETAIL_STORE = "timeSeriesStore";

    private final TimeSeriesStore store;
    private final Clock clock;
    private final long cacheTtlMs;

    /** 最近一次探测结果（含时刻）；并发调用下允许重复探测一次，不做加锁。 */
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    /**
     * 构造（默认缓存时长）。
     *
     * @param store 时序存储（{@code reachable()} 为真实探测）
     * @param clock 时间源（可注入假时钟以便测试缓存行为）
     */
    public IotDbTimeSeriesHealthIndicator(TimeSeriesStore store, Clock clock) {
        this(store, clock, DEFAULT_CACHE_TTL_MS);
    }

    /**
     * 构造（自定义缓存时长，测试用）。
     *
     * @param store      时序存储
     * @param clock      时间源
     * @param cacheTtlMs 缓存时长（毫秒）
     */
    public IotDbTimeSeriesHealthIndicator(TimeSeriesStore store, Clock clock, long cacheTtlMs) {
        this.store = store;
        this.clock = clock;
        this.cacheTtlMs = cacheTtlMs;
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Cached current = cached.get();
        if (current != null && !now.isAfter(current.probedAt().plusMillis(cacheTtlMs))) {
            return current.health();
        }
        boolean reachable = store.reachable();
        Health health = reachable
            ? Health.up().withDetail(DETAIL_STORE, "reachable").build()
            : Health.down().withDetail(DETAIL_STORE, "unreachable").build();
        cached.set(new Cached(health, now));
        return health;
    }

    /**
     * 一次探测的结果与时刻。
     *
     * @param health   探测时的健康状态
     * @param probedAt 探测时刻
     */
    private record Cached(Health health, Instant probedAt) {
    }
}
