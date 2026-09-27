/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.ypbin.admin.common.config.InternalProperties;
import cn.ypbin.admin.iot.config.InternalMqttTokenFilter;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectReason;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectionException;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestReq;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestResult;
import cn.ypbin.admin.iot.service.MqttReadingIngestService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MQTT 入站端点的**原始 HTTP 状态码**门禁（设计 D2 破例范围，H10）。
 *
 * <p><b>为什么断言原始状态码而不是 {@code R.code}</b>：本端点的破例**就是**「对端只看状态码」。
 * 只断言业务码的用例在「有人把实现改回 HTTP 200 信封」时**依然全绿**——那正是设计 §6.5 最担心的
 * 静默失效形态。因此这里全部用 {@code status().isXxx()}，并专门包含「凭证错误必须是 401、不能是 200」
 * 的用例。</p>
 *
 * <p><b>为什么用 standalone MockMvc</b>：被验证的是「控制器 + 凭证过滤器」的组合行为，不需要起
 * Spring 容器（也就不依赖 DB/Redis/Nacos）；起容器会把断言从「HTTP 语义」变成「整栈行为」，
 * 失败时难以定位。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
class InternalMqttReadingControllerTest {

    private static final String TOKEN = "unit-test-internal-token";

    private MqttReadingIngestService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(MqttReadingIngestService.class);
        mockMvc = buildMockMvc(TOKEN);
    }

    @Test
    @DisplayName("凭证缺失/错误必须是**原始 401**（不是 200 + R.code；否则 EMQX 判成功而静默丢数据）")
    void wrongTokenMustBeRawUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Internal-Token", "wrong-token"))
            .andExpect(status().isUnauthorized());
        verify(service, never()).ingest(anyString());
    }

    @Test
    @DisplayName("凭证未配置时 fail-closed：原始 401，且不放行到服务层")
    void missingConfiguredTokenMustFailClosed() throws Exception {
        MockMvc closed = buildMockMvc(null);
        closed.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Internal-Token", TOKEN))
            .andExpect(status().isUnauthorized());
        verify(service, never()).ingest(anyString());
    }

    @Test
    @DisplayName("非法报文返回 400（EMQX 据此判不可重试），且响应体带原因码")
    void rejectionMustBeRawBadRequest() throws Exception {
        when(service.ingest(anyString())).thenThrow(
            new MqttIngestRejectionException(MqttIngestRejectReason.QUALITY_INVALID, "质量码非法"));
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"r1\"}")
                .header("X-Internal-Token", TOKEN))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("QUALITY_INVALID"));
    }

    @Test
    @DisplayName("可重试故障返回 503（官方源码里 503/429 才被 EMQX 判为 recoverable）")
    void transientFailureMustBeRawServiceUnavailable() throws Exception {
        when(service.ingest(anyString()))
            .thenThrow(new DataAccessResourceFailureException("db down"));
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"r1\"}")
                .header("X-Internal-Token", TOKEN))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("TRANSIENT"));
    }

    @Test
    @DisplayName("合法报文返回 200，响应体是 R 信封（成功路径沿用仓内惯例）")
    void acceptedMustBeOk() throws Exception {
        when(service.ingest(anyString()))
            .thenReturn(MqttReadingIngestResult.accepted(9300012L, "r1", 1, 0));
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"r1\"}")
                .header("X-Internal-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accepted").value(1))
            .andExpect(jsonPath("$.data.duplicated").value(false));
    }

    @Test
    @DisplayName("幂等重投返回 200 且 duplicated=true（EMQX 不重试、也不重复落库）")
    void duplicateMustBeOkWithFlag() throws Exception {
        when(service.ingest(anyString()))
            .thenReturn(MqttReadingIngestResult.duplicated(9300012L, "r1", 1));
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"r1\"}")
                .header("X-Internal-Token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.duplicated").value(true));
    }

    @Test
    @DisplayName("服务层判超长报文时，控制器映射为原始 400 + BODY_TOO_LARGE（护栏与计数都在服务层）")
    void oversizedBodyMustBeRejected() throws Exception {
        // 体积护栏**刻意不在控制器**：控制器直接写 400 会绕过服务层的指标计数
        // （独立复核实测到 iot.mqtt.ingest.rejected{reason=BODY_TOO_LARGE} 恒为 0）。
        // 因此这里只断言"服务层的拒绝被映射成原始 400"，护栏本身与计数由
        // MqttReadingIngestServiceImplTest#oversizedBodyMustBeRejectedAndCounted 覆盖。
        String huge = "{\"requestId\":\"r1\",\"pad\":\""
            + "x".repeat(MqttReadingIngestReq.MAX_BODY_LENGTH + 10) + "\"}";
        when(service.ingest(anyString())).thenThrow(
            new MqttIngestRejectionException(MqttIngestRejectReason.BODY_TOO_LARGE, "超长"));
        mockMvc.perform(post("/internal/mqtt/readings")
                .contentType(MediaType.APPLICATION_JSON).content(huge)
                .header("X-Internal-Token", TOKEN))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BODY_TOO_LARGE"));
    }

    /**
     * 构造 standalone MockMvc（本控制器 + 凭证过滤器，用本测试的服务 mock）。
     *
     * @param token 配置的凭证（{@code null} = 未配置 ⇒ 应 fail-closed）
     * @return MockMvc
     */
    private MockMvc buildMockMvc(String token) {
        InternalProperties properties = new InternalProperties();
        properties.setToken(token);
        return MockMvcBuilders
            .standaloneSetup(new InternalMqttReadingController(service, jsonMapper()))
            .addFilters(new InternalMqttTokenFilter(properties))
            .build();
    }

    /**
     * 构造与运行期同构的 ObjectMapper（Jackson 3：Java 时间类型是内建支持，无需额外模块）。
     *
     * @return mapper
     */
    private static ObjectMapper jsonMapper() {
        return JsonMapper.builder().build();
    }
}
