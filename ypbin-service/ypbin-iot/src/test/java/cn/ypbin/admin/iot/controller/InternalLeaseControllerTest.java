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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.service.LeaseService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /internal/lease/epochs} 的 **HTTP 层**契约用例（R8-4 的补齐部分）。
 *
 * <p>为什么必须有这一层：{@code LeaseEpochNodeFilterIT} 是 **mapper 级**真库用例，只证明 SQL；
 * 而 R8-4 的核心是**契约**——「`accessNode` 必填，缺了就得被拒，绝不能静默退回全平台扫描」。
 * 参数绑定、缺参行为、错误码语义都只有走 HTTP 层才能钉住（复核指出这是本次契约改动最该补的空洞）。</p>
 *
 * <p>错误码口径：本平台内部端点的约定是 **HTTP 200 + {@code R.code}**（见 {@code GlobalExceptionHandler}）。
 * 这里断言的是 `R.code`：缺参/空白参必须是 **400（请求参数有误）**，而不是落到兜底分支的 500
 * （后者会伴随 ERROR 全栈日志，把「调用方传错了」伪装成「平台内部故障」）。</p>
 *
 * @author wenbin
 * @since 2026-10-09
 */
class InternalLeaseControllerTest {

    private LeaseService leaseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        leaseService = mock(LeaseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalLeaseController(leaseService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("★ 缺 accessNode：必须被拒（R.code=400），且绝不打到服务层——否则等于允许全平台扫描")
    void missingAccessNodeMustBeRejected() throws Exception {
        mockMvc.perform(get("/internal/lease/epochs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.success").value(false));

        verify(leaseService, never()).batchEpoch(anyString());
    }

    @Test
    @DisplayName("★ 空白 accessNode（空格/空串）：同样必须被拒（R.code=400）")
    void blankAccessNodeMustBeRejected() throws Exception {
        mockMvc.perform(get("/internal/lease/epochs").param("accessNode", "   "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/internal/lease/epochs").param("accessNode", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));

        verify(leaseService, never()).batchEpoch(anyString());
    }

    @Test
    @DisplayName("合法 accessNode：透传给服务层（原值，不 trim），返回成功信封")
    void validAccessNodeMustBePassedThrough() throws Exception {
        when(leaseService.batchEpoch("access-1")).thenReturn(new TenantEpochBatchResp());

        mockMvc.perform(get("/internal/lease/epochs").param("accessNode", "access-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(leaseService).batchEpoch("access-1");
    }

    @Test
    @DisplayName("带空格的 accessNode 也必须**原值**透传（读侧不得 trim：写侧存的就是原值）")
    void accessNodeMustNotBeTrimmedOnRead() throws Exception {
        when(leaseService.batchEpoch(" access-1 ")).thenReturn(new TenantEpochBatchResp());

        mockMvc.perform(get("/internal/lease/epochs").param("accessNode", " access-1 "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(leaseService).batchEpoch(" access-1 ");
    }
}
