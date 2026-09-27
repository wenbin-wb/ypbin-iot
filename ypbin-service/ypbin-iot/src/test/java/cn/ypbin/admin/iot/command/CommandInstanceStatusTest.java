/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.command;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.enums.CommandInstanceStatus;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 命令实例状态机的**穷举**门禁（6×6 = 36 种转换逐个断言）。
 *
 * <p><b>为什么必须穷举而不是抽查几条</b>：状态机的漏洞总是出现在"没人想过的组合"上（例如
 * {@code succeeded → sent} 把一次已成功的命令重新打开、或 {@code cancelled → timeout} 让取消失效）。
 * 抽查只能覆盖作者想到的那几条，穷举才能把"没想过的"暴露出来。</p>
 *
 * <p><b>本表的语义</b>：终态 = 「自动转换停止」，但设计 §7.2 明确允许**人工重发**把
 * {@code failed}/{@code timeout} 重新打开为 {@code sent}（同 requestId、retry_count+1）；
 * {@code succeeded}/{@code cancelled} 不在其列。{@code pending → succeeded/failed} 是**有意放宽**：
 * 平台"插 pending → 投递 → 改 sent"之间设备可能已回执（否则这条回执会被当重复丢弃、命令随后假超时）。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
class CommandInstanceStatusTest {

    /** 期望的完整转换表（与实现里的 ALLOWED 独立书写，防止"照着实现抄一遍"的恒真测试）。 */
    private static final Map<CommandInstanceStatus, Set<CommandInstanceStatus>> EXPECTED = Map.of(
        CommandInstanceStatus.PENDING, Set.of(CommandInstanceStatus.SENT,
            CommandInstanceStatus.SUCCEEDED, CommandInstanceStatus.FAILED,
            CommandInstanceStatus.TIMEOUT, CommandInstanceStatus.CANCELLED),
        CommandInstanceStatus.SENT, Set.of(CommandInstanceStatus.SUCCEEDED,
            CommandInstanceStatus.FAILED, CommandInstanceStatus.TIMEOUT,
            CommandInstanceStatus.CANCELLED),
        CommandInstanceStatus.SUCCEEDED, Set.of(),
        CommandInstanceStatus.FAILED, Set.of(CommandInstanceStatus.SENT),
        CommandInstanceStatus.TIMEOUT, Set.of(CommandInstanceStatus.SENT),
        CommandInstanceStatus.CANCELLED, Set.of());

    @Test
    @DisplayName("36 种转换逐个断言（含非法转换必须被拒）")
    void allTransitionsMustMatchTheTable() {
        for (CommandInstanceStatus from : CommandInstanceStatus.values()) {
            for (CommandInstanceStatus to : CommandInstanceStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                    .as("%s → %s 的判定必须为 %s", from.getCode(), to.getCode(), expected)
                    .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("空目标一律拒绝（防调用方传 null 时被当成'允许'）")
    void nullTargetMustBeRejected() {
        for (CommandInstanceStatus from : CommandInstanceStatus.values()) {
            assertThat(from.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    @DisplayName("可重发只含 failed/timeout；终态只含 succeeded/failed/timeout/cancelled")
    void resendableAndTerminalSets() {
        assertThat(CommandInstanceStatus.FAILED.isResendable()).isTrue();
        assertThat(CommandInstanceStatus.TIMEOUT.isResendable()).isTrue();
        assertThat(CommandInstanceStatus.SUCCEEDED.isResendable()).isFalse();
        assertThat(CommandInstanceStatus.PENDING.isResendable()).isFalse();
        assertThat(CommandInstanceStatus.SENT.isResendable()).isFalse();
        assertThat(CommandInstanceStatus.CANCELLED.isResendable()).isFalse();

        assertThat(CommandInstanceStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(CommandInstanceStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(CommandInstanceStatus.FAILED.isTerminal()).isTrue();
        assertThat(CommandInstanceStatus.TIMEOUT.isTerminal()).isTrue();
        assertThat(CommandInstanceStatus.PENDING.isTerminal()).isFalse();
        assertThat(CommandInstanceStatus.SENT.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("按码解析：大小写无关；未知码返回 null（不猜、不兜底成某个状态）")
    void ofCodeMustBeStrict() {
        assertThat(CommandInstanceStatus.ofCode("pending")).isEqualTo(CommandInstanceStatus.PENDING);
        assertThat(CommandInstanceStatus.ofCode(" SENT ")).isEqualTo(CommandInstanceStatus.SENT);
        assertThat(CommandInstanceStatus.ofCode("Succeeded")).isEqualTo(CommandInstanceStatus.SUCCEEDED);
        assertThat(CommandInstanceStatus.ofCode("nope")).isNull();
        assertThat(CommandInstanceStatus.ofCode(null)).isNull();
    }
}
