/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.access.decode.BytePayloadDecoder;
import cn.ypbin.admin.access.decode.BytePayloadDecoderRegistry;
import cn.ypbin.admin.access.decode.DecodeContext;
import cn.ypbin.admin.access.decode.DecodeOutcome;
import cn.ypbin.admin.access.decode.MqttBinaryNumberDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SPI 发现/装配契约单测：自动配置是否真的把解码器 bean 收进注册表（一处 SSOT）、业务 bean 是否被一并收集、
 * 非法配置是否 fail-fast。
 *
 * <p>为什么用 {@link ApplicationContextRunner} 而不是完整 {@code @SpringBootTest}：本模块整套应用上下文
 * 依赖 Nacos/远端 iot，单测不该起它；而本规则要验的正是「自动配置这一段」。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class BytePayloadDecoderAssemblyTest {

    private static final DecodeContext MQTT = new DecodeContext("100", "mqtt", "sensors/temp",
        "topic", "decimal", "big");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AccessDecodeConfiguration.class));

    @Test
    @DisplayName("未开启时：注册表存在但参考解码器不认领任何点位（等价「没有 SPI」）")
    void disabledByDefaultClaimsNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BytePayloadDecoderRegistry.class);
            BytePayloadDecoderRegistry registry = context.getBean(BytePayloadDecoderRegistry.class);
            assertThat(registry.find(MQTT)).isEmpty();
        });
    }

    @Test
    @DisplayName("开启并配置后：参考解码器被自动收集并认领 MQTT 点位")
    void referenceDecoderIsDiscovered() {
        runner.withPropertyValues(
                "ypbin.access.decode.mqtt-binary.enabled=true",
                "ypbin.access.decode.mqtt-binary.default-spec.number-type=uint",
                "ypbin.access.decode.mqtt-binary.default-spec.length=2",
                "ypbin.access.decode.mqtt-binary.default-spec.byte-order=big")
            .run(context -> {
                assertThat(context).hasNotFailed();
                BytePayloadDecoderRegistry registry = context.getBean(BytePayloadDecoderRegistry.class);
                assertThat(registry.decoders()).extracting(BytePayloadDecoder::id)
                    .containsExactly(MqttBinaryNumberDecoder.ID);
                assertThat(registry.find(MQTT)).isPresent();
                assertThat(registry.find(MQTT).orElseThrow()
                    .decode(new byte[] {0x12, 0x34}, MQTT).value()).isEqualTo(0x1234L);
            });
    }

    @Test
    @DisplayName("业务自带 BytePayloadDecoder bean 与参考实现一并被收集（扩展不改接入代码）")
    void userDecoderIsCollectedAlongside() {
        runner.withBean("customDecoder", BytePayloadDecoder.class, CustomDecoder::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                BytePayloadDecoderRegistry registry = context.getBean(BytePayloadDecoderRegistry.class);
                assertThat(registry.decoders()).extracting(BytePayloadDecoder::id)
                    .containsExactly("custom", MqttBinaryNumberDecoder.ID);
                assertThat(registry.find(MQTT)).get()
                    .extracting(BytePayloadDecoder::id).isEqualTo("custom");
            });
    }

    @Test
    @DisplayName("非法配置 fail-fast：启动即失败，不静默忽略")
    void invalidConfigurationFailsFast() {
        runner.withPropertyValues(
                "ypbin.access.decode.mqtt-binary.enabled=true",
                "ypbin.access.decode.mqtt-binary.default-spec.number-type=float",
                "ypbin.access.decode.mqtt-binary.default-spec.length=2")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure()).hasStackTraceContaining("自检未通过");
            });
    }

    /** 业务自定义解码器：认领所有 mqtt 点位（id 字典序在参考实现前，故优先命中）。 */
    private static final class CustomDecoder implements BytePayloadDecoder {

        @Override
        public String id() {
            return "custom";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return "mqtt".equals(context.protocol());
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return DecodeOutcome.ok("custom-value");
        }
    }
}
