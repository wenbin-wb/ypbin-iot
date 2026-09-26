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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 最新值写入器（Q8/D0.7 + §6.4 跨批乱序防护）：key/参数形态、批内取新、**每个设备一次 EVAL**、
 * 抑制计数与失败不回滚。
 *
 * <p><b>本类证明不了什么（必须如实说明）</b>：跨批与并发的**行为**由服务端 Lua 决定，mock 掉
 * {@code StringRedisTemplate} 只能证明「调用形态正确 + 指标/失败语义正确」。真正的跨批/并发证据在
 * {@code RedisLatestValueWriterIT}（真 Redis，Testcontainers）里，本类不重复声称。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
class RedisLatestValueWriterTest {

    private static final long TENANT_ID = 7L;

    private static final long DEVICE_ID = 11L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RedisLatestValueWriter writer = new RedisLatestValueWriter(redisTemplate, registry);

    private static LatestValue value(long ts, String raw) {
        return new LatestValue(TENANT_ID, DEVICE_ID, "temperature", raw, "GOOD", ts);
    }

    private static LatestValue point(String propertyId, long ts, String raw) {
        return new LatestValue(TENANT_ID, DEVICE_ID, propertyId, raw, "GOOD", ts);
    }

    /** 把一次捕获到的 ARGV 数组转成字符串列表（field,ts,json 三元组连续排列）。 */
    private static List<String> argsOf(ArgumentCaptor<Object[]> argsCaptor, int callIndex) {
        List<String> args = new ArrayList<>();
        for (Object arg : argsCaptor.getAllValues().get(callIndex)) {
            args.add(String.valueOf(arg));
        }
        return args;
    }

    /** 第 callIndex 次 EVAL 的目标 key。 */
    private static String keyOf(ArgumentCaptor<List<String>> keysCaptor, int callIndex) {
        return keysCaptor.getAllValues().get(callIndex).get(0);
    }

    @Test
    @DisplayName("★ key = iot:latest:{tenant}:{device}，ARGV = field,ts,json 三元组（ts 进脚本用于跨批比较）")
    void mustWritePerDeviceWithExpectedKeyAndTripleArgs() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(value(1_700_000_000_000L, "23.5")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keyOf(keysCaptor, 0)).isEqualTo("iot:latest:7:11");
        List<String> args = argsOf(argsCaptor, 0);
        assertThat(args.subList(0, 3)).containsExactly(
            "temperature", "1700000000000", "{\"v\":\"23.5\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        assertThat(args).as("末位是统一的超前上限（服务端时钟 + 允许偏移）").hasSize(4);
        assertThat(Long.parseLong(args.get(3)) - System.currentTimeMillis())
            .as("默认超前上限必须是「服务端 now + 5 分钟」，不能是设备端时间")
            .isBetween(0L, RedisLatestValueWriter.MAX_FUTURE_SKEW_MS);
    }

    @Test
    @DisplayName("★ 批内按 ts 取新：无论到达顺序，进脚本的都是读数时刻最新的那条")
    void mustKeepNewestWithinBatch() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(value(1_000L, "OLD"), value(2_000L, "NEW")));
        writer.writeAll(List.of(value(3_000L, "NEW3"), value(2_500L, "OLD3")));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsOf(argsCaptor, 0).subList(0, 3)).containsExactly(
            "temperature", "2000", "{\"v\":\"NEW\",\"q\":\"GOOD\",\"ts\":2000}");
        assertThat(argsOf(argsCaptor, 1).subList(0, 3)).containsExactly(
            "temperature", "3000", "{\"v\":\"NEW3\",\"q\":\"GOOD\",\"ts\":3000}");
    }

    @Test
    @DisplayName("★ 批内 ts 相等时保留**先到者**（同一 ts 的重放不改变已选中的值）")
    void mustKeepFirstOnEqualTsWithinBatch() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(value(1_000L, "FIRST"), value(1_000L, "SECOND")));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsOf(argsCaptor, 0).get(2)).contains("\"v\":\"FIRST\"");
    }

    @Test
    @DisplayName("★ 每个设备一次 EVAL（不是每个点位一次：避免 N+1 式往返放大）")
    void mustSendOneScriptCallPerDevice() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(
            point("a", 1_000L, "1"),
            point("b", 1_000L, "2"),
            point("c", 1_000L, "3"),
            new LatestValue(TENANT_ID, 12L, "a", "4", "GOOD", 1_000L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), keysCaptor.capture(),
            argsCaptor.capture());
        assertThat(keysCaptor.getAllValues()).hasSize(2);
        assertThat(keyOf(keysCaptor, 0)).isEqualTo("iot:latest:7:11");
        assertThat(keyOf(keysCaptor, 1)).isEqualTo("iot:latest:7:12");
        assertThat(argsCaptor.getAllValues().get(0)).as("同设备 3 个点位共 9 个 ARGV + 1 个超前上限")
            .hasSize(10);
        assertThat(argsCaptor.getAllValues().get(1)).hasSize(4);
        assertThat(argsOf(argsCaptor, 0)).containsSubsequence("a", "1000").containsSubsequence("c", "1000");
    }

    @Test
    @DisplayName("★ 脚本返回的「旧 ts 后到」抑制数必须计入 iot.ingest.latest.regressed")
    void mustCountSuppressedStaleWrites() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of(2L, 0L));

        writer.writeAll(List.of(value(1_000L, "STALE")));

        assertThat(registry.get(RedisLatestValueWriter.METRIC_REGRESSED).counter().count()).isEqualTo(2.0d);
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FUTURE_REJECTED).counter().count()).isZero();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count()).isZero();
    }

    @Test
    @DisplayName("★ 超前护栏：脚本报告的超前拒绝数计入 iot.ingest.latest.future_rejected（与乱序计数分开）")
    void mustCountFutureRejectedSeparately() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of(1L, 2L));

        writer.writeAll(List.of(value(1_000L, "X")));

        assertThat(registry.get(RedisLatestValueWriter.METRIC_REGRESSED).counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FUTURE_REJECTED).counter().count()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("★ 超前上限可注入：ceiling = 服务端 now + 传入偏移（供测试与将来接配置）")
    void ceilingMustHonourConfiguredSkew() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of(0L, 0L));
        RedisLatestValueWriter zeroSkew = new RedisLatestValueWriter(redisTemplate, registry, 0L);

        long before = System.currentTimeMillis();
        zeroSkew.writeAll(List.of(value(1_000L, "X")));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        long ceiling = Long.parseLong(argsOf(argsCaptor, 0).get(3));
        assertThat(ceiling).isBetween(before, System.currentTimeMillis());
    }

    @Test
    @DisplayName("★ 负的超前偏移必须当场报错（配置错不静默兜底）")
    void negativeSkewMustFailFast() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> new RedisLatestValueWriter(redisTemplate, registry, -1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超前偏移");
    }

    @Test
    @DisplayName("★ 脚本返回值缺项/非数字时按 0 计，不抛（返回形态异常不该拖垮上报）")
    void malformedScriptResultMustNotThrow() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of("not-a-number"));

        assertThatCode(() -> writer.writeAll(List.of(value(1_000L, "X")))).doesNotThrowAnyException();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_REGRESSED).counter().count()).isZero();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FUTURE_REJECTED).counter().count()).isZero();
    }

    @Test
    @DisplayName("★ 脚本返回空表时全部按 0 计，不抛（Lua 未返回计数的兜底）")
    void emptyScriptResultMustNotThrow() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of());

        assertThatCode(() -> writer.writeAll(List.of(value(1_000L, "X")))).doesNotThrowAnyException();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_REGRESSED).counter().count()).isZero();
    }

    @Test
    @DisplayName("★ 脚本返回 null（模拟 EVAL 无返回值）不得抛异常、不得误计")
    void mustTolerateNullScriptResult() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThatCode(() -> writer.writeAll(List.of(value(1_000L, "X")))).doesNotThrowAnyException();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_REGRESSED).counter().count()).isZero();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count()).isZero();
    }

    @Test
    @DisplayName("★ 空批次直接返回（不做任何 Redis 调用）")
    void mustShortCircuitEmptyBatch() {
        writer.writeAll(List.of());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("★ 写入失败必须计数且**不抛出去**（最新值属便利数据，不能回滚上报事务）")
    void mustCountFailureAndNotThrow() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> writer.writeAll(List.of(value(1_700_000_000_000L, "1"))))
            .doesNotThrowAnyException();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 一台设备失败不影响另一台（逐 key 兜底，不整批放弃）")
    void mustIsolateFailurePerDevice() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new RuntimeException("redis down"))
            .thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(value(1_000L, "1"),
            new LatestValue(TENANT_ID, 12L, "a", "2", "GOOD", 2_000L)));

        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ JSON 转义：值里的引号/反斜杠/换行必须被转义（否则写进去的 JSON 无法解析）")
    void mustEscapeJsonSpecials() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(value(1_700_000_000_000L, "a\"b\\c\nd")));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsOf(argsCaptor, 0).get(2))
            .isEqualTo("{\"v\":\"a\\\"b\\\\c\\nd\",\"q\":\"GOOD\",\"ts\":1700000000000}");
    }

    @Test
    @DisplayName("★ 转义后的值**不可能**在串尾拼出 `\"ts\":<数字>}`（Lua 用锚定匹配取 ts 的前提）")
    void escapedValueCannotForgeTrailingTs() {
        // 值里塞入伪造的 ts 尾部形态：转义后是 \"ts\":999}，锚定匹配只看串尾真正的 ts
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));

        writer.writeAll(List.of(value(2_000L, "\",\"ts\":999}")));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsOf(argsCaptor, 0).get(2))
            .isEqualTo("{\"v\":\"\\\",\\\"ts\\\":999}\",\"q\":\"GOOD\",\"ts\":2000}");
        assertThat(argsOf(argsCaptor, 0).get(2)).endsWith("\"ts\":2000}");
    }

    @Test
    @DisplayName("★ 非运行时异常包装（如序列化 IllegalStateException）也必须只计数不抛")
    void mustNotPropagateScriptExceptions() {
        doThrow(new IllegalStateException("serialize failed"))
            .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatCode(() -> writer.writeAll(List.of(value(1L, "1")))).doesNotThrowAnyException();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count()).isEqualTo(1.0d);
    }
}
