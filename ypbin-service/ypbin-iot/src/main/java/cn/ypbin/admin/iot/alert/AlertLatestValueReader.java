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

import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.values.RedisLatestValueWriter;
import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 评估器专用的**批量**最新值读取器（读 Redis {@code iot:latest:{tenantId}:{deviceId}}）。
 *
 * <p><b>为什么不用现成的 {@code LatestValueQueryService}</b>：那个服务的契约是「查一台设备」——
 * 它内部会按设备做一次 {@code iot_device} 查询与一次 {@code PointMappingIndex} 加载。评估器每轮要处理
 * 多台设备，逐台调用它就是把一次批量读放大成 N 次 DB 查询 + N 次 Redis 往返（N+1，架构门禁明确禁止）。
 * 本类因此把三件事都做成批量：**一次 pipeline 发完全部 HGETALL**、
 * **一次 {@code PointMappingIndex.loadCoordinates}**、**一次解析**。判定语义与
 * {@code LatestValueQueryService} 保持一致（同样的紧凑 JSON 字段名、同样的坐标形态归一），
 * 两处都直接复用 {@link RedisLatestValueWriter#key(Long, Long)} 以杜绝 key 格式漂移。</p>
 *
 * <p><b>过渡期坐标形态兼容</b>：写入侧自 2026-09-26 起一律写属性标识，但存量哈希里可能还有
 * 「属性主键字符串」形态的 field。本类按 {@code canonicalByForm} 归一，<b>同一标识的两种形态取 ts 更新者</b>
 * （并列时取规范标识形态，与 {@code LatestValueQueryService} 的裁决规则一致——Redis 哈希遍历顺序不保证，
 * 按「先到者胜」会让同一份数据两次查询给出不同的值）。</p>
 *
 * <p><b>失败语义（必须显式）</b>：Redis 未装配或不可用 ⇒ 抛 {@link AlertEvaluationException}，
 * 由评估器计入 {@code iot.alert.evaluate.redis_failed} 并**跳过本轮判定**（不产生 resolve、不产生触发）。
 * 这里**绝不**把「读不到」降级成空集合——设计 §2.2.4：读不到不得被当成「不越界」。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertLatestValueReader {

    private static final Logger log = LoggerFactory.getLogger(AlertLatestValueReader.class);

    /** 紧凑 JSON 里的值字段名（与 {@code RedisLatestValueWriter} 的写入格式逐字一致）。 */
    private static final String FIELD_VALUE = "v";

    /** 紧凑 JSON 里的质量码字段名。 */
    private static final String FIELD_QUALITY = "q";

    /** 紧凑 JSON 里的读数时刻字段名（epoch 毫秒）。 */
    private static final String FIELD_TS = "ts";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final PointMappingIndex pointMappingIndex;
    private final ObjectMapper objectMapper;

    public AlertLatestValueReader(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  PointMappingIndex pointMappingIndex, ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.pointMappingIndex = pointMappingIndex;
        this.objectMapper = objectMapper;
    }

    /**
     * 一个点位的最新值样本（不可变）。
     *
     * @param propertyId 归一后的点位标识（**规范形态**）
     * @param value      读数原值（字符串，原样）
     * @param quality    质量码
     * @param ts         读数时刻（epoch 毫秒；可空，表示存量脏数据）
     */
    public record AlertSample(String propertyId, String value, String quality, Long ts) {
    }

    /**
     * 批量读取一批设备的最新值。
     *
     * @param tenantId  租户 ID（调用方已进入该租户上下文）
     * @param deviceIds 设备 ID 列表（非空；调用方先判空短路）
     * @return 设备 ID → （规范点位标识 → 样本）；**没有数据的设备不会出现在 map 里**（不代表失败）
     * @throws AlertEvaluationException Redis 未装配或读取失败
     */
    public Map<Long, Map<String, AlertSample>> readLatest(long tenantId, List<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw new AlertEvaluationException("Redis 未装配：评估器无法读取最新值，本轮不做任何判定");
        }
        List<String> keys = new ArrayList<>(deviceIds.size());
        for (Long deviceId : deviceIds) {
            keys.add(RedisLatestValueWriter.key(tenantId, deviceId));
        }
        List<Object> raw;
        try {
            // 一次 pipeline 发完全部 HGETALL：与「每设备一次往返」相比，往返次数从 N 降到 1
            raw = redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(RedisOperations operations) {
                    for (String key : keys) {
                        operations.opsForHash().entries(key);
                    }
                    // pipeline 模式下返回值由 Spring 收集，回调返回 null 是约定
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            throw new AlertEvaluationException("批量读取最新值失败（pipeline）", ex);
        }
        if (raw == null || raw.size() != keys.size()) {
            // 返回条数与请求条数不一致 ⇒ 无法把结果与设备对齐，宁可整体失败也不能错位判定
            throw new AlertEvaluationException("最新值 pipeline 返回条数与请求不一致：请求 " + keys.size()
                + " 条、返回 " + (raw == null ? 0 : raw.size()) + " 条");
        }
        Map<String, String> canonicalByForm = loadCanonicalForms(deviceIds);
        Map<Long, Map<String, AlertSample>> result = new LinkedHashMap<>();
        for (int index = 0; index < deviceIds.size(); index++) {
            Object entry = raw.get(index);
            if (!(entry instanceof Map<?, ?> hash) || hash.isEmpty()) {
                // key 不存在 = 这台设备还没上报过：正常状态，不是错误
                continue;
            }
            Map<String, AlertSample> samples = parseHash(deviceIds.get(index), hash, canonicalByForm);
            if (!samples.isEmpty()) {
                result.put(deviceIds.get(index), samples);
            }
        }
        return result;
    }

    /** 一次批量加载坐标形态（{@code PointMappingIndex} 本身就是按批设计的）。 */
    private Map<String, String> loadCanonicalForms(List<Long> deviceIds) {
        Map<String, String> canonicalByForm = new LinkedHashMap<>();
        try {
            Map<Long, PointMappingIndex.DeviceCoordinates> coordinates =
                pointMappingIndex.loadCoordinates(deviceIds);
            for (PointMappingIndex.DeviceCoordinates item : coordinates.values()) {
                canonicalByForm.putAll(item.canonicalByForm());
            }
        } catch (RuntimeException ex) {
            // 坐标形态是可选的兼容层：映射表读不到时按「field 即规范标识」处理，**不因此放弃整轮判定**
            // （放弃判定会让所有告警停在原地，代价远大于「历史形态读不出来」）
            log.warn("[iot] 告警评估加载点位坐标形态失败，按「field 即规范标识」继续（不影响本轮判定）", ex);
        }
        return canonicalByForm;
    }

    /** 解析一台设备的哈希（同标识多形态时取 ts 更新者；并列取规范形态）。 */
    private Map<String, AlertSample> parseHash(long deviceId, Map<?, ?> hash,
                                               Map<String, String> canonicalByForm) {
        Map<String, AlertSample> winners = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : hash.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String form = String.valueOf(entry.getKey());
            AlertSample sample = parseSample(deviceId, form,
                entry.getValue() == null ? null : String.valueOf(entry.getValue()));
            if (sample == null) {
                continue;
            }
            String canonical = canonicalByForm.getOrDefault(form, form);
            AlertSample known = winners.get(canonical);
            if (known == null || isBetter(sample, form, canonical, known)) {
                winners.put(canonical, new AlertSample(canonical, sample.value(), sample.quality(),
                    sample.ts()));
            }
        }
        return winners;
    }

    /** 同标识两形态的裁决：ts 更新者胜；并列取规范标识形态（与 {@code LatestValueQueryService} 同口径）。 */
    private static boolean isBetter(AlertSample candidate, String candidateForm, String canonical,
                                    AlertSample known) {
        Long candidateTs = candidate.ts() == null ? Long.MIN_VALUE : candidate.ts();
        Long knownTs = known.ts() == null ? Long.MIN_VALUE : known.ts();
        if (!candidateTs.equals(knownTs)) {
            return candidateTs > knownTs;
        }
        return candidateForm.equals(canonical);
    }

    /** 解析单个点位的紧凑 JSON；损坏条目**跳过并报错**（不让一台设备的写入格式问题拖垮整轮）。 */
    private AlertSample parseSample(long deviceId, String propertyId, String json) {
        if (json == null || json.isBlank()) {
            log.error("[iot] 告警评估：最新值条目为空，已跳过：deviceId={} propertyId={}", deviceId,
                LogSanitizer.sanitize(propertyId));
            return null;
        }
        Map<String, Object> fields;
        try {
            fields = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException ex) {
            log.error("[iot] 告警评估：最新值 JSON 解析失败，已跳过该点位：deviceId={} propertyId={}",
                deviceId, LogSanitizer.sanitize(propertyId), ex);
            return null;
        }
        Object rawValue = fields.get(FIELD_VALUE);
        Object rawQuality = fields.get(FIELD_QUALITY);
        Object rawTs = fields.get(FIELD_TS);
        Long ts = rawTs instanceof Number number ? number.longValue() : null;
        return new AlertSample(propertyId, rawValue == null ? null : String.valueOf(rawValue),
            rawQuality == null ? null : String.valueOf(rawQuality), ts);
    }
}
