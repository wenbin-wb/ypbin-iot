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

import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotEventLog;
import cn.ypbin.admin.iot.event.EventIngestItemDto;
import cn.ypbin.admin.iot.event.EventIngestReq;
import cn.ypbin.admin.iot.event.EventIngestResult;
import cn.ypbin.admin.iot.event.EventLevel;
import cn.ypbin.admin.iot.event.EventLogQuery;
import cn.ypbin.admin.iot.event.EventLogResp;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotEventLogMapper;
import cn.ypbin.admin.iot.service.IotEventService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行期事件服务实现（G6）。
 *
 * <p><b>幂等</b>：按 {@code (租户, 设备, 幂等键)} 去重。批内先按键去重（同一批里重复投递同一条事件
 * 只落一行），再按设备批量查已存在的键（一次查询，非循环查），最后批量插入。并发重投落在唯一键上，
 * 由 {@code ON DUPLICATE KEY UPDATE id = id} 兜住（不抛异常、不改已有行）。</p>
 *
 * <p><b>租户上下文</b>：内部上报端点只有 {@code X-Internal-Token}、没有租户身份，而 {@code iot_event_log}
 * 与 {@code iot_device} 都是租户表（插件 fail-closed）⇒ 先用 {@link TenantContext#executeIgnore}
 * 按设备解析出租户，再逐设备进入该租户执行写入。**绝不**手写 tenant_id 过滤条件（见租户隔离门禁）。</p>
 *
 * <p><b>为什么上报走「先校验全批、再落库」</b>：级别非法属于上报方契约错误；若边写边发现问题，
 * 前面的行已落库、后面的被拒，调用方无法安全重试。这里在动库之前把整批校验完，非法就整批拒绝
 * （HTTP 200 + {@code R.code} 业务错误，本仓统一异常口径），调用方修好后重投即可——重投是幂等的。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Service
public class IotEventServiceImpl implements IotEventService {

    private static final Logger log = LoggerFactory.getLogger(IotEventServiceImpl.class);

    private final IotEventLogMapper eventLogMapper;

    private final IotDeviceMapper deviceMapper;

    public IotEventServiceImpl(IotEventLogMapper eventLogMapper, IotDeviceMapper deviceMapper) {
        this.eventLogMapper = eventLogMapper;
        this.deviceMapper = deviceMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EventIngestResult ingest(EventIngestReq req) {
        List<EventIngestItemDto> items = req.getItems();
        if (items.isEmpty()) {
            // 批量先判空短路：不在空集合上打库，也不开事务里的无谓往返
            return new EventIngestResult(0, 0, 0);
        }
        validateLevels(items);
        Map<Long, List<EventIngestItemDto>> byDevice = groupByDevice(items);
        Map<Long, Long> tenantByDevice = resolveTenants(byDevice.keySet());
        int accepted = 0;
        int duplicated = 0;
        int discarded = 0;
        for (Map.Entry<Long, List<EventIngestItemDto>> entry : byDevice.entrySet()) {
            Long tenantId = tenantByDevice.get(entry.getKey());
            if (tenantId == null) {
                // 设备不存在（或不在任何租户）：丢弃并暴露，不静默当成有效上报
                log.warn("[access→iot] 事件上报的设备不存在，已丢弃：deviceId={} 条数={}",
                    LogSanitizer.sanitize(entry.getKey()), entry.getValue().size());
                discarded += entry.getValue().size();
                continue;
            }
            Long deviceId = entry.getKey();
            List<EventIngestItemDto> deviceItems = entry.getValue();
            EventIngestResult one = TenantContext.executeWithTenant(tenantId,
                () -> applyDeviceBatch(tenantId, deviceId, deviceItems));
            accepted += one.getAccepted();
            duplicated += one.getDuplicated();
        }
        if (duplicated > 0 || discarded > 0) {
            log.info("[access→iot] 事件上报（含幂等去重/丢弃）：收到={} 落库={} 幂等命中={} 丢弃={}",
                items.size(), accepted, duplicated, discarded);
        }
        return new EventIngestResult(accepted, duplicated, discarded);
    }

    @Override
    public PageResult<EventLogResp> pageEvents(Long deviceId, EventLogQuery query) {
        if (deviceId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        // 报错而不是静默交换：把区间给反会得到一份看起来正常的空时间线，属最难排查的一类问题
        if (query.getFrom() != null && query.getTo() != null && query.getFrom().isAfter(query.getTo())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "时间范围起点必须早于终点：" + query.getFrom() + " > " + query.getTo());
        }
        String level = null;
        if (query.getLevel() != null && !query.getLevel().isBlank()) {
            EventLevel parsed = EventLevel.ofCode(query.getLevel());
            if (parsed == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "事件级别非法（只支持 info/warn/error）：" + LogSanitizer.sanitize(query.getLevel()));
            }
            level = parsed.getCode();
        }
        // 设备存在性校验：跨租户访问时插件查不到行 ⇒ 按「设备不存在」处理，不泄露存在性
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        LambdaQueryWrapper<IotEventLog> wrapper = Wrappers.<IotEventLog>lambdaQuery()
            .eq(IotEventLog::getDeviceId, deviceId)
            .ge(query.getFrom() != null, IotEventLog::getEventTs, query.getFrom())
            .lt(query.getTo() != null, IotEventLog::getEventTs, query.getTo())
            .eq(level != null, IotEventLog::getLevel, level)
            // 时间倒序 + id 倒序：同一时刻的多条事件顺序稳定（分页时不出现「同一行出现两页」）
            .orderByDesc(IotEventLog::getEventTs)
            .orderByDesc(IotEventLog::getId);
        // 排序固定为「最新优先」：不接 PageQuery.sortField——它来自请求，交回给用户会引入 ORDER BY 注入面
        IPage<IotEventLog> source = eventLogMapper.selectPage(
            new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<EventLogResp> items = new ArrayList<>(source.getRecords().size());
        for (IotEventLog row : source.getRecords()) {
            items.add(toResp(row));
        }
        return PageResult.of(items, source.getTotal(), source.getCurrent(), source.getSize());
    }

    /**
     * 落地一台设备的一批事件：批内去重 → 批量查已存在键 → 批量插入。
     *
     * <p>三步都在**同一租户上下文**内（调用方已进入），租户条件由插件追加。</p>
     *
     * @param tenantId  设备所属租户（已进入该租户上下文）
     * @param deviceId  设备 ID
     * @param items     该设备的事件（非空）
     * @return 落库/去重条数
     */
    private EventIngestResult applyDeviceBatch(Long tenantId, Long deviceId,
                                               List<EventIngestItemDto> items) {
        // 批内去重：同一批里重复投递同一个幂等键只落一行（先到者胜，后者算幂等命中）
        Map<String, EventIngestItemDto> unique = new LinkedHashMap<>();
        int duplicated = 0;
        for (EventIngestItemDto item : items) {
            String key = item.normalizedIdempotentKey();
            if (unique.putIfAbsent(key, item) != null) {
                duplicated++;
            }
        }
        List<String> keys = new ArrayList<>(unique.keySet());
        if (keys.isEmpty()) {
            // 幂等键为空属于契约错误（DTO 上 @NotBlank 已拦），这里只做短路不静默吞
            log.warn("[access→iot] 事件上报的幂等键整批为空，已丢弃：deviceId={} 条数={}",
                LogSanitizer.sanitize(deviceId), items.size());
            return new EventIngestResult(0, 0, items.size());
        }
        Set<String> existing = new LinkedHashSet<>(
            eventLogMapper.selectExistingKeys(deviceId, keys));
        List<IotEventLog> rows = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (existing.contains(key)) {
                duplicated++;
                continue;
            }
            rows.add(toEntity(tenantId, deviceId, unique.get(key)));
        }
        if (rows.isEmpty()) {
            return new EventIngestResult(0, duplicated, 0);
        }
        int affected = eventLogMapper.insertBatch(rows);
        if (affected < rows.size()) {
            // 只可能是「并发重投撞上唯一键」：受影响行数小于提交行数（受影响行数口径受 JDBC
            // useAffectedRows 影响，故这里只用于告警，不用于幂等计数——见 IotEventLogMapper 的说明）
            log.warn("[access→iot] 事件批量插入的实际影响行数小于提交行数（并发重投？）："
                + "deviceId={} 提交={} 影响={}", LogSanitizer.sanitize(deviceId), rows.size(), affected);
        }
        return new EventIngestResult(rows.size(), duplicated, 0);
    }

    /**
     * 按设备分组（纯计算，无外部调用）。
     *
     * @param items 上报项
     * @return 设备 ID → 该设备的上报项（保持上报顺序）
     */
    private static Map<Long, List<EventIngestItemDto>> groupByDevice(List<EventIngestItemDto> items) {
        Map<Long, List<EventIngestItemDto>> byDevice = new LinkedHashMap<>();
        for (EventIngestItemDto item : items) {
            if (item == null || item.getDeviceId() == null) {
                continue;
            }
            byDevice.computeIfAbsent(item.getDeviceId(), key -> new ArrayList<>()).add(item);
        }
        return byDevice;
    }

    /**
     * 校验全批的事件级别（动库之前）。
     *
     * @param items 上报项
     */
    private static void validateLevels(List<EventIngestItemDto> items) {
        for (EventIngestItemDto item : items) {
            if (item == null || item.getDeviceId() == null) {
                continue;
            }
            if (EventLevel.ofCode(item.normalizedLevel()) == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "事件级别非法（只支持 info/warn/error）：deviceId=" + item.getDeviceId()
                        + " level=" + LogSanitizer.sanitize(item.getLevel()));
            }
        }
    }

    /**
     * 解析设备 → 租户（内部端点没有租户身份，必须显式忽略租户条件再回到各租户）。
     *
     * @param deviceIds 设备 ID 集合（非空）
     * @return 设备 → 租户（设备不存在则无对应项）
     */
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

    /**
     * 上报项 → 实体。
     *
     * <p>时刻转换复用 {@link AvailabilityRules#toLocalDateTime}：上报来的是 epoch 毫秒，落库要变成
     * 平台墙上时间；两处各写一份时区口径就会漂移（该转换是平台固定 GMT+8）。</p>
     *
     * @param tenantId 设备所属租户（显式写入，供批量插入一行一句使用）
     * @param deviceId 设备 ID
     * @param item     上报项
     * @return 待插入实体（id 用 {@code IdWorker} 预生成——批量语句绕过 MP 的字段填充）
     */
    private static IotEventLog toEntity(Long tenantId, Long deviceId, EventIngestItemDto item) {
        IotEventLog row = new IotEventLog();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setDeviceId(deviceId);
        row.setEventCode(item.getEventCode());
        row.setEventName(item.getEventName());
        row.setLevel(EventLevel.ofCode(item.normalizedLevel()).getCode());
        row.setParams(item.getParams());
        row.setEventTs(AvailabilityRules.toLocalDateTime(item.getTs()));
        row.setIdempotentKey(item.normalizedIdempotentKey());
        return row;
    }

    /**
     * 实体 → 视图。
     *
     * @param row 事件实例
     * @return 视图
     */
    private static EventLogResp toResp(IotEventLog row) {
        EventLogResp resp = new EventLogResp();
        resp.setId(row.getId());
        resp.setDeviceId(row.getDeviceId());
        resp.setEventCode(row.getEventCode());
        resp.setEventName(row.getEventName());
        resp.setLevel(row.getLevel());
        resp.setParams(row.getParams());
        resp.setEventTs(row.getEventTs());
        resp.setCreateTime(row.getCreateTime());
        return resp;
    }
}
