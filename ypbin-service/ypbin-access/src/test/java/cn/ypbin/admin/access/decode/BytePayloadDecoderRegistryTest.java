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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

/**
 * 解码器注册/选择单测：一处 SSOT、空注册表等价「没有 SPI」、选择顺序确定（{@code @Order} 优先，
 * 同序按 id 兜底）。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class BytePayloadDecoderRegistryTest {

    private static final DecodeContext MQTT = new DecodeContext("100", "mqtt", "sensors/temp",
        "topic", "decimal", "big");

    @Test
    @DisplayName("空注册表：不认领任何点位（byte[] 仍走出口闸门）")
    void emptyRegistryClaimsNothing() {
        BytePayloadDecoderRegistry registry = BytePayloadDecoderRegistry.empty();
        assertThat(registry.decoders()).isEmpty();
        assertThat(registry.find(MQTT)).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(new BytePayloadDecoderRegistry(null).find(MQTT)).isEmpty();
    }

    @Test
    @DisplayName("挑第一个支持的解码器；null 元素被忽略；不支持的不会被选中")
    void selectsFirstSupportingDecoder() {
        BytePayloadDecoder never = decoder("b-never", false);
        BytePayloadDecoder hit = decoder("a-hit", true);
        BytePayloadDecoderRegistry registry =
            new BytePayloadDecoderRegistry(Arrays.asList(never, null, hit));

        assertThat(registry.decoders()).extracting(BytePayloadDecoder::id)
            .containsExactly("a-hit", "b-never");
        assertThat(registry.find(MQTT)).get().extracting(BytePayloadDecoder::id).isEqualTo("a-hit");
    }

    @Test
    @DisplayName("顺序确定：@Order 小的优先，即使 id 字典序更大")
    void orderAnnotationWins() {
        BytePayloadDecoderRegistry registry =
            new BytePayloadDecoderRegistry(new ArrayList<>(List.of(new LateDecoder(), new EarlyDecoder())));

        assertThat(registry.find(MQTT)).get().extracting(BytePayloadDecoder::id).isEqualTo("z-early");
    }

    @Test
    @DisplayName("同序按 id 字典序兜底（结果不随注册次序漂动）")
    void sameOrderBreaksTieById() {
        BytePayloadDecoderRegistry registry =
            new BytePayloadDecoderRegistry(new ArrayList<>(List.of(decoder("z-later", true),
                decoder("a-first", true))));

        assertThat(registry.find(MQTT)).get().extracting(BytePayloadDecoder::id).isEqualTo("a-first");
    }

    private static BytePayloadDecoder decoder(String id, boolean supports) {
        return new BytePayloadDecoder() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean supports(DecodeContext context) {
                return supports;
            }

            @Override
            public DecodeOutcome decode(byte[] payload, DecodeContext context) {
                return DecodeOutcome.ok(payload.length);
            }
        };
    }

    /** @Order 更小 ⇒ 排在前面。 */
    @Order(-5)
    private static final class EarlyDecoder implements BytePayloadDecoder {

        @Override
        public String id() {
            return "z-early";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return true;
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return DecodeOutcome.ok(1L);
        }
    }

    /** @Order 更大 ⇒ 排在后面。 */
    @Order(10)
    private static final class LateDecoder implements BytePayloadDecoder {

        @Override
        public String id() {
            return "a-late";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return true;
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return DecodeOutcome.ok(2L);
        }
    }
}
