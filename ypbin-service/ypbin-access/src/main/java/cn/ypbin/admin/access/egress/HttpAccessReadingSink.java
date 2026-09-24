/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import cn.ypbin.admin.access.config.AccessEgressProperties;
import cn.ypbin.admin.iot.availability.IReadingClient;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.starter.core.model.R;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 读数出口的 HTTP 实现（M-2）：**有界队列 → 微批 → 内部接口**。
 *
 * <p>三条硬约束（协议回调线程是采集热路径，不能被网络拖住）：</p>
 * <ol>
 *   <li><b>绝不阻塞</b>：入队用 {@code offer}，队满即丢弃并计数（丢弃是可控损失，阻塞会反压到协议栈）；</li>
 *   <li><b>超时显式</b>：Feign 的 connect/read 超时与「不重试」在 {@code ReadingFeignConfiguration} 里写死
 *       （重试只会占住 flush 线程、放大远端压力）；</li>
 *   <li><b>失败可见</b>：上报失败计数 + ERROR 全堆栈；本批**丢弃不重试**（读数只用于断档/可用率，
 *       丢一批会让缺口被算长一些，但绝不能因此把服务拖垮）——`iot.access.egress.failed` 就是它的账。</li>
 * </ol>
 *
 * <p>只用「质量 + 时刻」上报（不含读数**值**）：值的存储属数据面（IoTDB/Redis，依赖 Q8）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public class HttpAccessReadingSink implements AccessReadingSink {

    /** 指标前缀。 */
    static final String METRIC_PREFIX = "iot.access.egress.";

    /** 队列满的告警最小间隔（避免爆满时把日志打爆）。 */
    static final long DROP_WARN_INTERVAL_MS = 60_000L;

    private static final Logger log = LoggerFactory.getLogger(HttpAccessReadingSink.class);

    private final IReadingClient client;
    private final int batchSize;
    private final BlockingQueue<ReadingObservationDto> queue;
    private final Counter acceptedCounter;
    private final Counter droppedCounter;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter invalidCounter;
    private final AtomicLong lastDropWarnAt = new AtomicLong(Long.MIN_VALUE);

    public HttpAccessReadingSink(IReadingClient client, AccessEgressProperties properties,
                                 MeterRegistry meterRegistry) {
        this.client = client;
        this.batchSize = Math.max(1, Math.min(properties.getBatchSize(),
            ReadingIngestReq.MAX_BATCH_SIZE));
        this.queue = new ArrayBlockingQueue<>(Math.max(this.batchSize, properties.getQueueCapacity()));
        this.acceptedCounter = meterRegistry.counter(METRIC_PREFIX + "accepted");
        this.droppedCounter = meterRegistry.counter(METRIC_PREFIX + "dropped");
        this.sentCounter = meterRegistry.counter(METRIC_PREFIX + "sent");
        this.failedCounter = meterRegistry.counter(METRIC_PREFIX + "failed");
        this.invalidCounter = meterRegistry.counter(METRIC_PREFIX + "invalid");
        Gauge.builder(METRIC_PREFIX + "pending", queue, BlockingQueue::size)
            .description("待上报的读数条数").register(meterRegistry);
    }

    @Override
    public void accept(AccessReading reading) {
        ReadingObservationDto observation = toObservation(reading);
        if (observation == null) {
            // 计数在 toObservation 里；这里不重复计，避免两类问题混在一个指标上
            return;
        }
        if (queue.offer(observation)) {
            acceptedCounter.increment();
            return;
        }
        droppedCounter.increment();
        warnDropIfNeeded();
    }

    /** 微批上报（周期由 {@code ypbin.access.egress.flush-interval-ms} 控制）。 */
    @Scheduled(fixedDelayString = "${ypbin.access.egress.flush-interval-ms:1000}")
    public void flush() {
        List<ReadingObservationDto> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return;
        }
        ReadingIngestReq req = new ReadingIngestReq();
        req.setItems(batch);
        try {
            R<Integer> resp = client.ingest(req);
            if (resp == null || !resp.isSuccess()) {
                failedCounter.increment(batch.size());
                log.error("[access] 读数上报返回非成功信封（本批 {} 条已丢弃，不重试）：code={} msg={}",
                    batch.size(), resp == null ? null : resp.getCode(),
                    resp == null ? null : resp.getMessage());
                return;
            }
            sentCounter.increment(batch.size());
            log.debug("[access] 读数上报成功：本批 {} 条，服务端处理 {} 条", batch.size(), resp.getData());
        } catch (RuntimeException ex) {
            failedCounter.increment(batch.size());
            log.error("[access] 读数上报失败（本批 {} 条已丢弃，不重试；见 iot.access.egress.failed）：",
                batch.size(), ex);
        }
    }

    /**
     * 停机前把队列刷空（**循环**刷，直到空或达到次数上限）。
     *
     * <p>为什么不能只刷一批：队列容量（默认 10000）可以远大于微批（默认 200），只刷一批会把尾部
     * **静默丢掉**——既不计 `dropped` 也没有日志，排查时「读数去哪了」无从回答。剩下的必须计数 + 告警。</p>
     */
    @PreDestroy
    public void close() {
        int maxRounds = Math.max(1, queue.size() / batchSize + 2);
        for (int round = 0; round < maxRounds && !queue.isEmpty(); round++) {
            try {
                flush();
            } catch (RuntimeException ex) {
                log.error("[access] 停机前刷出残留读数失败：pending={}", pendingCount(), ex);
                break;
            }
        }
        int left = queue.size();
        if (left > 0) {
            droppedCounter.increment(left);
            log.warn("[access] 停机时仍有 {} 条读数未送出（已计入 iot.access.egress.dropped）", left);
        }
    }

    /** 待上报条数（观测/测试用）。 */
    public int pendingCount() {
        return queue.size();
    }

    /**
     * 读数 → 上报观察；不可用（设备号非数字、缺时刻/质量）时计数并返回 {@code null}。
     *
     * @param reading 读数
     * @return 观察；不可用返回 {@code null}
     */
    private ReadingObservationDto toObservation(AccessReading reading) {
        if (reading == null || reading.timestamp() == null || reading.quality() == null
            || reading.quality().isBlank()) {
            invalidCounter.increment();
            log.warn("[access] 读数缺少时刻/质量，已丢弃：device={}", reading == null ? null : reading.deviceId());
            return null;
        }
        Long deviceId = parseDeviceId(reading.deviceId());
        if (deviceId == null) {
            invalidCounter.increment();
            log.warn("[access] 读数设备号不是数字（无法对应设备台账），已丢弃：device={}",
                reading.deviceId());
            return null;
        }
        ReadingObservationDto observation = new ReadingObservationDto();
        observation.setDeviceId(deviceId);
        observation.setPollIntervalMs(reading.pollIntervalMs());
        observation.setQuality(reading.quality());
        // 点位与值（Q8/D0.7）：值字符串化后上报——类型由物模型定义，上报契约不做窄化（避免丢信息/精度）
        observation.setPropertyId(reading.propertyId());
        observation.setValue(reading.value() == null ? null : String.valueOf(reading.value()));
        // epoch 毫秒：跨服务不用字符串时间，避免两端时区/格式配置不一致
        observation.setTs(reading.timestamp().toEpochMilli());
        return observation;
    }

    /** 设备号（协议栈侧是字符串，台账里是 BIGINT）转数字；非数字返回 null。 */
    private static Long parseDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(deviceId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 队列满的告警按最小间隔打，避免每丢一条打一行。 */
    private void warnDropIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastDropWarnAt.get();
        if (now - last < DROP_WARN_INTERVAL_MS || !lastDropWarnAt.compareAndSet(last, now)) {
            return;
        }
        log.warn("[access] 读数上报队列已满，正在丢弃（累计丢弃 {} 条；调大 {} 或排查 iot 侧是否不可达）",
            (long) droppedCounter.count(), AccessEgressProperties.PREFIX + ".queue-capacity");
    }
}
