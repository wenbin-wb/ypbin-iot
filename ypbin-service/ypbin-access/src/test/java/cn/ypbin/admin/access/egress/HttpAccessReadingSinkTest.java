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
import java.time.Instant;
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
    }

    @Test
    @DisplayName("入队即可见：有效读数进队列并计数；**空队列** flush 不打远端")
    void acceptShouldEnqueueAndEmptyFlushShouldNotCallRemote() {
        HttpAccessReadingSink sink = sink();

        sink.flush();
        verify(client, never()).ingest(any());

        sink.accept(reading(DEVICE, "GOOD", 5_000));

        assertThat(sink.pendingCount()).isEqualTo(1);
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

        sink.flush();

        ArgumentCaptor<ReadingIngestReq> captor = ArgumentCaptor.forClass(ReadingIngestReq.class);
        verify(client, times(1)).ingest(captor.capture());
        assertThat(captor.getValue().getItems()).hasSize(3);
        ReadingObservationDto first = captor.getValue().getItems().getFirst();
        assertThat(first.getDeviceId()).isEqualTo(100L);
        assertThat(first.getQuality()).isEqualTo("GOOD");
        assertThat(first.getTs()).as("epoch 毫秒（跨服务不用字符串时间）").isEqualTo(TS.toEpochMilli());
        assertThat(first.getPollIntervalMs()).isEqualTo(5_000);
        assertThat(sink.pendingCount()).isZero();
        assertThat(meterRegistry.get("iot.access.egress.sent").counter().count()).isEqualTo(3.0d);
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

        assertThat(meterRegistry.get("iot.access.egress.accepted").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("iot.access.egress.dropped").counter().count()).isEqualTo(4.0d);
        assertThat(sink.pendingCount()).isEqualTo(1);
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
    @DisplayName("★ 上报失败（异常/非成功信封）不得抛出：计数 + 丢弃本批，调度线程继续跑")
    void failureMustBeCountedWithoutThrowing() {
        // 必须用 doThrow：`when(mock.method())` 会在打桩时就执行该方法（此时 mock 还没被赋予异常，
        // 但后续再 when(...) 时就会踩到已打上的 thenThrow）
        doThrow(new IllegalStateException("iot 不可达")).when(client).ingest(any());
        HttpAccessReadingSink sink = sink();
        sink.accept(reading(DEVICE, "GOOD", 5_000));
        sink.accept(reading(DEVICE, "GOOD", 5_000));

        assertThatCode(sink::flush).doesNotThrowAnyException();

        assertThat(meterRegistry.get("iot.access.egress.failed").counter().count()).isEqualTo(2.0d);
        assertThat(sink.pendingCount()).as("失败不重试：本批丢弃（由指标暴露）").isZero();

        doReturn(R.fail(500, "boom")).when(client).ingest(any());
        sink.accept(reading(DEVICE, "GOOD", 5_000));
        assertThatCode(sink::flush).doesNotThrowAnyException();
        assertThat(meterRegistry.get("iot.access.egress.failed").counter().count()).isEqualTo(3.0d);
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

    private HttpAccessReadingSink sink() {
        return new HttpAccessReadingSink(client, properties, meterRegistry);
    }

    private static AccessReading reading(String deviceId, String quality, Integer pollIntervalMs) {
        return new AccessReading(deviceId, "900", 1, quality, TS, pollIntervalMs);
    }
}
