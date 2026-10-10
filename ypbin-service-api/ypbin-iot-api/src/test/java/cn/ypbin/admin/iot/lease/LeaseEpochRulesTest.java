/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 台账版本号与租约判据的纯函数测试（不起 Spring、不连库）。
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseEpochRulesTest {

    @Test
    @DisplayName("快照/事件只有在版本**更新**时才可采用/应用（等于不算，防止旧数据覆盖新数据）")
    void shouldAdoptOnlyNewerEpoch() {
        assertThat(LeaseEpochRules.shouldAdoptSnapshot(3L, 4L)).isTrue();
        assertThat(LeaseEpochRules.shouldAdoptSnapshot(4L, 4L)).isFalse();
        assertThat(LeaseEpochRules.shouldAdoptSnapshot(4L, 3L)).isFalse();
        assertThat(LeaseEpochRules.shouldApplyEvent(3L, 4L)).isTrue();
        assertThat(LeaseEpochRules.shouldApplyEvent(4L, 4L)).isFalse();
    }

    @Test
    @DisplayName("nextEpoch 逐次 +1；到达上限时显式报错（不静默溢出）")
    void nextEpochShouldIncrementOrFailLoudly() {
        assertThat(LeaseEpochRules.nextEpoch(1L)).isEqualTo(2L);
        assertThat(LeaseEpochRules.nextEpoch(Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> LeaseEpochRules.nextEpoch(Long.MAX_VALUE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已达上限");
        assertThat(LeaseEpochRules.INITIAL_EPOCH).isEqualTo(1L);
    }

    @Test
    @DisplayName("状态判据：只有 ACTIVE 不需要停采；null 状态按需要停采处理（fail-safe）")
    void shouldFenceOnlyForNonActiveState() {
        assertThat(LeaseEpochRules.shouldFence(LeaseState.ACTIVE)).isFalse();
        assertThat(LeaseEpochRules.shouldFence(LeaseState.PENDING_TAKEOVER)).isTrue();
        assertThat(LeaseEpochRules.shouldFence(LeaseState.RELEASED)).isTrue();
        assertThat(LeaseEpochRules.shouldFence(null)).isFalse();
    }

    @Test
    @DisplayName("组合判据：状态失效 **或** 已过期 **或** 到期时间为空 ⇒ 必须自我停采")
    void needsSelfFenceShouldCombineStateAndExpiry() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 12, 0);
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, now.plusSeconds(1), now)).isFalse();
        // 边界：到期时刻**等于** now 视为已过期（!isAfter）
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, now, now)).isTrue();
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, now.minusSeconds(1), now)).isTrue();
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, null, now)).isTrue();
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.RELEASED, now.plusSeconds(60), now)).isTrue();
    }

    @Test
    @DisplayName("状态码双向映射：稳定码可解析，非法码返回 null（不静默兜底成 ACTIVE）")
    void stateCodeShouldRoundTrip() {
        for (LeaseState state : LeaseState.values()) {
            assertThat(LeaseState.ofCode(state.getCode())).isEqualTo(state);
            assertThat(state.getDesc()).isNotBlank();
        }
        assertThat(LeaseState.ofCode("nope")).isNull();
        assertThat(LeaseState.ACTIVE.getCode()).isEqualTo("active");
    }

    @Test
    @DisplayName("快照之后的事件要重放；到期判据用 !isAfter")
    void replayAndExpiryBoundaries() {
        assertThat(LeaseEpochRules.shouldReplayAfterSnapshot(2L, 3L)).isTrue();
        assertThat(LeaseEpochRules.shouldReplayAfterSnapshot(3L, 3L)).isFalse();
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 12, 0);
        assertThat(LeaseEpochRules.isLeaseExpired(now, now)).isTrue();
        assertThat(LeaseEpochRules.isLeaseExpired(now.plusNanos(1), now)).isFalse();
    }

    @Test
    @DisplayName("★ epoch 分页入参：节点必填、limit 有界、offset 非负（非法一律抛，不回落全量）")
    void epochPageArgumentsMustBeValidated() {
        // 合法：边界值也要能过（1 / 上限 / offset=0）
        LeaseEpochRules.validateEpochPage("access-1", 1, 0);
        LeaseEpochRules.validateEpochPage("access-1", LeaseEpochRules.EPOCH_PAGE_MAX_LIMIT, 0);
        LeaseEpochRules.validateEpochPage("access-1", LeaseEpochRules.EPOCH_PAGE_DEFAULT_LIMIT, 500);

        assertThatThrownBy(() -> LeaseEpochRules.validateEpochPage(null, 500, 0))
            .as("节点为空 ⇒ 拒绝（不按节点过滤就等于拉全平台）")
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accessNode");
        assertThatThrownBy(() -> LeaseEpochRules.validateEpochPage("   ", 500, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaseEpochRules.validateEpochPage("access-1", 0, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> LeaseEpochRules.validateEpochPage("access-1",
            LeaseEpochRules.EPOCH_PAGE_MAX_LIMIT + 1, 0))
            .as("超过上限 ⇒ 拒绝（否则可用大 limit 把退化行为再打开）")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaseEpochRules.validateEpochPage("access-1", 500, -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("offset");
    }
}
