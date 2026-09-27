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

import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.entity.IotAlertRulePoint;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.AlertScopeType;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 把「规则集合」解析成「本轮要评估的（设备 × 点位条件）候选集合」（设计 §2.2.2 与 §2.1 的作用域优先级）。
 *
 * <p><b>为什么单独成类</b>：作用域解析是本设计里第二容易出错的地方（第一是去重键）——{@code TENANT} 级规则
 * 只能命中**本租户**设备（设计 §3.5-N5 要求两个租户的对照用例），而「产品级规则要反查设备」这件事很
 * 容易写成「逐条规则查一次设备表」的 N+1。本类把设备查询**按作用域类型聚合成至多三次批量查询**，
 * 与规则条数无关。</p>
 *
 * <p><b>租户边界</b>：所有设备查询都走 MyBatis-Plus 条件构造器（不写原生 SQL）⇒ 租户插件统一追加
 * {@code tenant_id} 条件；调用方必须在**目标租户的上下文**里调用本类。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertCandidateResolver {

    private final IotDeviceMapper deviceMapper;

    public AlertCandidateResolver(IotDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    /**
     * 一个候选：某条规则的某个点位条件，落到某台设备上。
     *
     * @param rule  规则
     * @param point 点位条件（断档类规则为 {@code null}）
     */
    public record Candidate(IotAlertRule rule, IotAlertRulePoint point) {
    }

    /**
     * 解析结果。
     *
     * @param candidatesByDevice 设备 ID → 候选列表
     * @param devices            设备 ID → 设备（含名称编码，用于通知文案）
     * @param truncated          是否因单轮上限被截断（**必须计数上报**，不静默丢弃）
     */
    public record Resolved(Map<Long, List<Candidate>> candidatesByDevice, Map<Long, IotDevice> devices,
                           boolean truncated) {
    }

    /**
     * 解析候选集合。
     *
     * @param rules      启用规则（同一租户）
     * @param points     规则 ID → 条件行（无条件的规则不出现）
     * @param maxDevices 单轮设备上限（超出则截断并把 {@code truncated} 置真）
     * @return 解析结果
     */
    public Resolved resolve(List<IotAlertRule> rules, Map<Long, List<IotAlertRulePoint>> points,
                            int maxDevices) {
        Resolved empty = new Resolved(Map.of(), Map.of(), false);
        if (rules.isEmpty()) {
            return empty;
        }
        // ① 聚合查询条件（只查一次设备表，与规则条数无关）
        Set<Long> productIds = new LinkedHashSet<>();
        Set<Long> explicitDeviceIds = new LinkedHashSet<>();
        boolean tenantWide = false;
        for (IotAlertRule rule : rules) {
            AlertScopeType scope = AlertScopeType.of(rule.getScopeType());
            if (scope == null) {
                // 作用域码非法：加载侧已校验过，这里再兜一层（非法规则不参与评估，而不是被当成最宽作用域）
                continue;
            }
            switch (scope) {
                case PRODUCT -> {
                    if (rule.getScopeProductId() != null) {
                        productIds.add(rule.getScopeProductId());
                    }
                }
                case DEVICE, POINT -> {
                    if (rule.getScopeDeviceId() != null) {
                        explicitDeviceIds.add(rule.getScopeDeviceId());
                    }
                    if (rule.getScopeProductId() != null) {
                        productIds.add(rule.getScopeProductId());
                    }
                }
                case TENANT -> tenantWide = true;
            }
        }
        Map<Long, IotDevice> devices = new LinkedHashMap<>();
        if (!explicitDeviceIds.isEmpty()) {
            collect(devices, deviceMapper.selectList(deviceSelect()
                .in(IotDevice::getId, new ArrayList<>(explicitDeviceIds))));
        }
        boolean truncated = false;
        if (!productIds.isEmpty()) {
            // 产品级规则的设备集合也可能很大（一个产品下上万台）⇒ 与租户级同样用单轮上限 + 跨轮滚动
            Page<IotDevice> page = deviceMapper.selectPage(new Page<>(1, maxDevices),
                deviceSelect().in(IotDevice::getProductId, new ArrayList<>(productIds))
                    .orderByAsc(IotDevice::getId));
            collect(devices, page.getRecords());
            truncated = page.getTotal() > page.getRecords().size();
        }
        if (tenantWide) {
            // 租户级规则会命中本租户全部设备 ⇒ 用**单轮上限**截断并跨轮滚动（设计 §2.2.2），
            // 而不是「一次全量查出来」把内存与延迟放大到不可控
            Page<IotDevice> page = deviceMapper.selectPage(new Page<>(1, maxDevices),
                deviceSelect().orderByAsc(IotDevice::getId));
            collect(devices, page.getRecords());
            truncated = truncated || page.getTotal() > page.getRecords().size();
        }
        // ② 展开候选（纯内存，无任何 DB/RPC）
        Map<Long, List<Candidate>> candidatesByDevice = new LinkedHashMap<>();
        List<Long> orderedDeviceIds = new ArrayList<>(devices.keySet());
        for (Long deviceId : orderedDeviceIds) {
            IotDevice device = devices.get(deviceId);
            if (device == null) {
                continue;
            }
            List<Candidate> bucket = candidatesByDevice.computeIfAbsent(deviceId, key -> new ArrayList<>());
            for (IotAlertRule rule : rules) {
                AlertScopeType scope = AlertScopeType.of(rule.getScopeType());
                if (scope == null || !matches(scope, rule, device)) {
                    continue;
                }
                List<IotAlertRulePoint> rulePoints = points.get(rule.getId());
                if (rulePoints == null || rulePoints.isEmpty()) {
                    // 断档类规则：没有点位条件，不参与点位评估（由断档映射链路处理）
                    continue;
                }
                for (IotAlertRulePoint point : rulePoints) {
                    bucket.add(new Candidate(rule, point));
                }
            }
            if (bucket.isEmpty()) {
                candidatesByDevice.remove(deviceId);
            }
        }
        return new Resolved(candidatesByDevice, devices, truncated);
    }

    /** 设备查询的公共投影：只取展示与判定需要的列（不查影子 JSON 这类大字段）。 */
    private static LambdaQueryWrapper<IotDevice> deviceSelect() {
        return new LambdaQueryWrapper<IotDevice>()
            .select(IotDevice::getId, IotDevice::getTenantId, IotDevice::getProductId,
                IotDevice::getDeviceName, IotDevice::getDeviceCode, IotDevice::getOnlineStatus);
    }

    /**
     * 作用域是否命中该设备（**最具体者优先由「全部生效」实现**：设计 §2.1 明确同优先级全部生效，
     * 不同优先级之间也不做覆盖——因为它们可能配的是不同点位或不同级别）。
     */
    private static boolean matches(AlertScopeType scope, IotAlertRule rule, IotDevice device) {
        return switch (scope) {
            case TENANT -> true;
            case PRODUCT -> rule.getScopeProductId() != null
                && rule.getScopeProductId().equals(device.getProductId());
            case DEVICE, POINT -> rule.getScopeDeviceId() != null
                && rule.getScopeDeviceId().equals(device.getId());
        };
    }

    private static void collect(Map<Long, IotDevice> target, List<IotDevice> found) {
        for (IotDevice device : found) {
            target.putIfAbsent(device.getId(), device);
        }
    }
}
