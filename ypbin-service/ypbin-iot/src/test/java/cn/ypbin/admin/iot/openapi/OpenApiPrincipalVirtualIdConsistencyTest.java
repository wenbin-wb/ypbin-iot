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

import cn.ypbin.starter.security.identity.VirtualPrincipalScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 虚拟主体 ID 上界的一致性门禁。
 *
 * <p>为什么要有它：虚拟 ID 上界曾在**三处**各写一遍字面量（starter
 * `VirtualPrincipalScopes.DEFAULT_VIRTUAL_USER_ID_MAX`、本仓
 * `OpenApiPrincipal.VIRTUAL_USER_ID_MAX`、`OpenApiKeyConstants.VIRTUAL_USER_ID_BASE`）。
 * 一旦漂移，就会出现"网关认为是虚拟主体、服务端认为是真实用户"（或反之）的**身份误判**——
 * 正是 `OpenApiPrincipal` 类注释警告的"Key 意外持有真实用户权限"。</p>
 *
 * <p>现以 starter 为准（`OpenApiPrincipal` 直接引用 starter 常量），本门禁把三处钉在一起：
 * 任何一处改字面量都会转红。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
class OpenApiPrincipalVirtualIdConsistencyTest {

    @Test
    @DisplayName("三处虚拟 ID 上界必须同值（starter 为准）")
    void threeBoundsMustAgree() {
        assertThat(OpenApiPrincipal.VIRTUAL_USER_ID_MAX)
            .as("OpenApiPrincipal 必须直接引用 starter 常量，不复制字面量")
            .isEqualTo(VirtualPrincipalScopes.DEFAULT_VIRTUAL_USER_ID_MAX);
        assertThat(OpenApiKeyConstants.VIRTUAL_USER_ID_BASE)
            .as("OpenApiKeyConstants 的保留段上界必须与 starter 同值"
                + "（api 模块不依赖 starter-security，故保留字面量 + 本门禁，而非直接引用）")
            .isEqualTo(VirtualPrincipalScopes.DEFAULT_VIRTUAL_USER_ID_MAX);
    }

    @Test
    @DisplayName("本仓判定必须与 starter 判定在边界上完全一致")
    void localJudgementMustAgreeWithStarter() {
        long bound = VirtualPrincipalScopes.DEFAULT_VIRTUAL_USER_ID_MAX;
        // 上界本身、其邻域、哨兵值、极值：两侧必须逐点一致
        for (long id : new long[]{bound, bound + 1, bound - 1,
            0L, 1L, -1L, -3L, -4L, -5L, -999_999_999L,
            Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThat(OpenApiPrincipal.isVirtualPrincipal(id))
                .as("userId=%d 两侧判定必须一致", id)
                .isEqualTo(VirtualPrincipalScopes.isVirtualPrincipal(id));
        }
        assertThat(OpenApiPrincipal.isVirtualPrincipal(null))
            .isEqualTo(VirtualPrincipalScopes.isVirtualPrincipal(null));
    }

    @Test
    @DisplayName("单 Key 虚拟 ID 恒落在虚拟段内（结构属性：行 id 为正数且在安全范围内）")
    void virtualUserIdAlwaysInVirtualSegment() {
        // 真实行 id 是雪花 ID（正数，实测约 19 位、2.1e18 量级），远小于下溢边界；
        // 此处另加边界值覆盖，证明"正常 id 范围内恒虚拟"。
        for (long rowId : new long[]{1L, 5L, 2104373415356145665L, 9223372035854775807L}) {
            long virtualId = OpenApiKeyConstants.virtualUserId(rowId);
            assertThat(OpenApiPrincipal.isVirtualPrincipal(virtualId))
                .as("rowId=%d 算出的虚拟 ID 必须被识别为虚拟主体", rowId)
                .isTrue();
            assertThat(VirtualPrincipalScopes.isVirtualPrincipal(virtualId))
                .as("rowId=%d 算出的虚拟 ID 必须被 starter 识别为虚拟主体", rowId)
                .isTrue();
        }
    }

    @Test
    @DisplayName("下溢边界如实登记：超过 9223372035854775807 的行 id 会回绕（非虚拟）")
    void virtualUserIdOverflowBoundaryIsDocumented() {
        // virtualUserId = BASE - rowId；rowId 超过 9223372035854775807
        //（= BASE - Long.MIN_VALUE）时减法下溢回绕，结果为正数 ⇒ 不再是虚拟主体。
        // 真实雪花 ID 达不到该量级（实测 2.1e18），故生产不受影响；此处显式断言，
        // 防止将来有人把"恒虚拟"当成无条件成立的不变量。
        assertThat(OpenApiPrincipal.isVirtualPrincipal(
                OpenApiKeyConstants.virtualUserId(Long.MAX_VALUE)))
            .as("超出安全范围的行 id 回绕后不再是虚拟主体（已知边界，非缺陷）")
            .isFalse();
    }
}
