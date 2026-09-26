/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 影子 reported 落库写入器的单测（G2）：批形态、解析出的 JSON 增量与失败语义。
 *
 * <p>这里只验「发给 Mapper 的东西对不对」与「失败不外抛」；合并本身（{@code JSON_MERGE_PATCH} 的原子性、
 * 幂等、并发不丢更新）写在存储层，由 {@code ShadowReportedIT} 用真库验证——mock Mapper 的单测
 * 咬不到 SQL 语义。</p>
 *
 * @author wenbin
 * @since 2026-09-25
 */
class DbShadowReportedWriterTest {

    private static final Long TENANT = 11L;
    private static final Long DEVICE = 100L;
    private static final LocalDateTime REPORT_TS = LocalDateTime.of(2026, 9, 25, 10, 0);

    private IotShadowMapper shadowMapper;
    private MeterRegistry meterRegistry;
    private DbShadowReportedWriter writer;

    @BeforeEach
    void setUp() {
        shadowMapper = mock(IotShadowMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        writer = new DbShadowReportedWriter(shadowMapper, new ObjectMapper(), meterRegistry);
    }

    @Test
    @DisplayName("★ 整批收敛成**一次** Mapper 调用（多设备也是一条语句，不做 N+1）")
    void mustSendWholeBatchInOneStatement() {
        ShadowReportedUpdate first = new ShadowReportedUpdate(TENANT, DEVICE,
            Map.of("temperature", "23.5"), REPORT_TS);
        ShadowReportedUpdate second = new ShadowReportedUpdate(TENANT, 200L,
            Map.of("humidity", "61"), REPORT_TS);

        writer.writeAll(List.of(first, second));

        ArgumentCaptor<List<ShadowReportedRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(shadowMapper).mergeReported(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().getFirst().id()).as("插入路径需要雪花主键").isNotNull();
        assertThat(captor.getValue().getFirst().reportedJson())
            .as("增量必须是 JSON 文本，且值按字符串原样带过去").isEqualTo("{\"temperature\":\"23.5\"}");
        assertThat(captor.getValue().getFirst().reportTs()).isEqualTo(REPORT_TS);
        assertThat(captor.getValue().getFirst().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("★ 空批短路：不碰 Mapper（避免把空批当异常，也省一次往返）")
    void mustShortCircuitEmptyBatch() {
        writer.writeAll(List.of());

        verify(shadowMapper, never()).mergeReported(anyList());
    }

    @Test
    @DisplayName("★ 失败只计数不抛：Mapper 抛异常时不得把上报链路拖崩，且要有失败计数")
    void failureMustBeCountedNotThrown() {
        doThrow(new IllegalStateException("影子写入失败（模拟）")).when(shadowMapper).mergeReported(anyList());

        assertThatCode(() -> writer.writeAll(List.of(
            new ShadowReportedUpdate(TENANT, DEVICE, Map.of("temperature", "23.5"), REPORT_TS))))
            .as("上报已落库，影子写失败不得外抛")
            .doesNotThrowAnyException();

        assertThat(meterRegistry.get(DbShadowReportedWriter.METRIC_FAILED).counter().count())
            .as("失败必须可观测（计数），不能静默")
            .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 序列化失败也只计数不抛（增量值不得因为一次转换异常拖崩上报）")
    void serializationFailureMustBeCountedNotThrown() {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new IllegalStateException("序列化失败（模拟）"));
        DbShadowReportedWriter failing = new DbShadowReportedWriter(shadowMapper, broken, meterRegistry);

        assertThatCode(() -> failing.writeAll(List.of(
            new ShadowReportedUpdate(TENANT, DEVICE, Map.of("temperature", "23.5"), REPORT_TS))))
            .doesNotThrowAnyException();

        verify(shadowMapper, never()).mergeReported(anyList());
        assertThat(meterRegistry.get(DbShadowReportedWriter.METRIC_FAILED).counter().count()).isEqualTo(1.0d);
    }
}
