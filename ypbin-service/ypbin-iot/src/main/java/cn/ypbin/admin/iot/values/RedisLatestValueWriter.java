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
import java.time.Duration;
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
 *       在真 Redis 上实测确认会被判为「解析不出」）。选覆盖而不是跳过，是为了不把点位永久冻结在一个
 *       无法比较的值上。（**负数 ts** 曾属这一类：正则 `%d+` 不匹配负号 ⇒ 该 field 的 CAS 静默失效；
 *       2026-09-26 已把正则改为 `(-?%d+)` 修掉，并有真 Redis 用例 + 变异验证。）</li>
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
 * <p><b>超前护栏的作用域（如实登记，勿夸大）</b>：{@link #MAX_FUTURE_SKEW_MS} 只作用于**最新值**这一条写入路径
 * ——本类的 CAS。活性的 {@code last_good_at}、断档判定与 IoTDB 时序写入仍按上报原始 {@code ts} 处理
 * （{@code AvailabilityServiceImpl.aggregate} 不钳制 ts）⇒ 一个超前 ts 仍会让设备显示「刚刚有数据」并写进
 * 时序库，只是不会把最新值顶到未来。两条链路的口径差异在此登记；是否给 ingest 也加统一护栏属独立决策。</p>
 *
 * <p><b>「ts 落后」没有护栏（如实登记其运营含义）</b>：本写入器只认「更大的 ts」，所以设备时钟**回拨或停滞**
 * （例如设备时钟停在 2020 年）时，该点位会**长期停留在旧值**上、不会自愈到当前时刻附近——只能靠
 * {@value #METRIC_REGRESSED} 观测到「旧 ts 后到」在持续发生。要处置应另加「ts 落后于已存值过多」的策略
 * （例如超阈值告警/要求设备校时），本轮不做。</p>
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
     * 「`ts` 超前服务端时钟过多」被拒绝的写入数（防设备误报远未来 `ts` 把点位**永久冻结**）。
     *
     * <p>为什么需要它：CAS 只认「更大的 `ts`」⇒ 一个远未来的 `ts` 会让该点位后续所有合法读数（`ts` 更小）
     * 全被抑制，直到出现更大的 `ts` 为止。真实触发面是**设备时钟错**，不是攻击面。</p>
     */
    public static final String METRIC_FUTURE_REJECTED = "iot.ingest.latest.future_rejected";

    /**
     * 允许的最大超前偏移（默认 5 分钟）：`ts > 服务端当前时刻 + 本值` 即**不写入**并计数。
     *
     * <p>'now' 取**服务端**时钟（{@code System.currentTimeMillis()}），不用设备端时间——判据必须是
     * 「相对平台时钟的偏差」。抽成具名常量而非散落的裸数字；要改成可配置时，把它接到配置绑定类即可
     * （本轮不做，见类注释的取舍说明）。</p>
     */
    public static final long MAX_FUTURE_SKEW_MS = Duration.ofMinutes(5).toMillis();

    /** 脚本返回值里的第 0 项：被抑制（旧 ts 后到）的点位数。 */
    private static final int COUNT_REGRESSED = 0;

    /** 脚本返回值里的第 1 项：因 ts 超前被拒绝的点位数。 */
    private static final int COUNT_FUTURE = 1;

    /**
     * 逐 field 的「比较后写入」（CAS）脚本。
     *
     * <p>入参：`KEYS[1]` = 设备 hash key；`ARGV` = `field1, ts1, json1, field2, ts2, json2, … , ceiling`
     * —— 三元组连续排列，**最后一位是本批统一的超前上限**（= 服务端 now + {@link #MAX_FUTURE_SKEW_MS}，
     * 由 Java 侧按服务端时钟算出后传入；放末位是为了让三元组保持连续、解析只需 `1, #ARGV-1, 3`）。</p>
     *
     * <p>返回：`{被抑制的严格更旧写入数, 因 ts 超前被拒绝的点位数}` —— 两个计数必须**可分辨**，
     * 故返回 Lua 表（Spring 映射为 {@code List}）。</p>
     *
     * <p>用 `string.match` 取尾部 `"ts":(-?数字)}` 而不是 `cjson.decode`：① 不需要 Lua 侧的 cjson 依赖；
     * ② 本写入器产出的 JSON 恒以 ts 结尾，且值里的引号已被转义（`\"`）⇒ 值内部不可能拼出未转义的
     * `"ts":` 形态，锚定在串尾的匹配只会命中真正的 `ts` 字段；③ `-?` 是必须的：不加负号时
     * `{"ts":-5}` 会匹配失败 ⇒ 被当成「解析不出 ts」⇒ 该 field 的 CAS 静默失效（独立复核实测）。</p>
     */
    private static final String CAS_LUA = """
        local key = KEYS[1]
        local ceiling = tonumber(ARGV[#ARGV])
        local suppressed = 0
        local future = 0
        for i = 1, #ARGV - 1, 3 do
          local field = ARGV[i]
          local ts = tonumber(ARGV[i + 1])
          local payload = ARGV[i + 2]
          if ceiling and ts > ceiling then
            future = future + 1
          else
            local write = true
            local stored = redis.call('HGET', key, field)
            if stored then
              local storedTs = string.match(stored, '"ts":(-?%d+)%s*}%s*$')
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
        end
        return {suppressed, future}
        """;

    private final StringRedisTemplate redisTemplate;

    /**
     * CAS 脚本（返回 Lua 表 ⇒ Spring 映射为 {@code List<Long>}）。
     *
     * <p>用裸 `List` 是因为 {@code DefaultRedisScript} 要的是 {@code Class<T>}，而 {@code List<Long>.class}
     * 在 Java 里不可表达；结果元素用 {@link #countAt} 按 {@link Number} 读，不做未检查的强转。</p>
     */
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> casScript;

    private final long maxFutureSkewMs;
    private final Counter failedCounter;
    private final Counter regressedCounter;
    private final Counter futureRejectedCounter;

    /**
     * 用默认超前上限（{@link #MAX_FUTURE_SKEW_MS}）构造。
     *
     * @param redisTemplate Redis 模板
     * @param meterRegistry 指标
     */
    public RedisLatestValueWriter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this(redisTemplate, meterRegistry, MAX_FUTURE_SKEW_MS);
    }

    /**
     * 指定超前上限（测试与将来接配置用）。
     *
     * @param redisTemplate   Redis 模板
     * @param meterRegistry   指标
     * @param maxFutureSkewMs 允许的最大超前偏移（毫秒；必须 ≥ 0）
     */
    public RedisLatestValueWriter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry,
                                  long maxFutureSkewMs) {
        if (maxFutureSkewMs < 0) {
            // 配置错误必须当场暴露，不静默兜底（负值会让所有读数都被判「超前」而全部拒写）
            throw new IllegalArgumentException("允许的超前偏移不能为负：" + maxFutureSkewMs);
        }
        this.redisTemplate = redisTemplate;
        this.maxFutureSkewMs = maxFutureSkewMs;
        this.casScript = new DefaultRedisScript<>(CAS_LUA, List.class);
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("最新值写入 Redis 失败的批次数").register(meterRegistry);
        this.regressedCounter = Counter.builder(METRIC_REGRESSED)
            .description("最新值写入被抑制且计数为「旧 ts 后到」的点位数").register(meterRegistry);
        this.futureRejectedCounter = Counter.builder(METRIC_FUTURE_REJECTED)
            .description("最新值因读数时刻超前服务端时钟过多而被拒绝的点位数").register(meterRegistry);
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
     * 原子写一台设备的全部点位（一次脚本调用）。
     *
     * @param key      设备 hash key
     * @param winners  该设备本批的胜出点位（field → 最新值）
     */
    private void writeKeyCas(String key, Map<String, LatestValue> winners) {
        List<String> args = new ArrayList<>(winners.size() * 3 + 1);
        for (LatestValue winner : winners.values()) {
            args.add(winner.propertyId());
            args.add(String.valueOf(winner.ts()));
            args.add(json(winner));
        }
        // 末位：本批统一的超前上限（服务端时钟 + 允许偏移），放在三元组之后以免破坏解析步长
        long ceiling = System.currentTimeMillis() + maxFutureSkewMs;
        args.add(String.valueOf(ceiling));
        try {
            List<?> result = redisTemplate.execute(casScript, List.of(key), args.toArray());
            long suppressed = countAt(result, COUNT_REGRESSED);
            long future = countAt(result, COUNT_FUTURE);
            if (suppressed > 0) {
                // 被抑制的写入是**正常业务结果**（乱序重投），不记日志刷屏，只计数供观测
                regressedCounter.increment(suppressed);
            }
            if (future > 0) {
                // 设备时钟错属异常数据：计数 + WARN（同样不上抛、不影响上报落库）
                futureRejectedCounter.increment(future);
                log.warn("[iot] 最新值拒绝写入：读数时刻超前服务端时钟超过 {} ms（已计数）："
                        + "key={} 点位数={} 服务端上限={}",
                    maxFutureSkewMs, LogSanitizer.sanitize(key), future, ceiling);
            }
        } catch (RuntimeException ex) {
            failedCounter.increment();
            log.error("[iot] 最新值写入失败（已计数，不影响上报落库）：key={} 点位数={}",
                LogSanitizer.sanitize(key), winners.size(), ex);
        }
    }

    /** 读脚本返回的计数（缺项/非数字一律按 0，不抛——脚本返回形态异常不该拖垮上报）。 */
    private static long countAt(List<?> result, int index) {
        if (result == null || result.size() <= index) {
            return 0L;
        }
        Object value = result.get(index);
        return value instanceof Number number ? number.longValue() : 0L;
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
