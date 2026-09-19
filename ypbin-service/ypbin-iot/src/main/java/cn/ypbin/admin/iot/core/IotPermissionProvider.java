/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.core;

import cn.ypbin.admin.system.api.cache.SysCache;
import cn.ypbin.starter.security.core.PermissionProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * IoT 服务的权限数据源。
 *
 * <p>⚠️ <b>不实现它会让本服务的所有 {@code @SaCheckPermission} 端点必然 403</b>：
 * starter 默认装一个「空权限数据源」（返回空列表 ⇒ 一律判无权），而本服务三个端点都带权限码。
 * 与 {@code AiPermissionProvider} 同构：薄适配 {@code SysCache}，查询结果缺失时按拒绝处理（不静默放行）。</p>
 *
 * <p>为什么走 {@code SysCache} 而不是每次 Feign：{@code @SaCheckPermission} 在请求线程上同步执行，
 * 直连 system 会让每个请求都多一次远程调用；SysCache 是带本地缓存的既有通道。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class IotPermissionProvider implements PermissionProvider {

    private static final Logger log = LoggerFactory.getLogger(IotPermissionProvider.class);

    @Override
    public List<String> getPermissions(Object loginId, String loginType) {
        Long userId = resolveUserId(loginId, loginType);
        if (userId == null) {
            return List.of();
        }
        List<String> permissions = SysCache.getUserPermissions(userId);
        if (permissions == null) {
            log.error("[iot] 权限码查询结果缺失，按拒绝处理（未放行）：userId={}", userId);
            return List.of();
        }
        return permissions;
    }

    @Override
    public List<String> getRoles(Object loginId, String loginType) {
        Long userId = resolveUserId(loginId, loginType);
        if (userId == null) {
            return List.of();
        }
        List<String> roleCodes = SysCache.getUserRoleCodes(userId);
        if (roleCodes == null) {
            log.error("[iot] 角色码查询结果缺失，按无角色处理（未放行）：userId={}", userId);
            return List.of();
        }
        return roleCodes;
    }

    /**
     * 解析登录用户 ID（loginId 可能是字符串形态，非数字按无权限处理）。
     *
     * @param loginId   登录标识
     * @param loginType 登录类型（仅用于日志）
     * @return 用户 ID；无法解析时返回 {@code null}
     */
    private Long resolveUserId(Object loginId, String loginType) {
        if (loginId == null) {
            log.warn("[iot] 权限查询缺少 loginId，按无权限处理：loginType={}", loginType);
            return null;
        }
        try {
            return Long.valueOf(loginId.toString().trim());
        } catch (NumberFormatException ex) {
            log.warn("[iot] 权限查询的 loginId 不是合法用户 ID，按无权限处理：loginType={}, loginId={}",
                loginType, loginId);
            return null;
        }
    }
}
