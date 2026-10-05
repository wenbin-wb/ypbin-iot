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

import cn.ypbin.admin.iot.openapi.OpenApiPrincipal;
import cn.ypbin.admin.system.api.cache.SysCache;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.core.PermissionProvider;
import cn.ypbin.starter.security.identity.IdentityContext;
import java.util.List;
import java.util.Set;
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
 * <p><b>开放 API 的虚拟主体（看板 #11，方案 B2，2026-09-30 新增）</b>：按**用户 ID 段**分流 ——
 * 落在 {@link OpenApiPrincipal#isVirtualPrincipal} 的保留段时，权限取**该请求 scopes**
 * （网关放进既有 {@code X-Roles} 头，已由 starter 解析进 {@link LoginUser#getRoles()}），
 * **不查库**；真实用户**走原路径、行为不变**。</p>
 *
 * <p>⚠️ <b>为什么这条分流是安全的</b>：① 虚拟段是负数高位段，与真实雪花 ID（正数）**永不重叠**；
 * ② 客户端**无法**自带 {@code X-Roles} 穿透 —— 该头在网关 {@code header-sanitize} 表内会被清洗，
 * 而直连 iot 又过不了 {@code X-Gateway-Signed} 校验（SF-5 fail-closed，已实测）；
 * ③ scopes 里的**通配符会被剔除**（见 {@link OpenApiPrincipal#scopesToPermissions}），
 * 否则 starter 会把它升级成全权限（越权为超管）。</p>
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
        if (OpenApiPrincipal.isVirtualPrincipal(userId)) {
            return resolveVirtualPrincipalPermissions(userId);
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
        if (OpenApiPrincipal.isVirtualPrincipal(userId)) {
            // 虚拟主体**没有角色**：scopes 只在"权限码"这一维表达。
            // 刻意**不**把 scopes 同时当角色返回——否则 @SaCheckRole 会被 scopes 意外满足，
            // 把两种语义混在一起（fail-closed：虚拟主体一律无角色）。
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
     * 解析**虚拟主体**的权限（= 该请求的 scopes，来自网关注入的 {@code X-Roles}）。
     *
     * <p>语义与真实用户路径一致：**取不到就拒绝**（返回空列表 ⇒ 端点 403），
     * 且失败必须**留日志**（否则"Key 明明配了 scope 却 403"无从排查）。</p>
     *
     * @param userId 虚拟用户 ID（仅用于日志）
     * @return 权限码列表（**绝不返回 `null`**）
     */
    private List<String> resolveVirtualPrincipalPermissions(Long userId) {
        LoginUser loginUser = IdentityContext.getLoginUser().orElse(null);
        if (loginUser == null) {
            // fail-closed + 可见：无身份上下文 ⇒ 不授予任何权限
            log.warn("[iot] 虚拟主体缺少身份上下文，按拒绝处理：userId={}", userId);
            return List.of();
        }
        // 🔴 一致性校验（独立复核 2026-09-30 指出的"行为未定义"路径）：
        // 权限只认**这个身份自己的** scopes。若 loginId 与身份上下文里的 userId 不一致，
        // 说明线程态异常（或被错误复用），此时**绝不能**拿上下文的 roles 当权限——
        // 因为真实用户的 roles 也可能恰好长得像权限码。fail-closed + 留痕。
        if (!userId.equals(loginUser.getId())) {
            log.warn("[iot] 虚拟主体 loginId 与身份上下文不一致，按拒绝处理：loginId={}, contextUserId={}",
                userId, loginUser.getId());
            return List.of();
        }
        Set<String> scopes = loginUser.getRoles();
        if (scopes == null || scopes.isEmpty()) {
            // fail-closed + 可见：有身份但 X-Roles 为空 ⇒ 不授予任何权限
            log.warn("[iot] 虚拟主体缺少 scopes（X-Roles 为空），按拒绝处理：userId={}", userId);
            return List.of();
        }
        List<String> wildcards = OpenApiPrincipal.wildcardsIn(scopes);
        if (!wildcards.isEmpty()) {
            // 不静默：通配符被剔除是"Key 配错了/想越权"的信号，必须能查出来。
            // wildcards 来自 X-Roles 头（外部可控），必须 sanitize（CodeQL java/sensitive-log）。
            log.warn("[iot] 虚拟主体 scopes 含通配符，已剔除（防止越权为超管）：userId={}, wildcards={}",
                userId, LogSanitizer.sanitize(wildcards));
        }
        List<String> dropped = OpenApiPrincipal.droppedScopes(scopes);
        if (!dropped.isEmpty()) {
            // 白名单外的值同样不静默：否则"Key 配了 scope 却不生效"无从排查。
            // 注意 droppedScopes 已含通配符项，故这里用去掉通配符后的差集描述"非通配符的未知 scope"。
            List<String> unknown = dropped.stream().filter(item -> !wildcards.contains(item)).toList();
            if (!unknown.isEmpty()) {
                // 同上：unknown 同样来自外部头，sanitize 后再记。
                log.warn("[iot] 虚拟主体 scopes 含白名单外的值，已丢弃（不允许授予）：userId={}, dropped={}",
                    userId, LogSanitizer.sanitize(unknown));
            }
        }
        List<String> permissions = OpenApiPrincipal.scopesToPermissions(scopes);
        if (permissions.isEmpty()) {
            // 覆盖"scopes 非空但一项都不可用"（例如只填了空白、或全是白名单外的值）——
            // 上面两条日志按"通配符/白名单外"分类，这里兜住"看起来有 scopes 却全部无效"的情形，
            // 否则该情形会**静默 403**（独立复核 2026-09-30 指出的漏日志路径）。
            log.warn("[iot] 虚拟主体的 scopes 全部不可用（白名单外或为空），按拒绝处理：userId={}, scopes={}",
                userId, scopes);
        }
        return permissions;
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
