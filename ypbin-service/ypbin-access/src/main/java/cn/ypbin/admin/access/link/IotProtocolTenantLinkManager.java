/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.link;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接真实协议栈的链路管理器（增量 3b-2）：把「租约判定」翻译成框架认识的设备增删。
 *
 * <p><b>为什么这样实现</b>（对框架装配源码的核实结论）：框架只在 {@code ApplicationReadyEvent}
 * 调一次 {@code DeviceRegistry.loadAll()} 并注册变更监听；之后的运行期增删**完全依赖宿主推
 * {@code DeviceChange}**。所以「开始/停止采集某租户」在本框架下的正确表达就是：为该租户的设备
 * 发 ADD、断链时发 REMOVE。</p>
 *
 * <p><b>三个会静默失效的耦合在此守住</b>：</p>
 * <ol>
 *   <li>revision 必须按设备单调递增（框架对 ≤ 已应用值的变更直接丢弃且无日志）——统一走
 *       {@link AccessDeviceRegistry#nextRevision(String)}；</li>
 *   <li>本类不控制 {@code ypbin.iot.enabled} / {@code ypbin.iot.devices.enabled} 两个开关：
 *       开关关掉时框架根本不装配，现象是「一条日志都没有」，与「设备推不进去」要能区分；</li>
 *   <li>启动顺序：本类由租约管理器在<b>启动握手（注册/领取）之后</b>调用，保证框架首轮
 *       {@code loadAll()} 看到的是已领取的租约集合。</li>
 * </ol>
 *
 * <p>REMOVE 复用 ADD 时那一份 {@link DeviceSpec}，不重新拉远端配置：断链不应依赖远端可用性，
 * 也避免为「删除」发明占位协议值。幂等：重复 start / 重复 fence 都不产生重复变更。</p>
 *
 * <p>线程模型：租约调度是单线程（{@code LeaseRenewScheduler}），故这里用 {@code synchronized}
 * 保证 start/fence 的成对语义，不追求高并发。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class IotProtocolTenantLinkManager implements TenantLinkManager {

    private static final Logger log = LoggerFactory.getLogger(IotProtocolTenantLinkManager.class);

    private final DeviceSpecSource source;
    private final AccessDeviceRegistry registry;
    private final SubscriptionPlanner planner;

    /** 本节点正在采集的租户 → 已推给框架的设备（deviceId → 当时那份规格，断链时原样用于 REMOVE）。 */
    private final Map<Long, Map<String, DeviceSpec>> collected = new ConcurrentHashMap<>();

    public IotProtocolTenantLinkManager(DeviceSpecSource source, AccessDeviceRegistry registry,
                                        SubscriptionPlanner planner) {
        this.source = source;
        this.registry = registry;
        this.planner = planner;
    }

    @Override
    public synchronized void startCollecting(Long tenantId) {
        if (collected.containsKey(tenantId)) {
            return;
        }
        Map<String, DeviceSpec> devices = new LinkedHashMap<>();
        for (DeviceSpec device : source.loadByTenant(tenantId)) {
            devices.put(device.deviceId(), device);
        }
        collected.put(tenantId, devices);
        for (DeviceSpec device : devices.values()) {
            registry.emit(new DeviceChange(ChangeType.ADD, device,
                registry.nextRevision(device.deviceId())));
        }
        // ADD 已让框架同步建链；随后按点位建立订阅（框架不主动订阅，见 AccessSubscriptionPlanner）
        int subscribed = planner.subscribe(List.copyOf(devices.values()));
        log.info("[access] 协议栈开始采集租户：tenantId={} 设备数={} 已订阅设备数={}",
            LogSanitizer.sanitize(tenantId), devices.size(), subscribed);
    }

    @Override
    public synchronized void fence(Long tenantId, String reason) {
        Map<String, DeviceSpec> devices = collected.remove(tenantId);
        if (devices == null) {
            return;
        }
        for (DeviceSpec device : devices.values()) {
            registry.emit(new DeviceChange(ChangeType.REMOVE, device,
                registry.nextRevision(device.deviceId())));
        }
        log.warn("[access] 协议栈断链停采：tenantId={} 设备数={} reason={}",
            LogSanitizer.sanitize(tenantId), devices.size(), LogSanitizer.sanitize(reason));
    }

    @Override
    public void fenceAll(String reason) {
        List<Long> tenants = List.copyOf(collected.keySet());
        for (Long tenantId : tenants) {
            fence(tenantId, reason);
        }
        if (!tenants.isEmpty()) {
            log.warn("[access] 协议栈整体断链停采：租户数={} reason={}",
                tenants.size(), LogSanitizer.sanitize(reason));
        }
    }

    @Override
    public boolean isCollecting(Long tenantId) {
        return collected.containsKey(tenantId);
    }

    @Override
    public Set<Long> collectingTenants() {
        return Set.copyOf(collected.keySet());
    }
}
