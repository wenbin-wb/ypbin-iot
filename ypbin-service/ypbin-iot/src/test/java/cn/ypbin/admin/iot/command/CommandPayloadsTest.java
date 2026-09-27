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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.iot.enums.CommandKind;
import cn.ypbin.starter.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 下行 topic/payload 构造的门禁：**主题注入防护**、payload 契约、体积护栏。
 *
 * <p>主题注入不是理论风险：{@code down/service/{identifier}} 里的 identifier 来自请求，含 {@code /}
 * {@code +} {@code #} 就能把主题扩成更宽的模式（{@code +}/{@code #} 是 MQTT 通配符）。
 * 单测因此把"非法标识必须被拒"逐字符集钉死。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
class CommandPayloadsTest {

    private static final long TENANT = 1L;

    private static final long DEVICE = 9300012L;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("三个下行主题与设计 §5.1 逐字一致")
    void topicsMustMatchDesign() {
        assertThat(CommandPayloads.topic(CommandKind.PROPERTY_SET, TENANT, DEVICE, "temperature"))
            .isEqualTo("ypbin/v1/1/9300012/down/property/set");
        assertThat(CommandPayloads.topic(CommandKind.PROPERTY_GET, TENANT, DEVICE, null))
            .isEqualTo("ypbin/v1/1/9300012/down/property/get");
        assertThat(CommandPayloads.topic(CommandKind.SERVICE_CALL, TENANT, DEVICE, "setTemp"))
            .isEqualTo("ypbin/v1/1/9300012/down/service/setTemp");
    }

    @Test
    @DisplayName("property_set：properties 是映射（点位 → value），requestId 在报文里")
    void propertySetPayload() {
        String payload = CommandPayloads.build(CommandKind.PROPERTY_SET, "cmd-1", "temperature",
            "{\"value\":25.0}", mapper);
        JsonNode node = mapper.readTree(payload);
        assertThat(node.get("requestId").asString()).isEqualTo("cmd-1");
        assertThat(node.get("properties").get("temperature").asDouble()).isEqualTo(25.0d);
    }

    @Test
    @DisplayName("property_get：有标识是单元素列表；**空标识 = 全部可读属性（空数组）**")
    void propertyGetPayload() {
        JsonNode withId = mapper.readTree(CommandPayloads.build(CommandKind.PROPERTY_GET, "cmd-2",
            "humidity", null, mapper));
        assertThat(withId.get("properties").isArray()).isTrue();
        assertThat(withId.get("properties")).hasSize(1);
        assertThat(withId.get("properties").get(0).asString()).isEqualTo("humidity");

        JsonNode all = mapper.readTree(CommandPayloads.build(CommandKind.PROPERTY_GET, "cmd-3",
            null, null, mapper));
        assertThat(all.get("properties").isArray()).isTrue();
        assertThat(all.get("properties")).as("空数组 = 该设备全部可读属性（评审确认的语义）").isEmpty();
    }

    @Test
    @DisplayName("service_call：params 原样进 payload")
    void serviceCallPayload() {
        JsonNode node = mapper.readTree(CommandPayloads.build(CommandKind.SERVICE_CALL, "cmd-4",
            "setTemp", "{\"target\":26}", mapper));
        assertThat(node.get("params").get("target").asInt()).isEqualTo(26);
    }

    @Test
    @DisplayName("主题注入：含 / + # 空格 或超长的标识在**发布之前**被拒（三个类型都验）")
    void invalidIdentifierMustBeRejectedBeforePublish() {
        String[] bad = {"../evil", "a/b", "a#", "a+b", "a b", "x".repeat(129)};
        for (String identifier : bad) {
            assertThatThrownBy(() -> CommandPayloads.topic(CommandKind.SERVICE_CALL, TENANT, DEVICE,
                identifier)).as("主题里的非法标识必须被拒：%s", identifier)
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> CommandPayloads.build(CommandKind.SERVICE_CALL, "cmd", identifier,
                null, mapper)).as("payload 里的非法标识必须被拒：%s", identifier)
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> CommandPayloads.build(CommandKind.PROPERTY_SET, "cmd", identifier,
                "{\"value\":1}", mapper)).as("属性设置的非法标识必须被拒：%s", identifier)
                .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("property_set 缺 value、params 不是对象、params/报文超限：都在构造期拒绝")
    void malformedParamsMustBeRejected() {
        assertThatThrownBy(() -> CommandPayloads.build(CommandKind.PROPERTY_SET, "cmd", "temperature",
            "{}", mapper)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandPayloads.build(CommandKind.PROPERTY_SET, "cmd", "temperature",
            "[1,2]", mapper)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandPayloads.build(CommandKind.PROPERTY_SET, "cmd", "temperature",
            "not-json", mapper)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandPayloads.readObjectOrNull(
            "{\"value\":\"" + "x".repeat(CommandPayloads.MAX_PAYLOAD_LENGTH) + "\"}", mapper))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CommandPayloads.build(CommandKind.SERVICE_CALL, "cmd", "setTemp",
            "{\"blob\":\"" + "x".repeat(70 * 1024) + "\"}", mapper))
            .as("构造出的报文超过 64KB 必须拒绝").isInstanceOf(BusinessException.class);
    }
}
