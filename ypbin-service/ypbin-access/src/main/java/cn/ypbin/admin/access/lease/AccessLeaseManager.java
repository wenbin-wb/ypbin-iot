/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.lease;

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseRenewAck;
import cn.ypbin.admin.iot.lease.LeaseRenewItem;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * access 的租约状态机：注册 → 领取 → 周期续约 → **self-fencing** → 周期重领。
 *
 * <p>五条硬要求（spec §3.1① + M-2 的配置对账）：</p>
 * <ol>
 *   <li><b>启动握手 fail-fast</b>：注册/领取任何非成功信封或传输异常都让应用启动失败——
 *       节点绝不在「没有归属」的状态下开始采集；</li>
 *   <li><b>周期续约</b>：拿到回执就刷新本地快照（含服务端 epoch）；</li>
 *   <li><b>self-fencing</b>：{@code revokedTenantIds} 逐租户停采、{@code nodeFenced} 整体停采后
 *       重新注册并重新领取、**本地过期自检**（续约失败/超时后，到期就自己停采，不等业务侧）；</li>
 *   <li><b>周期重领</b>：只在启动时领取会让「待接管」的租户永远无人接手，到点必须再领；</li>
 *   <li><b>配置版本对账</b>（M-2）：每轮结束后把「本节点持有的租户」交给
 *       {@link ConfigEpochReconciler}——台账版本号变了才重取设备清单（「不一致才拉全量」）。
 *       停采/回收时同步 {@code forget}，保证重新领取后一定重新对账。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public class AccessLeaseManager {

    private static final Logger log = LoggerFactory.getLogger(AccessLeaseManager.class);

    /** 指标前缀。 */
    static final String METRIC_PREFIX = "iot.access.lease.";

    /**
     * 时钟偏移告警阈值（秒）：超过它说明节点时钟与服务端明显不一致，值得一条 WARN。
     *
     * <p>偏移本身**不影响租约语义**（判据已用服务端时间校准），它是**运维信号**：NTP 坏了、容器时区配错等。</p>
     */
    static final long SKEW_WARN_SECONDS = 5L;

    private final ILeaseClient leaseClient;
    private final TenantLinkManager linkManager;
    private final AccessProperties properties;
    private final ConfigEpochReconciler reconciler;
    private final Map<Long, LeaseSnapshot> holdings = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDateTime> lastAcquireAt = new AtomicReference<>();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final Counter renewSuccess;
    private final Counter renewFailure;
    private final Counter revokedCounter;
    private final Counter selfFencedCounter;
    private final Counter nodeFencedCounter;
    private final Counter acquiredCounter;

    /**
     * 本地时钟相对**服务端（数据库）时钟**的偏移：{@code serverTime - localTime}。
     *
     * <p>正值 = 服务端比本机快（本机钟慢）；负值 = 本机钟快。它是「单次采样的 NTP 式估计」：
     * 每次 acquire/renew 回执都带服务端时间，取「调用前后本地时刻的中点」与服务端时间之差，
     * 抵消掉大部分往返延迟。请求失败时保留上一次的值（不得退回 0——那等于把校准丢掉）。</p>
     */
    private final AtomicReference<Duration> clockSkew = new AtomicReference<>(Duration.ZERO);

    /** 上次因时钟偏移告警时的偏移值（用于抑制日志噪声：只在跨过阈值或明显变化时再告警）。 */
    private final AtomicReference<Duration> lastWarnedSkew = new AtomicReference<>(null);

    /**
     * 是否**已成功校准过**。
     *
     * <p>首次读数**一律采纳**（哪怕偏移很大）：未校准时判据用的是本机原始时钟——那正是本能力要消灭的形态
     * （钟快提前停采、钟慢超期多采）。外委复核 A/B 实测过反面：若首次大偏移被拒，判据退回未校准，
     * 「容器时区误配」场景会变成**每轮拆链**（比不设保护更糟）。</p>
     */
    private final AtomicBoolean calibrated = new AtomicBoolean(false);

    /** 上一次「跳变待确认」的读数：与下一次一致（容差内）才采纳。 */
    private final AtomicReference<Duration> pendingJump = new AtomicReference<>(null);

    /** 跳变被暂缓（待确认）的累计次数。 */
    private final Counter skewDeferredCounter;

    /** 当前**连续**暂缓次数（采纳即清零；到 {@link #SKEW_CONFIRM_MAX_DEFERRALS} 强制采纳）。 */
    private final AtomicInteger deferredJumps = new AtomicInteger();

    private final AtomicLong lastSkewWarnAt = new AtomicLong(Long.MIN_VALUE);

    /**
     * 「连续两次一致」的确认容差。
     *
     * <p>=30s（不是 5s）：本估计的**最坏噪声约 6s**（秒级截断 ±1s + 网络非对称 ≤ RTT/2，本仓最坏 RTT 4s ⇒ ≤2s，
     * 叠加两次采样），取 5s 恰好会被噪声反复越过 ⇒ 读数每轮漂移 > 容差时**永不收敛**（判据停在旧值、
     * 大偏移方向不利时继续每轮拆链——外委复核实测过该构造性缺陷）。30s ≈ 5× 最坏噪声。</p>
     */
    static final Duration SKEW_CONFIRM_TOLERANCE = Duration.ofSeconds(30);

    /**
     * 跳变**暂缓的上限**：连续暂缓超过这个次数就强制采纳。
     *
     * <p>与容差共同保证「收敛」不是必要条件：即使读数每轮漂移都超过容差（坏抖动/持续阶跃），
     * 最长 3 个周期（默认 renew 10s ⇒ ~30s）后也会跟上真值，不会无限期停在旧判据上。</p>
     */
    static final int SKEW_CONFIRM_MAX_DEFERRALS = 3;

    /** 跳变阈值默认值（配置缺省/被显式置空时使用）。 */
    static final Duration DEFAULT_SKEW_JUMP_THRESHOLD = Duration.ofSeconds(60);

    /** 拒绝/待确认告警的最小间隔（避免每轮 10s 一条把日志打爆）。 */
    static final long SKEW_WARN_INTERVAL_MS = 60_000L;

    /** 本地时间源（生产=系统时钟；单测注入可推进/可偏移的假时钟）。 */
    private final Clock clock;

    /**
     * 构造状态机。
     *
     * @param leaseClient  租约客户端
     * @param linkManager  链路控制端口
     * @param properties   节点参数
     * @param meterRegistry 指标注册表
     * @param reconciler   配置版本对账器（M-2：把 {@code config_epoch} 变化变成「拉一次全量设备清单」）
     */
    public AccessLeaseManager(ILeaseClient leaseClient, TenantLinkManager linkManager,
            AccessProperties properties, MeterRegistry meterRegistry,
            ConfigEpochReconciler reconciler, Clock clock) {
        this.leaseClient = leaseClient;
        this.linkManager = linkManager;
        this.properties = properties;
        this.reconciler = reconciler;
        this.clock = clock;
        this.renewSuccess = Counter.builder(METRIC_PREFIX + "renew.success").register(meterRegistry);
        this.renewFailure = Counter.builder(METRIC_PREFIX + "renew.failure").register(meterRegistry);
        this.revokedCounter = Counter.builder(METRIC_PREFIX + "revoked").register(meterRegistry);
        this.selfFencedCounter = Counter.builder(METRIC_PREFIX + "self_fenced").register(meterRegistry);
        this.nodeFencedCounter = Counter.builder(METRIC_PREFIX + "node_fenced").register(meterRegistry);
        this.acquiredCounter = Counter.builder(METRIC_PREFIX + "acquired").register(meterRegistry);
        this.skewDeferredCounter = Counter.builder(METRIC_PREFIX + "clock_skew.deferred")
            .description("时钟偏移跳变被暂缓（待连续两次确认）的次数").register(meterRegistry);
        // 偏移量上大盘：节点钟漂移是「数据看起来莫名变少/变多」的常见根因
        Gauge.builder(METRIC_PREFIX + "clock_skew_seconds", clockSkew,
                ref -> ref.get().toMillis() / 1000.0d)
            .baseUnit("seconds")
            .description("本机时钟相对服务端时钟的偏移（正=本机慢）").register(meterRegistry);
    }

    /** 启动握手（fail-fast）：注册 + 领取。 */
    public void start() {
        registerOrFail();
        // 必须在领取之前记录：否则调度器若在这一瞬抢跑，会把「还没握手」误判为「到点重领」
        lastAcquireAt.set(LocalDateTime.now(clock));
        acquireOrFail();
        lastAcquireAt.set(LocalDateTime.now(clock));
    }

    /** 对外入口（调度器用）。 */
    public void renewAndSelfCheck() {
        renewAndSelfCheck(LocalDateTime.now(clock));
    }

    /**
     * 带时间注入的实现（测试用它模拟过期，不必真的等）。
     *
     * @param now 当前时刻
     */
    void renewAndSelfCheck(LocalDateTime now) {
        selfFenceExpiredLocally(now);
        if (holdings.isEmpty()) {
            log.debug("本地没有持有租户，跳过续约");
        } else {
            renew(now);
        }
        refreshAssignmentsIfDue(now);
        // 放在最后：本轮新领到的租户也要参与对账（否则要等下一个周期才知道配置变没变）
        reconciler.reconcile(Set.copyOf(holdings.keySet()));
    }

    /**
     * 本地过期自检：到期就停采（不依赖业务侧）。
     *
     * @param now 当前时刻
     */
    private void selfFenceExpiredLocally(LocalDateTime now) {
        // ⚠️ M0b-4 收口：判据必须用**校准到服务端时钟**的时刻——本机钟快会提前停采（数据凭空变少）、
        //    钟慢会在服务端已判定接管后仍多采一段（双采）。本地时刻只用于本地调度（lastAcquireAt）。
        Duration skew = clockSkew.get();
        LocalDateTime serverNow = now.plus(skew);
        List<Long> expired = holdings.entrySet().stream()
            .filter(entry -> entry.getValue().mustSelfFence(serverNow))
            .map(Map.Entry::getKey)
            .toList();
        for (Long tenantId : expired) {
            holdings.remove(tenantId);
            // 同时忘掉配置版本号：重新领取后必须重新对账（期间上游可能已经改过配置）
            reconciler.forget(tenantId);
            linkManager.fence(tenantId, "本地租约已过期（未成功续约）");
            selfFencedCounter.increment();
            log.warn("本地租约过期，自行停采：tenantId={} 本地时刻={} 校准后={} 时钟偏移={}秒",
                LogSanitizer.sanitize(tenantId), now, serverNow, skew.toSeconds());
        }
    }

    /**
     * 用服务端时间校准本地时钟偏移（每次 acquire/renew 回执都调用）。
     *
     * @param localSample 本次调用的本地时刻中点（抵消往返延迟）
     * @param serverTime  服务端时间；{@code null}（如续约被节点级否决）时保留上一次的校准结果
     */
    private void updateClockSkew(LocalDateTime localSample, LocalDateTime serverTime) {
        if (serverTime == null) {
            log.debug("服务端未回传时间，沿用上一次时钟校准：偏移={}秒", clockSkew.get().toSeconds());
            return;
        }
        Duration skew = Duration.between(localSample, serverTime);
        Duration current = clockSkew.get();
        Duration configured = properties.getClockSkewJumpThreshold();
        // 配置被显式置空时兜底（不能拿 null 去 compareTo：renew 路径没有 try 包裹 ⇒ 会变成 NPE 停机）
        Duration jumpThreshold = configured == null ? DEFAULT_SKEW_JUMP_THRESHOLD : configured;
        if (!calibrated.get()) {
            if (skew.abs().compareTo(jumpThreshold) > 0) {
                // 首次校准就很大：**照采**——未校准的判据（本机原始时钟）比大偏移更危险
                log.warn("首次时钟校准发现较大偏移 {} 秒（跳变阈值 {} 秒）⇒ **照采**"
                    + "（不校准的判据会让钟快节点提前停采、钟慢节点超期多采）：node={}", skew.toSeconds(),
                    jumpThreshold.toSeconds(), LogSanitizer.sanitize(properties.getNodeId()));
            }
        } else if (skew.minus(current).abs().compareTo(jumpThreshold) > 0) {
            // 相对**已校准值**的跳变：先暂缓（等下一次读数确认），但**有次数上限**——
            // 没有上限时，读数每轮漂移都超过容差就永不收敛（判据停在旧值、大偏移方向不利时每轮拆链）。
            Duration pending = pendingJump.get();
            boolean consistent = pending != null
                && skew.minus(pending).abs().compareTo(SKEW_CONFIRM_TOLERANCE) <= 0;
            int deferrals = deferredJumps.incrementAndGet();
            if (!consistent && deferrals <= SKEW_CONFIRM_MAX_DEFERRALS) {
                pendingJump.set(skew);
                skewDeferredCounter.increment();
                warnSkewRateLimited("时钟偏移相对已校准值跳变 {} 秒（阈值 {} 秒）⇒ **暂缓采纳**（第 {} 次），"
                    + "保留原校准 {} 秒；读数一致或暂缓达上限才采纳（请查 NTP/DB/容器时区）：node={}",
                    skew.minus(current).toSeconds(), jumpThreshold.toSeconds(), deferrals,
                    current.toSeconds(), LogSanitizer.sanitize(properties.getNodeId()));
                return;
            }
            log.warn("时钟偏移跳变 {} 秒{} ⇒ 采纳新偏移（原 {} 秒，连续暂缓 {} 次）：node={}",
                skew.minus(current).toSeconds(),
                consistent ? "经连续两次确认一致" : "已达暂缓上限 " + SKEW_CONFIRM_MAX_DEFERRALS,
                current.toSeconds(), deferrals, LogSanitizer.sanitize(properties.getNodeId()));
        }
        calibrated.set(true);
        pendingJump.set(null);
        deferredJumps.set(0);
        clockSkew.set(skew);
        Duration warned = lastWarnedSkew.get();
        boolean crossed = exceedsWarnThreshold(skew)
            && (warned == null || !exceedsWarnThreshold(warned)
                || skew.minus(warned).abs().compareTo(Duration.ofSeconds(SKEW_WARN_SECONDS)) > 0);
        if (crossed) {
            lastWarnedSkew.set(skew);
            log.warn("节点时钟与服务端相差 {} 秒（正=本机慢；租约判据已按服务端时间校准，"
                + "但请检查 NTP/容器时区）：node={}", skew.toSeconds(),
                LogSanitizer.sanitize(properties.getNodeId()));
        } else {
            log.debug("时钟校准：偏移={}秒", skew.toSeconds());
        }
    }

    /** 跳变/大偏移告警按最小间隔打（默认 60s），避免每轮一条。 */
    private void warnSkewRateLimited(String format, Object... args) {
        // 用注入时钟：既避免「时钟旁路」（外委复核点出），也让限流可被假时钟测试
        long now = clock.millis();
        long last = lastSkewWarnAt.get();
        if (now - last < SKEW_WARN_INTERVAL_MS || !lastSkewWarnAt.compareAndSet(last, now)) {
            return;
        }
        log.warn(format, args);
    }

    /**
     * 本地时刻中点：取「调用前采样」到「现在」的中点，作为服务端时间的本地对应点，抵消大部分往返延迟。
     *
     * @param localBefore 调用前的本地时刻
     * @return 调用前到现在的近似中点
     */
    private LocalDateTime midpoint(LocalDateTime localBefore) {
        LocalDateTime localAfter = LocalDateTime.now(clock);
        return localBefore.plus(Duration.between(localBefore, localAfter).dividedBy(2));
    }

    /**
     * 是否超过告警阈值。
     *
     * <p>必须用 {@link Duration#abs()} **比较 Duration 本身**：{@code Duration.toSeconds()} 对负值**向下取整**
     * （-5.5s → -6），用 {@code Math.abs(toSeconds())} 会让 +5.5s 判成「未超阈值」而 -5.5s 判成「超阈值」——
     * 同量级偏移、方向不同结论不同（外委复核实测 +6.0s/-5.0s 不对称）。</p>
     *
     * @param skew 偏移
     * @return 超过阈值返回 {@code true}
     */
    static boolean exceedsWarnThreshold(Duration skew) {
        return skew.abs().compareTo(Duration.ofSeconds(SKEW_WARN_SECONDS)) > 0;
    }

    /** 当前观测到的时钟偏移（秒，正=本机慢）；观测/测试用。 */
    public long clockSkewSeconds() {
        return clockSkew.get().toSeconds();
    }

    /**
     * 周期续约：一次批量续约，按回执刷新快照、按回收清单停采。
     *
     * @param now 当前时刻
     */
    private void renew(LocalDateTime now) {
        LeaseRenewReq req = new LeaseRenewReq();
        req.setAccessNode(properties.getNodeId());
        List<LeaseRenewItem> items = new ArrayList<>();
        for (Map.Entry<Long, LeaseSnapshot> entry : holdings.entrySet()) {
            LeaseRenewItem item = new LeaseRenewItem();
            item.setTenantId(entry.getKey());
            item.setEpoch(entry.getValue().epoch());
            items.add(item);
        }
        req.setLeases(items);
        R<LeaseRenewResp> resp;
        // 紧贴调用前采样：用本轮起点（now）会把 selfFence/组装耗时算进去程，让偏移偏正（复核 R8-5）
        LocalDateTime renewBefore = LocalDateTime.now(clock);
        try {
            resp = leaseClient.renew(req);
        } catch (RuntimeException ex) {
            renewFailure.increment();
            log.error("续约失败（本地到期时间不会延长，到期后将自行停采）：node={}",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
            return;
        }
        if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
            renewFailure.increment();
            log.error("续约返回非成功信封：node={} code={}", LogSanitizer.sanitize(properties.getNodeId()),
                resp == null ? "null" : resp.getCode());
            return;
        }
        renewSuccess.increment();
        updateClockSkew(midpoint(renewBefore), resp.getData().getServerTime());
        applyRenewResponse(resp.getData(), now);
    }

    /**
     * 落地续约回执。
     *
     * @param resp 回执
     * @param now  当前时刻
     */
    private void applyRenewResponse(LeaseRenewResp resp, LocalDateTime now) {
        if (resp.isNodeFenced()) {
            // 优先处理并返回：否则会先对「已被判定失效」的节点 startCollecting（3b 会真的建链），
            // 再被 fenceAll 拆掉 —— 白建一次链
            handleNodeFenced(now);
            return;
        }
        for (LeaseRenewAck ack : resp.getRenewedLeases()) {
            holdings.put(ack.getTenantId(), new LeaseSnapshot(ack.getLeaseExpireAt(), ack.getEpoch()));
        }
        for (Long tenantId : resp.getRevokedTenantIds()) {
            // 无条件停采 + 计数：fence 是幂等的；3b 换真实现后本地快照可能已无该租户，
            // 但「服务端要求停采」这件事必须执行（否则会漏停采）
            holdings.remove(tenantId);
            // 同 fence：忘掉配置版本号，重新领取后必须重新对账
            reconciler.forget(tenantId);
            revokedCounter.increment();
            linkManager.fence(tenantId, "business 判定该租户已失效/被接管");
            log.warn("租户被回收，断链停采：tenantId={}", LogSanitizer.sanitize(tenantId));
        }
    }

    /**
     * 节点级失效：整体停采 → 重新注册并重新领取；失败则保持停采并把 registered 置回 false。
     *
     * @param now 当前时刻
     */
    private void handleNodeFenced(LocalDateTime now) {
        nodeFencedCounter.increment();
        log.error("business 判定本节点已失效（nodeFenced）：整体停采并重新注册：node={}",
            LogSanitizer.sanitize(properties.getNodeId()));
        linkManager.fenceAll("business 判定节点失效");
        // 整体停采：全部租户的配置版本号一并忘掉（重新领取即重新对账）
        for (Long tenantId : List.copyOf(holdings.keySet())) {
            reconciler.forget(tenantId);
        }
        holdings.clear();
        lastAcquireAt.set(now);
        try {
            registerOrFail();
            acquireOrFail();
        } catch (RuntimeException ex) {
            registered.set(false);
            log.error("重新注册/领取失败，节点保持停采（下一轮补注册后再试）：node={}",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
        }
    }

    /**
     * 周期重领（接管的执行入口）。
     *
     * @param now 当前时刻
     */
    private void refreshAssignmentsIfDue(LocalDateTime now) {
        if (!registered.get()) {
            if (lastAcquireAt.get() == null) {
                // 握手还没开始（start() 还没跑到预置那行）⇒ **绝不抢跑**：
                // 旧栈复核实测过调度器会在这一瞬完成 register+acquire，令「注册→领取→采集」的顺序变成偶然。
                return;
            }
            // nodeFenced 恢复失败时 registered 会停在 false，而服务端的 acquire 拒绝未注册节点
            // ⇒ 不在这里补注册，节点会永久零采集（旧实现只记错误，活性缺陷）。register 是幂等覆盖。
            try {
                registerOrFail();
            } catch (RuntimeException ex) {
                log.error("重领前补注册失败（节点仍处停采状态）：node={}",
                    LogSanitizer.sanitize(properties.getNodeId()), ex);
                return;
            }
        }
        LocalDateTime last = lastAcquireAt.get();
        if (last != null && Duration.between(last, now).toMillis() < properties.getAcquireIntervalMs()) {
            return;
        }
        lastAcquireAt.set(now);
        LocalDateTime localBefore = LocalDateTime.now(clock);
        R<LeaseAcquireResp> resp;
        try {
            resp = leaseClient.acquire(acquireRequest());
        } catch (RuntimeException ex) {
            log.error("周期重领失败（已在采的租户不受影响）：node={}",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
            return;
        }
        if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
            // 服务端可能已经重启、丢了进程内的节点注册（acquire 显式拒绝未注册节点）⇒ 置回未注册，
            // 下一轮先补注册。否则「0 租户节点 + 无 renew」会永远拿不到 nodeFenced ⇒ 永久零采集。
            registered.set(false);
            log.error("周期重领返回非成功信封：node={} code={}（已置回未注册，下一轮补注册）",
                LogSanitizer.sanitize(properties.getNodeId()), resp == null ? "null" : resp.getCode());
            return;
        }
        updateClockSkew(midpoint(localBefore), resp.getData().getServerTime());
        int gained = applyAcquireResponse(resp.getData());
        if (gained > 0) {
            acquiredCounter.increment(gained);
            log.warn("周期重领到租户（接管/新分配）：node={} 新增={}",
                LogSanitizer.sanitize(properties.getNodeId()), gained);
        }
    }

    /** 注册（失败即启动失败）。 */
    private void registerOrFail() {
        AccessNodeRegisterReq req = new AccessNodeRegisterReq();
        req.setAccessNode(properties.getNodeId());
        req.setMaxTenants(properties.getCapacity());
        R<Void> resp;
        try {
            resp = leaseClient.register(req);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("access 启动失败：无法完成节点注册（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + "）", ex);
        }
        if (resp == null || resp.getCode() != 200) {
            throw new IllegalStateException("access 启动失败：注册节点未成功（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + ", code="
                + (resp == null ? "null" : resp.getCode()) + "）");
        }
        registered.set(true);
        log.info("节点注册成功：node={} capacity={}", LogSanitizer.sanitize(properties.getNodeId()),
            properties.getCapacity() == null ? "不限" : properties.getCapacity());
    }

    /** 领取（失败即启动失败）。 */
    private void acquireOrFail() {
        R<LeaseAcquireResp> resp;
        LocalDateTime localBefore = LocalDateTime.now(clock);
        try {
            resp = leaseClient.acquire(acquireRequest());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("access 启动失败：无法领取租约（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + "）", ex);
        }
        if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
            throw new IllegalStateException("access 启动失败：领取租约未成功（node="
                + LogSanitizer.sanitize(properties.getNodeId()) + ", code="
                + (resp == null ? "null" : resp.getCode()) + "）");
        }
        updateClockSkew(midpoint(localBefore), resp.getData().getServerTime());
        applyAcquireResponse(resp.getData());
        log.info("租户领取完成：node={} 持有租户={}", LogSanitizer.sanitize(properties.getNodeId()),
            LogSanitizer.sanitize(holdings.keySet()));
    }

    /**
     * 落地领取响应：写入快照并开始采集。
     *
     * @param data 领取响应
     * @return 本次新增（此前未持有）的租户数
     */
    private int applyAcquireResponse(LeaseAcquireResp data) {
        int gained = 0;
        for (LeaseAssignmentDto assignment : data.getAssignments()) {
            boolean isNew = !holdings.containsKey(assignment.getTenantId());
            holdings.put(assignment.getTenantId(),
                new LeaseSnapshot(assignment.getLeaseExpireAt(), assignment.getEpoch()));
            linkManager.startCollecting(assignment.getTenantId());
            if (isNew) {
                gained++;
            }
        }
        return gained;
    }

    /** 领取请求（启动领取与周期重领共用）。 */
    private LeaseAcquireReq acquireRequest() {
        LeaseAcquireReq req = new LeaseAcquireReq();
        req.setAccessNode(properties.getNodeId());
        return req;
    }

    /** 当前持有的租户（测试/观测用）。 */
    public Set<Long> heldTenants() {
        return Set.copyOf(holdings.keySet());
    }
}
