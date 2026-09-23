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
import java.util.ArrayList;
import java.util.Comparator;
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
 * 保证 start/fence/reconcile 的成对语义，不追求高并发。</p>
 *
 * <p><b>配置变更对账（M-2）</b>：{@link #reconcile(Long)} 由租约管理器在台账 {@code config_epoch}
 * 变化时调用，重取一次清单并应用差异。它是「上游改了设备/点位之后接入侧能跟上」的<b>唯一</b>路径——
 * 此前清单只在首次采集时取一次，租约持续续约时连 fence 都不会发生，于是变更永远不被发现。</p>
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

    /** 无会话设备重发 ADD 的退避起点（N-2：建链失败/设备离线时不每个周期猛重试）。 */
    static final Duration REBIND_BACKOFF_BASE = Duration.ofSeconds(30);

    /** 无会话设备重发 ADD 的退避上限。 */
    static final Duration REBIND_BACKOFF_MAX = Duration.ofMinutes(2);

    /** 每轮对账最多重发多少个 ADD（防「整批设备离线」时把框架与远端打爆）。 */
    static final int REBIND_MAX_PER_CYCLE = 20;

    private static final Logger log = LoggerFactory.getLogger(IotProtocolTenantLinkManager.class);

    private final DeviceSpecSource source;
    private final AccessDeviceRegistry registry;
    private final SubscriptionPlanner planner;

    private final Clock clock;

    private final Counter emptySpecCounter;

    /** 取数**失败**次数（与「确实没有设备」分开计数：失败必须在大盘上可见，见复核 G3）。 */
    private final Counter specFailureCounter;

    /** 因退避跳过的取数次数（观测「省了多少次远端调用」）。 */
    private final Counter backoffSkippedCounter;

    /** 配置变更对账**已完成**的次数（含「确实没有设备」）。 */
    private final Counter reconcileAppliedCounter;

    /** 每个租户的空清单退避状态（有设备即移除）。 */
    private final Map<Long, Backoff> emptyBackoff = new ConcurrentHashMap<>();

    /**
     * 逐设备的「重发 ADD」退避状态（N-2）：设备还没有会话时，隔一段时间重发一次 ADD 让框架重建链。
     *
     * <p>为什么必须由宿主重发：框架只在 {@code bind()} **成功**后才挂重连监听——启动瞬间设备离线时 ADD
     * 发出去但建链失败，框架**不会**自己重试 ⇒ 该设备永久零数据（日志里只有一行 DEBUG「暂无会话」）。</p>
     */
    private final Map<String, Backoff> rebindBackoff = new ConcurrentHashMap<>();

    /** 重发 ADD 的累计次数。 */
    private final Counter rebindCounter;

    /** 因「每轮上限」被推迟的重发次数（观测「本来想重发多少」）。 */
    private final Counter rebindDeferredCounter;

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
        // 指标前缀统一为 `iot.access.*`（与既有的 `iot.access.lease.*` 一致）
        this.emptySpecCounter = meterRegistry.counter("iot.access.spec.empty");
        this.specFailureCounter = meterRegistry.counter("iot.access.spec.failure");
        this.backoffSkippedCounter = meterRegistry.counter("iot.access.spec.backoff.skipped");
        this.reconcileAppliedCounter = meterRegistry.counter("iot.access.spec.reconcile.applied");
        this.rebindCounter = meterRegistry.counter("iot.access.device.rebind");
        this.rebindDeferredCounter = meterRegistry.counter("iot.access.device.rebind.deferred");
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
            List<DeviceSpec> loaded;
            try {
                loaded = source.loadByTenant(tenantId);
            } catch (DeviceSpecLoadException ex) {
                // 取数失败**不是**「没有设备」：不能缓存、不能下架既有设备，按失败退避后重试。
                // 与「确实没有设备」（返回空集合）分开计数与日志，否则 G3 的「失败不可观测」会一直存在。
                onLoadFailure(tenantId, ex);
                return;
            }
            for (DeviceSpec device : loaded) {
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
                // 刚发过 ADD：先预置一次退避，避免同一轮立刻再发一次（建链结果下一轮才看得出来）
                rebindBackoff.put(device.deviceId(),
                    new Backoff(1, clock.instant().plus(REBIND_BACKOFF_BASE)));
            }
            log.info("[access] 协议栈开始采集租户：tenantId={} 设备数={}",
                LogSanitizer.sanitize(tenantId), devices.size());
        }
        List<DeviceSpec> current = List.copyOf(devices.values());
        int subscribed = planner.subscribe(current);
        if (subscribed > 0) {
            log.info("[access] 已发起订阅（异步完成；成败看 subscribe.success/failure 与日志）：tenantId={} 本次发起设备数={}",
                LogSanitizer.sanitize(tenantId), subscribed);
        }
        retryDevicesWithoutSession(tenantId, current);
    }

    /**
     * 对「仍没有会话」的设备重发 ADD（N-2）：让框架重新走一次 {@code bind}，使「首次绑定失败/设备离线」
     * 能自愈（框架只在 bind **成功**后才挂重连监听，失败后不会自己重试 ⇒ 设备会永久零数据）。
     *
     * <p>三重节制：逐设备指数退避（{@link #REBIND_BACKOFF_BASE} 起、{@link #REBIND_BACKOFF_MAX} 封顶）、
     * 每轮上限 {@link #REBIND_MAX_PER_CYCLE}、以及**首次 ADD 当轮先预置一次退避**（建链结果下一轮才看得出来，
     * 同一轮再发一次纯属浪费）。会话一旦建立即清除该设备的退避。</p>
     *
     * @param tenantId 租户 ID
     * @param devices  本轮清单
     */
    private void retryDevicesWithoutSession(Long tenantId, List<DeviceSpec> devices) {
        Set<String> missing = planner.devicesWithoutSession(devices);
        if (missing.isEmpty()) {
            for (DeviceSpec device : devices) {
                rebindBackoff.remove(device.deviceId());
            }
            return;
        }
        List<DeviceSpec> candidates = new ArrayList<>();
        for (DeviceSpec device : devices) {
            String deviceId = device.deviceId();
            if (!missing.contains(deviceId)) {
                rebindBackoff.remove(deviceId);
                continue;
            }
            Backoff backoff = rebindBackoff.get(deviceId);
            if (backoff != null && clock.instant().isBefore(backoff.nextRetryAt())) {
                continue;
            }
            candidates.add(device);
        }
        if (candidates.isEmpty()) {
            return;
        }
        // ⚠️ 公平性（外委复核实测的缺陷）：**按「下次可重试时刻」升序**取本轮配额，而不是按设备列表顺序。
        //    否则当采集间隔 > 退避上限（例如 acquire-interval-ms=5min、退避封顶 2min）时，列表前 20 台每轮
        //    退避都已过期、永远先吃掉配额 ⇒ 第 21 台起**永久零数据**（正是 N-2 要消灭的静默失效）。
        //    按时刻升序后，刚发过的设备时刻最新 ⇒ 自动排到队尾，被挡住的下一轮必然排到前面。
        candidates.sort(Comparator.comparing(device -> {
            Backoff backoff = rebindBackoff.get(device.deviceId());
            return backoff == null ? Instant.MIN : backoff.nextRetryAt();
        }));
        int emitted = 0;
        for (DeviceSpec device : candidates) {
            if (emitted >= REBIND_MAX_PER_CYCLE) {
                rebindDeferredCounter.increment();
                continue;
            }
            String deviceId = device.deviceId();
            Backoff backoff = rebindBackoff.get(deviceId);
            int attempts = backoff == null ? 1 : backoff.attempts() + 1;
            Duration delay = backoffDelay(attempts, REBIND_BACKOFF_BASE, REBIND_BACKOFF_MAX);
            rebindBackoff.put(deviceId, new Backoff(attempts, clock.instant().plus(delay)));
            registry.emit(new DeviceChange(ChangeType.ADD, device, registry.nextRevision(deviceId)));
            rebindCounter.increment();
            emitted++;
            log.warn("[access] 设备仍无会话（建链失败/设备离线），重发 ADD 重建链：tenantId={} deviceId={} "
                + "第 {} 次 下次重试={} 秒后（若持续无会话请查设备端点与网络）",
                LogSanitizer.sanitize(tenantId), deviceId, attempts, delay.toSeconds());
        }
    }

    /** 当前登记的「重发 ADD」退避条数（观测/测试用；正常应随设备下架/会话建立而回落）。 */
    int rebindBackoffCount() {
        return rebindBackoff.size();
    }

    /**
     * 按最新配置对账该租户的设备清单（M-2：{@code config_epoch} 变化时由租约管理器触发，修 G7）。
     *
     * <p>差异处理：<b>新增</b> ⇒ ADD；<b>消失</b> ⇒ REMOVE 并清理订阅跟踪；<b>规格变化</b>（点位/端点/周期）
     * ⇒ 重新 ADD（框架对同一设备的变更走「先解绑再绑定」，新会话随后会触发重订阅）。</p>
     *
     * <p><b>取数失败与「确实没有设备」严格分开</b>：失败抛 {@link DeviceSpecLoadException} ⇒ 本轮不下架任何设备、
     * 不进版本号、退避后重试；成功信封 + 空列表 ⇒ 该租户确实没有设备，全部下架。</p>
     *
     * <p><b>全部下架后不缓存空清单</b>（与 N-1 同一取向）：把清单条目移除，交还给
     * {@code startCollecting} 的「空清单不缓存 + 退避重取」路径——设备重新出现时无需等台账版本号变化即可恢复。</p>
     *
     * @param tenantId 租户 ID
     * @return 已完成对账返回 {@code true}（含「确实没有设备」）；未采集/取数失败/退避中返回 {@code false}
     */
    @Override
    public synchronized boolean reconcile(Long tenantId) {
        if (!collecting.contains(tenantId)) {
            // 已 fence 或尚未开始采集：不得推设备（否则会「复活」一个本节点不负责的租户）
            return false;
        }
        Map<String, DeviceSpec> current = collected.get(tenantId);
        if (current == null) {
            // 尚未成功取过清单（启动期为空的租户）：交给 startCollecting 的退避重取路径，
            // 这里再拉一次只会与它重复打同一个远端接口。
            return false;
        }
        if (inEmptyBackoff(tenantId)) {
            backoffSkippedCounter.increment();
            return false;
        }
        List<DeviceSpec> latest;
        try {
            latest = source.loadByTenant(tenantId);
        } catch (DeviceSpecLoadException ex) {
            onLoadFailure(tenantId, ex);
            return false;
        }
        Map<String, DeviceSpec> next = new LinkedHashMap<>();
        for (DeviceSpec device : latest) {
            next.put(device.deviceId(), device);
        }
        clearEmptyBackoff(tenantId);
        if (next.isEmpty()) {
            removeAllDevices(tenantId, current);
            reconcileAppliedCounter.increment();
            return true;
        }
        int removed = 0;
        for (Map.Entry<String, DeviceSpec> entry : current.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                registry.emit(new DeviceChange(ChangeType.REMOVE, entry.getValue(),
                    registry.nextRevision(entry.getKey())));
                planner.forget(entry.getKey());
                // C2：单设备下架同样要清「重发 ADD」退避（否则 Map 无界增长 + 同 id 复现吃陈旧退避）
                rebindBackoff.remove(entry.getKey());
                removed++;
            }
        }
        int added = 0;
        int changed = 0;
        for (Map.Entry<String, DeviceSpec> entry : next.entrySet()) {
            DeviceSpec previous = current.get(entry.getKey());
            if (previous == null) {
                registry.emit(new DeviceChange(ChangeType.ADD, entry.getValue(),
                    registry.nextRevision(entry.getKey())));
                // 与首次采集同一取向：刚发过 ADD 先预置一次退避，避免同轮/下一轮立刻再发一次
                rebindBackoff.put(entry.getKey(),
                    new Backoff(1, clock.instant().plus(REBIND_BACKOFF_BASE)));
                added++;
            } else if (!previous.equals(entry.getValue())) {
                // 规格变了：重新 ADD（框架会先解绑再绑定），revision 必须继续递增
                registry.emit(new DeviceChange(ChangeType.ADD, entry.getValue(),
                    registry.nextRevision(entry.getKey())));
                // 与新增一致：重新 ADD 后也预置一次退避（同轮/下一轮不必马上再发）
                rebindBackoff.put(entry.getKey(),
                    new Backoff(1, clock.instant().plus(REBIND_BACKOFF_BASE)));
                changed++;
            }
        }
        collected.put(tenantId, next);
        int subscribed = planner.subscribe(List.copyOf(next.values()));
        reconcileAppliedCounter.increment();
        if (removed > 0 || added > 0 || changed > 0) {
            log.info("[access] 配置变更对账完成：tenantId={} 新增={} 变更={} 删除={} 发起订阅={}",
                LogSanitizer.sanitize(tenantId), added, changed, removed, subscribed);
        } else {
            log.debug("[access] 配置变更对账完成（清单无差异）：tenantId={} 设备数={}",
                LogSanitizer.sanitize(tenantId), next.size());
        }
        return true;
    }

    /**
     * 把某租户的设备全部下架（上游确认「确实没有设备」时）。
     *
     * @param tenantId 租户 ID
     * @param current  已推给框架的清单
     */
    private void removeAllDevices(Long tenantId, Map<String, DeviceSpec> current) {
        for (Map.Entry<String, DeviceSpec> entry : current.entrySet()) {
            registry.emit(new DeviceChange(ChangeType.REMOVE, entry.getValue(),
                registry.nextRevision(entry.getKey())));
            planner.forget(entry.getKey());
            // 下架即清「重发 ADD」退避：否则设备被删后该 Map 无界增长，且同 id 重新出现会吃陈旧退避
            rebindBackoff.remove(entry.getKey());
        }
        // 不缓存空清单：交给 startCollecting 的「空清单不缓存 + 退避重取」路径，
        // 设备重新出现时无需依赖台账版本号变化即可恢复（与 N-1 的取向一致）
        collected.remove(tenantId);
        log.info("[access] 上游确认该租户已无设备，全部下架：tenantId={} 下架数={}",
            LogSanitizer.sanitize(tenantId), current.size());
    }

    /** 是否处于空清单退避窗口内。 */
    private boolean inEmptyBackoff(Long tenantId) {
        Backoff state = emptyBackoff.get(tenantId);
        return state != null && clock.instant().isBefore(state.nextRetryAt());
    }

    /** 记一次空清单：计数、推进退避、分级日志（首次 WARN，之后 DEBUG，避免日志噪声）。 */
    private void onEmptySpec(Long tenantId) {
        emptySpecCounter.increment();
        Backoff previous = emptyBackoff.get(tenantId);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        Duration delay = backoffDelay(attempts);
        emptyBackoff.put(tenantId, new Backoff(attempts, clock.instant().plus(delay)));
        if (attempts == 1) {
            log.warn("[access] 租户设备清单为空，本轮不缓存并退避重取：tenantId={} 下次重取={} 秒后"
                + "（若持续为空，请检查点位映射与内部接口）", LogSanitizer.sanitize(tenantId),
                delay.toSeconds());
        } else {
            log.debug("[access] 租户设备清单仍为空，退避中：tenantId={} 已连续={} 次 下次重取={} 秒后",
                LogSanitizer.sanitize(tenantId), attempts, delay.toSeconds());
        }
    }

    /**
     * 记一次取数**失败**：计数、推进退避、分级日志（首次 WARN 带完整堆栈，之后 DEBUG 带引用以免日志洪水）。
     *
     * <p>与 {@link #onEmptySpec} 共用同一份退避状态：两者对远端而言都是「本轮没拿到可用清单」，
     * 分开计数是为了让「接口持续失败」在大盘上看得见（G3）。</p>
     *
     * @param tenantId 租户 ID
     * @param ex       取数异常
     */
    private void onLoadFailure(Long tenantId, DeviceSpecLoadException ex) {
        specFailureCounter.increment();
        Backoff previous = emptyBackoff.get(tenantId);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        Duration delay = backoffDelay(attempts);
        emptyBackoff.put(tenantId, new Backoff(attempts, clock.instant().plus(delay)));
        if (attempts == 1) {
            log.warn("[access] 拉取设备清单失败（本轮不缓存、不下架既有设备；{} 秒后重试）：tenantId={}",
                delay.toSeconds(), LogSanitizer.sanitize(tenantId), ex);
        } else {
            log.debug("[access] 拉取设备清单持续失败（退避中）：tenantId={} 已连续={} 次 {} 秒后重试",
                LogSanitizer.sanitize(tenantId), attempts, delay.toSeconds(), ex);
        }
    }

    /** 指数退避（空清单：起始 {@link #BACKOFF_BASE}、上限 {@link #BACKOFF_MAX}）。 */
    private static Duration backoffDelay(int attempts) {
        return backoffDelay(attempts, BACKOFF_BASE, BACKOFF_MAX);
    }

    /**
     * 通用指数退避：{@code base × 2^(attempts-1)}，封顶 {@code max}。
     *
     * @param attempts 连续次数（≥1）
     * @param base     起始间隔
     * @param max      上限
     * @return 本次退避时长
     */
    private static Duration backoffDelay(int attempts, Duration base, Duration max) {
        long millis = base.toMillis() * (1L << Math.min(Math.max(attempts, 1) - 1, 20));
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
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
            rebindBackoff.remove(device.deviceId());
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
     * 退避状态（空清单与「重发 ADD」共用）。
     *
     * @param attempts    连续次数
     * @param nextRetryAt 下一次允许动作的时刻
     * @author wenbin
     * @since 2026-09-22
     */
    private record Backoff(int attempts, Instant nextRetryAt) {
    }
}
