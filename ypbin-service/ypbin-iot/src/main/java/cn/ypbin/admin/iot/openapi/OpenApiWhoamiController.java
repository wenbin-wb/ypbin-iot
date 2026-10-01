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

import cn.ypbin.starter.core.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API 自检（看板 #11 门面，设计 §6.4 whoami）。
 *
 * <p>无 {@code @SaCheckPermission}：任何 scope 组合的 Key 都应能自检（否则只配了
 * {@code iot:series:get} 的 Key 连"我是谁"都问不了）。鉴权不靠注解，靠
 * {@link OpenApiKeyService#whoami()} 的身份分流 —— 非虚拟主体一律拒绝
 * （fail-closed），且管理面用户走管理端点，不走这里。</p>
 *
 * <p>只回当前 Key 自身信息（不含明文 secret）；配额/状态读库实时值，吊销立即体现。</p>
 */
@RestController
@RequestMapping("/open-api/v1/whoami")
@RequiredArgsConstructor
public class OpenApiWhoamiController {

    private final OpenApiKeyService openApiKeyService;

    /** 当前 Key 自检。 */
    @GetMapping
    public R<OpenApiKeyDtos.WhoamiResp> whoami() {
        return R.ok(openApiKeyService.whoami());
    }
}
