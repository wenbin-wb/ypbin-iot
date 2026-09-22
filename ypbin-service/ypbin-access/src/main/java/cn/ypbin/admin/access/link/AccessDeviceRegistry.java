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
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.core.spi.ValidationResult;
import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * access 侧的 {@link DeviceRegistry} 实现：把「本节点租约内的设备」喂给协议栈框架。
 *
 * <p><b>两条必须守住的语义</b>（来自对框架装配源码的核实，见 IOT-ROADMAP 增量 3b-2 清单）：</p>
 * <ol>
 *   <li>{@link #loadAll()} 只返回<b>本节点当前持有租约</b>的租户的设备。框架在
 *       {@code ApplicationReadyEvent} 时调用它一次；返回别的租户的设备 = 采了不该采的数据。</li>
 *   <li>{@link DeviceChange#revision()} 必须<b>按设备严格递增</b>：框架按设备记录「已应用的
 *       revision」，≤ 它的变更<b>被直接丢弃且没有任何日志</b>。因此这里用 {@link AtomicLong}
 *       逐设备自增，绝不复用旧值。</li>
 * </ol>
 *
 * <p>运行期的增删由 {@link TenantLinkManager} 驱动：它调用 {@link #emit(DeviceChange)}，
 * 本类把变更投递给框架注册进来的监听器（框架在引导时通过 {@link #addChangeListener} 注册）。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class AccessDeviceRegistry implements DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(AccessDeviceRegistry.class);

    private final DeviceSpecSource source;

    /** 本节点持有租约的租户集合（惰性取，避免与 AccessLeaseManager 形成构造期循环依赖）。 */
    private final Supplier<Set<Long>> heldTenants;

    private final List<Consumer<DeviceChange>> listeners = new CopyOnWriteArrayList<>();

    /** 逐设备的变更版本号，必须单调递增（框架按它去重，非递增会被静默丢弃）。 */
    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    public AccessDeviceRegistry(DeviceSpecSource source, Supplier<Set<Long>> heldTenants) {
        this.source = source;
        this.heldTenants = heldTenants;
    }

    @Override
    public List<DeviceSpec> loadAll() {
        Set<Long> tenants = heldTenants.get();
        if (tenants == null || tenants.isEmpty()) {
            log.debug("[access] 本节点暂无租约，协议栈引导为空");
            return List.of();
        }
        List<DeviceSpec> devices = new ArrayList<>();
        for (Long tenantId : tenants) {
            try {
                devices.addAll(source.loadByTenant(tenantId));
            } catch (DeviceSpecLoadException ex) {
                // 引导是框架 ApplicationReadyEvent 的一次性调用：单个租户取数失败不得让整个协议栈起不来
                // （与 N-1 同一取向——「本轮少采」而不是「整体失败」）。运行期由配置变更对账补齐。
                log.error("[access] 协议栈引导取数失败，本轮跳过该租户：tenantId={}",
                    LogSanitizer.sanitize(tenantId), ex);
            }
        }
        log.info("[access] 协议栈引导取数：租户数={} 设备数={}", tenants.size(), devices.size());
        return devices;
    }

    @Override
    public ValidationResult validate(DeviceSpec device) {
        // 点位合法性由 iot 服务在写入时校验（§3.9）；协议栈侧不做二次判断，避免两处口径漂移
        return ValidationResult.ok();
    }

    @Override
    public void addChangeListener(Consumer<DeviceChange> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 投递一条设备变更（供 {@link TenantLinkManager} 在租约变化时调用）。
     *
     * @param change 变更（revision 由调用方经 {@link #nextRevision(String)} 取得）
     */
    public void emit(DeviceChange change) {
        if (listeners.isEmpty()) {
            // 不静默：没有监听器意味着运行期增删设备完全无效（框架尚未接线或开关被关）
            log.warn("[access] 协议栈尚未接线变更监听器，本次设备变更被丢弃：device={} type={}",
                change.device().deviceId(), change.type());
            return;
        }
        for (Consumer<DeviceChange> listener : listeners) {
            listener.accept(change);
        }
    }

    /**
     * 取某设备的下一个变更版本号（严格递增）。
     *
     * @param deviceId 设备标识
     * @return 严格大于此前任何一次返回值的版本号
     */
    public long nextRevision(String deviceId) {
        return revisions.computeIfAbsent(deviceId, key -> new AtomicLong()).incrementAndGet();
    }

    /** 当前已注册的变更监听器数量（供自检/测试断言接线是否完成）。 */
    public int listenerCount() {
        return listeners.size();
    }

    /** 已记录版本号的设备数（供测试断言）。 */
    public int trackedRevisionCount() {
        return revisions.size();
    }
}

