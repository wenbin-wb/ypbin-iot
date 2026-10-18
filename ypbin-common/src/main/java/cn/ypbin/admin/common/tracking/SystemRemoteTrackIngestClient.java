/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.common.tracking;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.tracking.core.TrackEvent;
import cn.ypbin.starter.tracking.spi.RemoteTrackIngestClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 埋点事件跨服务上报的 <b>SPI 实现</b>：把采集链路产出的事件上报给 system 落 {@code sys_track_event}。
 *
 * <p><b>自建实现已删除，改由 starter 4.1.0 装配（starter #100）</b>：本仓原有的
 * 自建的「埋点远程上报自动配置」+「上报用 TrackEventSink 实现」已上收到基座
 * （基座的埋点远程上报自动配置内的
 * {@code @Bean @ConditionalOnMissingBean TrackEventSink remoteTrackEventSink(RemoteTrackIngestClient)}）⇒
 * 本仓只保留这一个 SPI 实现。装配顺序、与 system 侧本地实现的互斥全由基座负责。</p>
 *
 * <p><b>为什么是上报而不是直连库</b>：auth 没有数据源（auth/ai 禁止直连共享库），而埋点落库实现在
 * system 服务里。若不提供本实现，starter 会装配只打印日志的 {@code LoggingTrackEventSink}——
 * 事件只进应用日志、漏斗/留存/趋势永远查不到。</p>
 *
 * <p><b>调用线程</b>：由 starter 的埋点消费线程调用，与业务请求线程隔离，允许阻塞（一次 Feign 往返）。
 * 事件里的 IP/UA/链路 ID/用户等维度已由上报方在请求线程上捕获，本类不做二次补采。</p>
 *
 * <p><b>异常语义</b>：失败必须记完整堆栈并上抛，由采集链路计入失败计数、退避后重试一次。上抛不会波及
 * 业务请求：埋点链路与业务线程本就隔离。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
public class SystemRemoteTrackIngestClient implements RemoteTrackIngestClient {

    private static final Logger log = LoggerFactory.getLogger(SystemRemoteTrackIngestClient.class);

    private final ISystemClient systemClient;

    /**
     * 创建上报客户端。
     *
     * @param systemClient system 服务 Feign 客户端
     */
    public SystemRemoteTrackIngestClient(ISystemClient systemClient) {
        this.systemClient = systemClient;
    }

    @Override
    public R<Void> ingestTrackEvents(List<TrackEvent> events) {
        if (events == null || events.isEmpty()) {
            // 空批次不发起调用：starter 的 sink 可能以空列表进入（无事件可上报），发一次空请求纯属浪费
            return R.ok();
        }
        R<Void> result;
        try {
            result = systemClient.ingestTrackEvents(events);
        } catch (RuntimeException ex) {
            // 传输层失败：连接/读取超时、熔断、响应解码异常。记完整堆栈后原样上抛（保留原始栈）
            log.error("[ypbin-admin] 埋点事件上报 system 服务失败（传输异常），本批 {} 条未落库: eventCodes={}",
                events.size(), codesOf(events), ex);
            throw ex;
        }
        if (result == null || !result.isSuccess()) {
            // 业务码失败（/internal 凭证不匹配、埋点未启用、system 侧异常）没有异常对象，显式构造以产出可定位堆栈
            IllegalStateException failure = new IllegalStateException(
                "[ypbin-admin] 埋点事件上报 system 服务失败，本批 " + events.size() + " 条未落库: "
                    + "code=" + (result == null ? "null" : result.getCode())
                    + ", message=" + (result == null ? "响应为空" : result.getMessage()));
            log.error("[ypbin-admin] 埋点事件上报 system 服务失败（业务码），eventCodes={}", codesOf(events),
                failure);
            throw failure;
        }
        return result;
    }

    /**
     * 事件码摘要，仅用于日志定位（不含任何事件属性，避免把 payload 写进日志）。
     *
     * @param events 事件批次
     * @return 事件码列表
     */
    private static List<String> codesOf(List<TrackEvent> events) {
        return events.stream().map(TrackEvent::eventCode).toList();
    }
}
