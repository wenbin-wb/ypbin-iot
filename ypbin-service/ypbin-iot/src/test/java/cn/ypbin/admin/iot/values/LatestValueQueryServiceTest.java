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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.starter.core.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 最新值查询（G1）：命中 / key 不存在 / Redis 异常三态 + 租户解析 + key 与写入侧逐字一致
 * + **过渡期坐标形态归一**（历史主键字符串 field → 属性标识）。
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

    /** 历史主键字符串形态（坐标统一前 access 上报的形态）。 */
    private static final String LEGACY_FORM = "9130001";

    private static final String IDENTIFIER = "temperature";

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

    private final PointMappingIndex pointMappingIndex = mock(PointMappingIndex.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final LatestValueQueryService service = new LatestValueQueryService(redisProvider,
        deviceMapper, new ObjectMapper(), pointMappingIndex, registry);

    /** 默认「没有历史坐标形态」：既有用例的口径不受影响（专项用例用小 Stub 覆盖）。 */
    @BeforeEach
    void stubCoordinatesByDefault() {
        lenient().when(pointMappingIndex.loadCoordinates(any())).thenReturn(Map.of());
    }

    /** 某设备存在「历史主键字符串 → 属性标识」的映射（过渡期读侧要认得）。 */
    private void stubLegacyCoordinate() {
        when(pointMappingIndex.loadCoordinates(any())).thenReturn(Map.of(DEVICE_ID,
            new PointMappingIndex.DeviceCoordinates(
                Map.of(IDENTIFIER, IDENTIFIER, LEGACY_FORM, IDENTIFIER),
                Map.of(IDENTIFIER, Set.of(LEGACY_FORM)), Set.of(), Set.of(), Set.of())));
    }

    private double legacyFieldCount() {
        return registry.get(LatestValueQueryService.METRIC_LEGACY_FIELD).counter().count();
    }

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
    @DisplayName("★ 过渡期兼容：历史**主键字符串** field 被归一到属性标识后仍能按标识命中，且计数 +1")
    void legacyPrimaryKeyFieldMustBeCanonicalizedToIdentifier() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        // 坐标统一前 access 写下的形态：field 是属性主键字符串
        hash.put(LEGACY_FORM, "{\"v\":\"23.4\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement().satisfies(value -> {
            assertThat(value.getPropertyId())
                .as("对外字段名不变（仍是 propertyId），但值必须是规范坐标 → 按标识能查到")
                .isEqualTo(IDENTIFIER);
            assertThat(value.getValue()).isEqualTo("23.4");
            assertThat(value.getTs()).isEqualTo(1_700_000_000_000L);
        });
        assertThat(legacyFieldCount()).as("能看出这条数据来自历史形态").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 过渡期兼容：同一标识的两种形态并存 ⇒ 按 ts 取新（旧 ts 的历史形态不得覆盖新值）")
    void bothFormsMustPickNewestByTs() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put(LEGACY_FORM, "{\"v\":\"old\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        hash.put(IDENTIFIER, "{\"v\":\"new\",\"q\":\"GOOD\",\"ts\":1700000009999}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement()
            .satisfies(value -> assertThat(value.getValue()).isEqualTo("new"));
    }

    @Test
    @DisplayName("★ 过渡期兼容：标识形态里是旧值、历史形态里是更新的值时 ⇒ 取更新的那条（不因归一丢数据）")
    void newerLegacyValueMustWinOverStalerIdentifierForm() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put(IDENTIFIER, "{\"v\":\"stale\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        hash.put(LEGACY_FORM, "{\"v\":\"fresh\",\"q\":\"GOOD\",\"ts\":1700000009999}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement().satisfies(value -> {
            assertThat(value.getPropertyId()).isEqualTo(IDENTIFIER);
            assertThat(value.getValue()).as("历史形态里更新的读数必须被看见，否则归一等于丢数据")
                .isEqualTo("fresh");
        });
    }

    @Test
    @DisplayName("★ ts 并列裁决（顺序 A：标识先、历史后）⇒ 取**标识形态**，与遍历顺序无关")
    void equalTsMustPreferIdentifierWhenIdentifierComesFirst() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put(IDENTIFIER, "{\"v\":\"from-identifier\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        hash.put(LEGACY_FORM, "{\"v\":\"from-legacy\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement()
            .satisfies(value -> assertThat(value.getValue()).isEqualTo("from-identifier"));
    }

    @Test
    @DisplayName("★ ts 并列裁决（顺序 B：历史先、标识后）⇒ 仍取**标识形态**（结果不随 Redis HGETALL 顺序跳变）")
    void equalTsMustPreferIdentifierWhenLegacyComesFirst() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put(LEGACY_FORM, "{\"v\":\"from-legacy\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        hash.put(IDENTIFIER, "{\"v\":\"from-identifier\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement().satisfies(value -> {
            assertThat(value.getPropertyId()).isEqualTo(IDENTIFIER);
            assertThat(value.getValue())
                .as("ts 并列时必须取规范标识形态（否则同一个 key 两次查询可能给出不同的值）")
                .isEqualTo("from-identifier");
        });
    }

    @Test
    @DisplayName("★ ts 并列时**历史形态更新也不许篡位**：只要标识形态同 ts 在场，结果就固定为标识形态")
    void equalTsMustStayDeterministicAcrossRepeatedReads() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put(LEGACY_FORM, "{\"v\":\"from-legacy\",\"q\":\"BAD\",\"ts\":1700000000000}");
        hash.put(IDENTIFIER, "{\"v\":\"from-identifier\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        // 连读两次必须完全一致（不确定性的可观测形态就是「同一份数据两次不同」）
        LatestValueResp first = service.listLatest(DEVICE_ID).getFirst();
        LatestValueResp second = service.listLatest(DEVICE_ID).getFirst();

        assertThat(first.getValue()).isEqualTo(second.getValue()).isEqualTo("from-identifier");
        assertThat(first.getQuality()).isEqualTo(second.getQuality()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("★ ts 并列（坐标级撞名：库里只有两个历史形态、没有标识形态）⇒ 按形态字典序定胜负，仍与遍历顺序无关")
    void equalTsWithOnlyLegacyFormsMustUseLexicographicOrder() {
        stubDevice();
        stubRedis();
        // 两个点位共用规范标识 temperature，库里只有各自的历史主键形态 9130001 / 9130002
        when(pointMappingIndex.loadCoordinates(any())).thenReturn(Map.of(DEVICE_ID,
            new PointMappingIndex.DeviceCoordinates(
                Map.of("temperature", "temperature", "9130001", "temperature", "9130002", "temperature"),
                Map.of("temperature", Set.of("9130001", "9130002")), Set.of(), Set.of(),
                Set.of("temperature"))));
        Map<Object, Object> legacyFirst = new LinkedHashMap<>();
        legacyFirst.put("9130001", "{\"v\":\"from-9130001\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        legacyFirst.put("9130002", "{\"v\":\"from-9130002\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(legacyFirst);
        LatestValueResp first = service.listLatest(DEVICE_ID).getFirst();

        // 对调哈希遍历顺序，结果必须一致（字典序最小者胜 ⇒ 恒为 9130001）
        Map<Object, Object> reversed = new LinkedHashMap<>();
        reversed.put("9130002", "{\"v\":\"from-9130002\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        reversed.put("9130001", "{\"v\":\"from-9130001\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(reversed);
        LatestValueResp second = service.listLatest(DEVICE_ID).getFirst();

        assertThat(first.getValue()).isEqualTo("from-9130001");
        assertThat(second.getValue())
            .as("两种形态都不是规范标识时也必须确定（字典序），不得随 Redis HGETALL 顺序跳变")
            .isEqualTo("from-9130001");
    }

    @Test
    @DisplayName("★ 非本设备的历史形态不参与归一：不做映射的 field 原样保留（不误伤其它来源的数据）")
    void unknownFieldMustStayAsIs() {
        stubDevice();
        stubRedis();
        stubLegacyCoordinate();
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("p_temp", "{\"v\":\"9\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement()
            .satisfies(value -> assertThat(value.getPropertyId()).isEqualTo("p_temp"));
        assertThat(legacyFieldCount()).isZero();
    }

    @Test
    @DisplayName("★ 交叉不误伤：历史形态不会被判到同一设备的另一个点位上")
    void legacyFormMustNotBeAttributedToAnotherPoint() {
        stubDevice();
        stubRedis();
        when(pointMappingIndex.loadCoordinates(any())).thenReturn(Map.of(DEVICE_ID,
            new PointMappingIndex.DeviceCoordinates(
                Map.of(IDENTIFIER, IDENTIFIER, "humidity", "humidity", LEGACY_FORM, IDENTIFIER,
                    "9130002", "humidity"),
                Map.of(IDENTIFIER, Set.of(LEGACY_FORM), "humidity", Set.of("9130002")),
                Set.of(), Set.of(), Set.of())));
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("9130002", "{\"v\":\"61\",\"q\":\"GOOD\",\"ts\":1700000000000}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).singleElement()
            .satisfies(value -> assertThat(value.getPropertyId()).isEqualTo("humidity"));
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
    @DisplayName("★ 单条损坏 JSON 只丢该点位，兄弟点位不受影响（不整台丢数据）")
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

    @Test
    @DisplayName("★ 缺 ts（写入器不会这么写，出现即不可信）同样跳过：不返回无法判时效的值")
    void mustSkipEntryWithoutTs() {
        stubDevice();
        stubRedis();
        Map<Object, Object> hash = new LinkedHashMap<>();
        // 写入器 json() 恒定输出 v/q/ts 三键 ⇒ 缺 ts 说明这条数据不是本写入器写的（或被人改过）
        hash.put("noTs", "{\"v\":\"9\",\"q\":\"GOOD\"}");
        hash.put("nonNumericTs", "{\"v\":\"9\",\"q\":\"GOOD\",\"ts\":\"not-a-number\"}");
        hash.put("ok", "{\"v\":\"1\",\"q\":\"GOOD\",\"ts\":123}");
        when(hashOperations.entries(anyString())).thenReturn(hash);

        List<LatestValueResp> values = service.listLatest(DEVICE_ID);

        assertThat(values).extracting(LatestValueResp::getPropertyId).containsExactly("ok");
    }
}
