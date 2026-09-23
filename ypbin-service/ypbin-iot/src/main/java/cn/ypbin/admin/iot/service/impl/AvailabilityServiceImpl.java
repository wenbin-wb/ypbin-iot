/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.availability.AvailabilityCalculator;
import cn.ypbin.admin.iot.availability.AvailabilityProperties;
import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.availability.MaintenanceWindowDto;
import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.availability.OutageDetector;
import cn.ypbin.admin.iot.availability.OutageEventResp;
import cn.ypbin.admin.iot.availability.OutageReason;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 断档与可用率服务实现（M-2，口径见 {@link AvailabilityRules}）。
 *
 * <p><b>静默设备是这条链路存在的理由</b>：设备彻底不再上报时没有任何请求进来，所以断档不能只在
 * 「收到读数」时判定——必须由周期扫描按「最近有效数据 + K × 采集周期」打开断档。
 * 上报只负责两件事：刷新活性、以及有效数据到达时闭合断档。</p>
 *
 * <p><b>租户上下文</b>：内部上报端点只有 {@code X-Internal-Token}、没有租户身份，而
 * {@code iot_device} / {@code device_liveness} 都是租户表（插件 fail-closed）⇒ 先用
 * {@link TenantContext#executeIgnore} 按设备解析出租户，再进入该租户执行写入。扫描同理：
 * 跨租户读候选，逐条进入各自租户写事件——**绝不**手写 tenant_id 过滤（见租户隔离门禁）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Service
public class AvailabilityServiceImpl implements AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityServiceImpl.class);

    private final DeviceLivenessMapper livenessMapper;
    private final OutageEventMapper outageMapper;

    private final MaintenanceWindowMapper maintenanceWindowMapper;
    private final IotDeviceMapper deviceMapper;
    private final AvailabilityProperties properties;

    public AvailabilityServiceImpl(DeviceLivenessMapper livenessMapper, OutageEventMapper outageMapper,
                                   MaintenanceWindowMapper maintenanceWindowMapper,
                                   IotDeviceMapper deviceMapper, AvailabilityProperties properties) {
        this.livenessMapper = livenessMapper;
        this.outageMapper = outageMapper;
        this.maintenanceWindowMapper = maintenanceWindowMapper;
        this.deviceMapper = deviceMapper;
        this.properties = properties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ingest(ReadingIngestReq req) {
        List<ReadingObservationDto> items = req.getItems();
        if (items.isEmpty()) {
            return 0;
        }
        Map<Long, DeviceReadingBatch> batches = aggregate(items);
        Map<Long, Long> tenantByDevice = resolveTenants(batches.keySet());
        int processed = 0;
        for (Map.Entry<Long, DeviceReadingBatch> entry : batches.entrySet()) {
            Long tenantId = tenantByDevice.get(entry.getKey());
            if (tenantId == null) {
                // 设备不存在（或不在任何租户）：丢弃并暴露，不静默当成有效上报
                log.warn("[iot] 读数上报的设备不存在，已丢弃：deviceId={} 条数={}",
                    LogSanitizer.sanitize(entry.getKey()), entry.getValue().count());
                continue;
            }
            Long deviceId = entry.getKey();
            DeviceReadingBatch batch = entry.getValue();
            processed += TenantContext.executeWithTenant(tenantId, () -> applyBatch(tenantId, deviceId, batch));
        }
        return processed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanAndOpenOutages() {
        if (!properties.isEnabled()) {
            return 0;
        }
        // 数据库时钟：候选 SQL 与下面的纯逻辑复核必须用**同一个基准**，否则两处会得出不同结论
        LocalDateTime now = livenessMapper.selectNow();
        // 跨租户读候选（扫描没有租户身份），逐条进入各自租户写 —— 见类注释
        List<DeviceLiveness> candidates = TenantContext.executeIgnore(() -> livenessMapper.selectList(
            Wrappers.<DeviceLiveness>lambdaQuery()
                .isNull(DeviceLiveness::getOpenOutageId)
                .apply("COALESCE(last_good_at, first_observed_at) IS NOT NULL")
                // 生效周期口径必须与 OutageDetector.effectiveIntervalMs 完全一致：
                // 「上报了正周期就用它，否则用兜底」——不用 GREATEST（那会把 1s 周期的设备抬到 5s，与 spec 的 K×周期不符）
                .apply("TIMESTAMPADD(MICROSECOND, CAST((CASE WHEN COALESCE(poll_interval_ms, 0) > 0"
                    + " THEN poll_interval_ms ELSE {0} END) AS SIGNED) * {1} * 1000,"
                    + " COALESCE(last_good_at, first_observed_at)) < NOW()",
                    properties.getFallbackIntervalMs(), properties.getKFactor())
                .orderByAsc(DeviceLiveness::getId)
                .last("LIMIT " + properties.getScanBatchSize())));
        List<DeviceLiveness> collectible = filterCollectible(candidates);
        int opened = 0;
        for (DeviceLiveness candidate : collectible) {
            opened += openOutage(candidate, now);
        }
        if (opened > 0) {
            log.warn("[iot] 断档扫描：新开断档 {} 个（候选 {} 个，其中可采集 {} 个；K={} 兜底周期={}ms）",
                opened, candidates.size(), collectible.size(), properties.getKFactor(),
                properties.getFallbackIntervalMs());
        }
        return opened;
    }

    /**
     * 只保留「设备仍存在且启用」的候选。
     *
     * <p><b>为什么必须筛</b>：设备被删除或停用后，活性行不会自己消失——若不过滤，它们会**永远**被判成断档，
     * 报表上出现一堆假断档（可用率看起来像坏了一样）。这里一次批量查设备（不是循环查，避免 N+1），
     * 只保留 {@code status=启用} 且未被逻辑删除的设备。</p>
     *
     * @param candidates 扫描候选
     * @return 可采集的候选
     */
    private List<DeviceLiveness> filterCollectible(List<DeviceLiveness> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> deviceIds = candidates.stream()
            .map(DeviceLiveness::getDeviceId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        Set<Long> enabled = TenantContext.executeIgnore(() -> deviceMapper.selectList(
            Wrappers.<IotDevice>lambdaQuery()
                .select(IotDevice::getId)
                .in(IotDevice::getId, deviceIds)
                .eq(IotDevice::getStatus, EntityStatus.ENABLED.getCode())))
            .stream()
            .map(IotDevice::getId)
            .collect(Collectors.toSet());
        List<DeviceLiveness> collectible = new ArrayList<>(candidates.size());
        // 设备已删除/停用的活性行不会自己消失，**必须清掉**而不是只跳过——候选查询按 id 升序 +
        // LIMIT 批次上限，这类行永远满足条件、永远占住前段，累积 ≥ 批次上限后**其它设备的断档再也
        // 不会被发现**（是饥饿，不是延迟）。清理走**一次批量删**（循环里逐条删是 N+1 写，被架构门禁拦）。
        List<Long> orphanIds = new ArrayList<>();
        for (DeviceLiveness candidate : candidates) {
            if (candidate.getDeviceId() != null && enabled.contains(candidate.getDeviceId())) {
                collectible.add(candidate);
            } else {
                orphanIds.add(candidate.getId());
            }
        }
        if (!orphanIds.isEmpty()) {
            // 平台级维护删除：**必须显式 ignore 租户**（扫描本来就在无租户上下文下跨租户读候选），
            // 否则租户插件会以「缺少租户上下文」直接拒绝（fail-closed），把整轮扫描打断——
            // 这是 CI 真库用例实测出来的：只跳过不清理会饥饿，清理写法不对会整轮失败。
            // 安全性来自显式 id 列表（取自本轮刚读到的候选行），且只删「设备已不存在/停用」的那些。
            TenantContext.executeIgnore(() -> livenessMapper.deleteBatchIds(orphanIds));
            log.info("[iot] 断档扫描清理了 {} 条设备已删除/停用的活性行（它们不再参与断档判定）",
                orphanIds.size());
        }
        return collectible;
    }

    @Override
    public AvailabilityResp query(Long deviceId, LocalDateTime from, LocalDateTime to) {
        if (deviceId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            // 不存在（含跨租户访问）按「查不到」处理，不泄露存在性
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        LocalDateTime now = livenessMapper.selectNow();
        LocalDateTime windowTo = to != null ? to : now;
        LocalDateTime windowFrom = from != null
            ? from : windowTo.minusHours(properties.getDefaultWindowHours());
        if (windowFrom.isAfter(windowTo)) {
            // 参数给反了就报错，不静默交换：否则「用错参数」会得到一份看起来正常的报表
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "统计窗口起点必须早于终点：" + windowFrom + " > " + windowTo);
        }
        DeviceLiveness liveness = livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, deviceId));
        long intervalMs = OutageDetector.effectiveIntervalMs(
            liveness == null ? null : liveness.getPollIntervalMs(), properties.getFallbackIntervalMs());
        // 汇总走**精确聚合**（与明细条数无关）：明细有返回上限，够不到时求和会低估断档 ⇒ 可用率偏高
        Long tenantId = TenantContext.getTenantId().orElse(null);
        if (tenantId == null) {
            // 查询路径必然有租户身份（网关注入）；缺失时不猜、不查全表，直接暴露
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "缺少租户上下文，无法统计可用率");
        }
        Map<String, Object> aggregate = outageMapper.summarizeInWindow(tenantId, deviceId, windowFrom, windowTo,
            now);
        long outageSeconds = toLong(aggregate == null ? null : aggregate.get("outageSeconds"));
        long longestOutageSeconds = toLong(aggregate == null ? null : aggregate.get("longestOutageSeconds"));
        long outageInMaintenanceSeconds = toLong(
            aggregate == null ? null : aggregate.get("outageInMaintenanceSeconds"));
        int outageCount = (int) toLong(aggregate == null ? null : aggregate.get("outageCount"));
        long windowSeconds = Math.max(0L, Duration.between(windowFrom, windowTo).getSeconds());
        // 维护窗口（spec §12.5）：统计总时长要排除计划停机；断档落在窗口内的部分也一并剔除（否则计划停机仍拉低可用率）
        Long maintenanceRaw = maintenanceWindowMapper.sumMaintenanceSecondsInWindow(tenantId, deviceId,
            windowFrom, windowTo, now);
        long maintenanceSeconds = Math.max(0L, maintenanceRaw == null ? 0L : maintenanceRaw);
        boolean truncated = outageCount > AvailabilityRules.MAX_OUTAGE_ROWS;
        if (truncated) {
            log.warn("[iot] 窗口内断档 {} 条超过明细上限 {}：明细按**最新优先**截断展示，"
                + "可用率/最长断档仍由精确聚合给出（不因截断偏高）：deviceId={} from={} to={}",
                outageCount, AvailabilityRules.MAX_OUTAGE_ROWS, LogSanitizer.sanitize(deviceId), windowFrom,
                windowTo);
        }
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(windowSeconds,
            maintenanceSeconds, outageSeconds, longestOutageSeconds, outageInMaintenanceSeconds, outageCount,
            intervalMs, truncated);
        if (summary.maintenanceSeconds() > 0L) {
            log.info("[iot] 可用率统计已排除维护窗口：deviceId={} 窗口={}秒 维护={}秒 统计总时长={}秒 "
                + "（其中断档落在维护内被剔除 {} 秒）",
                LogSanitizer.sanitize(deviceId), windowSeconds, summary.maintenanceSeconds(),
                summary.effectiveWindowSeconds(), summary.outageInMaintenanceSeconds());
        }
        // 明细：**最新优先**（截断时保留最近的断档，比丢最新更有用）
        List<OutageEvent> limited = outageMapper.selectList(Wrappers.<OutageEvent>lambdaQuery()
            .eq(OutageEvent::getDeviceId, deviceId)
            .lt(OutageEvent::getStartTs, windowTo)
            .and(wrapper -> wrapper.isNull(OutageEvent::getEndTs)
                .or().gt(OutageEvent::getEndTs, windowFrom))
            .orderByDesc(OutageEvent::getStartTs)
            .last("LIMIT " + AvailabilityRules.MAX_OUTAGE_ROWS));
        AvailabilityResp resp = new AvailabilityResp();
        resp.setDeviceId(deviceId);
        resp.setFrom(windowFrom);
        resp.setTo(windowTo);
        resp.setWindowSeconds(summary.windowSeconds());
        resp.setEffectiveWindowSeconds(summary.effectiveWindowSeconds());
        resp.setMaintenanceSeconds(summary.maintenanceSeconds());
        resp.setOutageInMaintenanceSeconds(summary.outageInMaintenanceSeconds());
        resp.setOutageSeconds(summary.outageSeconds());
        resp.setLongestOutageSeconds(summary.longestOutageSeconds());
        resp.setOutageCount(summary.outageCount());
        resp.setAvailability(summary.availability());
        resp.setMeetsTarget(summary.meetsTarget());
        resp.setTargetAvailability(AvailabilityRules.TARGET_AVAILABILITY);
        resp.setMaxAllowedOutageSeconds(summary.maxAllowedOutageSeconds());
        resp.setTruncated(summary.truncated());
        for (OutageEvent row : limited) {
            resp.getOutages().add(toResp(row, now));
        }
        // 回显维护窗口（最多 MAX_MAINTENANCE_ROWS 条）：让「这段时间为什么不算断档」在响应里自解释
        List<MaintenanceWindow> windows = maintenanceWindowMapper.listOverlappingInWindow(tenantId, deviceId,
            windowFrom, windowTo, AvailabilityRules.MAX_MAINTENANCE_ROWS);
        for (MaintenanceWindow window : windows) {
            MaintenanceWindowDto dto = new MaintenanceWindowDto();
            dto.setId(window.getId());
            dto.setDeviceId(window.getDeviceId());
            dto.setStartTs(window.getStartTs());
            dto.setEndTs(window.getEndTs());
            dto.setSource(window.getSource());
            dto.setReason(window.getReason());
            resp.getMaintenanceWindows().add(dto);
        }
        return resp;
    }

    /**
     * 落地一批（同一设备）观察：刷新活性；有效数据到达则闭合进行中的断档。
     *
     * @param tenantId 设备所属租户（已进入该租户上下文）
     * @param deviceId 设备 ID
     * @param batch    聚合后的批次
     * @return 处理的观察条数
     */
    private int applyBatch(Long tenantId, Long deviceId, DeviceReadingBatch batch) {
        DeviceLiveness row = livenessMapper.selectByDeviceIncludingDeleted(tenantId, deviceId);
        boolean created = row == null;
        if (created) {
            row = new DeviceLiveness();
            row.setId(IdWorker.getId());
            row.setDeviceId(deviceId);
            row.setPollIntervalMs(0);
        }
        // 闭合断档必须用**更新前**的 lastGoodAt/firstObservedAt 当起点（起点不能等于恢复时刻）
        if (batch.lastGoodAt() != null && row.getOpenOutageId() != null) {
            LocalDateTime start = OutageDetector.outageStart(row.getLastGoodAt(), row.getFirstObservedAt());
            if (start == null || !batch.lastGoodAt().isAfter(start)) {
                // 乱序/重放：这条有效数据**早于**断档起点，不可能是「恢复」⇒ 不闭合。
                // 否则会写出一条 start > end、duration=0 的假恢复，并把进行中的断档标记清掉。
                log.warn("[access→iot] 有效数据早于断档起点，忽略本次「恢复」（乱序/重放上报）：deviceId={} ts={} 起点={}",
                    LogSanitizer.sanitize(deviceId), batch.lastGoodAt(), start);
            } else {
                closeOutage(row.getOpenOutageId(), start, batch.lastGoodAt());
                int cleared = livenessMapper.clearOpenOutage(row.getId(), row.getOpenOutageId());
                if (cleared == 0) {
                    // 清空期间标记已被别的路径改掉（扫描开了新断档）：保留新标记，不覆盖
                    log.warn("[access→iot] 断档标记在闭合期间已被改动，保留当前标记：deviceId={} 期望清空={}",
                        LogSanitizer.sanitize(deviceId), row.getOpenOutageId());
                }
            }
        }
        if (batch.pollIntervalMs() != null && batch.pollIntervalMs() > 0) {
            row.setPollIntervalMs(batch.pollIntervalMs());
        }
        row.setFirstObservedAt(earliest(row.getFirstObservedAt(), batch.firstObservedAt()));
        row.setLastObservedAt(latest(row.getLastObservedAt(), batch.lastObservedAt()));
        row.setLastGoodAt(latest(row.getLastGoodAt(), batch.lastGoodAt()));
        if (created) {
            livenessMapper.insert(row);
        } else {
            livenessMapper.reviveAndUpdate(row);
        }
        return batch.count();
    }

    /**
     * 打开一条断档（多副本安全：只有把 {@code open_outage_id} 从 NULL 改成新值的那一方算开成功）。
     *
     * @param candidate 候选活性行（来自跨租户扫描）
     * @return 实际打开返回 1，被其它副本抢先返回 0
     */
    private int openOutage(DeviceLiveness candidate, LocalDateTime now) {
        Long tenantId = candidate.getTenantId();
        LocalDateTime start = OutageDetector.outageStart(candidate.getLastGoodAt(),
            candidate.getFirstObservedAt());
        long intervalMs = OutageDetector.effectiveIntervalMs(candidate.getPollIntervalMs(),
            properties.getFallbackIntervalMs());
        if (!OutageDetector.isOutage(start, now, intervalMs, properties.getKFactor())) {
            // 用与 SQL 同一套纯逻辑复核一遍：两处口径（SQL 与 Java）必须一致，不一致时这里会先暴露
            log.warn("[iot] 断档候选未通过纯逻辑复核（SQL 与 Java 口径可能漂移）：deviceId={} 起点={} 周期={}",
                LogSanitizer.sanitize(candidate.getDeviceId()), start, intervalMs);
            return 0;
        }
        return TenantContext.executeWithTenant(tenantId, () -> {
            OutageEvent event = new OutageEvent();
            event.setId(IdWorker.getId());
            event.setDeviceId(candidate.getDeviceId());
            event.setStartTs(start);
            event.setReason(OutageReason.NO_GOOD_DATA.getCode());
            outageMapper.insert(event);
            int marked = livenessMapper.markOpenOutage(candidate.getId(), event.getId());
            if (marked == 0) {
                // 另一个副本已开断档：撤销本次插入，避免同一段断档被记两次（可用率会被双计）
                outageMapper.deleteById(event.getId());
                log.debug("[iot] 断档已被其它副本打开，撤销本次插入：deviceId={}",
                    LogSanitizer.sanitize(candidate.getDeviceId()));
                return 0;
            }
            log.warn("[iot] 发现断档：deviceId={} start={}（连续超过 {}×采集周期无有效数据）",
                LogSanitizer.sanitize(candidate.getDeviceId()), start, properties.getKFactor());
            return 1;
        });
    }

    /**
     * 闭合断档并计算时长。
     *
     * @param outageId 断档事件 ID
     * @param start    断档起点（可空：起点缺失时只闭合、不算时长）
     * @param end      恢复时刻
     */
    private void closeOutage(Long outageId, LocalDateTime start, LocalDateTime end) {
        Long durationSeconds = start == null ? null : Math.max(0L, Duration.between(start, end).getSeconds());
        outageMapper.closeOutage(outageId, end, durationSeconds);
        log.info("[iot] 断档恢复：outageId={} 时长={}秒", LogSanitizer.sanitize(outageId), durationSeconds);
    }

    /** 按设备聚合一批观察（同一批里同一设备可能多条）。 */
    private static Map<Long, DeviceReadingBatch> aggregate(List<ReadingObservationDto> items) {
        Map<Long, DeviceReadingBatch> byDevice = new LinkedHashMap<>();
        for (ReadingObservationDto item : items) {
            if (item == null || item.getDeviceId() == null || item.getTs() == null) {
                continue;
            }
            LocalDateTime observedAt = AvailabilityRules.toLocalDateTime(item.getTs());
            if (observedAt == null) {
                continue;
            }
            boolean good = AvailabilityRules.QUALITY_GOOD.equals(item.getQuality());
            byDevice.merge(item.getDeviceId(), new DeviceReadingBatch(item.getDeviceId(),
                    item.getPollIntervalMs(), observedAt, observedAt, good ? observedAt : null, 1),
                (left, right) -> new DeviceReadingBatch(left.deviceId(),
                    right.pollIntervalMs() != null ? right.pollIntervalMs() : left.pollIntervalMs(),
                    earliest(left.firstObservedAt(), right.firstObservedAt()),
                    latest(left.lastObservedAt(), right.lastObservedAt()),
                    latest(left.lastGoodAt(), right.lastGoodAt()),
                    left.count() + right.count()));
        }
        return byDevice;
    }

    /** 解析设备 → 租户（内部端点没有租户身份，必须显式忽略租户条件再回到各租户）。 */
    private Map<Long, Long> resolveTenants(Set<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        List<IotDevice> devices = TenantContext.executeIgnore(
            () -> deviceMapper.selectBatchIds(deviceIds));
        Map<Long, Long> tenantByDevice = new LinkedHashMap<>(devices.size());
        for (IotDevice device : devices) {
            if (device.getTenantId() != null) {
                tenantByDevice.put(device.getId(), device.getTenantId());
            }
        }
        return tenantByDevice;
    }

    /** 实体 → 视图。 */
    private static OutageEventResp toResp(OutageEvent row, LocalDateTime now) {
        OutageEventResp resp = new OutageEventResp();
        resp.setId(row.getId());
        resp.setDeviceId(row.getDeviceId());
        resp.setStartTs(row.getStartTs());
        resp.setEndTs(row.getEndTs());
        resp.setReason(row.getReason());
        boolean ongoing = row.getEndTs() == null;
        resp.setOngoing(ongoing);
        if (row.getDurationSec() != null) {
            resp.setDurationSec(row.getDurationSec());
        } else if (ongoing && row.getStartTs() != null) {
            resp.setDurationSec(Math.max(0L, Duration.between(row.getStartTs(), now).getSeconds()));
        }
        return resp;
    }

    /**
     * 聚合结果取值（驱动差异：MySQL 的 COUNT 是 Long、SUM 可能是 BigDecimal/Integer）。
     *
     * @param value 聚合值（可空）
     * @return 长整型；空返回 0
     */
    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /** 取两个时刻里较早的非空值。 */
    private static LocalDateTime earliest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    /** 取两个时刻里较晚的非空值。 */
    private static LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    /**
     * 同一设备在一批观察里的聚合结果。
     *
     * @param deviceId         设备 ID
     * @param pollIntervalMs   采集周期（取批次里最后一个非空值）
     * @param firstObservedAt  最早观测时刻
     * @param lastObservedAt   最晚观测时刻
     * @param lastGoodAt       最晚有效数据时刻（无有效数据为 {@code null}）
     * @param count            观察条数
     */
    private record DeviceReadingBatch(Long deviceId, Integer pollIntervalMs, LocalDateTime firstObservedAt,
                                      LocalDateTime lastObservedAt, LocalDateTime lastGoodAt, int count) {
    }
}
