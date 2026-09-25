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

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 最新值查询服务（补 G1：最新值原本只有写路径 {@link RedisLatestValueWriter}，无任何读取端点）。
 *
 * <p><b>读的是什么</b>：Redis 哈希 {@code iot:latest:{tenantId}:{deviceId}}，field = 点位标识，
 * value = 写入器落下的紧凑 JSON {@code {"v":...,"q":...,"ts":...}}。key 的构造**直接复用**
 * {@link RedisLatestValueWriter#key(Long, Long)}，避免读写两侧各写一份格式而漂移。</p>
 *
 * <p><b>租户口径</b>：与 {@code TimeSeriesQueryService} 同源——先按当前身份取设备行
 * （{@code iot_device} 是租户表，多租户插件会追加租户条件），再由**设备自身的 tenantId** 组成 key。
 * 不走 {@code TenantContext} 直接拼 key：写入侧的租户取的就是设备行的 tenantId，读侧若换成请求上下文，
 * 两者一旦不一致就会「读到空」且无从察觉。</p>
 *
 * <p><b>失败语义（必须如实区分，不伪造数据）</b>：Redis 未装配、连接不上、或 key 不存在时**返回空列表**
 * 而不是报错——「没有最新值」与「平台故障」是两件事，前者是正常状态。但失败**必须可观测**：
 * 读异常一律 {@code log.error}（带完整堆栈）并累加指标 {@value #METRIC_READ_FAILED}，
 * 前端与值班不需要靠「页面空白」去猜是哪一种。</p>
 *
 * <p>禁止在循环里逐个读 Redis：本服务只发一次 {@code HGETALL}（{@code entries}），
 * 一台设备的全部点位一次取回（与写入侧的 Hash 设计对应）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Service
public class LatestValueQueryService {

    private static final Logger log = LoggerFactory.getLogger(LatestValueQueryService.class);

    /** 读取失败计数（按次计）：让「Redis 抖动 ⇒ 空列表」在指标上可见，而不是只在日志里。 */
    public static final String METRIC_READ_FAILED = "iot.latest.read.failed";

    /** 紧凑 JSON 里的值字段名（与 {@link RedisLatestValueWriter} 的写入格式逐字一致）。 */
    private static final String FIELD_VALUE = "v";

    /** 紧凑 JSON 里的质量码字段名。 */
    private static final String FIELD_QUALITY = "q";

    /** 紧凑 JSON 里的读数时刻字段名（epoch 毫秒）。 */
    private static final String FIELD_TS = "ts";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final IotDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final Counter readFailedCounter;

    public LatestValueQueryService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                   IotDeviceMapper deviceMapper, ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.deviceMapper = deviceMapper;
        this.objectMapper = objectMapper;
        this.readFailedCounter = Counter.builder(METRIC_READ_FAILED)
            .description("最新值读取 Redis 失败的次数").register(meterRegistry);
    }

    /**
     * 查询某设备全部点位的最新值。
     *
     * @param deviceId 设备 ID
     * @return 最新值列表（按点位标识升序；无数据或 Redis 不可用时为空列表，永不返回 {@code null}）
     */
    public List<LatestValueResp> listLatest(Long deviceId) {
        if (deviceId == null) {
            throw new BusinessException("设备 ID 不能为空");
        }
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null || device.getTenantId() == null) {
            log.warn("[iot] 最新值查询的设备不存在（或不属于当前租户），按拒绝处理：deviceId={}", deviceId);
            throw new BusinessException("设备不存在");
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            // 与写入侧同一取向：没有 Redis 就没有最新值（可用率/断档不受影响），如实返回空
            log.warn("[iot] 未发现 StringRedisTemplate：最新值查询返回空列表（deviceId={}）", deviceId);
            return List.of();
        }
        String key = RedisLatestValueWriter.key(device.getTenantId(), deviceId);
        Map<Object, Object> entries;
        try {
            // 一次 HGETALL 取回该设备全部点位（严禁在循环里逐个 HGET）
            entries = redisTemplate.opsForHash().entries(key);
        } catch (RuntimeException ex) {
            readFailedCounter.increment();
            log.error("[iot] 最新值读取失败（按空列表返回，不伪造数据）：key={}",
                LogSanitizer.sanitize(key), ex);
            return List.of();
        }
        if (entries == null || entries.isEmpty()) {
            // key 不存在 = 这台设备还没上报过：正常状态，不是错误
            return List.of();
        }
        // 按点位标识排序：Redis 哈希无序，输出顺序不固定会让同一份数据每次展示都不一样
        Map<String, String> ordered = new TreeMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            ordered.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null
                : String.valueOf(entry.getValue()));
        }
        List<LatestValueResp> result = new ArrayList<>(ordered.size());
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            LatestValueResp value = parse(entry.getKey(), entry.getValue(), deviceId);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 解析写入器落的紧凑 JSON（单个点位）。损坏的条目**跳过并报错**，不让整台设备的最新值全丢。
     *
     * @param propertyId 点位标识（哈希 field）
     * @param json       紧凑 JSON（哈希 value）
     * @param deviceId   设备 ID（仅用于告警定位）
     * @return 最新值；无法解析时返回 {@code null}
     */
    private LatestValueResp parse(String propertyId, String json, Long deviceId) {
        if (json == null || json.isBlank()) {
            log.error("[iot] 最新值条目为空，已跳过：deviceId={} propertyId={}", deviceId,
                LogSanitizer.sanitize(propertyId));
            return null;
        }
        Map<String, Object> fields;
        try {
            fields = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException ex) {
            log.error("[iot] 最新值 JSON 解析失败，已跳过该点位：deviceId={} propertyId={}", deviceId,
                LogSanitizer.sanitize(propertyId), ex);
            return null;
        }
        LatestValueResp resp = new LatestValueResp();
        resp.setPropertyId(propertyId);
        Object raw = fields.get(FIELD_VALUE);
        resp.setValue(raw == null ? null : String.valueOf(raw));
        Object quality = fields.get(FIELD_QUALITY);
        resp.setQuality(quality == null ? null : String.valueOf(quality));
        Object ts = fields.get(FIELD_TS);
        if (ts instanceof Number number) {
            resp.setTs(number.longValue());
        } else if (ts != null) {
            log.error("[iot] 最新值缺少合法的读数时刻（ts），已跳过该点位：deviceId={} propertyId={}",
                deviceId, LogSanitizer.sanitize(propertyId));
            return null;
        }
        return resp;
    }
}
