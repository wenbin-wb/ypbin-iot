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

import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 最新值写入器（设计 §5.3）：`iot:latest:{tenantId}:{deviceId}` 哈希，field=点位，value=紧凑 JSON。
 *
 * <p><b>为什么用 Hash 而不是每个点位一个 key</b>：一台设备的全部点位一次 `HGETALL` 就能取回（页面/接口
 * 常按设备展示），也避免 key 爆炸；value 里带 `ts`，消费方可判新旧。</p>
 *
 * <p><b>乱序处理（必须如实说明边界）</b>：批内按 `ts` 取新（同一设备同一批里的重复点位只留最新）；
 * <b>跨批次</b>的乱序（更早的批次晚到）目前**会覆盖**已存的较新值——这里没有做「读改写比较」，
 * 因为那需要 Lua/事务，本机与 CI 都没有真 Redis 可验证（宁可少承诺）。补偿手段：value 里带 `ts`，
 * 消费方/后续写入器可据此判新旧；该限制已登记在 ROADMAP 四点十六。</p>
 *
 * <p><b>失败语义</b>：写失败按设备计数 + `log.error`（带堆栈）暴露，**不抛出去**——最新值属便利数据，
 * 不能因为 Redis 抖动让可用率/断档的入库事务回滚。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class RedisLatestValueWriter implements LatestValueWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisLatestValueWriter.class);

    /** 最新值 key 前缀（不含租户/设备段）。 */
    public static final String KEY_PREFIX = "iot:latest:";

    /** 写入失败计数（按设备/批次计）。 */
    public static final String METRIC_FAILED = "iot.ingest.latest.failed";

    private final StringRedisTemplate redisTemplate;
    private final Counter failedCounter;

    public RedisLatestValueWriter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("最新值写入 Redis 失败的批次数").register(meterRegistry);
    }

    /** 最新值 key：`iot:latest:{tenantId}:{deviceId}`。 */
    public static String key(Long tenantId, Long deviceId) {
        return KEY_PREFIX + tenantId + ":" + deviceId;
    }

    @Override
    public void writeAll(List<LatestValue> values) {
        if (values.isEmpty()) {
            // 空集合短路：既省一次往返，也避免下游把「空批」当异常
            return;
        }
        Map<String, Map<String, String>> fieldsByKey = new LinkedHashMap<>();
        Map<String, Long> tsByField = new LinkedHashMap<>();
        for (LatestValue item : values) {
            String key = key(item.tenantId(), item.deviceId());
            String field = item.propertyId();
            String dedupeKey = key + "|" + field;
            Long known = tsByField.get(dedupeKey);
            if (known != null && known >= item.ts()) {
                // 同一批里同一设备的同一点位多次上报：只保留读数时刻最新的那条
                continue;
            }
            tsByField.put(dedupeKey, item.ts());
            fieldsByKey.computeIfAbsent(key, k -> new LinkedHashMap<>())
                .put(field, json(item));
        }
        for (Map.Entry<String, Map<String, String>> entry : fieldsByKey.entrySet()) {
            try {
                redisTemplate.opsForHash().putAll(entry.getKey(), entry.getValue());
            } catch (RuntimeException ex) {
                failedCounter.increment();
                log.error("[iot] 最新值写入失败（已计数，不影响上报落库）：key={} 点位数={}",
                    LogSanitizer.sanitize(entry.getKey()), entry.getValue().size(), ex);
            }
        }
    }

    /** 紧凑 JSON：`{"v":"...","q":"GOOD","ts":1690000000000}`（值字符串化，类型由物模型定义）。 */
    private static String json(LatestValue item) {
        return "{\"v\":" + escape(item.value()) + ",\"q\":" + escape(item.quality())
            + ",\"ts\":" + item.ts() + "}";
    }

    /** 最小 JSON 转义（只处理必须转义的字符；不引第三方序列化器，保持写入器无额外依赖）。 */
    private static String escape(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
