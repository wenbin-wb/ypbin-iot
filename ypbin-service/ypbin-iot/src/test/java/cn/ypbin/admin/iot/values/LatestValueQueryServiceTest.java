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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.starter.core.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 最新值查询（G1）：命中 / key 不存在 / Redis 异常三态 + 租户解析 + key 与写入侧逐字一致。
 *
 * <p>本用例之所以要覆盖「Redis 异常」这一态：写侧失败只计数不回滚（见 {@code RedisLatestValueWriter}），
 * 读侧若把异常当成「没有数据」而不留痕，前端看到的就是一个无法与「设备没上报」区分的空表——
 * 那正是本次要消灭的「看不出关联」。故断言必须同时钉住「返回空列表」与「失败计数 +1」。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class LatestValueQueryServiceTest {

    private static final Long DEVICE_ID = 9300012L;

    private static final Long TENANT_ID = 7L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final LatestValueQueryService service = new LatestValueQueryService(redisProvider,
        deviceMapper, new ObjectMapper(), registry);

    /** 租户表里的设备行（tenantId 是 key 的租户段来源）。 */
    private void stubDevice() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
    }

    private void stubRedis() {
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private double readFailedCount() {
        return registry.get(LatestValueQueryService.METRIC_READ_FAILED).counter().count();
    }

    @Test
    @DisplayName("★ 命中：一次 HGETALL 读回全部点位，key 与写入侧逐字一致，value/quality/ts 正确解析")
    void mustReturnAllPointsOnHit() {
        stubDevice();
        stubRedis();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("temperature", "{\"v\":\"23.4\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        hash.put("humidity", "{\"v\":\"58\",\"q\":\"GOOD\",\"ts\":1700000000500}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).hasSize(2);
        // 按点位标识升序（Redis 哈希无序，输出必须稳定）
        assertThat(values).extracting(LatestValueResp::getPropertyId)
            .containsExactly("humidity", "temperature");
        assertThat(values.get(1).getValue()).isEqualTo("23.4");
        assertThat(values.get(1).getQuality()).isEqualTo("GOOD");
        assertThat(values.get(1).getTs()).isEqualTo(1_700_000_000_000L);
        // 只有一次 Redis 往返，且 key 与 RedisLatestValueWriter.key 完全一致
        verify(hashOperations).entries(RedisLatestValueWriter.key(TENANT_ID, DEVICE_ID));
        assertThat(readFailedCount()).isZero();
    }

    @Test
    @DisplayName("★ key 不存在（空哈希）：返回空集合，不报错、不计数为失败")
    void mustReturnEmptyWhenKeyAbsent() {
        stubDevice();
        stubRedis();
        when(hashOperations.entries(anyString())).thenReturn(Map.of());

        assertThat(service.listLatest(DEVICE_ID)).isEmpty();
        assertThat(readFailedCount()).isZero();
    }

    @Test
    @DisplayName("★ Redis 异常：返回空集合（不抛给调用方）但必须计数 + 记日志，不得伪装成「没有数据」")
    void mustReturnEmptyAndCountWhenRedisFails() {
        stubDevice();
        stubRedis();
        when(hashOperations.entries(anyString()))
            .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(service.listLatest(DEVICE_ID)).isEmpty();
        assertThat(readFailedCount()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 未装配 Redis（无 StringRedisTemplate）：返回空集合，同样不报错")
    void mustReturnEmptyWhenRedisNotWired() {
        stubDevice();
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.listLatest(DEVICE_ID)).isEmpty();
        // 未装配是「部署形态」而非「读失败」，不计入失败指标
        assertThat(readFailedCount()).isZero();
    }

    @Test
    @DisplayName("★ 租户解析：租户取自设备行（多租户插件查不到即拒绝），不自行发明租户来源")
    void mustResolveTenantFromDeviceRow() {
        // 设备不存在（含跨租户被租户插件过滤掉的情形）：与 /series、/shadow 同口径按拒绝处理
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.listLatest(DEVICE_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");

        // 设备行的 tenantId 为空同样拒绝：key 里不能出现 "null" 段（那会读到别人的哈希）
        IotDevice noTenant = new IotDevice();
        noTenant.setId(DEVICE_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(noTenant);
        assertThatThrownBy(() -> service.listLatest(DEVICE_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
    }

    @Test
    @DisplayName("★ 设备 ID 缺失：拒绝而不是查全表")
    void mustRejectNullDeviceId() {
        assertThatThrownBy(() -> service.listLatest(null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 单条损坏 JSON 只丢该点位并计数留痕之外的兄弟点位不受影响（不整台丢数据）")
    void mustSkipBrokenEntryOnly() {
        stubDevice();
        stubRedis();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("broken", "{not json");
        hash.put("ok", "{\"v\":\"1\",\"q\":\"GOOD\",\"ts\":123}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).extracting(LatestValueResp::getPropertyId).containsExactly("ok");
        assertThat(values.get(0).getTs()).isEqualTo(123L);
    }
}
