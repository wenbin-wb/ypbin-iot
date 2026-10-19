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

import cn.ypbin.admin.access.egress.AccessReadingSink;
import cn.ypbin.admin.iot.device.AccessPointMappingDto;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.spring.subscription.SubscriptionBatchItem;
import cn.ypbin.iot.spring.subscription.SubscriptionOrchestrator;
import cn.ypbin.iot.spring.subscription.SubscriptionOutcome;
import cn.ypbin.iot.spring.subscription.SubscriptionPoint;
import cn.ypbin.iot.spring.subscription.SubscriptionResult;
import cn.ypbin.iot.spring.subscription.SubscriptionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 订阅规划器（access 侧）：把「框架已建链的设备」翻译成框架的订阅请求，并接上读数出口。
 *
 * <p><b>职责已收窄（iot-starter #37）</b>：重订、在途去重、部分成功只补失败地址、按「设备 + 会话实例」
 * 退避、整批 / 逐点位分流、多寄存器地址展开<b>全部由框架的
 * {@link SubscriptionOrchestrator}（{@code DefaultSubscriptionOrchestrator}）承担</b>。
 * 本类只保留三件<b>框架不可能知道</b>的业务事：</p>
 * <ol>
 *   <li><b>哪些设备归我</b>：查询本节点已绑定的会话；无会话的设备跳过并上报宿主
 *       （{@link #devicesWithoutSession}，宿主据此把「建链失败」交给框架的 bind 重试）；</li>
 *   <li><b>点位 → 地址映射</b>：把设备规格里的点位 JSON 解析成 {@link SubscriptionPoint}
 *       （含 {@code word_count}——框架据此调协议声明的 {@code ModbusAddressExpansion} 展开兄弟寄存器，
 *       本类<b>不再</b>做任何地址算术）；</li>
 *   <li><b>读数出口接线</b>：为每台设备建 {@link PointMappingDataListener}（物模型规范化、质量判定、
 *       落列口径都在它里面，与订阅方式正交）。</li>
 * </ol>
 *
 * <p><b>本类不再直接调用协议层的订阅方法：100% 经框架编排</b>。此前
 * {@code payload-format=key-value} 的 TCP 需要「一次多地址订阅」，而框架当时只能按**协议级**声明
 * 寻址方式（TCP 恒 {@code PER_ADDRESS}）⇒ 宿主不得不自己分流 + 自己维护一张退避表（技术债）。
 * iot-starter `release/0.5.0` 已反哺 {@link cn.ypbin.iot.core.protocol.SubscriptionAddressingPolicy}
 * （协议级默认 + **按形态覆盖表** + 形态解析器），`TcpAdapter` 现在按 {@code payload-format} 细分：
 * {@code key-value ⇒ BATCH}、{@code binary/text/number ⇒ PER_ADDRESS}。因此宿主的分流分支、
 * key-value 专用退避表、以及那条绕开框架的「单次多地址直调」路径已**全部删除**，退避由框架
 * {@code SubscriptionBackoffPolicy} 统一承担。详见 {@code docs/SUBSCRIPTION-DOWNSTREAM.md} §三·④。</p>
 *
 * <p><b>为什么订阅必须由宿主发起</b>：框架只负责建链（{@code IotLifecycle.bind} 创建
 * {@link DeviceSession}），采集什么点由宿主决定。故本类在
 * {@link IotProtocolTenantLinkManager#startCollecting} 发出 ADD（框架同步建链）之后被调用。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class AccessSubscriptionPlanner implements SubscriptionPlanner {

    private static final Logger log = LoggerFactory.getLogger(AccessSubscriptionPlanner.class);

    /** 订阅成功计数（框架结果记为已订阅 / 部分成功且全成功时累加）。 */
    private static final String METRIC_SUCCESS = "iot.access.subscribe.success";

    /** 订阅失败计数（本轮结果非全成功时按设备累加一次）。 */
    private static final String METRIC_FAILURE = "iot.access.subscribe.failure";

    /**
     * 在途跳过指标（**保留原名**）：迁移到框架后「在途」由框架维护，但本类仍持有轻量在途表
     * （用于返回值的「本次发起数」口径），故该指标仍按真实语义累加。
     */
    private static final String METRIC_INFLIGHT_SKIPPED = "iot.access.subscribe.inflight.skipped";

    /**
     * 退避跳过指标（**保留原名**）：框架退避时本类不持有退避表，但可从
     * {@link SubscriptionStatus#SKIPPED_BACKOFF} 感知并累加 ⇒ 该指标仍反映真实语义
     * （运维看板「因退避省了多少次远端调用」不断裂）。
     */
    private static final String METRIC_BACKOFF_SKIPPED = "iot.access.subscribe.backoff.skipped";

    private final Supplier<Map<String, DeviceSession>> sessions;

    private final ObjectMapper objectMapper;

    private final AccessReadingSink readingSink;

    private final MeterRegistry meterRegistry;

    private final SubscriptionOrchestrator orchestrator;

    private final BytePayloadDecoderRegistry decoderRegistry;

    /** 时钟（key-value 退避的时间基准；单测注入可推进的假时钟）。 */
    private final Clock clock;

    private final Counter subscribeSuccess;

    private final Counter subscribeFailure;

    private final Counter inFlightSkipped;

    private final Counter backoffSkipped;

    /**
     * **已订阅成功**的「设备 → 会话实例」（语义与迁移前一致：失败不记，下一轮自动重试）。
     *
     * <p>订阅状态本体由框架维护；本表只用于两件事：① {@link #trackedSessionCount()} 观测；
     * ② 判断「同一会话实例是否已订过」——框架对重复 subscribe 会返回 {@code ALREADY_SUBSCRIBED}
     * 而不是「本轮未发起」，而本方法的返回值契约是「本次**发起**的设备数」，故仍需宿主侧判重。</p>
     */
    private final Map<String, DeviceSession> subscribedSessions = new ConcurrentHashMap<>();

    /** 在途中的设备（已发起、完成回调未到）：同一时刻不得重复发起（G1）。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AccessSubscriptionPlanner(Supplier<Map<String, DeviceSession>> sessions,
                                     ObjectMapper objectMapper, AccessReadingSink readingSink,
                                     MeterRegistry meterRegistry,
                                     SubscriptionOrchestrator orchestrator,
                                     BytePayloadDecoderRegistry decoderRegistry,
                                     Clock clock) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.readingSink = readingSink;
        this.meterRegistry = meterRegistry;
        this.orchestrator = orchestrator;
        this.clock = clock;
        this.decoderRegistry = decoderRegistry == null
            ? BytePayloadDecoderRegistry.empty() : decoderRegistry;
        this.subscribeSuccess = meterRegistry.counter(METRIC_SUCCESS);
        this.subscribeFailure = meterRegistry.counter(METRIC_FAILURE);
        this.inFlightSkipped = meterRegistry.counter(METRIC_INFLIGHT_SKIPPED);
        this.backoffSkipped = meterRegistry.counter(METRIC_BACKOFF_SKIPPED);
    }

    /**
     * 为一批已绑定设备发起订阅。
     *
     * @param devices 设备规格
     * @return 本次<b>发起</b>订阅的设备数（完成是异步的；失败不记跟踪，下个租约周期自动对账重试）
     */
    @Override
    public int subscribe(List<DeviceSpec> devices) {
        Map<String, DeviceSession> bound = sessions.get();
        int dispatched = 0;
        for (DeviceSpec device : devices) {
            DeviceSession session = bound.get(device.deviceId());
            if (session == null) {
                // 启动期会话尚未建链或建链失败：本方法每租约周期调用一次，本轮跳过、下轮补上
                log.debug("[access] 设备暂无会话，本轮跳过订阅（下个周期重试）：deviceId={}",
                    device.deviceId());
                continue;
            }
            List<AccessPointMappingDto> points;
            try {
                points = parsePoints(device);
            } catch (RuntimeException ex) {
                // 解析失败属契约/编程错误：暴露，只跳过本设备（不拖垮整轮 tick）
                log.error("[access] 点位清单解析失败（本设备本轮跳过订阅）：deviceId={}",
                    device.deviceId(), ex);
                continue;
            }
            if (points.isEmpty()) {
                log.debug("[access] 设备无点位映射，无需订阅：deviceId={}", device.deviceId());
                continue;
            }
            if (dispatchOne(device, session, points)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /**
     * 为单台设备发起订阅。
     *
     * @param device  设备规格
     * @param session 已绑定会话
     * @param points  点位映射
     * @return 是否真的发起了订阅
     */
    private boolean dispatchOne(DeviceSpec device, DeviceSession session,
                                List<AccessPointMappingDto> points) {
        PointMappingDataListener listener = new PointMappingDataListener(device.deviceId(),
            device.protocol().value(), pollIntervalMs(device), points, readingSink, meterRegistry,
            decoderRegistry);
        String deviceId = device.deviceId();
        if (subscribedSessions.get(deviceId) == session) {
            // 同一会话实例已订阅成功 ⇒ 本轮不重复发起（框架侧会返回 ALREADY_SUBSCRIBED，
            // 但本方法返回的是「本次发起的设备数」，故在宿主侧先判掉）
            return false;
        }
        if (!inFlight.add(deviceId)) {
            inFlightSkipped.increment();
            log.debug("[access] 订阅仍在途，本轮跳过（避免重复订阅）：deviceId={}", deviceId);
            return false;
        }
        try {
            CompletableFuture<SubscriptionResult> stage = orchestrator.subscribe(
                new SubscriptionBatchItem(device, session, toSubscriptionPoints(points), listener))
                .toCompletableFuture();
            // 框架对「跳过型」（重复/退避/无地址/无会话）返回**已完成的 future**，
            // 对真正发起订阅返回 pending（完成回调里才知道结果）。故这里先用「是否已完成」判定
            // 「本轮是否真的发起了订阅」——这正是本方法返回值的对外语义（与迁移前一致）。
            boolean skipOnly = stage.isDone();
            stage.whenComplete((result, error) -> recordOutcome(device.deviceId(), result, error, session));
            if (skipOnly) {
                SubscriptionResult now = stage.getNow(null);
                SubscriptionStatus status = now == null ? null : now.status();
                if (status == SubscriptionStatus.SKIPPED_BACKOFF) {
                    // 框架内部退避：本类不再持有退避表，但保留该指标的语义（观测「省了多少次远端调用」）
                    backoffSkipped.increment();
                }
                return countsAsDispatch(status);
            }
            return true;
        } catch (RuntimeException ex) {
            subscribedFailed(device.deviceId(), ex);
            return false;
        }
    }

    /**
     * 把点位映射翻译成框架点位（{@code word_count} 原样交给框架做地址展开）。
     *
     * @param points 点位映射
     * @return 框架订阅点位
     */
    private static List<SubscriptionPoint> toSubscriptionPoints(List<AccessPointMappingDto> points) {
        List<SubscriptionPoint> result = new ArrayList<>(points.size());
        for (AccessPointMappingDto point : points) {
            int wordCount = point.getWordCount() == null ? 1 : point.getWordCount();
            result.add(SubscriptionPoint.of(point.getAddress(), Math.max(1, wordCount)));
        }
        return result;
    }

    /**
     * 记录一次订阅结果（指标 + 日志）。
     *
     * @param deviceId 设备标识
     * @param result   框架结果（回调带异常时可能为 null）
     * @param error    异常（成功时为 null）
     */
    private void recordOutcome(String deviceId, SubscriptionResult result, Throwable error,
                               DeviceSession session) {
        inFlight.remove(deviceId);
        if (error != null) {
            subscribedFailed(deviceId, error);
            return;
        }
        if (result == null) {
            subscribedFailed(deviceId, new IllegalStateException("订阅回调返回空结果"));
            return;
        }
        SubscriptionStatus status = result.status();
        SubscriptionOutcome outcome = result.outcome();
        int subscribed = outcome == null ? 0 : outcome.subscribed().size();
        int failed = outcome == null ? 0 : outcome.failures().size();
        switch (status) {
            case SUBSCRIBED, ALREADY_SUBSCRIBED -> {
                // 成功后才记跟踪（S5：失败不记 ⇒ 下一轮自动重试）
                subscribedSessions.put(deviceId, session);
                subscribeSuccess.increment();
                log.debug("[access] 订阅完成：deviceId={} status={}", deviceId, status);
            }
            case PARTIAL -> {
                // 部分成功：框架已记成功地址，下一轮只补失败地址（不重复挂监听）。
                // 设备级跟踪**不记**（本轮未全成功）。
                // 指标按**地址级**累加（lead 决策：运营看板口径不能从地址级变设备级，
                // 否则同一故障看起来轻了 N 倍，属静默弱化）；见 docs/METRICS.md。
                subscribeFailure.increment(failed);
                // ⚠️ 地址扩展失败（如 word_count=2 但地址是传统 5 位编号）也走这个分支：
                // 框架把该点位记为 SubscriptionFailure（不订该点位、也**不**退而只订基址），
                // 设备级仍算发起。这条 WARN + failure 计数就是它的可观测出口——
                // 没有它，「发起 1 次但点位永远无数据」会变成新的静默失效。
                log.warn("[access] 订阅部分成功（框架将只补失败地址；含地址扩展失败的点位）："
                    + "deviceId={} subscribed={} failed={}", deviceId, subscribed, failed);
            }
            case NO_ADDRESSES -> log.debug("[access] 设备无地址可订：deviceId={}", deviceId);
            case SKIPPED_NO_SESSION -> log.debug("[access] 订阅跳过（无会话）：deviceId={}", deviceId);
            case SKIPPED_BACKOFF -> log.debug("[access] 订阅跳过（框架退避中）：deviceId={}", deviceId);
            case FAILED -> {
                // 地址级累加（同上）；至少计 1（防御 outcome 为空但状态是 FAILED 的形态）
                subscribeFailure.increment(Math.max(1, failed));
                log.warn("[access] 订阅失败（框架将按设备+会话退避后重试）：deviceId={} failed={}",
                    deviceId, failed);
            }
            default -> {
                subscribeFailure.increment();
                log.warn("[access] 订阅结果未预期：deviceId={} status={}", deviceId, status);
            }
        }
    }

    /**
     * 该状态是否代表「本轮真的发起了订阅」。
     *
     * <p>与迁移前的返回口径一致：只有实际发起过协议层调用的三种状态算「发起」，
     * 跳过型状态（重复/退避/无地址/无会话）一律不算——调用方据本方法的返回值决定是否记
     * 「已发起订阅」日志，若把跳过算成发起，日志会每轮刷「已发起」而实际没动作。</p>
     *
     * @param status 框架状态（可空）
     * @return 算作发起返回 {@code true}
     */
    private static boolean countsAsDispatch(SubscriptionStatus status) {
        return status == SubscriptionStatus.SUBSCRIBED
            || status == SubscriptionStatus.PARTIAL
            || status == SubscriptionStatus.FAILED;
    }

    /** 记一次设备级订阅失败（异常路径）。 */
    private void subscribedFailed(String deviceId, Throwable error) {
        subscribeFailure.increment();
        log.error("[access] 订阅发起失败（框架将按设备+会话退避后重试）：deviceId={}", deviceId, error);
    }

    @Override
    public void forget(String deviceId) {
        subscribedSessions.remove(deviceId);
        inFlight.remove(deviceId);
        // 框架侧状态也要清：否则设备重新出现时会被旧的「已成功地址集 / 退避窗口」挡住
        orchestrator.forget(deviceId);
        log.debug("[access] 设备已移除，清理订阅跟踪：deviceId={}", deviceId);
    }

    @Override
    public Set<String> devicesWithoutSession(List<DeviceSpec> devices) {
        if (devices == null || devices.isEmpty()) {
            return Set.of();
        }
        Map<String, DeviceSession> bound = sessions.get();
        Set<String> missing = new LinkedHashSet<>();
        for (DeviceSpec device : devices) {
            if (bound.get(device.deviceId()) == null) {
                missing.add(device.deviceId());
            }
        }
        return missing;
    }

    /** 已发起过订阅的设备数（观测/自检用；不参与判定）。 */
    public int trackedSessionCount() {
        return subscribedSessions.size();
    }

    private static Integer pollIntervalMs(DeviceSpec device) {
        Duration interval = device.pollInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return null;
        }
        // 上限钳到 Integer.MAX_VALUE：超过 ~24.8 天的周期在协议上无意义，静默溢出成负数才是坑
        return (int) Math.min(interval.toMillis(), Integer.MAX_VALUE);
    }

    private List<AccessPointMappingDto> parsePoints(DeviceSpec device) {
        String json = device.properties().get(HttpDeviceSpecSource.PROPERTY_POINTS);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AccessPointMappingDto>>() { });
        } catch (Exception ex) {
            // 解析失败属契约/编程错误：暴露，不降级为「没有点位」
            throw new IllegalStateException("设备点位清单解析失败：deviceId=" + device.deviceId(), ex);
        }
    }
}
