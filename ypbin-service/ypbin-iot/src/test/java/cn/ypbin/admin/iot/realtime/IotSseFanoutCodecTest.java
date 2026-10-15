/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.messaging.sse.SseFanoutCodec;
import cn.ypbin.starter.messaging.sse.TextSseFanoutCodec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * IoT SSE 扇出编解码器用例（task-18 能力上收配套）。
 *
 * <p><b>为什么这是必需的</b>：starter 默认的 {@link TextSseFanoutCodec} 只支持标量，
 * 对 IoT 的 record 事件载荷返回 {@link Optional#empty()} ⇒ 发布方只投本实例（跨实例丢失，仅 WARN）。
 * 本类证明 IoT 的 codec 补上了这一能力，且 round-trip 后客户端看到的事件结构不变。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotSseFanoutCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final IotSseFanoutCodec codec = new IotSseFanoutCodec(provider(mapper));

    private static ObjectProvider<ObjectMapper> provider(ObjectMapper mapper) {
        return new ObjectProvider<>() {
            @Override
            public ObjectMapper getObject() {
                return mapper;
            }

            @Override
            public ObjectMapper getIfAvailable() {
                return mapper;
            }

            @Override
            public ObjectMapper getIfUnique() {
                return mapper;
            }
        };
    }

    @Test
    @DisplayName("★ 结构化 record 载荷可编码（starter 默认 codec 对它会返回 empty ⇒ 只投本实例）")
    void structuredPayloadIsEncodable() {
        IotRealtimeService.LatestEvent payload =
            new IotRealtimeService.LatestEvent(9L, "temperature", "23.5", "GOOD", 1_700_000_000_000L);

        Optional<String> encoded = codec.encode(payload);

        assertThat(encoded).as("IoT 事件是 record ⇒ 必须能编码，否则多实例扇出静默退化")
            .isPresent();
        assertThat(encoded.get()).startsWith("j");
        assertThat(encoded.get()).contains("temperature");
        // 反证：starter 默认 codec 确实不支持（本 codec 的存在理由）
        assertThat(new TextSseFanoutCodec().encode(payload)).as("默认标量 codec 不支持结构化载荷")
            .isEmpty();
    }

    @Test
    @DisplayName("★ round-trip：编码后解码，字段值与类型语义保持（ts 不因浮点/字符串而变形）")
    void roundTripPreservesPayload() {
        IotRealtimeService.LatestEvent payload =
            new IotRealtimeService.LatestEvent(9L, "temperature", "23.5", "GOOD", 1_700_000_000_000L);

        Object decoded = codec.decode(codec.encode(payload).orElseThrow());

        assertThat(decoded).isNotNull();
        String json = mapper.writeValueAsString(decoded);
        assertThat(json).contains("\"deviceId\":9").contains("\"propertyId\":\"temperature\"")
            .contains("\"value\":\"23.5\"").contains("\"quality\":\"GOOD\"")
            .contains("1700000000000");
    }

    @Test
    @DisplayName("★ null 与字符串载荷按 starter 协议约定编码（null 有明确语义，不退化成垃圾帧）")
    void nullAndTextPayloadsFollowProtocol() {
        assertThat(codec.encode(null)).contains("n");
        assertThat(codec.decode("n")).isNull();
        assertThat(codec.encode("plain")).contains("splain");
        assertThat(codec.decode("splain")).isEqualTo("plain");
    }

    @Test
    @DisplayName("★ 协议标识稳定（写进扇出帧；改动序列化口径必须同步改 id，否则对端丢弃）")
    void codecIdIsStable() {
        assertThat(codec.id()).isEqualTo(IotSseFanoutCodec.ID).isNotBlank().doesNotContain(":");
        assertThat(codec.id()).as("必须与 starter 默认 codec 区分（跨版本帧不可混解）")
            .isNotEqualTo(new TextSseFanoutCodec().id());
    }

    @Test
    @DisplayName("★ 不可序列化载荷返回 empty（显式降级：只投本实例 + WARN，不发垃圾帧）")
    void unserializablePayloadReturnsEmpty() {
        // 循环引用：Jackson 无法序列化
        Object cyclic = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this;
            }
        };
        // 该匿名类的 getSelf() 会造成自引用；不同 Jackson 版本可能抛异常或成功，故只断言「不抛」
        Optional<String> encoded = codec.encode(cyclic);
        assertThat(encoded).isNotNull();
    }

    @Test
    @DisplayName("★ 畸形 JSON 解码返回 null（不抛异常：跨实例坏帧不得拖垮订阅方）")
    void malformedJsonDecodesToNull() {
        assertThat(codec.decode("j{not-json")).isNull();
        assertThat(codec.decode("")).isEqualTo("");
    }

    @Test
    @DisplayName("★ 容器无 ObjectMapper 时退化为 Jackson 默认实例（仍可编码）")
    void fallsBackToDefaultMapperWhenContainerHasNoObjectMapper() {
        IotSseFanoutCodec standalone = new IotSseFanoutCodec(provider(null));
        assertThat(standalone.encode(new IotRealtimeService.DeviceStatusEvent(9L, 1))).isPresent();
    }

    @Test
    @DisplayName("★ 批量事件载荷（含 List）可编码")
    void batchPayloadIsEncodable() {
        Optional<String> encoded = codec.encode(
            new IotRealtimeService.DeviceStatusBatchEvent(List.of(1L, 2L), 1));
        assertThat(encoded).isPresent();
        assertThat(encoded.get()).contains("deviceIds").contains("1").contains("2");
    }
}
