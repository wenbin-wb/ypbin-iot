package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.openapi.OpenApiKeyDtos;
import cn.ypbin.admin.iot.openapi.OpenApiKeyService;
import cn.ypbin.starter.core.model.R;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API Key 管理端点（看板 #11 第 1 批）。
 *
 * <p>⚠️ 路径刻意用 <b>/open-api-keys</b>（不是 /open-api/**）—— 网关的开放 API 过滤器只匹配
 * {@code /iot/open-api/v1/**}，管理面走既有 iot 路由与会话鉴权，绝不错配。</p>
 */
@RestController
@RequestMapping("/open-api-keys")
public class OpenApiKeyController {

    private final OpenApiKeyService openApiKeyService;

    public OpenApiKeyController(OpenApiKeyService openApiKeyService) {
        this.openApiKeyService = openApiKeyService;
    }

    /** 创建（明文仅此一次返回）。 */
    @PostMapping
    @SaCheckPermission("iot:openapi:key-create")
    public R<OpenApiKeyDtos.CreateResp> create(@Valid @RequestBody OpenApiKeyDtos.CreateReq req) {
        return R.ok(openApiKeyService.create(req));
    }

    /** 列表（只回 prefix）。 */
    @GetMapping
    @SaCheckPermission("iot:openapi:key-list")
    public R<List<OpenApiKeyDtos.ListItemResp>> list() {
        return R.ok(openApiKeyService.listAll());
    }

    /** 吊销（立即生效）。 */
    @PostMapping("/{id}/revoke")
    @SaCheckPermission("iot:openapi:key-revoke")
    public R<Void> revoke(@PathVariable("id") Long id) {
        openApiKeyService.revoke(id);
        return R.ok();
    }
}