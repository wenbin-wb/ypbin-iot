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

import cn.ypbin.admin.iot.alert.AlertGate;
import cn.ypbin.admin.iot.alert.AlertMetrics;
import cn.ypbin.admin.iot.alert.AlertNotifyPlanner;
import cn.ypbin.admin.iot.alert.AlertProperties;
import cn.ypbin.admin.iot.alert.AlertRules;
import cn.ypbin.admin.iot.alert.AlertSilencePolicy;
import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertReason;
import cn.ypbin.admin.iot.enums.AlertState;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotAlertInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotAlertNotificationMapper;
import cn.ypbin.admin.iot.mapper.IotAlertRuleMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.req.AlertInstanceQuery;
import cn.ypbin.admin.iot.model.resp.AlertInstanceResp;
import cn.ypbin.admin.iot.model.resp.AlertNotificationResp;
import cn.ypbin.admin.iot.model.resp.AlertSummaryResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.security.core.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Duration;
import java.time.LocalDateTime;
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
 * 告警实例服务实现（设计 §2.1 表 C 的只读 + 确认 + 静默面）。
 *
 * <p><b>列表响应的装配成本是常数级查询</b>：设备名（1 次）、规则名（1 次）、投递记录（1 次）、
 * 产品筛选时的设备反查（至多 1 次）——不会随行数增长（本仓禁止 N+1）。</p>
 *
 * <p><b>没有「人工关闭」</b>（用户口径 5）：误报的出口是停用规则（停止判定）或静默（停止打扰）。
 * 设计 §2.3 曾建议提供 {@code MANUAL_CLOSE}，本实现按用户批准口径**不做**，已在文档登记为偏差。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Service
public class AlertInstanceServiceImpl implements AlertInstanceService {

    private static final Logger log = LoggerFactory.getLogger(AlertInstanceServiceImpl.class);

    /** 产品筛选时反查设备数量的上限（防「一个大产品下上万台设备」把 IN 撑爆）。 */
    private static final int MAX_PRODUCT_DEVICES = 2_000;

    private final IotAlertInstanceMapper instanceMapper;
    private final IotAlertNotificationMapper notificationMapper;
    private final IotAlertRuleMapper ruleMapper;
    private final IotDeviceMapper deviceMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final AlertSilencePolicy silencePolicy;
    private final AlertNotifyPlanner notifyPlanner;
    private final AlertMetrics metrics;
    private final AlertGate gate;
    private final AlertProperties properties;

    public AlertInstanceServiceImpl(IotAlertInstanceMapper instanceMapper,
                                    IotAlertNotificationMapper notificationMapper,
                                    IotAlertRuleMapper ruleMapper, IotDeviceMapper deviceMapper,
                                    DeviceLivenessMapper livenessMapper,
                                    AlertSilencePolicy silencePolicy, AlertNotifyPlanner notifyPlanner,
                                    AlertMetrics metrics, AlertGate gate, AlertProperties properties) {
        this.instanceMapper = instanceMapper;
        this.notificationMapper = notificationMapper;
        this.ruleMapper = ruleMapper;
        this.deviceMapper = deviceMapper;
        this.livenessMapper = livenessMapper;
        this.silencePolicy = silencePolicy;
        this.notifyPlanner = notifyPlanner;
        this.metrics = metrics;
        this.gate = gate;
        this.properties = properties;
    }

    @Override
    public PageResult<AlertInstanceResp> page(AlertInstanceQuery query) {
        gate.requireEnabled();
        int pageNo = positive(query.getPage(), 1);
        int pageSize = Math.min(positive(query.getPageSize(), 10), properties.getMaxPageSize());
        LambdaQueryWrapper<IotAlertInstance> wrapper = new LambdaQueryWrapper<>();
        Set<String> states = parseStates(query.getState());
        if (!states.isEmpty()) {
            wrapper.in(IotAlertInstance::getState, states);
        } else if (Boolean.TRUE.equals(query.getActiveOnly())) {
            wrapper.isNotNull(IotAlertInstance::getActiveDedupKey);
        }
        if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            wrapper.eq(IotAlertInstance::getSeverity, query.getSeverity().trim());
        }
        if (query.getDeviceId() != null) {
            wrapper.eq(IotAlertInstance::getDeviceId, query.getDeviceId());
        }
        if (query.getPropertyId() != null && !query.getPropertyId().isBlank()) {
            wrapper.eq(IotAlertInstance::getPropertyId, query.getPropertyId().trim());
        }
        if (query.getRuleId() != null) {
            wrapper.eq(IotAlertInstance::getRuleId, query.getRuleId());
        }
        if (query.getFrom() != null) {
            wrapper.ge(IotAlertInstance::getStartTs, query.getFrom());
        }
        if (query.getTo() != null) {
            wrapper.le(IotAlertInstance::getStartTs, query.getTo());
        }
        if (query.getProductId() != null) {
            List<Long> deviceIds = deviceIdsOfProduct(query.getProductId());
            if (deviceIds.isEmpty()) {
                // 该产品下没有设备 ⇒ 结果必然为空；直接返回空页（不生成 `IN ()`，也不查实例表）
                return PageResult.of(List.of(), 0L, pageNo, pageSize);
            }
            wrapper.in(IotAlertInstance::getDeviceId, deviceIds);
        }
        wrapper.orderByDesc(IotAlertInstance::getStartTs).orderByDesc(IotAlertInstance::getId);
        IPage<IotAlertInstance> source = instanceMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResult.of(toRespList(source.getRecords(), true), source.getTotal(),
            source.getCurrent(), source.getSize());
    }

    @Override
    public AlertInstanceResp detail(Long id) {
        gate.requireEnabled();
        if (id == null) {
            throw new BusinessException("告警 ID 不能为空");
        }
        IotAlertInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("告警不存在或不属于当前租户");
        }
        return toRespList(List.of(instance), true).get(0);
    }

    @Override
    public AlertSummaryResp summary(Long deviceId) {
        gate.requireEnabled();
        LocalDateTime now = livenessMapper.selectNow();
        Map<String, Object> row = instanceMapper.selectSummary(deviceId, now.minusHours(24));
        AlertSummaryResp resp = new AlertSummaryResp();
        resp.setActiveCount(asLong(row == null ? null : row.get("activeCount")));
        resp.setPendingCount(asLong(row == null ? null : row.get("pendingCount")));
        resp.setFiringCount(asLong(row == null ? null : row.get("firingCount")));
        resp.setAckedCount(asLong(row == null ? null : row.get("ackedCount")));
        resp.setCriticalCount(asLong(row == null ? null : row.get("criticalCount")));
        resp.setWarningCount(asLong(row == null ? null : row.get("warningCount")));
        resp.setInfoCount(asLong(row == null ? null : row.get("infoCount")));
        resp.setResolvedLast24h(asLong(row == null ? null : row.get("resolvedLast24h")));
        return resp;
    }

    @Override
    public Map<Long, Integer> activeCountsByDevice(List<Long> deviceIds) {
        gate.requireEnabled();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (deviceIds == null || deviceIds.isEmpty()) {
            return counts;
        }
        for (Map<String, Object> row : instanceMapper.countActiveByDeviceIds(deviceIds)) {
            Long deviceId = asLong(row.get("deviceId"));
            if (deviceId != null) {
                counts.put(deviceId, (int) asLong(row.get("activeCount")));
            }
        }
        return counts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ack(List<Long> ids) {
        gate.requireEnabled();
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        LocalDateTime now = livenessMapper.selectNow();
        return instanceMapper.batchAck(ids, UserContext.getUserId(), now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int silence(List<Long> ids, int minutes) {
        gate.requireEnabled();
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        if (minutes <= 0) {
            throw new BusinessException("静默时长必须大于 0 分钟");
        }
        LocalDateTime now = livenessMapper.selectNow();
        return instanceMapper.batchSilence(ids, now.plusMinutes(minutes));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resolveByRuleIds(List<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return 0;
        }
        List<IotAlertInstance> actives = instanceMapper.selectActiveByRuleIds(ruleIds);
        if (actives.isEmpty()) {
            return 0;
        }
        LocalDateTime now = livenessMapper.selectNow();
        Map<Long, IotAlertRule> rules = new LinkedHashMap<>();
        for (IotAlertRule rule : ruleMapper.selectByIds(ruleIds)) {
            rules.put(rule.getId(), rule);
        }
        List<MaintenanceWindow> windows = silencePolicy.loadActiveWindows(now);
        List<IotAlertNotification> rows = new ArrayList<>();
        for (IotAlertInstance instance : actives) {
            instance.setState(AlertState.RESOLVED.getCode());
            instance.setResolvedTs(now);
            instance.setReason(AlertReason.RULE_DISABLED.getCode());
            // 释放去重键：规则重新启用后同一键可以再开一条新实例
            instance.setActiveDedupKey(null);
            instance.setUpdateTime(now);
            IotAlertRule rule = rules.get(instance.getRuleId());
            if (silencePolicy.isSilenced(instance, rule, instance.getDeviceId(), now, windows)) {
                // 静默只影响通知，不影响「收口」这个事实
                metrics.notifySilenced();
                continue;
            }
            instance.setLastNotifiedTs(now);
            instance.setNotifyCount(instance.getNotifyCount() == null ? 1 : instance.getNotifyCount() + 1);
            rows.addAll(notifyPlanner.plan(instance, rule, resolveChannels(rule),
                rule == null ? null : rule.getNotifyTargets(), AlertNotifyEvent.RESOLVED, now));
        }
        instanceMapper.batchUpdate(actives);
        metrics.resolvedBatch(actives.size());
        if (!rows.isEmpty()) {
            int inserted = notificationMapper.insertBatchIgnore(rows);
            metrics.notifyQueued(inserted);
        }
        log.info("[iot] 规则停用收口活动告警：实例 {} 条，通知入队 {} 条", actives.size(), rows.size());
        return actives.size();
    }

    /** 按产品反查设备 ID（一次查询；上限 {@value #MAX_PRODUCT_DEVICES}，超出只取前 N 台并不静默失败）。 */
    private List<Long> deviceIdsOfProduct(Long productId) {
        List<IotDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
            .select(IotDevice::getId)
            .eq(IotDevice::getProductId, productId)
            .orderByAsc(IotDevice::getId)
            .last("LIMIT " + MAX_PRODUCT_DEVICES));
        List<Long> ids = new ArrayList<>(devices.size());
        for (IotDevice device : devices) {
            ids.add(device.getId());
        }
        if (ids.size() == MAX_PRODUCT_DEVICES) {
            log.warn("[iot] 按产品筛选告警：产品 {} 下设备数超过 {}，本次只覆盖前 {} 台（请改用设备筛选）",
                productId, MAX_PRODUCT_DEVICES, MAX_PRODUCT_DEVICES);
        }
        return ids;
    }

    /** 组装视图（设备名/规则名/投递记录各一次批量查询）。 */
    private List<AlertInstanceResp> toRespList(List<IotAlertInstance> instances, boolean withNotify) {
        if (instances.isEmpty()) {
            return List.of();
        }
        Set<Long> deviceIds = new LinkedHashSet<>();
        Set<Long> ruleIds = new LinkedHashSet<>();
        List<Long> instanceIds = new ArrayList<>(instances.size());
        for (IotAlertInstance instance : instances) {
            instanceIds.add(instance.getId());
            if (instance.getDeviceId() != null) {
                deviceIds.add(instance.getDeviceId());
            }
            if (instance.getRuleId() != null && instance.getRuleId() != AlertRules.RULE_ID_OUTAGE) {
                ruleIds.add(instance.getRuleId());
            }
        }
        Map<Long, IotDevice> devices = new LinkedHashMap<>();
        if (!deviceIds.isEmpty()) {
            for (IotDevice device : deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
                .select(IotDevice::getId, IotDevice::getDeviceName, IotDevice::getDeviceCode,
                    IotDevice::getProductId)
                .in(IotDevice::getId, new ArrayList<>(deviceIds)))) {
                devices.put(device.getId(), device);
            }
        }
        Map<Long, String> ruleNames = new LinkedHashMap<>();
        if (!ruleIds.isEmpty()) {
            // 传 List 而不是 Set：与 Mapper 的形参一致，也便于批量 IN 的 `foreach` 绑定
            for (IotAlertRule rule : ruleMapper.selectByIds(new ArrayList<>(ruleIds))) {
                ruleNames.put(rule.getId(), rule.getRuleName());
            }
        }
        Map<Long, List<IotAlertNotification>> notificationsByInstance = new LinkedHashMap<>();
        if (withNotify) {
            for (IotAlertNotification row : notificationMapper.selectByInstanceIds(instanceIds)) {
                notificationsByInstance.computeIfAbsent(row.getInstanceId(), key -> new ArrayList<>())
                    .add(row);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<AlertInstanceResp> result = new ArrayList<>(instances.size());
        for (IotAlertInstance instance : instances) {
            AlertInstanceResp resp = new AlertInstanceResp();
            resp.setId(instance.getId());
            resp.setRuleId(instance.getRuleId());
            boolean outage = instance.getRuleId() != null
                && instance.getRuleId() == AlertRules.RULE_ID_OUTAGE;
            resp.setOutage(outage);
            resp.setRuleName(outage ? null : ruleNames.get(instance.getRuleId()));
            resp.setDeviceId(instance.getDeviceId());
            resp.setPropertyId(instance.getPropertyId());
            resp.setSeverity(instance.getSeverity());
            resp.setState(instance.getState());
            resp.setTriggerValue(instance.getTriggerValue());
            resp.setThresholdSnapshot(instance.getThresholdSnapshot());
            resp.setStartTs(instance.getStartTs());
            resp.setFiringTs(instance.getFiringTs());
            resp.setResolvedTs(instance.getResolvedTs());
            resp.setAckedTs(instance.getAckedTs());
            resp.setAckedBy(instance.getAckedBy());
            resp.setLastNotifiedTs(instance.getLastNotifiedTs());
            resp.setNotifyCount(instance.getNotifyCount());
            resp.setReason(instance.getReason());
            resp.setSilenceUntil(instance.getSilenceUntil());
            resp.setCreateTime(instance.getCreateTime());
            resp.setDurationSeconds(durationSeconds(instance, now));
            IotDevice device = devices.get(instance.getDeviceId());
            if (device != null) {
                resp.setDeviceName(device.getDeviceName());
                resp.setDeviceCode(device.getDeviceCode());
                resp.setProductId(device.getProductId());
            }
            List<IotAlertNotification> rows = notificationsByInstance.get(instance.getId());
            if (rows != null) {
                List<AlertNotificationResp> notifyResps = new ArrayList<>(rows.size());
                for (IotAlertNotification row : rows) {
                    AlertNotificationResp item = new AlertNotificationResp();
                    item.setId(row.getId());
                    item.setChannel(row.getChannel());
                    item.setTarget(row.getTarget());
                    item.setEvent(row.getEvent());
                    item.setNotifyStatus(row.getNotifyStatus());
                    item.setAttempt(row.getAttempt());
                    item.setNextRetryTs(row.getNextRetryTs());
                    item.setLastError(row.getLastError());
                    item.setCreateTime(row.getCreateTime());
                    notifyResps.add(item);
                }
                resp.setNotifications(notifyResps);
            } else {
                resp.setNotifications(List.of());
            }
            result.add(resp);
        }
        return result;
    }

    /** 已持续时长（秒）：活动算到「现在」，已恢复算到恢复时刻。 */
    private static Long durationSeconds(IotAlertInstance instance, LocalDateTime now) {
        if (instance.getStartTs() == null) {
            return null;
        }
        LocalDateTime end = instance.getResolvedTs() == null ? now : instance.getResolvedTs();
        long seconds = Duration.between(instance.getStartTs(), end).getSeconds();
        return Math.max(0L, seconds);
    }

    /** 解析状态筛选（逗号分隔；非法码**明确报错**，不静默忽略成「全部」）。 */
    private static Set<String> parseStates(String raw) {
        Set<String> states = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return states;
        }
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            AlertState state = AlertState.of(item);
            if (state == null) {
                throw new BusinessException("告警状态只能是「待确认条件 / 已触发 / 已确认 / 已恢复」之一");
            }
            states.add(state.getCode());
        }
        return states;
    }

    /** 生效通知渠道：规则没配就用平台默认（**不能传 null**，那会变成「一条通知都不排」）。 */
    private String resolveChannels(IotAlertRule rule) {
        return rule == null || rule.getNotifyChannels() == null || rule.getNotifyChannels().isBlank()
            ? properties.getDefaultNotifyChannels() : rule.getNotifyChannels();
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
