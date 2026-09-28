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

    /** 用途标记：评估器与断档映射各自解析设备，游标必须分开（否则一轮推两次，反而漏页）。 */
    private static final String PURPOSE_POINT = "POINT";

    private static final String PURPOSE_OUTAGE = "OUTAGE";

    private final IotDeviceMapper deviceMapper;

    private final AlertDeviceCursor cursor;

    public AlertCandidateResolver(IotDeviceMapper deviceMapper, AlertDeviceCursor cursor) {
        this.deviceMapper = deviceMapper;
        this.cursor = cursor;
    }

    /**
     * 一个候选：某条规则的某个点位条件，落到某台设备上。
     *
     * @param rule  规则
     * @param point 点位条件
     */
    public record Candidate(IotAlertRule rule, IotAlertRulePoint point) {
    }

    /**
     * 点位候选解析结果。
     *
     * @param candidatesByDevice 设备 ID → 候选列表
     * @param devices            设备 ID → 设备（含名称编码，用于通知文案）
     * @param truncated          是否因单轮上限被截断（**必须计数上报**，不静默丢弃）
     */
    public record Resolved(Map<Long, List<Candidate>> candidatesByDevice, Map<Long, IotDevice> devices,
                           boolean truncated) {
    }

    /**
     * 设备级作用域解析结果（断档类规则用：没有点位条件，只需要「哪台设备被哪条最具体的规则覆盖」）。
     *
     * @param ruleByDevice 设备 ID → 覆盖它且**最具体**的规则
     * @param devices      设备 ID → 设备
     * @param truncated    是否被单轮上限截断
     */
    public record DeviceScope(Map<Long, IotAlertRule> ruleByDevice, Map<Long, IotDevice> devices,
                              boolean truncated) {
    }

    /**
     * 解析点位候选集合。
     *
     * @param rules      启用规则（同一租户）
     * @param points     规则 ID → 条件行（无条件的规则不出现）
     * @param maxDevices 单轮设备上限（超出则截断并把 {@code truncated} 置真）
     * @return 解析结果
     */
    public Resolved resolve(List<IotAlertRule> rules, Map<Long, List<IotAlertRulePoint>> points,
                            int maxDevices) {
        Loaded loaded = loadDevices(rules, maxDevices, PURPOSE_POINT);
        Map<Long, List<Candidate>> candidatesByDevice = new LinkedHashMap<>();
        for (Map.Entry<Long, IotDevice> entry : loaded.devices().entrySet()) {
            IotDevice device = entry.getValue();
            List<Candidate> bucket = new ArrayList<>();
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
            if (!bucket.isEmpty()) {
                candidatesByDevice.put(entry.getKey(), bucket);
            }
        }
        return new Resolved(candidatesByDevice, loaded.devices(), loaded.truncated());
    }

    /**
     * 解析「设备 → 覆盖它的最具体规则」（断档类规则专用）。
     *
     * <p>最具体 = {@code POINT} &gt; {@code DEVICE} &gt; {@code PRODUCT} &gt; {@code TENANT}
     * （设计 §2.1 的作用域优先级）；同具体程度时取 **id 最小**的那条，保证每轮结果稳定可复现
     * （不取「先遍历到者」——那会让同一份数据在不同轮次给出不同级别）。</p>
     *
     * @param rules      断档类规则（无点位条件；同一租户）
     * @param maxDevices 单轮设备上限
     * @return 解析结果
     */
    public DeviceScope resolveDeviceScope(List<IotAlertRule> rules, int maxDevices) {
        Loaded loaded = loadDevices(rules, maxDevices, PURPOSE_OUTAGE);
        Map<Long, IotAlertRule> ruleByDevice = new LinkedHashMap<>();
        for (Map.Entry<Long, IotDevice> entry : loaded.devices().entrySet()) {
            IotDevice device = entry.getValue();
            IotAlertRule winner = null;
            for (IotAlertRule rule : rules) {
                AlertScopeType scope = AlertScopeType.of(rule.getScopeType());
                if (scope == null || !matches(scope, rule, device)) {
                    continue;
                }
                if (winner == null || isMoreSpecific(rule, winner)) {
                    winner = rule;
                }
            }
            if (winner != null) {
                ruleByDevice.put(entry.getKey(), winner);
            }
        }
        return new DeviceScope(ruleByDevice, loaded.devices(), loaded.truncated());
    }

    /** 规则 A 是否比 B 更具体（具体程度高者胜；相同则 id 小者胜，保证确定性）。 */
    private static boolean isMoreSpecific(IotAlertRule candidate, IotAlertRule current) {
        AlertScopeType candidateScope = AlertScopeType.of(candidate.getScopeType());
        AlertScopeType currentScope = AlertScopeType.of(current.getScopeType());
        if (candidateScope == null) {
            return false;
        }
        if (currentScope == null) {
            return true;
        }
        int comparison = Integer.compare(candidateScope.getSpecificity(), currentScope.getSpecificity());
        if (comparison != 0) {
            return comparison > 0;
        }
        Long candidateId = candidate.getId();
        Long currentId = current.getId();
        if (candidateId == null) {
            return false;
        }
        return currentId == null || candidateId < currentId;
    }

    /**
     * 批量加载被规则覆盖的设备（**至多三次查询**，与规则条数无关），并按**跨轮滚动**取页。
     *
     * @param rules      规则集合
     * @param maxDevices 单轮设备上限（= 页容量）
     * @param purpose    用途标记（{@code POINT} / {@code OUTAGE}），用于隔离游标
     * @return 设备集合与「本轮是否只覆盖了一部分」标记
     */
    private Loaded loadDevices(List<IotAlertRule> rules, int maxDevices, String purpose) {
        if (rules.isEmpty()) {
            return new Loaded(Map.of(), false);
        }
        Set<Long> productIds = new LinkedHashSet<>();
        Set<Long> explicitDeviceIds = new LinkedHashSet<>();
        boolean tenantWide = false;
        for (IotAlertRule rule : rules) {
            AlertScopeType scope = AlertScopeType.of(rule.getScopeType());
            if (scope == null) {
                // 作用域码非法：不参与评估（不按最宽作用域兜底）
                continue;
            }
            switch (scope) {
                case TENANT -> tenantWide = true;
                case PRODUCT -> addIfPresent(productIds, rule.getScopeProductId());
                case DEVICE, POINT -> {
                    addIfPresent(explicitDeviceIds, rule.getScopeDeviceId());
                    addIfPresent(productIds, rule.getScopeProductId());
                }
            }
        }
        Map<Long, IotDevice> devices = new LinkedHashMap<>();
        boolean truncated = false;
        if (!explicitDeviceIds.isEmpty()) {
            LoadedPage page = loadPage("EXPLICIT", purpose, fingerprint(explicitDeviceIds), maxDevices,
                deviceSelect().in(IotDevice::getId, new ArrayList<>(explicitDeviceIds)));
            collect(devices, page.records());
            truncated = page.truncated();
        }
        if (!productIds.isEmpty()) {
            // 产品级规则可能覆盖一个产品下上万台设备 ⇒ 同样跨轮滚动，不按「取前 N 台」静默丢弃
            LoadedPage page = loadPage("PRODUCT", purpose, fingerprint(productIds), maxDevices,
                deviceSelect().in(IotDevice::getProductId, new ArrayList<>(productIds)));
            collect(devices, page.records());
            truncated = truncated || page.truncated();
        }
        if (tenantWide) {
            // 租户级规则会命中本租户全部设备 ⇒ 单轮只取一页并**跨轮滚动**（设计 §2.2.2）
            LoadedPage page = loadPage("TENANT", purpose, "", maxDevices, deviceSelect());
            collect(devices, page.records());
            truncated = truncated || page.truncated();
        }
        return new Loaded(devices, truncated);
    }

    /**
     * 取一页设备并推进游标。
     *
     * <p>三种查询形状（显式 ID / 产品 / 全租户）各自持有游标：页号由 {@link AlertDeviceCursor} 记忆，
     * 取满则下轮进页、取不满或取空则回到第一页 ⇒ **任意连续 N 轮内覆盖全部设备**。
     * 取空且页号 &gt; 1 时（数据集变小）回到第一页重查一次，避免在一张不存在的页上空转。</p>
     *
     * @param shape       查询形状（{@code EXPLICIT}/{@code PRODUCT}/{@code TENANT}）
     * @param purpose     用途标记
     * @param variant     作用域指纹（同一形状下规则集合变化时隔离游标；无则空串）
     * @param maxDevices  页容量
     * @param wrapper     查询条件（不含排序与分页）
     * @return 本页结果
     */
    private LoadedPage loadPage(String shape, String purpose, String variant, int maxDevices,
                                LambdaQueryWrapper<IotDevice> wrapper) {
        String key = shape + AlertRules.DEDUP_SEPARATOR + purpose + AlertRules.DEDUP_SEPARATOR + variant;
        int pageNo = cursor.currentPage(key);
        Page<IotDevice> page = deviceMapper.selectPage(new Page<>(pageNo, maxDevices),
            wrapper.clone().orderByAsc(IotDevice::getId));
        if (page.getRecords().isEmpty() && pageNo > 1) {
            cursor.reset(key);
            pageNo = 1;
            page = deviceMapper.selectPage(new Page<>(1, maxDevices),
                wrapper.clone().orderByAsc(IotDevice::getId));
        }
        cursor.advance(key, page.getRecords().size(), maxDevices);
        // 「本轮未覆盖全部设备」= 本页之后还有数据（用页码×页容量与总数比，而不是
        // 「总数 > 本页条数」——后者在最后一页也成立，会让滚动成为常态时每轮都记 truncated 并打 WARN）
        boolean hasMore = page.getCurrent() * page.getSize() < page.getTotal();
        return new LoadedPage(page.getRecords(), hasMore);
    }

    /** 作用域指纹：把 ID 集合折叠成稳定短串（同一规则集合每轮得到同一个键）。 */
    private static String fingerprint(Set<Long> ids) {
        long hash = 1125899906842597L;
        for (Long id : ids) {
            hash = 31 * hash + (id == null ? 0 : id);
        }
        return Long.toHexString(hash);
    }

    /** 一页结果。 */
    private record LoadedPage(List<IotDevice> records, boolean truncated) {
    }

    /** 设备加载结果。 */
    private record Loaded(Map<Long, IotDevice> devices, boolean truncated) {
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

    private static void addIfPresent(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static void collect(Map<Long, IotDevice> target, List<IotDevice> found) {
        for (IotDevice device : found) {
            target.putIfAbsent(device.getId(), device);
        }
    }
}
