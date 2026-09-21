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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

    /** 空清单退避的起始间隔。 */
    static final Duration BACKOFF_BASE = Duration.ofSeconds(30);

    /**
     * 空清单退避的上限。
     *
     * <p>取值是权衡：太大 ⇒ 设备刚配好却要等很久才开始采集；太小 ⇒ 退避没意义。
     * 2 分钟意味着「设备清单刚变非空」最坏等 2 分钟即自动开始采集（无需重启、无需人工干预）。</p>
     */
    static final Duration BACKOFF_MAX = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(IotProtocolTenantLinkManager.class);

    private final DeviceSpecSource source;
    private final AccessDeviceRegistry registry;
    private final SubscriptionPlanner planner;

    private final Clock clock;

    private final Counter emptySpecCounter;

    /** 因退避跳过的取数次数（观测「省了多少次远端调用」）。 */
    private final Counter backoffSkippedCounter;

    /** 每个租户的空清单退避状态（有设备即移除）。 */
    private final Map<Long, EmptyBackoff> emptyBackoff = new ConcurrentHashMap<>();

    /** 本节点正在采集的租户 → 已推给框架的设备（deviceId → 当时那份规格，断链时原样用于 REMOVE）。 */
    private final Map<Long, Map<String, DeviceSpec>> collected = new ConcurrentHashMap<>();

    /**
     * 本节点「负责」的租户集合（与设备清单缓存**分开**）。
     *
     * <p>为什么必须分开：设备清单可能因取数瞬时失败而为空，此时不能把它缓存下来（会把该租户永久钉死为零设备），
     * 但「本节点负责该租户」这个事实仍然成立（否则 fencing 语义与观测都会失真）。</p>
     */
    private final Set<Long> collecting = ConcurrentHashMap.newKeySet();

    public IotProtocolTenantLinkManager(DeviceSpecSource source, AccessDeviceRegistry registry,
                                        SubscriptionPlanner planner, MeterRegistry meterRegistry,
                                        Clock clock) {
        this.source = source;
        this.registry = registry;
        this.planner = planner;
        this.clock = clock;
        this.emptySpecCounter = meterRegistry.counter("ypbin.access.spec.empty");
        this.backoffSkippedCounter = meterRegistry.counter("ypbin.access.spec.backoff.skipped");
    }

    /**
     * 开始采集某租户（**对账语义**：可被每个租约周期反复调用）。
     *
     * <p>为什么不是「只做一次」：启动期的租约领取发生在 {@code ApplicationRunner} 阶段，
     * <b>早于</b>框架的 {@code ApplicationReadyEvent}</b>——那时变更监听器尚未接线、设备尚未建链，
     * 既发不出 ADD、也没有会话可订阅。若本方法只做一次，订阅将永远是 0（且不会自愈）。
     * 因此：<b>ADD 只在首次采集时发</b>（避免重复建链），<b>订阅每轮对账</b>——
     * 启动期跳过、下一周期补上；框架重连换了会话实例时也会在此补订阅。</p>
     */
    @Override
    public synchronized void startCollecting(Long tenantId) {
        collecting.add(tenantId);
        Map<String, DeviceSpec> devices = collected.get(tenantId);
        if (devices == null) {
            if (inEmptyBackoff(tenantId)) {
                // S7：空清单退避窗口内**不再打远端**（否则零设备租户每 15s 一次调用 + 一条 WARN）
                backoffSkippedCounter.increment();
                return;
            }
            devices = new LinkedHashMap<>();
            for (DeviceSpec device : source.loadByTenant(tenantId)) {
                devices.put(device.deviceId(), device);
            }
            if (devices.isEmpty()) {
                // **空清单不得被缓存**：取数失败（内部接口尚未就绪/瞬时抖动）返回的就是空集合，
                // 若在此缓存，该租户会被永久钉死为「零设备」且不再重取——与 B1 同类、更早一步的静默零数据。
                // 这里只是「本轮跳过」，并按 S7 退避后重取。
                onEmptySpec(tenantId);
                return;
            }
            clearEmptyBackoff(tenantId);
            collected.put(tenantId, devices);
            for (DeviceSpec device : devices.values()) {
                registry.emit(new DeviceChange(ChangeType.ADD, device,
                    registry.nextRevision(device.deviceId())));
            }
            log.info("[access] 协议栈开始采集租户：tenantId={} 设备数={}",
                LogSanitizer.sanitize(tenantId), devices.size());
        }
        int subscribed = planner.subscribe(List.copyOf(devices.values()));
        if (subscribed > 0) {
            log.info("[access] 已发起订阅（异步完成；成败看 subscribe.success/failure 与日志）：tenantId={} 本次发起设备数={}",
                LogSanitizer.sanitize(tenantId), subscribed);
        }
    }

    /** 是否处于空清单退避窗口内。 */
    private boolean inEmptyBackoff(Long tenantId) {
        EmptyBackoff state = emptyBackoff.get(tenantId);
        return state != null && clock.instant().isBefore(state.nextRetryAt());
    }

    /** 记一次空清单：计数、推进退避、分级日志（首次 WARN，之后 DEBUG，避免日志噪声）。 */
    private void onEmptySpec(Long tenantId) {
        emptySpecCounter.increment();
        EmptyBackoff previous = emptyBackoff.get(tenantId);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        Duration delay = backoffDelay(attempts);
        emptyBackoff.put(tenantId, new EmptyBackoff(attempts, clock.instant().plus(delay)));
        if (attempts == 1) {
            log.warn("[access] 租户设备清单为空，本轮不缓存并退避重取：tenantId={} 下次重取={} 秒后"
                + "（若持续为空，请检查点位映射与内部接口）", LogSanitizer.sanitize(tenantId),
                delay.toSeconds());
        } else {
            log.debug("[access] 租户设备清单仍为空，退避中：tenantId={} 已连续={} 次 下次重取={} 秒后",
                LogSanitizer.sanitize(tenantId), attempts, delay.toSeconds());
        }
    }

    /** 指数退避（起始 {@link #BACKOFF_BASE}，上限 {@link #BACKOFF_MAX}）。 */
    private static Duration backoffDelay(int attempts) {
        long millis = BACKOFF_BASE.toMillis() * (1L << Math.min(attempts - 1, 20));
        return Duration.ofMillis(Math.min(millis, BACKOFF_MAX.toMillis()));
    }

    /** 取到设备后清除退避状态。 */
    private void clearEmptyBackoff(Long tenantId) {
        if (emptyBackoff.remove(tenantId) != null) {
            log.info("[access] 租户设备清单已非空，退出退避：tenantId={}",
                LogSanitizer.sanitize(tenantId));
        }
    }

    @Override
    public synchronized void fence(Long tenantId, String reason) {
        collecting.remove(tenantId);
        emptyBackoff.remove(tenantId);
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
        List<Long> tenants = List.copyOf(collecting);
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
        return collecting.contains(tenantId);
    }

    @Override
    public Set<Long> collectingTenants() {
        return Set.copyOf(collecting);
    }

    /**
     * 空清单退避状态。
     *
     * @param attempts    连续为空的次数
     * @param nextRetryAt 下一次允许重取的时刻
     * @author wenbin
     * @since 2026-09-21
     */
    private record EmptyBackoff(int attempts, Instant nextRetryAt) {
    }
}
