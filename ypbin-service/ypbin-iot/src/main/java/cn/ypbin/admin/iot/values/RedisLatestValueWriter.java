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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 最新值写入器（设计 §5.3）：`iot:latest:{tenantId}:{deviceId}` 哈希，field=点位，value=紧凑 JSON。
 *
 * <p><b>为什么用 Hash 而不是每个点位一个 key</b>：一台设备的全部点位一次 `HGETALL` 就能取回（页面/接口
 * 常按设备展示），也避免 key 爆炸；value 里带 `ts`，消费方可判新旧。</p>
 *
 * <p><b>乱序处理（含跨批，2026-09-26 起）</b>：同一批内按 `ts` 取新（重复点位只留读数时刻最新的一条），
 * 跨批次由服务端 Lua 脚本做**逐 field 的比较后写入**（CAS）——即「只有本次 `ts` 严格大于库里该 field 的
 * `ts` 才写」。因此 EMQX 的 at-least-once 重投（旧批次晚到）**不会**再把已存的最新值回退。
 * 三条语义边界（必须如实理解）：</p>
 * <ol>
 *   <li><b>比较的是该 field 自己的 `ts`</b>（同一设备的每个点位各比各的），不是「整条记录一个时间戳」；</li>
 *   <li><b>`ts` 相等时保留先到者</b>（不写、不计数）：同一 ts 的重放是幂等 no-op，语义稳定可重放；</li>
 *   <li><b>存量值解析不出「串尾 `"ts":<数字>}`」时按「旧值更旧」处理并覆盖</b>。本写入器产出的值恒以
 *       `"ts":<数字>}` 结尾（见 {@link #json(LatestValue)}），所以该分支的实际触发面是**外部写入**：
 *       ① 脏数据；② **合法 JSON 但 `ts` 不在串尾**（例如 `{"ts":5000,"v":"x"}`，独立复核 2026-09-26
 *       在真 Redis 上实测确认会被判为「解析不出」）；③ **负数 ts**（正则 `%d+` 不匹配负号 ⇒ 该 field 的
 *       CAS 对该值失效，即后续写入不再受保护）。选覆盖而不是跳过，是为了不把点位永久冻结在一个无法比较的
 *       值上；②③ 两种情况本仓正常链路都不会产生（写入器是本 key 的唯一生产者、`ts` 是 epoch 毫秒），
 *       故按低风险登记；若要彻底消掉 ③，把正则改为 `(-?%d+)` 即可（本轮**未**改，避免扩大改动面）。</li>
 * </ol>
 *
 * <p><b>并发安全</b>：比较与写入在同一段 Lua 里完成，Redis 单线程执行脚本 ⇒ 天然原子。
 * 多实例、多线程同时写同一设备的**不同或相同**点位都不会回退（候选方案里的 `HGET`+比较+`HSET`
 * 是三次往返、非原子，在并发下仍会回退，故未采用；`WATCH` 事务在冲突时要重试，写放大更差）。
 * <b>往返次数没有放大</b>：每个设备 key 一次**脚本调用**（与改动前的 `putAll` 次数相同），
 * 参数里一次性带上该设备本批的全部 `field/ts/value` 三元组，**不是**每个点位一次往返。
 * 严格说：脚本缓存命中时是 `EVALSHA` 一次往返；缓存未命中/失效时 Redis 会回落到 `EVAL`
 * （独立复核 2026-09-26 用 `MONITOR` 线级实测：576 次 `EVALSHA` + 1 次 `EVAL`）。</p>
 *
 * <p><b>失败语义（与改动前一致）</b>：写失败按设备计数 + `log.error`（带堆栈）暴露，**不抛出去**——
 * 最新值属便利数据，不能因为 Redis 抖动让可用率/断档的入库事务回滚。</p>
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

    /**
     * 「旧 ts 后到」被抑制的写入数（设计 §6.4 新增指标）。
     *
     * <p>只统计**严格更旧**的到达；`ts` 相等的幂等重放不计入（它不是乱序）。该指标持续增长说明
     * 上游存在跨批乱序（EMQX QoS1 重投 / `inflight_window` 乱序 / 设备重传），是入站链路的健康信号。</p>
     */
    public static final String METRIC_REGRESSED = "iot.ingest.latest.regressed";

    /**
     * 逐 field 的「比较后写入」（CAS）脚本。
     *
     * <p>入参：`KEYS[1]` = 设备 hash key；`ARGV` = `field1, ts1, json1, field2, ts2, json2, …`（三元组连续排列）。
     * 返回：被抑制的**严格更旧**写入数量（`ts` 相等的幂等重放不计）。</p>
     *
     * <p>用 `string.match` 取尾部 `"ts":<数字>}` 而不是 `cjson.decode`：① 不需要 Lua 侧的 cjson 依赖；
     * ② 本写入器产出的 JSON 恒以 ts 结尾，且值里的引号已被转义（`\"`）⇒ 值内部不可能拼出未转义的
     * `"ts":` 形态，锚定在串尾的匹配只会命中真正的 `ts` 字段。</p>
     */
    private static final String CAS_LUA = """
        local key = KEYS[1]
        local suppressed = 0
        for i = 1, #ARGV, 3 do
          local field = ARGV[i]
          local ts = tonumber(ARGV[i + 1])
          local payload = ARGV[i + 2]
          local write = true
          local stored = redis.call('HGET', key, field)
          if stored then
            local storedTs = string.match(stored, '"ts":(%d+)%s*}%s*$')
            if storedTs then
              local oldTs = tonumber(storedTs)
              if oldTs > ts then
                suppressed = suppressed + 1
                write = false
              elseif oldTs == ts then
                write = false
              end
            end
          end
          if write then
            redis.call('HSET', key, field, payload)
          end
        end
        return suppressed
        """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> casScript;
    private final Counter failedCounter;
    private final Counter regressedCounter;

    public RedisLatestValueWriter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.casScript = new DefaultRedisScript<>(CAS_LUA, Long.class);
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("最新值写入 Redis 失败的批次数").register(meterRegistry);
        this.regressedCounter = Counter.builder(METRIC_REGRESSED)
            .description("最新值写入被抑制且计数为「旧 ts 后到」的点位数").register(meterRegistry);
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
        // 批内先去重（同一设备同一点位只留 ts 最新的一条，ts 相等保留先到者），
        // 跨批的新旧判定交给下面的 Lua
        Map<String, Map<String, LatestValue>> winnersByKey = new LinkedHashMap<>();
        for (LatestValue item : values) {
            String key = key(item.tenantId(), item.deviceId());
            winnersByKey.computeIfAbsent(key, k -> new LinkedHashMap<>())
                .merge(item.propertyId(), item, (left, right) -> left.ts() >= right.ts() ? left : right);
        }
        for (Map.Entry<String, Map<String, LatestValue>> entry : winnersByKey.entrySet()) {
            writeKeyCas(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 原子写一台设备的全部点位（一次 `EVAL`）。
     *
     * @param key      设备 hash key
     * @param winners  该设备本批的胜出点位（field → 最新值）
     */
    private void writeKeyCas(String key, Map<String, LatestValue> winners) {
        List<String> args = new ArrayList<>(winners.size() * 3);
        for (LatestValue winner : winners.values()) {
            args.add(winner.propertyId());
            args.add(String.valueOf(winner.ts()));
            args.add(json(winner));
        }
        try {
            Long suppressed = redisTemplate.execute(casScript, List.of(key), args.toArray());
            if (suppressed != null && suppressed > 0) {
                // 被抑制的写入是**正常业务结果**（乱序重投），不记日志刷屏，只计数供观测
                regressedCounter.increment(suppressed);
            }
        } catch (RuntimeException ex) {
            failedCounter.increment();
            log.error("[iot] 最新值写入失败（已计数，不影响上报落库）：key={} 点位数={}",
                LogSanitizer.sanitize(key), winners.size(), ex);
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
