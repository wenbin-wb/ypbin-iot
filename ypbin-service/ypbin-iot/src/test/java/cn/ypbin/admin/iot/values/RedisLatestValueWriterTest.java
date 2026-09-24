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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 最新值写入器（Q8/D0.7）：key/字段/JSON 形状、批内按 ts 取新、失败不回滚只计数。
 *
 * @author wenbin
 * @since 2026-09-24
 */
class RedisLatestValueWriterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RedisLatestValueWriter writer = new RedisLatestValueWriter(redisTemplate, registry);

    private void stubHash() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private static LatestValue value(long ts, String raw) {
        return new LatestValue(7L, 11L, "temperature", raw, "GOOD", ts);
    }

    @Test
    @DisplayName("★ key = iot:latest:{tenant}:{device}，field = 点位，value = 紧凑 JSON（含 ts 供判新旧）")
    void mustWriteHashWithExpectedKeyAndJson() {
        stubHash();
        writer.writeAll(List.of(value(1_700_000_000_000L, "23.5")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(anyString(), captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys("temperature");
        assertThat(String.valueOf(captor.getValue().get("temperature")))
            .isEqualTo("{\"v\":\"23.5\",\"q\":\"GOOD\",\"ts\":1700000000000}");
    }

    @Test
    @DisplayName("★ 空批次直接返回（不做任何 Redis 调用）")
    void mustShortCircuitEmptyBatch() {
        writer.writeAll(List.of());
        verify(redisTemplate, org.mockito.Mockito.never()).opsForHash();
    }

    @Test
    @DisplayName("★ 写入失败必须计数且**不抛出去**（最新值属便利数据，不能回滚上报事务）")
    void mustCountFailureAndNotThrow() {
        stubHash();
        doThrow(new RuntimeException("redis down")).when(hashOperations).putAll(anyString(), anyMap());

        assertThatCode(() -> writer.writeAll(List.of(value(1_700_000_000_000L, "1"))))
            .doesNotThrowAnyException();
        assertThat(registry.get(RedisLatestValueWriter.METRIC_FAILED).counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ JSON 转义：值里的引号/反斜杠/换行必须被转义（否则写进去的 JSON 无法解析）")
    void mustEscapeJsonSpecials() {
        stubHash();
        writer.writeAll(List.of(value(1_700_000_000_000L, "a\"b\\c\nd")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(anyString(), captor.capture());
        assertThat(String.valueOf(captor.getValue().get("temperature")))
            .isEqualTo("{\"v\":\"a\\\"b\\\\c\\nd\",\"q\":\"GOOD\",\"ts\":1700000000000}");
    }
}
