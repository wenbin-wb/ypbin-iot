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
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 最新值查询服务（补 G1：最新值原本只有写路径 {@link RedisLatestValueWriter}，无任何读取端点）。
 *
 * <p><b>读的是什么</b>：Redis 哈希 {@code iot:latest:{tenantId}:{deviceId}}，field = 点位坐标，
 * value = 写入器落下的紧凑 JSON {@code {"v":...,"q":...,"ts":...}}。key 的构造**直接复用**
 * {@link RedisLatestValueWriter#key(Long, Long)}，避免读写两侧各写一份格式而漂移。</p>
 *
 * <p><b>过渡期的坐标形态兼容（为什么读侧要认得两种）</b>：写入侧自 2026-09-26 起一律写
 * <b>属性标识</b>（规范坐标），但统一之前 access 链路上报的是**属性主键字符串** ⇒ 存量哈希里可能
 * 存在第二种 field（同一个点位两条记录）。本服务因此把每个 field 先经
 * {@link PointMappingIndex} 归一：主键字符串形态映射回标识，**同一标识的两种形态按 {@code ts} 取新、
 * `ts` 并列时取规范标识形态**（与遍历顺序无关，见 {@code isBetterCandidate} 的说明——按「先到者胜」
 * 会让同一个 key 的两次查询返回不同的值），对外仍只暴露标识（字段名不变）。归一发生时记 INFO 日志并累加
 * {@value #METRIC_LEGACY_FIELD}——能看出「这条数据来自哪种形态」，又不必改对外契约。
 * 过渡期结束后（历史行 backfill 完）这段兼容可以整体删除。</p>
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

    /** 读到**历史坐标形态**（属性主键字符串 field）并已归一到属性标识的点位数（过渡期观测用）。 */
    public static final String METRIC_LEGACY_FIELD = "iot.latest.coordinate.legacy";

    /** 紧凑 JSON 里的值字段名（与 {@link RedisLatestValueWriter} 的写入格式逐字一致）。 */
    private static final String FIELD_VALUE = "v";

    /** 紧凑 JSON 里的质量码字段名。 */
    private static final String FIELD_QUALITY = "q";

    /** 紧凑 JSON 里的读数时刻字段名（epoch 毫秒）。 */
    private static final String FIELD_TS = "ts";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final IotDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final PointMappingIndex pointMappingIndex;
    private final Counter readFailedCounter;
    private final Counter legacyFieldCounter;

    public LatestValueQueryService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                   IotDeviceMapper deviceMapper, ObjectMapper objectMapper,
                                   PointMappingIndex pointMappingIndex, MeterRegistry meterRegistry) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.deviceMapper = deviceMapper;
        this.objectMapper = objectMapper;
        this.pointMappingIndex = pointMappingIndex;
        this.readFailedCounter = Counter.builder(METRIC_READ_FAILED)
            .description("最新值读取 Redis 失败的次数").register(meterRegistry);
        this.legacyFieldCounter = Counter.builder(METRIC_LEGACY_FIELD)
            .description("读到的历史坐标形态（属性主键字符串 field）点位数，已归一到属性标识")
            .register(meterRegistry);
    }

    /**
     * 批量查询多台设备的最新值（**一次 Redis pipeline**，不按设备循环 HGETALL）。
     *
     * <p><b>为什么必须批量</b>：孪生场景一次可能展示 500 个点位（对应最多 500 台设备）。
     * 逐台调用 {@link #listLatest(Long)} 会发出 500 次 Redis 往返 + 500 次设备查库 +
     * 500 次坐标索引查询——那是一次页面打开就要几十秒、且把 Redis 连接池打满的形态。
     * 本方法把这三类操作各压成一次。</p>
     *
     * <p><b>与单设备版的结果完全一致</b>：每台设备内部沿用同一套「历史坐标形态归一 + ts 取新 +
     * ts 并列时规范标识胜」的裁决（抽取为 {@code mergeDeviceEntries}，供两个方法共用），
     * 因此同一份 Redis 数据无论走哪条路径，返回值逐字段相同。</p>
     *
     * <p><b>失败语义（与单设备版一致）</b>：Redis 未装配或读取异常时，每台设备都返回空列表——
     * 「没有最新值」不是故障；但异常必须 {@code log.error} 带堆栈并累加
     * {@value #METRIC_READ_FAILED}，让「Redis 抖动」在指标上可见。</p>
     *
     * @param deviceIds 设备主键集合（为空时返回空 Map，不发任何查询）
     * @return 设备主键 → 最新值列表（每台设备的列表按属性标识升序；Redis 不可用时值为空列表）。
     *         返回的 Map 对入参中**每一台存在的设备**都有条目（可能对应空列表）。
     */
    public Map<Long, List<LatestValueResp>> listLatestBatch(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            // 空集合短路：既省三次查询，也避免空 IN 生成非法 SQL
            return Map.of();
        }
        List<Long> idList = deviceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (idList.isEmpty()) {
            return Map.of();
        }
        // ① 一次查设备（同时完成租户过滤：跨租户设备查不到 ⇒ 不出现在结果里）
        Map<Long, IotDevice> deviceById = new LinkedHashMap<>();
        for (IotDevice device : deviceMapper.selectByIds(idList)) {
            deviceById.put(device.getId(), device);
        }
        if (deviceById.isEmpty()) {
            return Map.of();
        }
        // ② 一次取坐标索引（与单设备版同一数据源，保证归一裁决口径相同）
        Map<Long, PointMappingIndex.DeviceCoordinates> coordinatesByDevice =
            pointMappingIndex.loadCoordinates(deviceById.keySet());
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.warn("[iot] 未发现 StringRedisTemplate：批量最新值查询返回空结果（设备数={}）",
                deviceById.size());
            return emptyResult(deviceById.keySet());
        }
        // ③ 一次 pipeline（不是 N 次 HGETALL）。入队顺序必须与 orderedIds 完全一致：
        //    pipeline 的结果按命令入队顺序返回，顺序错位会让 A 设备的值贴到 B 设备上。
        List<Long> orderedIds = new ArrayList<>(deviceById.keySet());
        List<Object> pipelined;
        try {
            pipelined = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long deviceId : orderedIds) {
                    IotDevice device = deviceById.get(deviceId);
                    byte[] key = RedisLatestValueWriter.key(device.getTenantId(), deviceId)
                        .getBytes(StandardCharsets.UTF_8);
                    connection.hashCommands().hGetAll(key);
                }
                // pipeline 内的回调返回值被框架忽略（结果由 executePipelined 收集）
                return null;
            });
        } catch (RuntimeException ex) {
            readFailedCounter.increment();
            log.error("[iot] 批量最新值读取失败（按空列表返回，不伪造数据）：设备数={}",
                deviceById.size(), ex);
            return emptyResult(deviceById.keySet());
        }
        Map<Long, List<LatestValueResp>> result = new LinkedHashMap<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            Long deviceId = orderedIds.get(index);
            Object raw = index < pipelined.size() ? pipelined.get(index) : null;
            Map<Object, Object> entries = toEntriesMap(raw, deviceId);
            result.put(deviceId,
                mergeDeviceEntries(deviceId, entries, coordinatesByDevice.get(deviceId)));
        }
        return result;
    }

    /**
     * 把 pipeline 的单个结果转成 entries（Redis 返回 {@code Map<Object,Object>}；
     * 空哈希返回空 Map）。
     *
     * @param raw      pipeline 单条结果
     * @param deviceId 设备主键（仅用于告警定位）
     * @return 哈希条目
     */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> toEntriesMap(Object raw, Long deviceId) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        if (raw != null) {
            log.warn("[iot] 批量最新值的 pipeline 结果类型非预期，按空处理：deviceId={} type={}",
                deviceId, raw.getClass().getName());
        }
        return Map.of();
    }

    /**
     * 合并单台设备的哈希条目为结果列表（**单设备版与批量版共用的唯一裁决实现**）。
     *
     * <p>把这段抽出来而不是在两个方法里各写一份：裁决规则（ts 取新、ts 并列时规范标识胜、
     * 都不是规范标识时按形态字典序）一旦有两份实现就会漂移，而漂移的表现是「同一份数据
     * 单查与批量查给出不同的值」——排障时几乎不可能想到是这个原因。</p>
     *
     * @param deviceId    设备主键
     * @param entries     Redis 哈希条目（可能为空）
     * @param coordinates 该设备的坐标索引（可空，空时按原样使用 field）
     * @return 最新值列表（按属性标识升序；空 entries ⇒ 空列表）
     */
    private List<LatestValueResp> mergeDeviceEntries(Long deviceId, Map<Object, Object> entries,
                                                     PointMappingIndex.DeviceCoordinates coordinates) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, String> canonicalByForm = coordinates == null
            ? Map.of() : coordinates.canonicalByForm();
        Map<String, LatestValueResp> winners = new LinkedHashMap<>();
        Map<String, String> winnerForm = new LinkedHashMap<>();
        int legacyFields = 0;
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String form = String.valueOf(entry.getKey());
            LatestValueResp value = parse(form, entry.getValue() == null
                ? null : String.valueOf(entry.getValue()), deviceId);
            if (value == null) {
                continue;
            }
            String canonical = canonicalByForm.getOrDefault(form, form);
            if (!canonical.equals(form)) {
                legacyFields++;
            }
            value.setPropertyId(canonical);
            LatestValueResp known = winners.get(canonical);
            if (known == null
                || isBetterCandidate(value, form, canonical, known, winnerForm.get(canonical))) {
                winners.put(canonical, value);
                winnerForm.put(canonical, form);
            }
        }
        if (legacyFields > 0) {
            legacyFieldCounter.increment(legacyFields);
            log.info("[iot] 最新值读到**历史坐标形态**（属性主键字符串）并已归一到属性标识："
                    + "deviceId={} 条数={}（过渡期兼容；backfill 完成后该日志与 "
                    + "iot.latest.coordinate.legacy 应归零）",
                LogSanitizer.sanitize(deviceId), legacyFields);
        }
        List<LatestValueResp> result = new ArrayList<>(winners.size());
        winners.keySet().stream().sorted().forEach(canonical -> result.add(winners.get(canonical)));
        return result;
    }

    /**
     * 构造「每台设备空列表」的结果（Redis 不可用/读取失败时使用，保证 Map 覆盖全部入参设备）。
     *
     * @param deviceIds 设备主键
     * @return 设备主键 → 空列表
     */
    private Map<Long, List<LatestValueResp>> emptyResult(Collection<Long> deviceIds) {
        Map<Long, List<LatestValueResp>> result = new LinkedHashMap<>();
        for (Long deviceId : deviceIds) {
            result.put(deviceId, List.of());
        }
        return result;
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
        // 归并与裁决统一走 mergeDeviceEntries（与批量版共用同一实现，避免两份规则漂移）：
        // 同一标识的两种形态（历史主键字符串 / 属性标识）只保留读数时刻最新的一条，
        // 且 ts 并列时的裁决与遍历顺序无关（Redis HGETALL 不保证顺序）。
        PointMappingIndex.DeviceCoordinates coordinates = pointMappingIndex
            .loadCoordinates(List.of(deviceId))
            .getOrDefault(deviceId, PointMappingIndex.DeviceCoordinates.empty());
        return mergeDeviceEntries(deviceId, entries, coordinates);
    }

    /**
     * 候选是否优于当前胜出者（**读侧过渡期的裁决规则，必须与遍历顺序无关**）。
     *
     * <ol>
     *   <li>读数时刻**严格更大**者胜（跨批/批内乱序的主判据，与写入侧 CAS 的「旧 ts 不回退」一致）；</li>
     *   <li>`ts` **相等**时**规范标识形态胜**（而不是「先遍历到者胜」）：Redis 哈希遍历顺序不保证，
     *       按顺序裁决会让同一份数据在两次查询间给出不同的值/质量码。选标识形态是因为它才是规范坐标
     *       （历史形态只是兼容通道），与 {@code PointMappingIndex} 的「撞名判给标识」同一取向；</li>
     *   <li>若两种形态**都不是**规范标识（只可能出现在坐标级撞名：库里只有两个点位各自的历史主键形态），
     *       则退到**形态字符串的字典序**——仍然是确定的，绝不留下「先遍历到者胜」。</li>
     * </ol>
     *
     * @param candidate    当前遍历到的候选
     * @param candidateForm 候选来自的哈希 field 形态
     * @param canonical    规范标识
     * @param known        当前胜出者
     * @param knownForm    当前胜出者来自的形态
     * @return 候选应当取代胜出者时返回 {@code true}
     */
    private static boolean isBetterCandidate(LatestValueResp candidate, String candidateForm, String canonical,
                                             LatestValueResp known, String knownForm) {
        if (candidate.getTs() > known.getTs()) {
            return true;
        }
        if (candidate.getTs() < known.getTs()) {
            return false;
        }
        boolean candidateIsCanonical = candidateForm.equals(canonical);
        boolean knownIsCanonical = knownForm != null && knownForm.equals(canonical);
        if (candidateIsCanonical != knownIsCanonical) {
            return candidateIsCanonical;
        }
        // 两种形态都不是规范标识（仅可能出现在「坐标级撞名」——同设备两个点位共用同一个标识，
        // 库里只有它们各自的历史主键形态）：此时仍**不能**按遍历顺序裁决，用形态字符串的字典序定胜负，
        // 保证「同一份数据两次查询返回值相同」。相等（同一 field，理论上不会出现两次）时保留先到者。
        return knownForm != null && candidateForm.compareTo(knownForm) < 0;
    }

    /**
     * 解析写入器落的紧凑 JSON（单个点位）。损坏的条目**跳过并报错**，不让整台设备的最新值全丢。
     *
     * @param propertyId 哈希 field 的**原始形态**（可能是属性标识，也可能是历史的主键字符串形态；
     *                 归一由调用方在解析后完成，见 {@link #listLatest(Long)}）
     * @param json     紧凑 JSON（哈希 value）
     * @param deviceId 设备 ID（仅用于告警定位）
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
        if (!(ts instanceof Number number)) {
            // 写入器**总是**写 ts（见 RedisLatestValueWriter#json）⇒ 缺失与非数字同属不可信数据：
            // 没有读数时刻的「最新值」无法判新旧（写入器已保证「ts 更旧的批次不覆盖已存值」，
            // 本条防御针对的是**外部程序写进同一个 key 的脏数据**：存量值连 ts 都解析不出来），
            // 宁可丢掉并报错，也不返回一个无法判断时效的值。
            log.error("[iot] 最新值缺少合法的读数时刻（ts），已跳过该点位：deviceId={} propertyId={}",
                deviceId, LogSanitizer.sanitize(propertyId));
            return null;
        }
        resp.setTs(number.longValue());
        return resp;
    }
}
