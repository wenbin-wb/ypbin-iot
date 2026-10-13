/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 解码结果二分形态单测：成功必有值、失败必带原因，杜绝「用 null 冒充失败/成功」。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class DecodeOutcomeTest {

    @Test
    @DisplayName("成功带值、失败带原因；success() 只看 failure")
    void binaryShape() {
        DecodeOutcome ok = DecodeOutcome.ok(42L);
        assertThat(ok.success()).isTrue();
        assertThat(ok.value()).isEqualTo(42L);
        assertThat(ok.failure()).isNull();

        DecodeOutcome failed = DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH);
        assertThat(failed.success()).isFalse();
        assertThat(failed.value()).isNull();
        assertThat(failed.failure()).isEqualTo(DecodeFailure.LENGTH_MISMATCH);
    }

    @Test
    @DisplayName("null 不是合法结果：成功/失败都不得缺项，失败也不得带值")
    void nullIsNotAValidOutcome() {
        assertThatThrownBy(() -> DecodeOutcome.ok(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DecodeOutcome.failed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DecodeOutcome(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecodeOutcome(1L, DecodeFailure.INVALID_VALUE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("原因码/描述稳定（指标 reason 标签值不可随文案漂移）")
    void failureCodesAreStable() {
        assertThat(DecodeFailure.LENGTH_MISMATCH.getCode()).isEqualTo("length-mismatch");
        assertThat(DecodeFailure.NOT_FINITE.getCode()).isEqualTo("not-finite");
        assertThat(DecodeFailure.INVALID_VALUE.getCode()).isEqualTo("invalid-value");
        assertThat(DecodeFailure.CONFIG_MISSING.getCode()).isEqualTo("config-missing");
        assertThat(DecodeFailure.DECODER_ERROR.getCode()).isEqualTo("decoder-error");
        for (DecodeFailure failure : DecodeFailure.values()) {
            assertThat(failure.getDesc()).isNotBlank();
        }
    }
}
