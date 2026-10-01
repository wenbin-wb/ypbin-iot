package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.openapi.OpenApiKeyService;
import cn.ypbin.admin.iot.openapi.OpenApiKeyVerifyDtos;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API Key **内部校验**端点（网关调用；受既有 /internal/** 的 X-Internal-Token 守护）。
 *
 * <p>失败统一 INVALID（防枚举）；成功返回虚拟主体 ID（保留段）+ scopes + 租户 —
 * 网关据此注入内部身份头（B2 已让 iot 权限解析认识虚拟主体）。</p>
 */
@RestController
@RequestMapping("/internal/open-api-key")
public class InternalOpenApiKeyController {

    private final OpenApiKeyService openApiKeyService;

    public InternalOpenApiKeyController(OpenApiKeyService openApiKeyService) {
        this.openApiKeyService = openApiKeyService;
    }

    @PostMapping("/verify")
    public R<OpenApiKeyVerifyDtos.VerifyResp> verify(
        @Valid @RequestBody OpenApiKeyVerifyDtos.VerifyReq req) {
        return R.ok(openApiKeyService.verify(req));
    }
}