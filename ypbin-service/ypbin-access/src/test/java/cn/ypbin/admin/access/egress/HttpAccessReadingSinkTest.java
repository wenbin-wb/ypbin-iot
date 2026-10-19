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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.access.config.AccessEgressProperties;
import cn.ypbin.admin.iot.availability.IReadingClient;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.starter.core.model.R;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 读数出口（HTTP 上报）单测（M-2）。
 *
 * <p>守三件事：<b>绝不阻塞采集线程</b>（队满丢弃并计数，不反压协议栈）、<b>失败必须可见</b>
 * （计数 + 不抛给调度线程）、<b>上报内容与口径一致</b>（设备号/质量/时刻/设备级周期，时刻用 epoch 毫秒）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
class HttpAccessReadingSinkTest {

    private static final String DEVICE = "100";
    private static final Instant TS = Instant.parse("2026-09-22T02:00:00Z");

    private IReadingClient client;
    private SimpleMeterRegistry meterRegistry;
    private AccessEgressProperties properties;

    @BeforeEach
    void setUp() {
        client = mock(IReadingClient.class);
        meterRegistry = new SimpleMeterRegistry();
        properties = new AccessEgressProperties();
        properties.setQueueCapacity(10);
        properties.setBatchSize(3);
        // 基座是异步单消费线程：测试里把 flush 窗口调小，避免每条用例等 1s
        properties.setFlushIntervalMs(20);
    }

    @Test
    @DisplayName("入队即可见：有效读数进队列并计数；**空队列** flush 不打远端")
    void acceptShouldEnqueueAndEmptyFlushShouldNotCallRemote() {
        HttpAccessReadingSink sink = sink();

        awaitDelivered(sink);
        verify(client, never()).ingest(any());

        sink.accept(reading(DEVICE, "GOOD", 5_000));

        // 消费线程是异步的：pendingCount 可能在断言时已被取走 ⇒ 断「入队被接受」这一事实
        assertThat(meterRegistry.get("iot.access.egress.accepted").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 微批上报：一次 flush 把队列送出，字段与口径一致（设备号/质量/时刻/周期）")
    void flushShouldSendBatchWithExpectedFields() {
        when(client.ingest(any())).thenReturn(R.ok(3));
        HttpAccessReadingSink sink = sink();
        sink.accept(reading(DEVICE, "GOOD", 5_000));
        sink.accept(reading(DEVICE, "BAD", 5_000));
        sink.accept(reading("101", "GOOD", null));

        awaitDelivered(sink);

        ArgumentCaptor<ReadingIngestReq> captor = ArgumentCaptor.forClass(ReadingIngestReq.class);
        verify(client, atLeastOnce()).ingest(captor.capture());
        List<ReadingObservationDto> all = captor.getAllValues().stream()
            .flatMap(req -> req.getItems().stream()).toList();
        assertThat(all).as("3 条必须全部送达（可能拆成多批）").hasSize(3);
        ReadingObservationDto first = all.getFirst();
        assertThat(first.getDeviceId()).isEqualTo(100L);
        assertThat(first.getQuality()).isEqualTo("GOOD");
        assertThat(first.getTs()).as("epoch 毫秒（跨服务不用字符串时间）").isEqualTo(TS.toEpochMilli());
        assertThat(first.getPollIntervalMs()).isEqualTo(5_000);
        // Q8/D0.7：点位与值必须透传（最新值链路的数据源）；值统一字符串化，类型由物模型定义
        assertThat(first.getPropertyId()).isEqualTo("900");
        assertThat(first.getValue()).isEqualTo("1");
        assertThat(sink.pendingCount()).isZero();
        assertThat(meterRegistry.get("iot.access.egress.sent").counter().count()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("★ 值为 null 时必须上报 null（不得变成字符串 \"null\"，否则最新值会写进脏数据）")
    void nullValueMustStayNull() {
        when(client.ingest(any())).thenReturn(R.ok(1));
        HttpAccessReadingSink sink = sink();
        sink.accept(new AccessReading(DEVICE, "900", null, "GOOD", TS, 1_000));

        awaitDelivered(sink);

        ArgumentCaptor<ReadingIngestReq> captor = ArgumentCaptor.forClass(ReadingIngestReq.class);
        verify(client).ingest(captor.capture());
        ReadingObservationDto observation = captor.getValue().getItems().getFirst();
        assertThat(observation.getPropertyId()).isEqualTo("900");
        assertThat(observation.getValue()).as("空值必须保持 null").isNull();
    }

    @Test
    @DisplayName("★ 队列满必须丢弃并计数，**绝不阻塞**（阻塞会反压到协议采集线程）")
    void queueFullMustDropInsteadOfBlocking() {
        properties.setQueueCapacity(1);
        properties.setBatchSize(1);
        HttpAccessReadingSink sink = sink();

        assertThatCode(() -> {
            for (int i = 0; i < 5; i++) {
                sink.accept(reading(DEVICE, "GOOD", 5_000));
            }
        }).doesNotThrowAnyException();

        // 基座由**单消费线程**边入队边消费 ⇒ 具体「几条进队列、几条被丢」是时序相关的，
        // 断言总账守恒（不阻塞、不丢账）与「丢弃可观测」才是稳定且有业务意义的判据。
        double accepted = meterRegistry.get("iot.access.egress.accepted").counter().count();
        double dropped = meterRegistry.get("iot.access.egress.dropped").counter().count();
        assertThat(accepted + dropped)
            .as("每条读数必须恰好被接受或丢弃一次（不得静默消失）").isEqualTo(5.0d);
        assertThat(dropped).as("容量 1 下连续 5 条必然出现丢弃（可观测的损失）").isPositive();
    }

    @Test
    @DisplayName("设备号不是数字 / 缺时刻质量：计数并丢弃，不把脏数据发给服务端")
    void invalidReadingsMustBeCountedAndSkipped() {
        HttpAccessReadingSink sink = sink();

        sink.accept(reading("not-a-number", "GOOD", 5_000));
        sink.accept(new AccessReading(DEVICE, "900", 1, "GOOD", null, 5_000));
        sink.accept(new AccessReading(DEVICE, "900", 1, " ", TS, 5_000));

        assertThat(meterRegistry.get("iot.access.egress.invalid").counter().count()).isEqualTo(3.0d);
        assertThat(sink.pendingCount()).isZero();
    }

    @Test
    @DisplayName("★ 接入侧只交付字符串值：数值形态与文本形态都原样上报（落列由 iot 侧按物模型类型决定）")
    void decodedStringsMustBeForwardedVerbatim() {
        when(client.ingest(any())).thenReturn(R.ok(2));
        HttpAccessReadingSink sink = sink();
        sink.accept(new AccessReading(DEVICE, "serial", "123456", "GOOD", TS, 5_000));
        sink.accept(new AccessReading(DEVICE, "counter", 65538, "GOOD", TS, 5_000));

        awaitDelivered(sink);

        ArgumentCaptor<ReadingIngestReq> captor = ArgumentCaptor.forClass(ReadingIngestReq.class);
        verify(client, atLeastOnce()).ingest(captor.capture());
        // 异步微批可能把 2 条拆成两批（时序相关）⇒ 断言「合起来恰好是这两条、顺序不变」
        assertThat(captor.getAllValues()).flatExtracting(ReadingIngestReq::getItems)
            .extracting(ReadingObservationDto::getValue)
            .as("值字符串原样上报；列不由接入侧决定（DTO 无任何列/类型标记）")
            .containsExactly("123456", "65538");
    }

    @Test
    @DisplayName("★ 值仍是未解码的原始字节：计数并丢弃（绝不让 [B@<hash> 这种无值语义的文本入库）")
    void rawByteValueMustBeDroppedNotStringified() {
        HttpAccessReadingSink sink = sink();

        sink.accept(new AccessReading(DEVICE, "temperature",
            "TEMP=23.5".getBytes(StandardCharsets.UTF_8), "GOOD", TS, 5_000));

        assertThat(sink.pendingCount()).as("原始字节能落库的唯一形态是 [B@…，属垃圾 ⇒ 丢弃").isZero();
        assertThat(meterRegistry.get("iot.access.egress.invalid").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 上报失败（异常/非成功信封）不得抛出：计数 + 丢弃本批，调度线程继续跑")
    void failureMustBeCountedWithoutThrowing() {
        // 必须用 doThrow：`when(mock.method())` 会在打桩时就执行该方法（此时 mock 还没被赋予异常，
        // 但后续再 when(...) 时就会踩到已打上的 thenThrow）
        doThrow(new IllegalStateException("iot 不可达")).when(client).ingest(any());
        HttpAccessReadingSink sink = sink();
        sink.accept(reading(DEVICE, "GOOD", 5_000));
        sink.accept(reading(DEVICE, "GOOD", 5_000));

        assertThatCode(() -> awaitDelivered(sink)).doesNotThrowAnyException();
        awaitMetric("iot.access.egress.failed", 2.0d);

        assertThat(currentCount("iot.access.egress.failed")).isEqualTo(2.0d);
        assertThat(sink.pendingCount()).as("失败不重试：本批丢弃（由指标暴露）").isZero();

        doReturn(R.fail(500, "boom")).when(client).ingest(any());
        sink.accept(reading(DEVICE, "GOOD", 5_000));
        assertThatCode(() -> awaitDelivered(sink)).doesNotThrowAnyException();
        awaitMetric("iot.access.egress.failed", 3.0d);
        assertThat(currentCount("iot.access.egress.failed")).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("停机前把队尾刷出去（close 尽力 flush，不抛）")
    void closeShouldFlushPending() {
        when(client.ingest(any())).thenReturn(R.ok(1));
        HttpAccessReadingSink sink = sink();
        sink.accept(reading(DEVICE, "GOOD", 5_000));

        sink.close();

        assertThat(sink.pendingCount()).isZero();
        assertThat(meterRegistry.get("iot.access.egress.sent").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 停机刷尾要**循环**刷空（队列远大于微批时，只刷一批会静默丢尾）")
    void closeMustDrainAllBatches() {
        properties.setQueueCapacity(5);
        properties.setBatchSize(2);
        when(client.ingest(any())).thenReturn(R.ok(2));
        HttpAccessReadingSink sink = sink();
        for (int i = 0; i < 5; i++) {
            sink.accept(reading(DEVICE, "GOOD", 5_000));
        }

        sink.close();

        assertThat(sink.pendingCount()).isZero();
        assertThat(meterRegistry.get("iot.access.egress.sent").counter().count())
            .as("5 条分 3 批（2+2+1）全部送出").isEqualTo(5.0d);
        assertThat(meterRegistry.get("iot.access.egress.dropped").counter().count())
            .as("没有静默丢弃").isZero();
    }

    private HttpAccessReadingSink sink() {
        return new HttpAccessReadingSink(client, properties, meterRegistry);
    }

    private static AccessReading reading(String deviceId, String quality, Integer pollIntervalMs) {
        return new AccessReading(deviceId, "900", 1, quality, TS, pollIntervalMs);
    }

    /**
     * 等基座把队列消费空（starter 的 BoundedBatchEgressQueue 由**单消费线程**按 batchSize +
     * flushInterval 微批投递；测试里没有调度器，只能等）。
     *
     * @param sink 被测出口
     */
    /**
     * 等到指定指标的计数达到期望值（最多 3s）。
     *
     * <p><b>为什么不能只等 {@code pendingCount()==0}</b>：消费线程 {@code poll} 走元素后队列即空，
     * 但此刻它还在 {@code deliver(batch)} 里调远端——指标还没 ++。只等队列空就去断言计数，
     * 会读到「0.0」而误判失败（真实 flaky，issue #292）。</p>
     *
     * @param metricName 指标名
     * @param expected   期望的下限计数
     */
    private void awaitMetric(String metricName, double expected) {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (currentCount(metricName) < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待指标计数被中断", ex);
            }
        }
    }

    /**
     * 读指标计数（指标尚未注册时按 0 计，避免 {@code registry.get()} 抛 NotFoundException）。
     *
     * @param metricName 指标名
     * @return 当前计数
     */
    private double currentCount(String metricName) {
        var counter = meterRegistry.find(metricName).counter();
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * 等待「已入队的读数**真正投递完成**」。
     *
     * <p><b>为什么不能只等 {@code pendingCount() == 0}</b>（2026-10-19 修 flaky，实测在未改动的
     * origin/main 上 3 次跑 1 次失败）：队列深度归零只说明「消费线程把元素**取走**了」，从取走到
     * {@code client.ingest(...)} 执行完之间还有窗口。断言 {@code verify(client)} / 捕获参数时若正好落在
     * 这个窗口里，就会偶发失败（同一文件里先报 decodedStrings…、再报 nullValue…，位置随机 ⇒ 典型时序竞态）。
     *
     * <p>改法：等到「队列已空 <b>且</b> 静置一个 flush 周期（{@code 2 × flushIntervalMs}）」——
     * 静置窗口内没有新的入队、消费线程也已跑完一轮，才认为投递确实结束。总等待仍有上限，
     * 超时不静默通过（由后续断言给出失败原因）。</p>
     *
     * @param sink 待等待的出口
     */
    private void awaitDelivered(HttpAccessReadingSink sink) {
        long deadline = System.currentTimeMillis() + 3_000L;
        long quietMillis = Math.max(40L, properties.getFlushIntervalMs() * 2L);
        long emptySince = -1L;
        while (System.currentTimeMillis() < deadline) {
            if (sink.pendingCount() == 0) {
                if (emptySince < 0) {
                    emptySince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - emptySince >= quietMillis) {
                    return;
                }
            } else {
                emptySince = -1L;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待微批投递被中断", ex);
            }
        }
    }
}
