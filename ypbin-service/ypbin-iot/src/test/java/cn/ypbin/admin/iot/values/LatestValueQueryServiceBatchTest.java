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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 批量最新值：**一次 pipeline**、空集合短路、Redis 故障返回空、顺序对齐。
 *
 * <p>为什么这些断言重要：孪生场景一次可能拉 500 台设备。若实现退化成「按设备循环
 * HGETALL」，功能看起来完全正常（值都对），但一次页面打开就要几十秒且打满连接池
 * ——这是最难在功能测试里发现的一类退化。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class LatestValueQueryServiceBatchTest {

    private static final long DEVICE_A = 9_000_001L;

    private static final long DEVICE_B = 9_000_002L;

    private static final long TENANT_ID = 1L;

    @Test
    @DisplayName("★ 空集合短路：不发任何查询")
    void emptyInputShortCircuits() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        LatestValueQueryService service = newService(deviceMapper, redisTemplate);

        assertThat(service.listLatestBatch(List.of())).isEmpty();
        assertThat(service.listLatestBatch(null)).isEmpty();
        verify(deviceMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("★ 两台设备只发一次 pipeline（不是两次 HGETALL）")
    void singlePipelineForManyDevices() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection()))
            .thenReturn(List.of(device(DEVICE_A), device(DEVICE_B)));
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(
            Map.of("temperature", "{\"v\":\"26.5\",\"q\":\"good\",\"ts\":1000}"),
            Map.of()));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result =
            service.listLatestBatch(List.of(DEVICE_A, DEVICE_B));

        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
        assertThat(result.get(DEVICE_A)).hasSize(1);
        assertThat(result.get(DEVICE_A).getFirst().getValue()).isEqualTo("26.5");
        assertThat(result.get(DEVICE_A).getFirst().getQuality()).isEqualTo("good");
        assertThat(result.get(DEVICE_A).getFirst().getTs()).isEqualTo(1000L);
        assertThat(result.get(DEVICE_B)).isEmpty();
    }

    @Test
    @DisplayName("★ Redis 读取异常 ⇒ 每台设备都返回空列表（不抛、不伪造）+ 失败计数")
    void redisFailureReturnsEmptyPerDevice() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection()))
            .thenReturn(List.of(device(DEVICE_A), device(DEVICE_B)));
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
            .thenThrow(new RedisConnectionFailureException("connection refused"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatestValueQueryService service = newServiceWithRegistry(deviceMapper, redisTemplate,
            registry);

        Map<Long, List<LatestValueResp>> result =
            service.listLatestBatch(List.of(DEVICE_A, DEVICE_B));

        assertThat(result).containsKeys(DEVICE_A, DEVICE_B);
        assertThat(result.get(DEVICE_A)).isEmpty();
        assertThat(result.get(DEVICE_B)).isEmpty();
        assertThat(registry.counter(LatestValueQueryService.METRIC_READ_FAILED).count())
            .as("Redis 读取失败必须计入指标（否则「Redis 抖动 ⇒ 空列表」在监控上不可见）")
            .isEqualTo(1.0D);
    }

    @Test
    @DisplayName("★ Redis 未装配 ⇒ 每台设备空列表，不发 pipeline")
    void missingRedisTemplateReturnsEmpty() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of(device(DEVICE_A)));

        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        PointMappingIndex index = mock(PointMappingIndex.class);
        when(index.loadCoordinates(anyCollection())).thenReturn(Map.of());
        LatestValueQueryService service = new LatestValueQueryService(provider, deviceMapper,
            new ObjectMapper(), index, new SimpleMeterRegistry());

        Map<Long, List<LatestValueResp>> result = service.listLatestBatch(List.of(DEVICE_A));

        assertThat(result.get(DEVICE_A)).isEmpty();
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("★ pipeline 结果按入队顺序对齐：A 空、B 有值时不串位")
    void pipelineOrderMatters() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection()))
            .thenReturn(List.of(device(DEVICE_A), device(DEVICE_B)));
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(
            Map.of(),
            Map.of("pressure", "{\"v\":\"1.2\",\"ts\":2000}")));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result =
            service.listLatestBatch(List.of(DEVICE_A, DEVICE_B));

        assertThat(result.get(DEVICE_A)).isEmpty();
        assertThat(result.get(DEVICE_B)).hasSize(1);
        assertThat(result.get(DEVICE_B).getFirst().getValue()).isEqualTo("1.2");
    }

    @Test
    @DisplayName("同一属性的两种坐标形态按 ts 取新（与单设备版同一裁决）")
    void mergePicksNewestTs() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of(device(DEVICE_A)));
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(
            Map.of(
                "temperature", "{\"v\":\"old\",\"ts\":1000}",
                "6001", "{\"v\":\"new\",\"ts\":2000}")));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result = service.listLatestBatch(List.of(DEVICE_A));

        // 坐标索引为 mock 空 ⇒ 两种形态各自保留；ts 较新者为 "new"
        assertThat(result.get(DEVICE_A))
            .extracting(LatestValueResp::getValue)
            .contains("new");
    }

    @Test
    @DisplayName("损坏的 JSON 条目被跳过，不让整台设备的最新值全丢")
    void corruptEntrySkipped() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of(device(DEVICE_A)));
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(
            Map.of(
                "broken", "not-json",
                "temperature", "{\"v\":\"26.5\",\"ts\":1000}")));

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);
        Map<Long, List<LatestValueResp>> result = service.listLatestBatch(List.of(DEVICE_A));

        assertThat(result.get(DEVICE_A)).hasSize(1);
        assertThat(result.get(DEVICE_A).getFirst().getPropertyId()).isEqualTo("temperature");
    }

    @Test
    @DisplayName("设备全部不存在（跨租户）⇒ 空 Map，不发 pipeline")
    void unknownDevicesReturnEmptyMap() {
        IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(deviceMapper.selectByIds(anyCollection())).thenReturn(List.of());

        LatestValueQueryService service = newService(deviceMapper, redisTemplate);

        assertThat(service.listLatestBatch(List.of(DEVICE_A))).isEmpty();
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    private static IotDevice device(long id) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(TENANT_ID);
        return device;
    }

    private static LatestValueQueryService newService(IotDeviceMapper deviceMapper,
                                                       StringRedisTemplate redisTemplate) {
        return newServiceWithRegistry(deviceMapper, redisTemplate, new SimpleMeterRegistry());
    }

    @SuppressWarnings("unchecked")
    private static LatestValueQueryService newServiceWithRegistry(
        IotDeviceMapper deviceMapper, StringRedisTemplate redisTemplate,
        SimpleMeterRegistry registry) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        PointMappingIndex index = mock(PointMappingIndex.class);
        when(index.loadCoordinates(anyCollection())).thenReturn(Map.of());
        return new LatestValueQueryService(provider, deviceMapper, new ObjectMapper(), index,
            registry);
    }
}
