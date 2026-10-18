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

import cn.ypbin.starter.messaging.sse.SseEmitterManager;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * IoT 实时事件流服务：**业务侧的订阅维度与事件路由**（连接管理/心跳/跨实例扇出由 starter 承担）。
 *
 * <p><b>2026-10-15 能力上收（task-18）</b>：本类原先自建「内存订阅注册表 + 心跳 + 断连清理 + 租户过滤」
 * 共 335 行，与 ypbin-starter 的 SSE 能力（连接管理 {@link SseEmitterManager} + Redis Pub/Sub 多实例扇出
 * + 可配置 audience 维度）重复 ⇒ 按「通用能力反哺、业务仓只留业务」整改为**薄适配**：本类只保留
 * 两件**业务**语义，其余全部委托 starter：</p>
 * <ol>
 *   <li><b>维度映射</b>：租户订阅者挂在 {@code tenant:<租户ID>}，平台订阅者挂在
 *       {@value #AUDIENCE_PLATFORM}（见 {@link #subscribe()}）；</li>
 *   <li><b>事件路由</b>：租户事件定向到该租户 + 平台键；平台事件（{@code tenantId == null}）只定向到平台键
 *       （见 {@link #publish(String, Long, Object)}）。</li>
 * </ol>
 *
 * <p><b>事件契约</b>（SSE 帧：{@code event:<type>} + Jackson 序列化的 data，与改造前**逐字不变**）：</p>
 * <ul>
 *   <li>{@value #EVENT_LATEST}：最新读数（{@link LatestEvent}），在最新值写入成功之后发布；</li>
 *   <li>{@value #EVENT_DEVICE_STATUS}：设备启停变化（{@link DeviceStatusEvent}），在更新成功后发布；</li>
 *   <li>{@value #EVENT_ALERT}：告警确认/静默/收口（{@link AlertEvent}，action 见 {@value #ALERT_ACTION_ACK}
 *       / {@value #ALERT_ACTION_SILENCE} / {@value #ALERT_ACTION_RESOLVE}）；</li>
 *   <li>心跳：由 starter 的 {@link SseEmitterManager} 按 {@code ypbin.sse.heartbeat-interval-seconds}
 *       （IoT 侧配置为 {@value #HEARTBEAT_INTERVAL_MS} 毫秒 = 15 秒）发送 comment 帧保活——
 *       本类**不再自建心跳**（多实例下每个实例各发各的连接，与改造前语义一致）。</li>
 * </ul>
 *
 * <p><b>租户过滤（安全边界，与改造前等价）</b>：订阅时快照租户，来源与 MP 租户插件完全一致——
 * {@link TenantContext} 显式绑定优先，其次 {@link TenantProvider}（生产 HTTP 请求经 IdentityContext 提供）。
 * 两者都无租户时才视为平台订阅者（收全量）。订阅期间租户固定，不随后续请求线程变化。</p>
 *
 * <p><b>⚠️ 为什么平台订阅者用定向 {@code sendToAudience(platform:all)} 而**不是** starter 的
 * {@code broadcast()}</b>：{@code broadcast} 的语义是「推给**本实例上全部连接**」——其中包含所有租户用户，
 * 用它给平台订阅者发全量会让**租户 A 的用户收到租户 B 的事件**，即越权泄漏。因此平台维度必须是一个
 * **保留的 audience 键**，只由平台订阅者挂载；租户事件对平台订阅者的可见性靠「同一事件额外定向到该键」
 * 表达。这样任何租户连接都不可能收到其它租户的事件（隔离性由 key 空间不相交保证，
 * 见 {@code IotRealtimeServiceTest} 的隔离性用例）。</p>
 *
 * <p><b>多实例扇出</b>：本类委托的 {@code sendToAudience} 会同时投本实例连接并经 Redis Pub/Sub 扇出到
 * 其它实例（无 Redis 时退化为纯单实例，行为与改造前一致）。结构化事件载荷（本类的 record）由
 * **starter 4.0.0 的 {@code JacksonSseFanoutCodec}**（协议 id {@code json-v1}）承载——本仓自建的
 * JSON codec 已于 issue #289 第 5 项删除；默认装配退化到 {@code TextSseFanoutCodec} 时 record 会被判
 * 「不支持」而只投本实例（显式 WARN，不静默发垃圾帧），装配前提由 {@code IotSseWiringTest} 守住。</p>
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

    /** 租户 audience 键前缀（完整键形如 {@code tenant:42}）。 */
    public static final String AUDIENCE_TENANT_PREFIX = "tenant:";

    /**
     * 平台订阅者的**保留** audience 键。
     *
     * <p>平台订阅者（订阅时无租户上下文）挂在该键上；租户事件会额外定向到它，从而「平台收全量」。
     * 该键与 {@code tenant:<id>} 空间不相交，故租户连接不可能收到其它租户的事件。</p>
     */
    public static final String AUDIENCE_PLATFORM = "platform:all";

    /** 心跳间隔（毫秒）：15s，低于常见网关 60s 空闲超时。 */
    public static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /** 租户来源（与 MP 租户插件同源：ThreadLocal 优先，其次请求身份/会话）。 */
    private final TenantProvider tenantProvider;

    /** 连接管理/心跳/跨实例扇出的**唯一**承担者（starter 通用能力，本类不自建）。 */
    private final SseEmitterManager sseEmitterManager;

    public IotRealtimeService(TenantProvider tenantProvider, SseEmitterManager sseEmitterManager) {
        this.tenantProvider = tenantProvider;
        this.sseEmitterManager = sseEmitterManager;
    }

    /**
     * 建立一条 SSE 订阅（由 Controller 调用；租户取订阅线程的 {@link TenantContext} 快照，
     * 其次 {@link TenantProvider}，与 MP 租户插件同源）。
     *
     * @return SSE 发射器（超时由 {@code ypbin.sse.timeout} 控制，默认 0 = 不主动断连，靠心跳保活与客户端重连）
     */
    public SseEmitter subscribe() {
        return sseEmitterManager.connectAudience(audienceOf(currentTenantId()));
    }

    /**
     * 发布一条事件：只推给同租户订阅者；平台订阅者（无租户上下文时建立）收全量。
     *
     * <p>发布发生在业务写入（含事务提交后回调）路径上，实时流是旁路：starter 的发送失败只摘除该连接
     * 并记 debug 日志，不会反向拖垮业务写入。</p>
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
        if (tenantId != null) {
            sseEmitterManager.sendToAudience(audienceOf(tenantId), type, data);
        }
        // 平台订阅者收全量：定向到保留键（**不能**用 broadcast——那会连同租户连接一起投，造成跨租户泄漏）
        sseEmitterManager.sendToAudience(AUDIENCE_PLATFORM, type, data);
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
     * 租户 → audience 键（{@code tenantId == null} ⇒ 平台保留键）。
     *
     * @param tenantId 租户 ID（可空 = 平台订阅者）
     * @return audience 键
     */
    static String audienceOf(Long tenantId) {
        return tenantId == null ? AUDIENCE_PLATFORM : AUDIENCE_TENANT_PREFIX + tenantId;
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
