/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.sign.core.ApiKeyCredentials;
import cn.ypbin.starter.sign.core.InMemoryNonceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开放 API {@code last_used_at} 节流回写用例（M4）。
 *
 * <p>背景：{@code verify()} 被网关对每个 {@code /iot/open-api/v1/**} 请求同步调用，旧实现每请求
 * {@code updateById} ⇒ 热点 Key 行锁竞争。修法：内存记录上次落库时刻，间隔内跳过。
 * 本用例钉住「节流生效」与「不影响鉴权正确性」，并验证间隔为 0 时回到每请求写。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class OpenApiLastUsedThrottleTest {

    private static final String TEST_PEPPER = "test-pepper-not-a-real-secret";
    private static final String SECRET = "sk_plain_secret_for_throttle";

    private IotOpenApiKeyMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(IotOpenApiKeyMapper.class);
        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(5L);
        row.setTenantId(1L);
        row.setAccessKeyId("ak_throttle");
        row.setSecretHash(ApiKeyCredentials.hashSecret(TEST_PEPPER, SECRET));
        row.setScopes("iot:series:get");
        row.setStatus(1);
        when(mapper.selectOne(any())).thenReturn(row);
    }

    private OpenApiKeyVerifyDtos.VerifyReq request() {
        OpenApiKeyVerifyDtos.VerifyReq req = new OpenApiKeyVerifyDtos.VerifyReq();
        req.setAccessKeyId("ak_throttle");
        req.setSecret(SECRET);
        return req;
    }

    @Test
    @DisplayName("★ M4：节流间隔内多次 verify 只落库一次 last_used_at，且鉴权结果仍正确")
    void repeatedVerifyWithinIntervalWritesOnce() {
        OpenApiKeyService service = new OpenApiKeyService(mapper, TEST_PEPPER,
            new InMemoryNonceStore(), false, null, 60L);

        assertThat(service.verify(request()).valid()).isTrue();
        assertThat(service.verify(request()).valid()).isTrue();
        assertThat(service.verify(request()).valid()).isTrue();

        verify(mapper, times(1)).updateById(any(IotOpenApiKey.class));
    }

    @Test
    @DisplayName("M4：节流间隔为 0 时不节流（每请求落库，保持旧行为）")
    void zeroIntervalDisablesThrottle() {
        OpenApiKeyService service = new OpenApiKeyService(mapper, TEST_PEPPER,
            new InMemoryNonceStore(), false, null, 0L);

        service.verify(request());
        service.verify(request());

        verify(mapper, times(2)).updateById(any(IotOpenApiKey.class));
    }
}
