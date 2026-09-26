/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.values;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.ypbin.starter.test.condition.EnabledIfRedisAvailable;
import cn.ypbin.starter.test.container.RedisIntegrationTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 最新值「跨批乱序」防护的真 Redis 集成测试（设计 §6.4 / P0-A-1）。
 *
 * <p><b>为什么必须真 Redis</b>：防护的**行为**全在服务端 Lua 里——比较与写入是否原子、跨批（新 ts 批先到、
 * 旧 ts 批后到）是否还会回退、并发（多实例/多线程）是否还会回退，mock 掉 {@code StringRedisTemplate}
 * 一条都证明不了（只能证明调用形态）。本类用 Testcontainers/外部实例起真 Redis，跑四类用例：
 * 跨批反例、逐点位独立比较、并发反例、失败路径。任一「防护被去掉」的实现都会在这里转红。</p>
 *
 * <p>无中间件的开发机上本类由 {@link EnabledIfRedisAvailable} 跳过（而非失败）；CI 的
 * {@code -Pit} 反应堆（runner 自带 Docker）会真实执行。跳过等于没测，故本类**不在** CI 的
 * 「断言无跳过」清单里作假绿承诺——它跑在 {@code -Pit verify} 全量 failsafe 中。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
@EnabledIfRedisAvailable
class RedisLatestValueWriterIT {

    private static final long TENANT_ID = 9_000_026L;

    private static final long DEVICE_ID = 42L;

    private static final String FIELD = "temperature";

    private static final long FRESH_TS = 2_000L;

    private static final long STALE_TS = 1_000L;

    private static RedisConnectionFactory factory;

    private static StringRedisTemplate template;

    /** 第二个模板（模拟「另一个实例」：独立 writer 对象 + 独立模板，指向同一 Redis）。 */
    private static StringRedisTemplate secondTemplate;

    private static SimpleMeterRegistry registry;

    private static RedisLatestValueWriter writer;

    /** 本类创建的 key（结束前清理，避免污染真实例；不依赖「测试专用实例」这种假设）。 */
    private static final List<String> CREATED_KEYS = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        factory = RedisIntegrationTestSupport.connectionFactory();
        template = newTemplate(factory);
        secondTemplate = newTemplate(factory);
        registry = new SimpleMeterRegistry();
        writer = new RedisLatestValueWriter(template, registry);
    }

    @AfterAll
    static void tearDown() {
        if (!CREATED_KEYS.isEmpty()) {
            template.delete(CREATED_KEYS);
        }
        if (factory instanceof LettuceConnectionFactory lettuce) {
            lettuce.destroy();
        }
    }

    @BeforeEach
    void resetState() {
        // 每个用例从干净状态开始：清 key（避免上一个用例的存量值影响本用例）并重建 registry
        // （SimpleMeterRegistry.clear() 会把已注册的 counter 整个摘掉，之后按名字就取不到了）
        if (!CREATED_KEYS.isEmpty()) {
            template.delete(CREATED_KEYS);
        }
        registry = new SimpleMeterRegistry();
        writer = new RedisLatestValueWriter(template, registry);
    }

    private static StringRedisTemplate newTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate created = new StringRedisTemplate(connectionFactory);
        created.afterPropertiesSet();
        return created;
    }

    /** 取一个指定设备的 key，并登记到清理清单。 */
    private static String deviceKey(long deviceId) {
        String key = RedisLatestValueWriter.key(TENANT_ID, deviceId);
        CREATED_KEYS.add(key);
        return key;
    }

    private static LatestValue value(long deviceId, long ts, String raw) {
        return new LatestValue(TENANT_ID, deviceId, FIELD, raw, "GOOD", ts);
    }

    private static LatestValue point(long deviceId, String propertyId, long ts, String raw) {
        return new LatestValue(TENANT_ID, deviceId, propertyId, raw, "GOOD", ts);
    }

    private static String stored(long deviceId, String propertyId) {
        return (String) template.opsForHash().get(deviceKey(deviceId), propertyId);
    }

    /** 从落库的紧凑 JSON 尾部取真正的 ts（`{"v":…,"q":…,"ts":N}`）。 */
    private static long storedTs(String json) {
        assertThat(json).as("最新值必须已落库").isNotNull();
        int index = json.lastIndexOf("\"ts\":");
        assertThat(index).as("落库值必须带 ts：%s", json).isPositive();
        return Long.parseLong(json.substring(index + "\"ts\":".length(), json.length() - 1));
    }

    private static long regressedCount() {
        return (long) registry.get(RedisLatestValueWriter.METRIC_REGRESSED).counter().count();
    }

    private static long failedCount() {
        return (long) registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count();
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception ex) {
            throw new IllegalStateException("并发用例的栅栏等待失败", ex);
        }
    }

    @Test
    @DisplayName("★ ①②同批乱序 → 取新；跨批「新 ts 先写、旧 ts 后写」→ 最新值不回退（EMQX 重投必然触发）")
    void staleBatchArrivingLaterMustNotRegressLatest() {
        // 同批乱序：进脚本的就是最新的那条
        writer.writeAll(List.of(value(DEVICE_ID, FRESH_TS, "NEW"), value(DEVICE_ID, STALE_TS, "OLD")));
        assertThat(storedTs(stored(DEVICE_ID, FIELD))).isEqualTo(FRESH_TS);
        assertThat(stored(DEVICE_ID, FIELD)).contains("\"v\":\"NEW\"");

        // 跨批（两次 writeAll 模拟两批）：新 ts 批先写、旧 ts 批后写 ⇒ 必须不回退
        writer.writeAll(List.of(value(DEVICE_ID, FRESH_TS, "NEW")));
        writer.writeAll(List.of(value(DEVICE_ID, STALE_TS, "STALE")));
        assertThat(storedTs(stored(DEVICE_ID, FIELD)))
            .as("更旧的批次晚到不得覆盖已存的较新值（去掉 Lua 比较即转红）").isEqualTo(FRESH_TS);
        assertThat(stored(DEVICE_ID, FIELD)).contains("\"v\":\"NEW\"");
        assertThat(regressedCount()).as("被抑制的「旧 ts 后到」必须计入指标").isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 乱序重投序列（3 批乱序到达）最终仍是最大 ts 那条")
    void outOfOrderBatchesMustConvergeToNewest() {
        for (long ts : List.of(3_000L, 2_000L, 1_000L, 2_500L, 1_500L)) {
            writer.writeAll(List.of(value(DEVICE_ID, ts, "V" + ts)));
        }
        assertThat(storedTs(stored(DEVICE_ID, FIELD))).isEqualTo(3_000L);
        assertThat(stored(DEVICE_ID, FIELD)).contains("\"v\":\"V3000\"");
    }

    @Test
    @DisplayName("★ 比较是**逐点位**的：同一设备不同点位的 ts 互不影响（按各自 field 判定）")
    void perFieldTimestampsMustBeComparedIndependently() {
        long deviceId = DEVICE_ID + 1;
        writer.writeAll(List.of(point(deviceId, "a", FRESH_TS, "A2"), point(deviceId, "b", STALE_TS, "B1")));
        // 第二批反转：a 更旧（应被抑制）、b 更新（应写入）
        writer.writeAll(List.of(point(deviceId, "a", STALE_TS, "A1"), point(deviceId, "b", FRESH_TS, "B2")));

        assertThat(storedTs(stored(deviceId, "a"))).isEqualTo(FRESH_TS);
        assertThat(stored(deviceId, "a")).contains("\"v\":\"A2\"");
        assertThat(storedTs(stored(deviceId, "b"))).isEqualTo(FRESH_TS);
        assertThat(stored(deviceId, "b")).contains("\"v\":\"B2\"");
        assertThat(regressedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 同一个 ts 的重放是幂等 no-op：保留先到者，且**不计入**乱序指标")
    void equalTimestampReplayMustBeIdempotentAndNotCounted() {
        writer.writeAll(List.of(value(DEVICE_ID, FRESH_TS, "FIRST")));
        writer.writeAll(List.of(value(DEVICE_ID, FRESH_TS, "REPLAY")));

        assertThat(stored(DEVICE_ID, FIELD)).contains("\"v\":\"FIRST\"");
        assertThat(storedTs(stored(DEVICE_ID, FIELD))).isEqualTo(FRESH_TS);
        assertThat(regressedCount()).as("ts 相等不是乱序").isZero();
    }

    @Test
    @DisplayName("★ ③并发反例：两个 writer 同时写同一设备（不同线程/不同模板），旧 ts 永远不得最终胜出")
    void concurrentWritersMustNeverLetStaleWin() throws Exception {
        int rounds = 40;
        List<String> regressions = new ArrayList<>();
        RedisLatestValueWriter other = new RedisLatestValueWriter(secondTemplate, new SimpleMeterRegistry());
        for (int round = 0; round < rounds; round++) {
            long deviceId = DEVICE_ID + 100 + round;
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread fresh = new Thread(() -> {
                awaitBarrier(barrier);
                writer.writeAll(List.of(value(deviceId, FRESH_TS, "FRESH")));
            }, "fresh-" + round);
            Thread stale = new Thread(() -> {
                awaitBarrier(barrier);
                other.writeAll(List.of(value(deviceId, STALE_TS, "STALE")));
            }, "stale-" + round);
            fresh.start();
            stale.start();
            fresh.join();
            stale.join();
            long finalTs = storedTs(stored(deviceId, FIELD));
            if (finalTs != FRESH_TS) {
                regressions.add("第 " + round + " 轮最终 ts=" + finalTs + "（旧 ts 胜出）");
            }
        }
        assertThat(regressions).as("并发下「旧 ts 覆盖新 ts」的轮次（Lua CAS 生效时必须为空）").isEmpty();
    }

    @Test
    @DisplayName("★ ③并发风暴：先落 ts=2000，再让 8 线程各写 60 次更旧的 ts ⇒ 最终仍是 2000")
    void maxTimestampMustSurviveConcurrentStaleStorm() throws Exception {
        long deviceId = DEVICE_ID + 200;
        writer.writeAll(List.of(value(deviceId, FRESH_TS, "MAX")));
        int threads = 8;
        int writesPerThread = 60;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                awaitBarrier(barrier);
                for (int i = 0; i < writesPerThread; i++) {
                    long staleRest = ThreadLocalRandom.current().nextLong(1L, FRESH_TS);
                    try {
                        writer.writeAll(List.of(value(deviceId, staleRest, "STALE" + staleRest)));
                    } catch (RuntimeException ex) {
                        failures.incrementAndGet();
                    }
                }
            }, "storm-" + t);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertThat(failures.get()).as("并发写不得有异常逃逸").isZero();
        assertThat(storedTs(stored(deviceId, FIELD)))
            .as("更旧的并发写不得覆盖最大值（去掉 Lua 比较时本用例几乎必然转红）").isEqualTo(FRESH_TS);
        assertThat(stored(deviceId, FIELD)).contains("\"v\":\"MAX\"");
        assertThat(regressedCount()).as("抑制计数应接近 8×60（允许被抑制的批次合并去重）").isPositive();
    }

    @Test
    @DisplayName("★ ④失败路径：Redis 不可达时只计数 + 不抛（最新值不得回滚上报事务）")
    void unreachableRedisMustOnlyCountAndNotThrow() throws Exception {
        RedisStandaloneConfiguration standalone =
            new RedisStandaloneConfiguration("127.0.0.1", freePort());
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(300)).build();
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(standalone, clientConfiguration);
        unreachable.afterPropertiesSet();
        try {
            SimpleMeterRegistry failing = new SimpleMeterRegistry();
            RedisLatestValueWriter broken = new RedisLatestValueWriter(newTemplate(unreachable), failing);

            assertThatCode(() -> broken.writeAll(List.of(value(DEVICE_ID, FRESH_TS, "X"))))
                .doesNotThrowAnyException();
            assertThat((long) failing.get(RedisLatestValueWriter.METRIC_FAILED).counter().count())
                .isEqualTo(1L);
        } finally {
            unreachable.destroy();
        }
        assertThat(failedCount()).isZero();
    }

    @Test
    @DisplayName("★ 边界：存量值解析不出 ts（外部脏数据）时按「旧值更旧」覆盖，且不抛")
    void unparsableStoredValueMustBeOverwrittenWithoutFailing() {
        long deviceId = DEVICE_ID + 300;
        template.opsForHash().put(deviceKey(deviceId), FIELD, "not-json");

        writer.writeAll(List.of(value(deviceId, STALE_TS, "NEW")));

        assertThat(stored(deviceId, FIELD)).contains("\"v\":\"NEW\"");
        assertThat(storedTs(stored(deviceId, FIELD))).isEqualTo(STALE_TS);
        assertThat(failedCount()).isZero();
    }

    @Test
    @DisplayName("★ 边界：值里伪造 `\"ts\":999}` 片段不得让脚本误判真实 ts（串尾锚定匹配）")
    void forgedTsFragmentInsideValueMustNotConfuseScript() {
        long deviceId = DEVICE_ID + 400;
        writer.writeAll(List.of(value(deviceId, FRESH_TS, "\",\"ts\":999}")));
        // 若脚本误把值里的 999 当成存量 ts，这条更旧的写就会被放行（ts=2000 → 1000）
        writer.writeAll(List.of(value(deviceId, STALE_TS, "STALE")));

        assertThat(storedTs(stored(deviceId, FIELD))).isEqualTo(FRESH_TS);
        assertThat(regressedCount()).isEqualTo(1L);
    }

    /** 取一个当前无人监听的端口（用于构造「Redis 不可达」）。 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
