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
import cn.ypbin.admin.iot.enums.IotProtocol;
import cn.ypbin.admin.iot.modbus.ModbusRegisterAddress;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅规划器：把「点位映射」变成协议栈的订阅请求（3b-2 的功能核心）。
 *
 * <p><b>为什么订阅必须由宿主动发起</b>（对框架源码的核实结论）：框架只负责建链
 * （{@code IotLifecycle.bind} 创建 {@link DeviceSession}），**主源码里没有任何地方构造
 * SubscribeRequest 或调用 subscribe** —— 采集什么点由宿主决定。因此本类在
 * {@link IotProtocolTenantLinkManager#startCollecting} 发出 ADD（框架同步建链）之后被调用。</p>
 *
 * <p>设备级周期（{@code DeviceSpec.pollInterval}）作为订阅采样周期；为 0 时用框架默认值。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class AccessSubscriptionPlanner implements SubscriptionPlanner {

    private static final Logger log = LoggerFactory.getLogger(AccessSubscriptionPlanner.class);

    /** 订阅失败后的退避起点。 */
    static final Duration SUBSCRIBE_BACKOFF_BASE = Duration.ofSeconds(30);

    /** 订阅失败退避上限。 */
    static final Duration SUBSCRIBE_BACKOFF_MAX = Duration.ofMinutes(2);

    private final Supplier<Map<String, DeviceSession>> sessions;
    private final ObjectMapper objectMapper;
    private final AccessReadingSink readingSink;

    private final MeterRegistry meterRegistry;

    /**
     * 已订阅的**会话实例**（按 deviceId）。会话实例变了（框架重连会新建会话）就必须重订阅，
     * 否则表现为「链路恢复但数据不再上报」——这是框架不给宿主发重连事件时的唯一可靠判据。
     */
    private final Map<String, DeviceSession> subscribedSessions = new ConcurrentHashMap<>();

    /**
     * 在途订阅（G1）：{@code session.subscribe(...)} 是**异步**的，慢订阅（&gt; 一个租约周期）会让下一轮
     * 对账再发起一次 ⇒ 同一设备重复订阅、监听器重复挂、远端压力翻倍。这里按设备去重：完成（无论成败）才移除。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * TCP 逐点位单订阅状态（UP-4）：一设备多次单地址订阅 ⇒ 需要按地址记住「哪些已成功、哪些在途」。
     * 设备→会话早退（{@link #subscribedSessions}）仍按设备记一次；这里只处理「同一会话内的多订阅」。
     */
    private final Map<String, TcpSubscriptionState> tcpStates = new ConcurrentHashMap<>();

    /** 订阅失败退避（G1）：失败设备不再每个周期重发，而是退避后重试（成功即清除）。 */
    private final Map<String, SubscriptionBackoff> failureBackoff = new ConcurrentHashMap<>();

    private final Clock clock;

    /**
     * 是否处于 TCP 的 {@code payload-format=key-value} 形态（由装配层从 SDK 的 {@code TcpProperties}
     * 解析后传入，见 {@code AccessIotProtocolConfiguration}）。
     *
     * <p>key-value 允许**一次订阅多地址**（SDK {@code TcpValueSelector} 对每个 selector 各产出一个
     * {@code PointValue}）；其它形态（binary/text/number）仍是「单点位流」，必须逐点位单订阅。
     * 用配置形态分流（而不是新增开关）的收益：<b>回退配置即回退订阅形态</b>，天然是滚动回退手段。</p>
     */
    private final boolean tcpKeyValuePayloadFormat;

    /** 订阅**成功**计数（会话建立后真正订阅成功的设备数）。 */
    private final Counter subscribeSuccess;

    /**
     * 订阅**失败**计数（失败不写跟踪表 ⇒ 退避后会对账重试；这里让它可观测）。
     *
     * <p>粒度 = 单次 {@code session.subscribe} 调用：<b>一次多地址订阅</b>（key-value TCP 与非 TCP）
     * 一设备一次；<b>逐点位单订阅</b>（非 key-value TCP）下一设备一轮最多可计 N 次（每个失败地址各一次），
     * 因此「N 里成功 M 个」在该形态下表现为 N-M 次失败。</p>
     */
    private final Counter subscribeFailure;

    /** 因「在途订阅」跳过的次数（观测重复订阅被挡下多少）。 */
    private final Counter inFlightSkipped;

    /** 因「失败退避」跳过的次数。 */
    private final Counter backoffSkipped;

    /**
     * TCP **逐点位单订阅**累计发起的单地址订阅次数（仅非 key-value 形态；观测一设备多订阅的规模）。
     *
     * <p>key-value 形态走一次多地址订阅，本计数**不增长**——测试用它区分「1 次多地址」与「N 次单地址」。</p>
     */
    private final AtomicInteger startedTcpAddressCount = new AtomicInteger();

    public AccessSubscriptionPlanner(Supplier<Map<String, DeviceSession>> sessions,
                                     ObjectMapper objectMapper, AccessReadingSink readingSink,
                                     MeterRegistry meterRegistry, Clock clock,
                                     boolean tcpKeyValuePayloadFormat) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.readingSink = readingSink;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.tcpKeyValuePayloadFormat = tcpKeyValuePayloadFormat;
        // 指标前缀统一为 `iot.access.*`（与既有的 `iot.access.lease.*` 一致；
        // S5 引入时用的 `ypbin.access.*` 是同一批 access 指标，混用会让大盘上出现两套前缀）
        this.subscribeSuccess = meterRegistry.counter("iot.access.subscribe.success");
        this.subscribeFailure = meterRegistry.counter("iot.access.subscribe.failure");
        this.inFlightSkipped = meterRegistry.counter("iot.access.subscribe.inflight.skipped");
        this.backoffSkipped = meterRegistry.counter("iot.access.subscribe.backoff.skipped");
    }

    /**
     * 为一批已绑定设备发起订阅（幂等：同一会话实例只会订阅一次）。
     *
     * @param devices 设备规格
     * @return 本次**发起**订阅的设备数（注意：订阅完成是异步的，成功与否看计数与日志；失败不会记录跟踪，
     *     下一个租约周期会对账重试）
     */
    @Override
    public int subscribe(List<DeviceSpec> devices) {
        Map<String, DeviceSession> bound = sessions.get();
        int subscribed = 0;
        for (DeviceSpec device : devices) {
            DeviceSession session = bound.get(device.deviceId());
            if (session == null) {
                // 启动期会话尚未建链（ApplicationRunner 早于 ApplicationReadyEvent）或建链失败：
                // 本方法由每个租约周期重复调用，因此这里只是「本轮跳过」，下一轮会补上
                log.debug("[access] 设备暂无会话，本轮跳过订阅（下个周期重试）：deviceId={}",
                    device.deviceId());
                continue;
            }
            if (subscribedSessions.get(device.deviceId()) == session) {
                continue;
            }
            if (inFlight.contains(device.deviceId())) {
                // G1：上一轮发起的订阅还没完成（异步），本轮不得重复发起
                inFlightSkipped.increment();
                log.debug("[access] 订阅仍在途，本轮跳过（避免重复订阅）：deviceId={}",
                    device.deviceId());
                continue;
            }
            if (inFailureBackoff(device.deviceId(), session)) {
                backoffSkipped.increment();
                log.debug("[access] 订阅失败退避中，本轮跳过：deviceId={}", device.deviceId());
                continue;
            }
            List<AccessPointMappingDto> points = parsePoints(device);
            if (points.isEmpty()) {
                log.debug("[access] 设备无点位映射，无需订阅：deviceId={}", device.deviceId());
                continue;
            }
            List<PointAddress> addresses;
            try {
                addresses = expandAddresses(device, points);
            } catch (RuntimeException ex) {
                // 映射校验应已拦住（word_count=2 必须是显式 type:offset）；直连 DB 的坏数据在这里暴露，
                // 且只跳过本设备、不拖垮整轮 tick（其它设备照常订阅）
                log.error("[access] 点位地址扩展失败（本设备本轮跳过订阅；请检查 word_count=2 的 raw_address）："
                    + "deviceId={}", device.deviceId(), ex);
                continue;
            }
            if (requiresPerAddressSubscribe(device)) {
                // 非 key-value 的 TCP 是「单点位流」：TcpSession#subscribe 对 >1 地址 fail-fast
                // （TcpAdapter.MSG_MULTI_ADDRESS_UNSUPPORTED），必须逐点位单订阅（UP-4 契约）。
                // key-value 形态不会进这里（它走下面的一次多地址订阅）。
                if (subscribeTcpPerAddress(device, session, points, addresses)) {
                    subscribed++;
                }
                continue;
            }
            // key-value TCP 与非 TCP（含 Modbus）：一次多地址订阅。
            // 按 raw 去重是必需的：key-value 下 SDK 对每个地址条目各产出一个 PointValue；Modbus
            // word_count=2 扩出的 base+1 也可能与其它点位的地址重叠 ⇒ 不去重会重复读数
            // （listener 的 registerByAddress 是后者覆盖）。
            List<PointAddress> subscribeAddresses = distinctByRaw(addresses);
            SubscribeRequest request = device.pollInterval().isZero()
                ? SubscribeRequest.of(subscribeAddresses)
                : new SubscribeRequest(subscribeAddresses, device.pollInterval(), device.pollInterval(),
                    null, Map.of());
            PointMappingDataListener listener = new PointMappingDataListener(device.deviceId(),
                device.protocol().value(), pollIntervalMs(device), points, readingSink, meterRegistry);
            // ⚠️ S5 修复：**订阅成功之后**才记录跟踪。
            //    此前是先写 `subscribedSessions` 再 subscribe ⇒ 异步失败时跟踪表已记上「已订阅」，
            //    对账会认为无需重试 ⇒ 该设备**永久停止采集**且只有一行 ERROR 日志。
            //    现在失败不写 ⇒ 下一个租约周期自动重试。
            inFlight.add(device.deviceId());
            try {
                startSubscribe(session, request, listener, device, subscribeAddresses);
            } catch (RuntimeException ex) {
                // ⚠️ 同步抛出（适配器契约并不禁止）：必须立刻摘掉在途标记，否则该设备**永久无法订阅**
                //    ——注释自己说了「不移除比重复订阅更糟」，那就不能只防异步失败这条路径。
                inFlight.remove(device.deviceId());
                subscribeFailure.increment();
                armFailureBackoff(device.deviceId(), session);
                log.error("[access] 订阅调用同步抛出异常（退避后重试）：deviceId={} addresses={}",
                    device.deviceId(), addresses.size(), ex);
                continue;
            }
            // 返回值是「本次**发起**的订阅数」：完成与否是异步的，调用方不得据此判断成功
            subscribed++;
        }
        return subscribed;
    }

    /**
     * 发起订阅并挂完成回调（回调里维护跟踪/退避/在途状态）。
     *
     * @param session   会话
     * @param request   订阅请求
     * @param listener  数据监听器
     * @param device    设备规格
     * @param addresses 点位地址（日志用）
     */
    private void startSubscribe(DeviceSession session, SubscribeRequest request,
                                PointMappingDataListener listener, DeviceSpec device,
                                List<PointAddress> addresses) {
        session.subscribe(request, listener).whenComplete((handle, error) -> {
            // 无论成败都要摘掉「在途」标记，否则该设备再也不会被订阅（比重复订阅更糟）
            inFlight.remove(device.deviceId());
            if (error != null) {
                subscribeFailure.increment();
                armFailureBackoff(device.deviceId(), session);
                // 订阅失败要暴露：否则表现为「采了但没数据」
                log.error("[access] 订阅失败（退避后重试）：deviceId={} addresses={} 下次重试={}秒后",
                    device.deviceId(), addresses.size(),
                    currentBackoffSeconds(device.deviceId()), error);
            } else {
                failureBackoff.remove(device.deviceId());
                subscribedSessions.put(device.deviceId(), session);
                subscribeSuccess.increment();
                log.info("[access] 订阅成功：deviceId={} 点位数={}", device.deviceId(),
                    addresses.size());
            }
        });
    }

    /** 是否 TCP 透传（协议码是协议的唯一身份，{@code ProtocolCode}，不会误判）。 */
    private static boolean isTcp(DeviceSpec device) {
        return IotProtocol.TCP.getCode().equals(device.protocol().value());
    }

    /**
     * 该设备是否**必须**走「逐点位单订阅」（而非一次多地址订阅）。
     *
     * <p>判据 = TCP 透传 **且** {@code payload-format} 不是 {@code key-value}：</p>
     * <ul>
     *   <li><b>非 key-value 的 TCP</b> 是「单点位流」：裸 TCP 帧没有寻址语义，
     *       {@code TcpSession#subscribe} 对 {@code addresses().size() > 1} 直接 fail-fast
     *       （{@code iot.tcp.subscribe.multi-address-unsupported}）；</li>
     *   <li><b>key-value</b>（starter 0.3.0 的 valueSelector）允许一次订阅多地址、对每个 selector
     *       各产出一个 {@code PointValue} ⇒ 走一次多地址订阅（N 点位从 N 次订阅降为 1 次，
     *       同一帧只解析/派发一次）；</li>
     *   <li><b>非 TCP</b>（Modbus/OPC UA 等）本就支持多地址，不受本判据影响。</li>
     * </ul>
     *
     * <p><b>为什么按配置形态而不是新增开关</b>：把 {@code payload-format} 改回
     * binary/text/number 时订阅自动回到逐点位单订阅，无需第二个开关，也天然是滚动回退手段。
     * <b>配置不可得</b>（TCP 未启用、{@code TcpProperties} bean 缺失）时按「非 key-value」处理
     * ⇒ 保持今天的行为。</p>
     *
     * <p>为什么不靠 {@code ProtocolCapability}：框架能力枚举里 TCP 与 MQTT 都用
     * {@code SUBSCRIBE_STREAM}，没有「单点位/多地址」这一档（框架 issue #15）；key-value 的
     * 多地址能力由 {@code payload-format} 表达，故按「协议码 + 配置形态」判断。</p>
     *
     * @param device 设备规格
     * @return 必须逐点位单订阅返回 {@code true}
     */
    private boolean requiresPerAddressSubscribe(DeviceSpec device) {
        return isTcp(device) && !tcpKeyValuePayloadFormat;
    }

    /**
     * 展开订阅地址：Modbus {@code word_count=2} 的点位额外订阅 {@code base+1}（同 {@code address_type}）。
     *
     * <p>{@code raw_address} 仍是**基址**；扩出的地址让 SDK 在一次读里同时取回两个字（相邻地址会被 SDK
     * 合并成一次请求、逐地址回调），宿主再按点位配对。{@code word_count=1} 与非 Modbus 与今天完全一致。</p>
     *
     * @param device 设备规格（取协议码）
     * @param points 点位映射
     * @return 订阅地址（可能含 base+1）
     * @throws IllegalArgumentException word_count=2 的地址不是显式 type:offset 或越界
     */
    private static List<PointAddress> expandAddresses(DeviceSpec device, List<AccessPointMappingDto> points) {
        boolean modbus = IotProtocol.MODBUS.getCode().equals(device.protocol().value());
        List<PointAddress> addresses = new ArrayList<>(points.size());
        for (AccessPointMappingDto point : points) {
            addresses.add(PointAddress.of(point.getAddress()));
            if (modbus && point.getWordCount() != null && point.getWordCount() == 2) {
                addresses.add(PointAddress.of(ModbusRegisterAddress.next(point.getAddress())));
            }
        }
        return addresses;
    }

    /**
     * 按 {@code raw} 去重并保持首次出现顺序。
     *
     * <p>为什么按 raw 而不是 {@link PointAddress} 相等：同一 raw 可能因点位映射行不同而出现多次，
     * 逐点位路径的 {@code distinct} 也是按 raw；key-value 下 SDK 对每个地址条目各产出一个
     * {@code PointValue}，不去重会产生重复读数（listener 的 {@code pointByAddress} 是后者覆盖）。</p>
     *
     * @param addresses 原始地址列表
     * @return 去重后的地址列表
     */
    private static List<PointAddress> distinctByRaw(List<PointAddress> addresses) {
        Map<String, PointAddress> byRaw = new LinkedHashMap<>();
        for (PointAddress address : addresses) {
            byRaw.putIfAbsent(address.raw(), address);
        }
        return List.copyOf(byRaw.values());
    }

    /**
     * TCP 透传的**逐点位单订阅**（UP-4 契约；**仅非 key-value 形态**走这里）。
     *
     * <p><b>什么时候走这里</b>：{@code requiresPerAddressSubscribe(device)} 为真，即 TCP 且
     * {@code payload-format} 不是 {@code key-value}。裸 TCP 帧没有寻址语义，
     * {@code TcpSession#subscribe} 对 {@code addresses().size() > 1} 直接 fail-fast
     * （{@code iot.tcp.subscribe.multi-address-unsupported}），所以必须逐点位单订阅。
     * <b>key-value 形态不走这里</b>：它允许一次多地址订阅（SDK valueSelector 逐个 selector 产出），
     * 见 {@link #requiresPerAddressSubscribe(DeviceSpec)} 与 {@code docs/VALUE-DECODE-DESIGN.md} §5.5。</p>
     *
     * <p><b>逐点位单订阅的语义</b>：{@code TcpSession.subscribe} 每次订阅都会新建
     * {@code TcpSubscription} 并向链路注册**独立的帧消费者**，帧内的 address 用该次订阅的地址
     * （{@code addresses().get(0)}）。因此 N 个单地址订阅 ⇒ 同一帧被派发 N 次，各自带自己的
     * {@code raw_address}；它们共享同一个 {@link PointMappingDataListener}（其 {@code pointByAddress}
     * 含全部点位），于是每个点位都能按 {@code raw_address} 取到自己的值。帧键选择由 starter
     * 0.3.0 的 {@code TcpValueSelector} 承担，宿主不再做帧解析。</p>
     *
     * <p><b>语义保持</b>：① 成功后才计入设备跟踪（S5）；② 在途去重按「设备+地址」，部分成功后重试
     * 只补失败地址，**不会**给已成功地址重复挂监听（否则同一点位会出重复读数）；③ 失败仍走指数退避
     * 重试；④ 未映射/孤儿映射丢弃语义不变（在 listener 内，与订阅方式正交）。</p>
     *
     * @param device    设备规格
     * @param session   已绑定的会话
     * @param points    点位映射（共享给单个 listener）
     * @param addresses 点位地址（与 points 一一对应，可能含重复 raw）
     * @return 本轮是否**发起**了至少一次订阅（供上层计数）
     */
    private boolean subscribeTcpPerAddress(DeviceSpec device, DeviceSession session,
                                           List<AccessPointMappingDto> points,
                                           List<PointAddress> addresses) {
        String deviceId = device.deviceId();
        if (inFailureBackoff(deviceId, session)) {
            backoffSkipped.increment();
            log.debug("[access] 订阅失败退避中，本轮跳过：deviceId={}", deviceId);
            return false;
        }
        // 去重：同一 raw 地址出现多次没有意义（listener 的 pointByAddress 也是后者覆盖）
        List<String> distinct = addresses.stream().map(PointAddress::raw).distinct().toList();
        TcpSubscriptionState state = tcpStates.compute(deviceId, (id, existing) ->
            existing == null || existing.session() != session
                ? new TcpSubscriptionState(session) : existing);
        if (state.succeeded().size() >= distinct.size()) {
            // 全部地址已成功（正常由完成回调写入跟踪；这里兜底幂等）
            subscribedSessions.put(deviceId, session);
            return false;
        }
        // 只补「未成功且未在途」的地址：部分成功后不重发已成功地址，避免重复读数
        List<PointAddress> pending = new ArrayList<>();
        for (String raw : distinct) {
            if (!state.succeeded().contains(raw) && state.inFlight().add(raw)) {
                pending.add(PointAddress.of(raw));
            }
        }
        if (pending.isEmpty()) {
            inFlightSkipped.increment();
            log.debug("[access] TCP 订阅仍在途，本轮跳过（避免重复订阅）：deviceId={} 待发起=0",
                deviceId);
            return false;
        }
        // 新一轮失败退避只在「本轮第一次失败」时记一次：一设备一轮 N 个地址一起失败不应把
        // 退避 attempts 放大 N 倍（否则第一轮失败就直接冲到退避上限）
        state.failureArmedThisRound().set(false);
        PointMappingDataListener listener = new PointMappingDataListener(deviceId,
            device.protocol().value(), pollIntervalMs(device), points, readingSink, meterRegistry);
        boolean initiated = false;
        for (PointAddress address : pending) {
            String raw = address.raw();
            SubscribeRequest request = device.pollInterval().isZero()
                ? SubscribeRequest.of(List.of(address))
                : new SubscribeRequest(List.of(address), device.pollInterval(),
                    device.pollInterval(), null, Map.of());
            try {
                startedTcpAddressCount.incrementAndGet();
                session.subscribe(request, listener).whenComplete((handle, error) ->
                    onTcpAddressComplete(device, session, state, distinct, raw, error));
                initiated = true;
            } catch (RuntimeException ex) {
                // 同步抛出（适配器契约并不禁止）：摘掉在途标记，否则该地址永久无法订阅
                state.inFlight().remove(raw);
                subscribeFailure.increment();
                if (state.failureArmedThisRound().compareAndSet(false, true)) {
                    armFailureBackoff(deviceId, session);
                }
                log.error("[access] TCP 单地址订阅同步抛出（退避后重试）：deviceId={} address={}",
                    deviceId, raw, ex);
            }
        }
        return initiated;
    }

    /**
     * TCP 单地址订阅的完成回调：维护成功集 / 在途集 / 退避 / 设备级跟踪。
     *
     * @param device    设备规格
     * @param session   会话
     * @param state     本设备的 TCP 订阅状态
     * @param allRaw    该设备全部去重后的地址（判「是否已全部订阅成功」）
     * @param raw       本次完成的地址
     * @param error     失败原因（成功为 {@code null}）
     */
    private void onTcpAddressComplete(DeviceSpec device, DeviceSession session,
                                      TcpSubscriptionState state, List<String> allRaw, String raw,
                                      Throwable error) {
        // 无论成败都摘在途标记，否则该地址再也不会被订阅（比重复订阅更糟）
        state.inFlight().remove(raw);
        String deviceId = device.deviceId();
        if (error != null) {
            subscribeFailure.increment();
            if (state.failureArmedThisRound().compareAndSet(false, true)) {
                armFailureBackoff(deviceId, session);
            }
            log.error("[access] TCP 单地址订阅失败（退避后重试）：deviceId={} address={} 下次重试={}秒后",
                deviceId, raw, currentBackoffSeconds(deviceId), error);
            return;
        }
        state.succeeded().add(raw);
        if (state.succeeded().size() >= allRaw.size() && state.completed().compareAndSet(false, true)) {
            // 设备级成功：全部地址订阅成功后才记跟踪 + 计数（保持「订阅成功设备数」语义）
            failureBackoff.remove(deviceId);
            subscribedSessions.put(deviceId, session);
            subscribeSuccess.increment();
            log.info("[access] 订阅成功：deviceId={} 点位数={}（TCP 逐点位单订阅）", deviceId,
                allRaw.size());
        } else {
            log.debug("[access] TCP 单地址订阅成功：deviceId={} address={} 已完成={}/{}", deviceId,
                raw, state.succeeded().size(), allRaw.size());
        }
    }

    /**
     * 忘记某设备的订阅跟踪（设备被移除时由对账路径调用）。
     *
     * @param deviceId 设备标识
     */
    @Override
    public void forget(String deviceId) {
        failureBackoff.remove(deviceId);
        // 设备被移除时在途标记也要清：否则同 id 重新出现会一直被「在途」挡住
        inFlight.remove(deviceId);
        // TCP 逐点位订阅状态同样要清：否则设备重新出现时会被旧会话的「已成功地址集」挡住
        tcpStates.remove(deviceId);
        if (subscribedSessions.remove(deviceId) != null) {
            log.debug("[access] 设备已移除，清理订阅跟踪：deviceId={}", deviceId);
        }
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

    /**
     * 是否处于订阅失败退避窗口内。
     *
     * <p><b>会话实例变了就不再退避</b>：退避是为了不把「同一个坏会话上的失败订阅」每个周期重发一遍；
     * 而实例变化说明框架已经重连（换了一条链路），此时立刻重试才是正确行为——否则一次失败会把
     * 「设备恢复」也挡在退避窗口外。</p>
     *
     * @param deviceId 设备 ID
     * @param session  当前会话实例
     * @return 处于退避窗口内返回 {@code true}
     */
    private boolean inFailureBackoff(String deviceId, DeviceSession session) {
        SubscriptionBackoff backoff = failureBackoff.get(deviceId);
        if (backoff == null) {
            return false;
        }
        if (backoff.session() != session) {
            failureBackoff.remove(deviceId);
            return false;
        }
        return clock.instant().isBefore(backoff.nextRetryAt());
    }

    /** 记一次订阅失败：指数退避（起点 {@link #SUBSCRIBE_BACKOFF_BASE}，上限 {@link #SUBSCRIBE_BACKOFF_MAX}）。 */
    private void armFailureBackoff(String deviceId, DeviceSession session) {
        SubscriptionBackoff previous = failureBackoff.get(deviceId);
        int attempts = previous == null || previous.session() != session ? 1 : previous.attempts() + 1;
        long millis = Math.min(SUBSCRIBE_BACKOFF_BASE.toMillis() * (1L << Math.min(attempts - 1, 20)),
            SUBSCRIBE_BACKOFF_MAX.toMillis());
        failureBackoff.put(deviceId, new SubscriptionBackoff(session, attempts,
            clock.instant().plusMillis(millis)));
    }

    /** 当前退避剩余（秒，仅用于日志；无退避返回 0）。 */
    private long currentBackoffSeconds(String deviceId) {
        SubscriptionBackoff backoff = failureBackoff.get(deviceId);
        if (backoff == null) {
            return 0L;
        }
        long millis = Duration.between(clock.instant(), backoff.nextRetryAt()).toMillis();
        return Math.max(0L, millis / 1000L);
    }

    /**
     * 订阅失败退避状态。
     *
     * @param session     失败发生在哪个会话实例上（实例变化即视为新上下文，退避作废）
     * @param attempts    连续失败次数
     * @param nextRetryAt 下一次允许发起订阅的时刻
     */
    private record SubscriptionBackoff(DeviceSession session, int attempts, Instant nextRetryAt) {
    }

    /**
     * TCP 一设备的逐点位订阅状态（会话实例绑定）。
     *
     * @param session   这些订阅挂在哪个会话实例上（实例变化即作废，见 {@link #tcpStates} 的 compute）
     * @param succeeded 已成功订阅的地址（raw）
     * @param inFlight  异步订阅中、尚未回调的地址（raw）
     * @param completed 是否已「全部成功」并计入设备级跟踪（避免多回调重复计数）
     * @param failureArmedThisRound 本轮是否已为失败记过退避（一设备一轮只记一次，避免 N 个失败地址
     *        把退避 attempts 放大 N 倍、退避瞬间冲到上限）
     */
    private record TcpSubscriptionState(DeviceSession session, Set<String> succeeded,
                                        Set<String> inFlight, AtomicBoolean completed,
                                        AtomicBoolean failureArmedThisRound) {

        private TcpSubscriptionState(DeviceSession session) {
            this(session, ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
                new AtomicBoolean(false), new AtomicBoolean(false));
        }
    }

    /**
     * 设备级采集周期（毫秒）：断档判定要用**真周期**（不然只能用兜底值，阈值会偏大或偏小）。
     *
     * @param device 设备规格
     * @return 周期毫秒；未指定（ZERO）返回 {@code null}
     */
    private static Integer pollIntervalMs(DeviceSpec device) {
        Duration interval = device.pollInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return null;
        }
        // 上限钳到 Integer.MAX_VALUE：超过 ~24.8 天的周期在协议上无意义，静默溢出成负数才是坑
        return (int) Math.min(interval.toMillis(), Integer.MAX_VALUE);
    }

    /** 已跟踪会话数的观测入口（测试/自检）。 */
    public int trackedSessionCount() {
        return subscribedSessions.size();
    }

    /**
     * TCP 逐点位单订阅累计发起的**单地址**订阅次数（观测入口）。
     *
     * <p>用于自检/单测：证明「一设备多订阅」确实按点位展开，且部分成功后不会对已成功地址重复发起。</p>
     *
     * @return 累计发起的单地址订阅次数
     */
    public int tcpStartedAddressSubscriptionCount() {
        return startedTcpAddressCount.get();
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

