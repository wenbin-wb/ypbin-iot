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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.access.link.LoggingTenantLinkManager;
import cn.ypbin.admin.access.link.TenantLinkManager;
import cn.ypbin.admin.iot.lease.ILeaseClient;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseAssignmentDto;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.LeaseState;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.tools.warn.WarnThrottled;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 偏移告警的<b>节流语义</b>测试（starter #106：手写 {@code lastSkewWarnAt + compareAndSet} 改用
 * {@link WarnThrottled}）。
 *
 * <p><b>为什么单独一个类</b>：节流是「错了不报错、只是日志量不对」的逻辑，且本类要<b>区分两种看起来
 * 很像的机制</b>——</p>
 * <ol>
 *   <li><b>时间节流</b>（{@code WarnThrottled}）：窗口内只打一条、跨窗口再打、抑制条数可见；</li>
 *   <li><b>值变化才告警</b>（{@code lastWarnedSkew}）：记录「上次告警时的偏移值」，只有跨过阈值或值明显
 *       变化才再打。<b>它不是节流，不能改用 {@code WarnThrottled} 替换</b>——替换后「偏移稳定在 5 分钟」
 *       会变成「每 60s 报一次同样的数」，反而更吵。</li>
 * </ol>
 *
 * <p>本类只驱动包内可见的构造器（注入节流器），因此可以精确控制窗口而不 sleep。</p>
 *
 * @author wenbin
 * @since 2026-10-19
 */
class AccessLeaseSkewWarnThrottleTest {

    private static final String NODE = "access-1";

    /**
     * 跨窗口用例的短间隔（毫秒）。
     *
     * <p>取 100ms：与真实等待 {@link #WAIT_PAST_SHORT_INTERVAL_MS} 配套，用例总耗时仍在百毫秒级。</p>
     */
    private static final long SHORT_INTERVAL_MS = 100L;

    /** 跨窗口等待（毫秒）：{@code 短间隔 × 2.5}，容忍调度抖动（sleep 只会更久）。 */
    private static final long WAIT_PAST_SHORT_INTERVAL_MS = SHORT_INTERVAL_MS * 5L / 2L;

    private static final long TENANT_A = 11L;

    private MutableClock clock;

    private ILeaseClient client;

    private TenantLinkManager linkManager;

    private AccessProperties properties;

    private SimpleMeterRegistry meterRegistry;

    private ConfigEpochReconciler reconciler;

    private Logger managerLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        client = mock(ILeaseClient.class);
        linkManager = spy(new LoggingTenantLinkManager());
        properties = new AccessProperties();
        properties.setNodeId(NODE);
        properties.setAcquireIntervalMs(Long.MAX_VALUE);
        meterRegistry = new SimpleMeterRegistry();
        reconciler = spy(new ConfigEpochReconciler(client, NODE, linkManager, meterRegistry,
            Clock.systemUTC(), 300_000L, 15_000L));
        when(client.batchEpoch(anyString())).thenReturn(R.ok(new TenantEpochBatchResp()));

        managerLogger = (Logger) LoggerFactory.getLogger(AccessLeaseManager.class);
        appender = new ListAppender<>();
        appender.start();
        managerLogger.addAppender(appender);
        // 预热日志链路（首次 log.warn 的冷启动耗时会挤进节流窗口，见 AccessDropWarnThrottleTest 的注释）
        managerLogger.warn("预热日志链路（不计入任何断言）");
        appender.list.clear();
    }

    @AfterEach
    void tearDown() {
        managerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("★ 窗口内多次偏移跳变只打 1 条告警（原文案保留 + 带「此前同类被抑制 N 条」）")
    void skewWarnShouldBeThrottledWithinWindow() {
        AccessLeaseManager manager = managerWith(new WarnThrottled(
            AccessLeaseManager.SKEW_WARN_INTERVAL_MS, managerLogger));
        startWithBigSkew(manager);

        // 再连续触发两次跳变（同一节流窗口内，窗口 60s、假时钟不推进 ⇒ 必被抑制）
        renewWithBigSkew(manager, 5);
        renewWithBigSkew(manager, 10);

        List<String> skewWarns = skewWarns();
        assertThat(skewWarns)
            .as("60s 窗口内重复跳变只允许落一条 WARN（每轮 10s 一条会把日志打爆）")
            .hasSize(1);
        assertThat(skewWarns.getFirst())
            .as("原文案必须保留（排障手册按它检索）")
            .contains("暂缓采纳");
        assertThat(skewWarns.getFirst())
            .as("必须写明被抑制条数——限流不等于静默")
            .contains("此前同类被抑制");
        assertThat(manager.suppressedSkewWarnCount())
            .as("第二轮跳变被抑制（首轮真打、次轮压掉）")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 跨窗口必须再打一条：窗口到期后恢复告警（用短间隔 + 真实等待，因基座时钟不可注入）")
    void skewWarnShouldEmitAgainAfterWindow() throws InterruptedException {
        // WarnThrottled 唯一带 Clock 的构造器包内可见 ⇒ 宿主侧无法用假时钟推进窗口；
        // 这里改为注入「短间隔」的节流器 + 真实等待（sleep 只会更久，故必然到期，不 flaky）。
        WarnThrottled throttle = new WarnThrottled(SHORT_INTERVAL_MS, managerLogger);
        AccessLeaseManager manager = managerWith(throttle);
        startWithBigSkew(manager);

        // 首轮跳变：窗口内第一条 ⇒ 真打
        renewWithBigSkew(manager, 5);
        assertThat(skewWarns()).hasSize(1);

        // 等过节流窗口后，再用一次更大的跳变触发同一告警路径
        Thread.sleep(WAIT_PAST_SHORT_INTERVAL_MS);
        renewWithBigSkew(manager, 10);

        assertThat(skewWarns())
            .as("跨窗口必须再打一条（窗口到期即恢复可观测）")
            .hasSize(2);
    }

    @Test
    @DisplayName("★ 节流不影响判定：被抑制的告警仍然照常累加「暂缓」计数")
    void throttlingShouldNotAffectDeferralAccounting() {
        AccessLeaseManager manager = managerWith(new WarnThrottled(
            AccessLeaseManager.SKEW_WARN_INTERVAL_MS, managerLogger));
        startWithBigSkew(manager);
        renewWithBigSkew(manager, 5);
        renewWithBigSkew(manager, 10);

        assertThat(meterRegistry.get("iot.access.lease.clock_skew.deferred").counter().count())
            .as("两次跳变都被暂缓 —— 限流只作用于日志")
            .isEqualTo(2.0d);
    }

    @Test
    @DisplayName("★ 区分两种机制：节流窗口再长，『值变化才告警』仍按值判据生效（不被节流吞掉）")
    void valueChangeWarningIsIndependentOfTimeThrottle() {
        // 给一个「永远抑制」的窗口：若实现把『值变化告警』错接进节流器，下面这条就不会出现
        AccessLeaseManager manager = managerWith(new WarnThrottled(
            Long.MAX_VALUE / 2L, managerLogger));
        startWithBigSkew(manager);

        // 「节点时钟与服务端相差 N 秒」这条由 lastWarnedSkew（值变化）驱动，不是时间节流驱动
        assertThat(bigSkewWarns())
            .as("首轮必须报出偏移（值变化判据），与时间节流无关")
            .hasSize(1);
    }

    @Test
    @DisplayName("★ 节流器窗口与生产常量一致（防止误把间隔改小/改 0）")
    void windowMustMatchProductionConstant() {
        WarnThrottled throttle = new WarnThrottled(
            AccessLeaseManager.SKEW_WARN_INTERVAL_MS, managerLogger);
        assertThat(throttle.minIntervalMillis())
            .as("生产窗口必须是 60s（每轮 renew 默认 10s ⇒ 窗口内最多 1 条）")
            .isEqualTo(AccessLeaseManager.SKEW_WARN_INTERVAL_MS)
            .isEqualTo(60_000L);
    }

    // ---------------------------------------------------------------- 场景驱动

    /**
     * 构造被测状态机（注入受控节流器），并对既有 {@code renewAndSelfCheck} 增加一次「映射语句」标记
     * （仅用于日志断言的可读性，不参与逻辑）。
     */
    private AccessLeaseManager managerWith(WarnThrottled throttle) {
        return new AccessLeaseManager(client, linkManager, properties, meterRegistry, reconciler,
            clock, throttle);
    }

    /**
     * 启动并让首轮校准落到一个「大偏移」：本地 0 点、服务端 30 分钟后 ⇒ 偏移 +30min。
     */
    private void startWithBigSkew(AccessLeaseManager manager) {
        LocalDateTime localNow = LocalDateTime.now(clock);
        when(client.register(any())).thenReturn(R.ok());
        when(client.acquire(any())).thenReturn(R.ok(acquireRespAt(localNow.plusMinutes(30),
            assignmentAt(TENANT_A, localNow.plusMinutes(30).plusSeconds(600)))));
        when(client.renew(any())).thenReturn(R.ok(new LeaseRenewResp()));
        manager.start();
    }

    /**
     * 再触发一轮「相对已校准值跳变」：每轮再跳 {@code minutes} 分钟（&gt; 阈值 60s，
     * 且与上一轮待确认值差 &gt; 容差 30s ⇒ 每轮都只「暂缓」，走节流告警路径）。
     */
    private void renewWithBigSkew(AccessLeaseManager manager, int minutes) {
        LeaseRenewResp resp = new LeaseRenewResp();
        resp.setServerTime(LocalDateTime.now(clock).plusMinutes(30 + minutes));
        when(client.renew(any())).thenReturn(R.ok(resp));
        manager.renewAndSelfCheck(LocalDateTime.now(clock).plusSeconds(1));
    }

    private static LeaseAcquireResp acquireRespAt(LocalDateTime serverTime, List<LeaseAssignmentDto> items) {
        LeaseAcquireResp resp = new LeaseAcquireResp();
        resp.setServerTime(serverTime);
        resp.setAssignments(items);
        return resp;
    }

    private static List<LeaseAssignmentDto> assignmentAt(long tenantId, LocalDateTime leaseExpireAt) {
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(tenantId);
        dto.setAccessNode(NODE);
        dto.setLeaseExpireAt(leaseExpireAt);
        dto.setEpoch(1L);
        dto.setState(LeaseState.ACTIVE);
        return List.of(dto);
    }

    /** 只统计「暂缓采纳」类告警（由节流器控制）。 */
    private List<String> skewWarns() {
        return warnMessages().stream()
            .filter(message -> message.contains("暂缓采纳"))
            .collect(Collectors.toList());
    }

    /** 只统计「节点时钟与服务端相差」类告警（由值变化判据控制）。 */
    private List<String> bigSkewWarns() {
        return warnMessages().stream()
            .filter(message -> message.contains("节点时钟与服务端相差"))
            .collect(Collectors.toList());
    }

    private List<String> warnMessages() {
        return appender.list.stream()
            .filter(event -> "WARN".equals(event.getLevel().toString()))
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }

    /** 可推进的假时钟（与 {@code AccessLeaseManagerTest.MutableClock} 同形）。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-22T06:00:00Z");

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
