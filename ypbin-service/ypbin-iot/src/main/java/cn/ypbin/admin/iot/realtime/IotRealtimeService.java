/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.realtime;

import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * IoT 实时事件流服务：按租户扇出 SSE 事件、心跳保活与断连清理。
 *
 * <p><b>事件契约</b>（SSE 帧：{@code event:<type>} + Jackson 序列化的 data）：</p>
 * <ul>
 *   <li>{@value #EVENT_LATEST}：最新读数（{@link LatestEvent}），在最新值写入成功之后发布；</li>
 *   <li>{@value #EVENT_DEVICE_STATUS}：设备启停变化（{@link DeviceStatusEvent}），在更新成功后发布；</li>
 *   <li>{@value #EVENT_ALERT}：告警确认/静默/收口（{@link AlertEvent}，action 见 {@value #ALERT_ACTION_ACK}
 *       / {@value #ALERT_ACTION_SILENCE} / {@value #ALERT_ACTION_RESOLVE}）；</li>
 *   <li>心跳：固定间隔发送 SSE comment 帧（{@value #HEARTBEAT_COMMENT}），只保活、不产生业务事件。</li>
 * </ul>
 *
 * <p><b>租户过滤</b>：订阅时快照租户，来源与 MP 租户插件完全一致——{@link TenantContext} 显式绑定优先，
 * 其次 {@link TenantProvider}（生产 HTTP 请求经 IdentityContext 提供；后台任务用前者显式绑定）。
 * 两者都无租户时才视为平台订阅者（收全量）。{@link #publish(String, Long, Object)} 只把事件推给
 * 「订阅租户 == 事件租户」的订阅者；订阅期间租户固定，不随后续请求线程变化（SSE 连接跨多个请求线程，
 * 不能每次发送再取上下文）。</p>
 *
 * <p><b>多实例局限（本期未解决，属已知限制）</b>：订阅注册表在单 JVM 内存中。多实例部署时，
 * 连到实例 A 的订阅只能收到在 A 上发布的事件；跨实例扇出需要 Redis Pub/Sub（或网关按租户
 * sticky session/路由）。因此本能力适合单实例或「能接受事件不跨实例」的部署形态，不承诺
 * 多副本下的事件可达性。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
@Service
public class IotRealtimeService {

    /** 事件名：最新读数。 */
    public static final String EVENT_LATEST = "latest";

    /** 事件名：设备启停状态变化。 */
    public static final String EVENT_DEVICE_STATUS = "device-status";

    /** 事件名：告警确认/静默/收口。 */
    public static final String EVENT_ALERT = "alert";

    /** 告警事件动作：确认。 */
    public static final String ALERT_ACTION_ACK = "ack";

    /** 告警事件动作：静默。 */
    public static final String ALERT_ACTION_SILENCE = "silence";

    /** 告警事件动作：按规则收口。 */
    public static final String ALERT_ACTION_RESOLVE = "resolve";

    /** 心跳 comment 文本（comment 帧不会触发客户端 onmessage）。 */
    public static final String HEARTBEAT_COMMENT = "heartbeat";

    /** 心跳间隔（毫秒）：15s，低于常见网关 60s 空闲超时。 */
    public static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private static final Logger log = LoggerFactory.getLogger(IotRealtimeService.class);

    /** 租户来源（与 MP 租户插件同源：ThreadLocal 优先，其次请求身份/会话）。 */
    private final TenantProvider tenantProvider;

    /**
     * 订阅注册表。元素在订阅时记录租户（{@code null} = 平台订阅者，收全量）；
     * 只增删、不修改元素，故用 {@link CopyOnWriteArrayList} 保证并发发布/订阅/断连安全。
     */
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public IotRealtimeService(TenantProvider tenantProvider) {
        this.tenantProvider = tenantProvider;
    }

    /**
     * 建立一条 SSE 订阅（由 Controller 调用；租户取订阅线程的 {@link TenantContext} 快照，
     * 其次 {@link TenantProvider}，与 MP 租户插件同源）。
     *
     * @return SSE 发射器（超时 0 = 不主动断连，由心跳保活与客户端重连兜底）
     */
    public SseEmitter subscribe() {
        Long tenantId = currentTenantId();
        SseEmitter emitter = createEmitter();
        Subscription subscription = new Subscription(tenantId, emitter);
        subscriptions.add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ex -> {
            remove(subscription);
            log.debug("[iot] SSE 连接异常，已移除订阅：tenantId={}",
                LogSanitizer.sanitize(tenantId), ex);
        });
        log.debug("[iot] SSE 新订阅：tenantId={}（null=平台订阅者）", LogSanitizer.sanitize(tenantId));
        return emitter;
    }

    /**
     * 发布一条事件：只推给同租户订阅者；平台订阅者（无租户上下文时建立）收全量。
     *
     * <p>单个订阅发送失败视为该连接已断，立即摘除并记日志；不影响其它订阅者，也不抛出——
     * 发布发生在业务写入（含事务提交后回调）路径上，实时流是旁路，不能反向拖垮业务写入。</p>
     *
     * @param type     事件名（见 {@code EVENT_*} 常量）
     * @param tenantId 事件所属租户；{@code null} 只对平台订阅者可见
     * @param data     事件负载（Jackson 序列化；不可为 {@code null}）
     */
    public void publish(String type, Long tenantId, Object data) {
        if (type == null || type.isBlank() || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0
            || data == null) {
            throw new IllegalArgumentException("SSE 事件的 type（不得含换行）与 data 不能为空");
        }
        for (Subscription subscription : subscriptions) {
            if (!accepts(subscription.tenantId(), tenantId)) {
                continue;
            }
            try {
                subscription.emitter().send(SseEmitter.event().name(type).data(data));
            } catch (IOException | RuntimeException ex) {
                remove(subscription);
                log.warn("[iot] SSE 事件发送失败，已移除订阅：type={} tenantId={}",
                    type, LogSanitizer.sanitize(tenantId), ex);
            }
        }
    }

    /**
     * 按**当前请求租户**发布（与 MP 租户插件同源：{@link TenantContext} 显式绑定优先，
     * 其次 {@link TenantProvider}）。
     *
     * <p>由 HTTP 请求触发的发布（如告警确认/静默/收口）必须走它：生产请求的 ThreadLocal 通常为空、
     * 租户在 IdentityContext；调用方若各自取 {@code TenantContext}，会把租户事件误发成平台事件，
     * 同租户订阅者收不到、平台订阅者却看得到。</p>
     *
     * @param type 事件名（见 {@code EVENT_*} 常量）
     * @param data 事件负载
     */
    public void publishToCurrentTenant(String type, Object data) {
        publish(type, currentTenantId(), data);
    }

    /**
     * 事务提交后再发布：避免「事务回滚但客户端已收到事件」的瞬时错误。
     *
     * <p>没有活动事务时立即发布（与 {@link #publish} 等价）。</p>
     *
     * @param type     事件名
     * @param tenantId 事件所属租户
     * @param data     事件负载
     */
    public void publishAfterCommit(String type, Long tenantId, Object data) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(type, tenantId, data);
                }
            });
            return;
        }
        publish(type, tenantId, data);
    }

    /**
     * 按当前请求租户在事务提交后发布（HTTP 请求入口优先用这个）。
     *
     * @param type 事件名
     * @param data 事件负载
     */
    public void publishToCurrentTenantAfterCommit(String type, Object data) {
        publishAfterCommit(type, currentTenantId(), data);
    }

    /**
     * 心跳：固定 15s 给所有订阅发 comment 帧，防止网关/代理按空闲连接断开；发送失败即视为断连并摘除注册项。
     *
     * <p>刻意不加分布式锁：心跳维护的是**本实例内存注册表**里的连接，多实例时每个实例都必须各发各的；
     * 加锁让「抢到锁的实例发心跳」只会让其它实例的连接被饿死。</p>
     */
    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
            } catch (IOException | RuntimeException ex) {
                remove(subscription);
                log.debug("[iot] SSE 心跳发送失败，已移除订阅：tenantId={}",
                    LogSanitizer.sanitize(subscription.tenantId()), ex);
            }
        }
    }

    /**
     * 当前订阅线程的租户：与 MP 租户插件同源（{@link TenantContext} 优先，其次 {@link TenantProvider}）。
     *
     * <p>不能只看 {@code TenantContext}：生产 HTTP 请求的租户由 {@code TenantProvider}（IdentityContext）
     * 提供，ThreadLocal 通常为空——只看前者会把所有租户订阅者误判成平台订阅者，造成跨租户推送。</p>
     *
     * @return 租户 ID；两者都无时返回 {@code null}（平台订阅者）
     */
    private Long currentTenantId() {
        return TenantContext.getTenantId().or(tenantProvider::getCurrentTenantId).orElse(null);
    }

    /** 订阅是否接收某租户的事件：平台订阅者收全量，租户订阅者只收同租户。 */
    private static boolean accepts(Long subscriberTenantId, Long eventTenantId) {
        return subscriberTenantId == null || subscriberTenantId.equals(eventTenantId);
    }

    /** 摘除订阅（幂等：重复回调不会重复计数）。 */
    private void remove(Subscription subscription) {
        if (subscriptions.remove(subscription)) {
            log.debug("[iot] SSE 订阅已移除：tenantId={}",
                LogSanitizer.sanitize(subscription.tenantId()));
        }
    }

    /**
     * 创建发射器（包内可见：纯单测覆写它捕获发送帧，不启真实 HTTP）。
     *
     * <p>使用超时 {@code 0L}（不主动超时断连）：SSE 长连接靠心跳保活 + 客户端断线重连，
     * 不依赖 Servlet 异步超时来回收。</p>
     */
    SseEmitter createEmitter() {
        return new SseEmitter(0L);
    }

    /** 当前订阅总数（单测断言用）。 */
    int subscriptionCount() {
        return subscriptions.size();
    }

    /** 指定租户的订阅数（{@code tenantId=null} 统计平台订阅者；单测断言用）。 */
    int subscriptionCount(Long tenantId) {
        int count = 0;
        for (Subscription subscription : subscriptions) {
            if (Objects.equals(subscription.tenantId(), tenantId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 一条订阅（订阅时刻的租户快照 + 发射器）。
     *
     * @param tenantId 订阅租户；{@code null}=平台订阅者（收全量）
     * @param emitter  SSE 发射器
     * @author wenbin
     * @since 2026-10-14
     */
    private record Subscription(Long tenantId, SseEmitter emitter) {
    }

    /**
     * {@value #EVENT_LATEST} 事件负载。
     *
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     * @param value      读数原值（字符串化）
     * @param quality    质量码
     * @param ts         读数时刻（epoch 毫秒）
     * @author wenbin
     * @since 2026-10-14
     */
    public record LatestEvent(Long deviceId, String propertyId, String value, String quality, long ts) {
    }

    /**
     * {@value #EVENT_DEVICE_STATUS} 事件负载。
     *
     * @param deviceId 设备 ID
     * @param status   启停状态：1 启用、0 停用
     * @author wenbin
     * @since 2026-10-14
     */
    public record DeviceStatusEvent(Long deviceId, Integer status) {
    }

    /**
     * {@value #EVENT_DEVICE_STATUS} 批量事件负载。
     *
     * @param deviceIds 设备 ID 列表（已去重）
     * @param status    启停状态：1 启用、0 停用
     * @author wenbin
     * @since 2026-10-15
     */
    public record DeviceStatusBatchEvent(List<Long> deviceIds, Integer status) {
    }

    /**
     * {@value #EVENT_DEVICE_STATUS} 批量删除事件负载。
     *
     * @param deviceIds 被删除的设备 ID 列表（已去重）
     * @author wenbin
     * @since 2026-10-15
     */
    public record DeviceDeletedBatchEvent(List<Long> deviceIds) {
    }

    /**
     * {@value #EVENT_ALERT} 事件负载。
     *
     * @param action      动作：{@value #ALERT_ACTION_ACK} / {@value #ALERT_ACTION_SILENCE} /
     *                    {@value #ALERT_ACTION_RESOLVE}
     * @param instanceIds 涉及的告警实例 ID（收口时为 {@code null}）
     * @param ruleIds     涉及的规则 ID（确认/静默时为 {@code null}）
     * @param count       本次生效条数
     * @param minutes     静默分钟数（仅静默动作有值）
     * @author wenbin
     * @since 2026-10-14
     */
    public record AlertEvent(String action, List<Long> instanceIds, List<Long> ruleIds, Integer count,
                             Integer minutes) {
    }
}
