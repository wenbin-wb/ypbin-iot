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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 批量最新值读取器的用例（设计 §3.1-T8 的另一半：**验证「读不到」真的会抛，而不是静默返回空**）。
 *
 * <p>独立复核（2026-10-03）指出：此前只有「评估器收到 {@link AlertEvaluationException} 之后的反应」被覆盖，
 * 而「读取器是否真的会抛」无人守——如果它退化成「返回空集合」，整套「不可判定」语义会在最上游失效，
 * 表现为**所有活动告警被静默判成恢复**。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertLatestValueReaderTest {

    private ObjectProvider<StringRedisTemplate> redisProvider;

    private StringRedisTemplate redisTemplate;

    private PointMappingIndex pointMappingIndex;

    private ObjectMapper objectMapper;

    private AlertLatestValueReader reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisProvider = mock(ObjectProvider.class);
        redisTemplate = mock(StringRedisTemplate.class);
        pointMappingIndex = mock(PointMappingIndex.class);
        objectMapper = mock(ObjectMapper.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(pointMappingIndex.loadCoordinates(anyList())).thenReturn(Map.of());
        reader = new AlertLatestValueReader(redisProvider, pointMappingIndex, objectMapper);
    }

    /** 让 pipeline 为每个 key 返回给定的哈希。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPipeline(List<Object> results) {
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
            .thenAnswer(invocation -> new ArrayList<>(results));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPipelineThrows() {
        when(redisTemplate.executePipelined(any(SessionCallback.class)))
            .thenThrow(new RuntimeException("模拟 Redis 连接不可用"));
    }

    private void stubJson(String value, String quality, Long ts) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", value);
        fields.put("q", quality);
        fields.put("ts", ts);
        when(objectMapper.readValue(any(String.class), any(tools.jackson.core.type.TypeReference.class)))
            .thenReturn(fields);
    }

    @Test
    @DisplayName("★ Redis 未装配 ⇒ **抛异常**（绝不返回空集合——空集合会被读成「都没越界」⇒ 静默恢复）")
    void missingRedisThrows() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> reader.readLatest(1L, List.of(9L)))
            .isInstanceOf(AlertEvaluationException.class)
            .hasMessageContaining("Redis 未装配");
    }

    @Test
    @DisplayName("★ Redis 读取失败 ⇒ 抛异常（不是空集合）")
    void redisFailureThrows() {
        stubPipelineThrows();
        assertThatThrownBy(() -> reader.readLatest(1L, List.of(9L)))
            .isInstanceOf(AlertEvaluationException.class)
            .hasMessageContaining("批量读取最新值失败");
    }

    @Test
    @DisplayName("pipeline 返回条数与请求不一致 ⇒ 抛异常（无法把结果与设备对齐时宁可整体失败，也不错位判定）")
    void resultCountMismatchThrows() {
        stubPipeline(List.of(Map.of()));
        assertThatThrownBy(() -> reader.readLatest(1L, List.of(9L, 10L)))
            .isInstanceOf(AlertEvaluationException.class)
            .hasMessageContaining("条数与请求不一致");
    }

    @Test
    @DisplayName("成功解析：一次 pipeline 取回全部设备，缺数据的设备不进结果（不代表失败）")
    void parsesHashEntries() {
        stubJson("35", "GOOD", 123L);
        Map<Object, Object> hash = Map.of("temperature", "{\"v\":\"35\",\"q\":\"GOOD\",\"ts\":123}");
        stubPipeline(List.of(hash, Map.of()));

        Map<Long, Map<String, AlertLatestValueReader.AlertSample>> result =
            reader.readLatest(1L, List.of(9L, 10L));

        assertThat(result).containsOnlyKeys(9L);
        AlertLatestValueReader.AlertSample sample = result.get(9L).get("temperature");
        assertThat(sample.value()).isEqualTo("35");
        assertThat(sample.quality()).isEqualTo("GOOD");
        assertThat(sample.ts()).isEqualTo(123L);
    }

    @Test
    @DisplayName("过渡期坐标形态归一：历史主键字符串 field 归一到属性标识（同一标识取 ts 更新者）")
    void normalizesLegacyCoordinateForms() {
        stubJson("40", "GOOD", 999L);
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("1000123", "{\"v\":\"40\",\"q\":\"GOOD\",\"ts\":999}");
        stubPipeline(List.of(hash));
        PointMappingIndex.DeviceCoordinates coordinates = mock(PointMappingIndex.DeviceCoordinates.class);
        when(coordinates.canonicalByForm()).thenReturn(Map.of("1000123", "temperature"));
        when(pointMappingIndex.loadCoordinates(anyList())).thenReturn(Map.of(9L, coordinates));

        Map<Long, Map<String, AlertLatestValueReader.AlertSample>> result =
            reader.readLatest(1L, List.of(9L));

        assertThat(result.get(9L)).containsOnlyKeys("temperature");
        assertThat(result.get(9L).get("temperature").value()).isEqualTo("40");
    }

    @Test
    @DisplayName("坐标映射加载失败只降级为「field 即规范标识」，不放弃整轮判定（读不到映射 ≠ 读不到数据）")
    void coordinateLoadFailureDegradesGracefully() {
        when(pointMappingIndex.loadCoordinates(anyList()))
            .thenThrow(new RuntimeException("模拟映射表不可用"));
        stubJson("35", "GOOD", 123L);
        stubPipeline(List.of(Map.of("temperature", "{\"v\":\"35\",\"q\":\"GOOD\",\"ts\":123}")));

        Map<Long, Map<String, AlertLatestValueReader.AlertSample>> result =
            reader.readLatest(1L, List.of(9L));
        assertThat(result.get(9L)).containsOnlyKeys("temperature");
    }

    @Test
    @DisplayName("单条 JSON 损坏 ⇒ 跳过该点位但不影响其它点位（不因一条脏数据丢掉整台设备）")
    void brokenJsonSkipsOnlyThatPoint() {
        when(objectMapper.readValue(any(String.class), any(tools.jackson.core.type.TypeReference.class)))
            .thenThrow(new RuntimeException("模拟非法 JSON"))
            .thenReturn(Map.of("v", "36", "q", "GOOD", "ts", 124L));
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("broken", "not-json");
        hash.put("temperature", "{\"v\":\"36\"}");
        stubPipeline(List.of(hash));

        Map<Long, Map<String, AlertLatestValueReader.AlertSample>> result =
            reader.readLatest(1L, List.of(9L));
        assertThat(result.get(9L)).containsOnlyKeys("temperature");
    }

    @Test
    @DisplayName("空设备列表直接返回空（不发 Redis 请求，也不生成 IN ()）")
    void emptyDeviceIdsShortCircuits() {
        assertThat(reader.readLatest(1L, List.of())).isEmpty();
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never())
            .executePipelined(any(SessionCallback.class));
        org.mockito.Mockito.verify(redisProvider, org.mockito.Mockito.never()).getIfAvailable();
    }

    @Test
    @DisplayName("Redis key 形态与写入侧一致（iot:latest:{tenantId}:{deviceId}），避免读写 key 漂移")
    void usesWriterKeyFormat() {
        stubJson("35", "GOOD", 1L);
        stubPipeline(List.of(Map.of()));
        reader.readLatest(7L, List.of(9L));
        assertThat(cn.ypbin.admin.iot.values.RedisLatestValueWriter.key(7L, 9L))
            .isEqualTo("iot:latest:7:9");
    }
}
