/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

import cn.ypbin.starter.tools.lock.LeaseLockGuard;
import cn.ypbin.starter.tools.lock.LeaseLockOutcome;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 保留清理实现（D0.8）。
 *
 * <p>要点：</p>
 * <ol>
 *   <li><b>用数据库时钟</b>算截止时刻（与应用时钟解耦，避免时区/漂移导致误删）；</li>
 *   <li><b>跨租户</b>：清理没有租户身份，必须 {@link TenantContext#executeIgnore}；表是租户表，
 *       不忽略的话租户插件会追加条件并在无上下文时直接拒绝执行；</li>
 *   <li><b>一轮两张表各一条 DELETE</b>：不在循环里做数据库调用（本仓铁律），批量删除交给单条语句；</li>
 *   <li><b>不可逆动作的护栏</b>：天数必须为正（启动自检 + 每轮复核），否则拒绝执行并报错，绝不清空全表；</li>
 *   <li><b>孤儿映射巡检</b>（2026-09-26 新增，与清理同一节拍）：只**计数 + 告警**，绝不删除
 *       （见 {@link #inspectOrphanMappings()}）。</li>
 *   <li><b>防重入</b>（#227 → #220）：定时任务与手工触发端点共用 {@link #cleanupOnce()}。
 *       #227 用 JVM 内 {@code AtomicBoolean}，只覆盖单实例；#220 已把它升级为
 *       {@link LeaseLockGuard} 包裹的**跨实例互斥锁**（Redis 租约；无 Redis 时退化为进程内，
 *       并打 WARN 暴露边界），抢不到锁直接跳过本轮。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class RetentionCleanupServiceImpl implements RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupServiceImpl.class);

    /**
     * 分布式锁名（**业务语义**：哪些任务互斥）。
     *
     * <p>2026-10-15 task-18 起 IoT 自建锁实现已删除、改用 ypbin-starter 的租约锁；锁名仍属业务语义，
     * 故留在各自任务身边（每个锁名恰好一处使用）。同时是运维观测键：{@code ypbin.lock.*} 按 task 标签区分。</p>
     */
    public static final String LOCK_NAME = "retention-cleanup";

    /** 清理计数（按表打 tag，便于分别观察）。 */
    public static final String METRIC_DELETED = "iot.retention.deleted";

    /**
     * 孤儿映射**存量** gauge（当前有多少条 {@code iot_point_mapping} 引用了已不存在的属性行）。
     *
     * <p>与 {@code iot.ingest.propertyid.orphan}（counter，按条计「被丢弃的读数」）分工不同：
     * 那个回答「这一批有多少读数因此被丢」，本指标回答「库里现在有多少条悬空映射」——
     * 前者是流量，后者是存量，运维据此判断要不要清理映射。</p>
     */
    public static final String METRIC_ORPHAN = "iot.pointmapping.orphan";

    private final OutageEventMapper outageEventMapper;
    private final MaintenanceWindowMapper maintenanceWindowMapper;
    private final DeviceLivenessMapper livenessMapper;
    private final IotPointMappingMapper pointMappingMapper;
    private final RetentionProperties properties;
    private final Counter outageCounter;
    private final Counter windowCounter;

    /** 孤儿映射存量（gauge 的取值源；由巡检写入，指标读它）。启动时装一次（见 {@link #inspectOrphansOnStartup()}）。 */
    private final AtomicLong orphanMappings = new AtomicLong();

    /**
     * 跨实例互斥守卫（#220）：定时任务与手工触发端点共用本服务，同一时刻全体副本只允许一轮清理在跑。
     *
     * <p>#227 的 {@code AtomicBoolean} 只能挡住同一 JVM 内的并发；多副本部署下两个副本各持一个
     * {@code AtomicBoolean}，谁也挡不住谁。这里换成 {@link LeaseLockGuard}（Redis 租约 + 看门狗续期 +
     * 异常必释放；2026-10-15 task-18 起由 ypbin-starter 提供，IoT 侧不再自建），抢不到锁直接返回
     * {@link RetentionSkipReason#LOCK_HELD_BY_OTHER}。</p>
     */
    private final LeaseLockGuard leaseLockGuard;

    public RetentionCleanupServiceImpl(OutageEventMapper outageEventMapper,
                                       MaintenanceWindowMapper maintenanceWindowMapper,
                                       DeviceLivenessMapper livenessMapper,
                                       IotPointMappingMapper pointMappingMapper,
                                       RetentionProperties properties, MeterRegistry meterRegistry,
                                       LeaseLockGuard leaseLockGuard) {
        this.outageEventMapper = outageEventMapper;
        this.maintenanceWindowMapper = maintenanceWindowMapper;
        this.livenessMapper = livenessMapper;
        this.pointMappingMapper = pointMappingMapper;
        this.properties = properties;
        this.leaseLockGuard = leaseLockGuard;
        this.outageCounter = Counter.builder(METRIC_DELETED).tag("table", "outage_event")
            .description("保留清理删除的行数").register(meterRegistry);
        this.windowCounter = Counter.builder(METRIC_DELETED).tag("table", "maintenance_window")
            .description("保留清理删除的行数").register(meterRegistry);
        Gauge.builder(METRIC_ORPHAN, orphanMappings, AtomicLong::doubleValue)
            .description("孤儿点位映射行数（映射行在、其引用的物模型属性行已不存在）")
            .register(meterRegistry);
    }

    /**
     * 启动即巡检一次孤儿映射。
     *
     * <p><b>为什么需要它（值班口径陷阱）</b>：gauge 的初值是 {@code 0}，而巡检默认要等
     * {@code initial-delay-ms}（默认 5 分钟）才跑第一轮 ⇒ 每次重启后的头几分钟里，
     * {@value #METRIC_ORPHAN} 恒为 0——它与「巡检跑了、结果确实是 0」**读数完全相同**。
     * 对一条会驱动「要不要清理映射」决策的指标，这种「0 可能是还没测」的歧义必须消掉，
     * 所以启动时立即巡检一次（失败也只记 ERROR，不影响启动）。</p>
     */
    @PostConstruct
    void inspectOrphansOnStartup() {
        inspectOrphanMappings();
    }

    @Override
    @Scheduled(fixedDelayString = "${ypbin.retention.cleanup-interval-ms:86400000}",
        initialDelayString = "${ypbin.retention.initial-delay-ms:300000}")
    public RetentionCleanupResult cleanupOnce() {
        long startedNanos = System.nanoTime();
        // 跨实例互斥（#220）：抢不到锁 = 另一副本正在清理，本轮**不跑**，如实返回原因。
        LeaseLockOutcome<RetentionCleanupResult> outcome = leaseLockGuard.callExclusively(
            LOCK_NAME, () -> runCleanupRound(startedNanos));
        if (!outcome.executed()) {
            log.warn("[iot] 保留清理未取得分布式锁（另一实例正在执行），本次触发跳过");
            return RetentionCleanupResult.skipped(RetentionSkipReason.LOCK_HELD_BY_OTHER,
                elapsedMs(startedNanos));
        }
        return outcome.result();
    }

    /**
     * 真正的一轮清理（调用方已持有分布式锁）。
     *
     * @param startedNanos 本轮起点（用于耗时统计）
     * @return 清理结果
     */
    private RetentionCleanupResult runCleanupRound(long startedNanos) {
        // 巡检先跑且**不受清理开关影响**：它是纯只读计数，关掉清理不代表不想看孤儿映射存量
        inspectOrphanMappings();
        if (!properties.isEnabled()) {
            log.warn("[iot] 保留清理已关闭（{}.enabled=false）：断档事件与维护窗口会无限增长",
                RetentionProperties.PREFIX);
            return RetentionCleanupResult.skipped(RetentionSkipReason.DISABLED, elapsedMs(startedNanos));
        }
        if (properties.getOutageEventDays() <= 0 || properties.getMaintenanceWindowDays() <= 0) {
            // 不可逆动作的护栏：天数配置成 0/负数意味着"全部过期"，这里拒绝执行而不是照删
            log.error("[iot] 保留天数非法（断档={} 天，维护窗口={} 天），本轮清理**拒绝执行**；"
                    + "请修正 {}.outage-event-days / {}.maintenance-window-days",
                properties.getOutageEventDays(), properties.getMaintenanceWindowDays(),
                RetentionProperties.PREFIX, RetentionProperties.PREFIX);
            return RetentionCleanupResult.skipped(RetentionSkipReason.INVALID_CONFIG, elapsedMs(startedNanos));
        }
        LocalDateTime now = livenessMapper.selectNow();
        LocalDateTime outageCutoff = now.minusDays(properties.getOutageEventDays());
        LocalDateTime windowCutoff = now.minusDays(properties.getMaintenanceWindowDays());

        int deletedOutages = TenantContext.executeIgnore(
            () -> outageEventMapper.deleteStartedBefore(outageCutoff));
        int deletedWindows = TenantContext.executeIgnore(
            () -> maintenanceWindowMapper.deleteStartedBefore(windowCutoff));
        outageCounter.increment(deletedOutages);
        windowCounter.increment(deletedWindows);

        if (deletedOutages > 0 || deletedWindows > 0) {
            log.info("[iot] 保留清理完成：断档事件 {} 行（截止 {}）、维护窗口 {} 行（截止 {}）",
                deletedOutages, outageCutoff, deletedWindows, windowCutoff);
        } else {
            log.info("[iot] 保留清理完成：无过期数据（断档截止 {}、维护窗口截止 {}）",
                LogSanitizer.sanitize(outageCutoff), LogSanitizer.sanitize(windowCutoff));
        }
        return RetentionCleanupResult.executed(deletedOutages, deletedWindows, elapsedMs(startedNanos));
    }

    /** 本轮耗时（毫秒）。 */
    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * 孤儿映射巡检：统计「映射行在、属性行已缺失」的行数，写入
     * {@value #METRIC_ORPHAN} 并在非零时告警。**只读，不删除**。
     *
     * <p><b>为什么要巡检而不是让入站发现</b>：入站只在「有读数打到这条映射」时才发现孤儿
     * （那时读数已被丢弃并计入 {@code iot.ingest.propertyid.orphan}）；停采设备的孤儿映射永远等不到读数，
     * 会一直躺在库里。巡检按库计数，覆盖「有没有流量都看得见」。</p>
     *
     * <p><b>为什么删不得</b>：删除映射行 = 改设备采集配置（可能把设备改成「少采一个点位」），
     * 这是需要人确认的动作；TTL/保留策略的对象是事件数据，不是配置。故此处只计数 + 告警。</p>
     *
     * <p><b>失败语义</b>：巡检失败（例如 SQL 异常）只记录 + 保留上一次取值，**不打断清理**——
     * 让只读巡检把不可逆的清理拖停是更糟的取舍。失败会以 ERROR 全堆栈暴露，不会被吞。</p>
     */
    private void inspectOrphanMappings() {
        try {
            // 跨租户巡检：无租户身份，必须显式忽略（否则租户插件 fail-closed 直接抛）
            long orphans = TenantContext.executeIgnore(() -> pointMappingMapper.countOrphanMappings());
            orphanMappings.set(orphans);
            if (orphans > 0) {
                log.warn("[iot] 孤儿点位映射巡检：{} 条映射引用的物模型属性行已不存在"
                        + "（读数会被按「孤儿」丢弃并计入 iot.ingest.propertyid.orphan；"
                        + "本巡检**只计数不删除**，处置需人工确认）",
                    orphans);
            } else {
                log.info("[iot] 孤儿点位映射巡检：0 条（映射与物模型属性一一对应）");
            }
        } catch (RuntimeException ex) {
            log.error("[iot] 孤儿点位映射巡检失败（保留上一次取值，不打断本轮清理）", ex);
        }
    }
}
