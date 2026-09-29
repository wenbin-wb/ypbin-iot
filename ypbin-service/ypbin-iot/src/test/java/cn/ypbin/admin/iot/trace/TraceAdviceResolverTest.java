/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.iot.enums.CommandErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 「定位建议」规则引擎用例（设计 §4 / §7）。
 *
 * <p>覆盖三条设计要求：① **每条 {@link CommandErrorCode} 都有对应规则**（穷举，防止将来
 * 新增归因码却忘了给建议）；② **不匹配时必须返回 `null`**（不许硬凑）；③
 * **`status_code=failed` 的两个来源必须被区分**（设计 §3.3.2b 指出的高频误判点）。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class TraceAdviceResolverTest {

    private static TraceItem commandItem(TraceOutcome outcome, String errorCode) {
        return new TraceItem(LocalDateTime.now(), TraceStage.DOWN_ACK, TraceDirection.DOWN, outcome,
            "下发：temperature", "iot_command_instance", 1L, 0,
            LocalDateTime.now().minusSeconds(5), LocalDateTime.now().minusSeconds(4),
            LocalDateTime.now(), errorCode, errorCode == null ? null : "原始错误", null);
    }

    @Test
    @DisplayName("成功的条目不给建议（成功的消息没有『下一步怎么办』）")
    void okItemMustHaveNoAdvice() {
        assertThat(TraceAdviceResolver.resolve(commandItem(TraceOutcome.OK, null))).isNull();
        assertThat(TraceAdviceResolver.resolve(commandItem(TraceOutcome.OK, "TIMEOUT"))).isNull();
    }

    @Test
    @DisplayName("null 条目不炸（防御：调用方可能还没查出来）")
    void nullItemMustNotThrow() {
        assertThat(TraceAdviceResolver.resolve(null)).isNull();
    }

    @ParameterizedTest
    @EnumSource(CommandErrorCode.class)
    @DisplayName("每一个归因码都要有建议，且建议非空、有动作（新增码时用例会红）")
    void everyErrorCodeMustHaveAdvice(CommandErrorCode errorCode) {
        TraceAdvice advice = TraceAdviceResolver.resolve(
            commandItem(TraceOutcome.FAILED, errorCode.getCode()));

        assertThat(advice)
            .as("归因码 %s 没有对应建议——新增归因码时必须一并补规则，否则用户看到『暂无建议』",
                errorCode.getCode())
            .isNotNull();
        assertThat(advice.ruleId()).isNotBlank();
        assertThat(advice.summary()).isNotBlank();
        assertThat(advice.actions()).isNotEmpty();
    }

    @Test
    @DisplayName("🔴 NO_SUBSCRIBER 与 DEVICE_REJECTED 必须给不同的建议（设计 §3.3.2b 的核心）")
    void twoSourcesOfFailedMustBeDistinguished() {
        // 两者在库里都是 status_code=failed，但一个"根本没送到"、一个"设备收到了并拒绝"
        TraceAdvice noSubscriber = TraceAdviceResolver.resolve(
            commandItem(TraceOutcome.FAILED, CommandErrorCode.NO_SUBSCRIBER.getCode()));
        TraceAdvice rejected = TraceAdviceResolver.resolve(
            commandItem(TraceOutcome.FAILED, CommandErrorCode.DEVICE_REJECTED.getCode()));

        assertThat(noSubscriber.ruleId()).isNotEqualTo(rejected.ruleId());
        // "没送到"的建议里必须让用户去看设备是否在线；"被拒绝"的建议里必须让用户看回执原文
        assertThat(String.join(" ", noSubscriber.actions())).contains("在线");
        assertThat(String.join(" ", rejected.actions())).contains("回执");
    }

    @Test
    @DisplayName("没有归因码但结果失败/超时时，给『未归因』建议并明说平台未归因")
    void unattributedMustBeExplicit() {
        TraceAdvice failed = TraceAdviceResolver.resolve(commandItem(TraceOutcome.FAILED, null));
        assertThat(failed).isNotNull();
        assertThat(failed.ruleId()).isEqualTo("CMD_FAILED_UNATTRIBUTED");
        assertThat(failed.summary()).contains("没有记录");

        TraceAdvice timeout = TraceAdviceResolver.resolve(commandItem(TraceOutcome.TIMEOUT, null));
        assertThat(timeout).isNotNull();
        assertThat(timeout.ruleId()).isEqualTo("CMD_TIMEOUT_UNATTRIBUTED");
    }

    @Test
    @DisplayName("不认识的错误码不炸、也不硬凑（走未归因分支，用户仍看得到原始码）")
    void unknownErrorCodeMustNotThrow() {
        TraceAdvice advice = TraceAdviceResolver.resolve(
            commandItem(TraceOutcome.FAILED, "SOME_FUTURE_CODE"));

        assertThat(advice).isNotNull();
        assertThat(advice.ruleId()).isEqualTo("CMD_FAILED_UNATTRIBUTED");
    }

    @Test
    @DisplayName("大小写与空白容错（历史数据可能不是规范形态）")
    void errorCodeParsingMustBeForgiving() {
        assertThat(TraceAdviceResolver.resolve(
            commandItem(TraceOutcome.FAILED, "  timeout  ")).ruleId()).isEqualTo("CMD_TIMEOUT");
        assertThat(TraceAdviceResolver.resolve(
            commandItem(TraceOutcome.FAILED, "no_subscriber")).ruleId())
            .isEqualTo("CMD_NO_SUBSCRIBER");
    }

    @Test
    @DisplayName("设备离线条目给离线建议")
    void offlineItemMustHaveAdvice() {
        TraceItem offline = new TraceItem(LocalDateTime.now(), TraceStage.DEVICE_OFFLINE,
            TraceDirection.INTERNAL, TraceOutcome.FAILED, "设备离线", "outage_event", 9L, null,
            null, null, null, null, "断档", null);

        TraceAdvice advice = TraceAdviceResolver.resolve(offline);
        assertThat(advice).isNotNull();
        assertThat(advice.ruleId()).isEqualTo("DEVICE_OFFLINE_WINDOW");
    }

    @Test
    @DisplayName("上行受理/设备事件不给建议（一期没有可用的失败痕迹，编一个就是造假）")
    void upAndEventMustNotInventAdvice() {
        TraceItem up = new TraceItem(LocalDateTime.now(), TraceStage.UP_RECEIVED,
            TraceDirection.UP, TraceOutcome.UNKNOWN, "上行受理", "iot_mqtt_ingest_receipt", 1L, null,
            null, null, null, null, null, null);
        TraceItem event = new TraceItem(LocalDateTime.now(), TraceStage.EVENT_REPORTED,
            TraceDirection.UP, TraceOutcome.UNKNOWN, "事件", "iot_event_log", 1L, null,
            null, null, null, "X", "原始", null);

        assertThat(TraceAdviceResolver.resolve(up)).isNull();
        assertThat(TraceAdviceResolver.resolve(event)).isNull();
    }

    @Test
    @DisplayName("TraceAdvice 不允许空 ruleId 或空动作（空壳建议等于没有建议）")
    void adviceMustNotBeHollow() {
        assertThatThrownBy(() -> new TraceAdvice("", "结论", List.of("做点什么")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceAdvice("R", "结论", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TraceAdvice("R", "结论", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("重发过的条目要能被识别（前端据此提示『历史时刻已被覆盖』）")
    void retriedItemMustBeFlagged() {
        TraceItem retried = commandItem(TraceOutcome.TIMEOUT, "TIMEOUT");
        TraceItem withRetry = new TraceItem(retried.occurredAt(), retried.stage(),
            retried.direction(), retried.outcome(), retried.title(), retried.source(),
            retried.sourceId(), 2, retried.enqueuedAt(), retried.publishedAt(), retried.ackedAt(),
            retried.errorCode(), retried.errorMsg(), retried.advice());

        assertThat(withRetry.hasOverwrittenHistory()).isTrue();
        assertThat(retried.hasOverwrittenHistory()).isFalse();
    }
}
