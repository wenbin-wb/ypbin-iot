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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.ypbin.admin.iot.retention.RetentionCleanupResult;
import cn.ypbin.admin.iot.retention.RetentionCleanupService;
import cn.ypbin.admin.iot.retention.RetentionSkipReason;
import cn.ypbin.starter.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code POST /internal/retention/cleanup} 的 **HTTP 层契约用例**（#227）。
 *
 * <p>为什么要有这一层：内部端点的对外契约是「HTTP 200 + {@code R.code}，是否执行看
 * {@code data.executed}」。只测服务层钉不住「开关关闭/防重入/失败」在响应体里的形态——
 * 尤其「关闭时不能静默成功」这条，必须断言 {@code executed=false + skipReason=disabled}。</p>
 *
 * <p>守卫（{@code X-Internal-Token}）由 {@code InternalTokenGuardWebConfig} 挂在
 * {@code /internal/**} 上，不参与本 standaloneSetup；本用例只钉控制器契约。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
class InternalRetentionControllerTest {

    private RetentionCleanupService retentionCleanupService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        retentionCleanupService = mock(RetentionCleanupService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalRetentionController(retentionCleanupService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("★ 正常一轮：R.code=200、executed=true，并带回各表删除行数与耗时")
    void executedRoundMustReturnCounts() throws Exception {
        when(retentionCleanupService.cleanupOnce())
            .thenReturn(RetentionCleanupResult.executed(5, 3, 12L));

        mockMvc.perform(post("/internal/retention/cleanup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.executed").value(true))
            .andExpect(jsonPath("$.data.skipReason").value("none"))
            .andExpect(jsonPath("$.data.outageEvents").value(5))
            .andExpect(jsonPath("$.data.maintenanceWindows").value(3))
            .andExpect(jsonPath("$.data.totalDeleted").value(8))
            .andExpect(jsonPath("$.data.durationMs").value(12));

        verify(retentionCleanupService).cleanupOnce();
    }

    @Test
    @DisplayName("★ 开关关闭：不执行且如实返回 disabled（不静默成功）")
    void disabledMustReturnReason() throws Exception {
        when(retentionCleanupService.cleanupOnce())
            .thenReturn(RetentionCleanupResult.skipped(RetentionSkipReason.DISABLED, 0L));

        mockMvc.perform(post("/internal/retention/cleanup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.executed").value(false))
            .andExpect(jsonPath("$.data.skipReason").value("disabled"))
            .andExpect(jsonPath("$.data.totalDeleted").value(0));
    }

    @Test
    @DisplayName("★ 配置非法：不执行且如实返回 invalid-config（绝不当成清空全表）")
    void invalidConfigMustReturnReason() throws Exception {
        when(retentionCleanupService.cleanupOnce())
            .thenReturn(RetentionCleanupResult.skipped(RetentionSkipReason.INVALID_CONFIG, 0L));

        mockMvc.perform(post("/internal/retention/cleanup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.executed").value(false))
            .andExpect(jsonPath("$.data.skipReason").value("invalid-config"));
    }

    @Test
    @DisplayName("★ 防重入：上一轮未结束 ⇒ executed=false + already-running（不再删一轮）")
    void alreadyRunningMustReturnReason() throws Exception {
        when(retentionCleanupService.cleanupOnce())
            .thenReturn(RetentionCleanupResult.skipped(RetentionSkipReason.ALREADY_RUNNING, 0L));

        mockMvc.perform(post("/internal/retention/cleanup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.executed").value(false))
            .andExpect(jsonPath("$.data.skipReason").value("already-running"));
    }

    @Test
    @DisplayName("★ 失败如实报错：服务层异常转 R.code=500（HTTP 仍 200，不吞）")
    void failureMustSurfaceAsErrorEnvelope() throws Exception {
        when(retentionCleanupService.cleanupOnce()).thenThrow(new IllegalStateException("DB 挂了"));

        mockMvc.perform(post("/internal/retention/cleanup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.success").value(false));

        verify(retentionCleanupService).cleanupOnce();
    }
}
