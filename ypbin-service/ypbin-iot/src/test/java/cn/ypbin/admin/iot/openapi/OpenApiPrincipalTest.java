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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 虚拟主体约定用例（看板 #11 / 方案 B2）。
 *
 * <p>重点锁死三条**安全判据**（做错了会直接导致越权，且不会报错）：</p>
 * <ol>
 *   <li><b>虚拟段不与真实用户重叠</b>：真实用户是正数雪花 ID；Sa-Token 哨兵值 `-3/-4/-5`
 *       必须**不算**虚拟主体（否则会改变它们的既有语义）；</li>
 *   <li><b>通配符必须被剔除</b>：starter 在权限集合含 `*:*:*` 时会**追加 `*`（全权限）**
 *       ⇒ 一把"只该看数据"的 Key 会越权成超管；</li>
 *   <li><b>取不到就拒绝</b>：空 scopes ⇒ 空权限列表（调用方据此 403），**绝不返回通配符兜底**。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class OpenApiPrincipalTest {

    @Test
    @DisplayName("🔴 真实用户 ID（正数雪花）绝不能被判为虚拟主体")
    void realUserIdsMustNeverBeVirtual() {
        // 生产实测见到的真实用户 ID 形态
        for (long id : new long[] {1L, 2L, 991_736_107L, 2_104_373_415_356_145_665L}) {
            assertThat(OpenApiPrincipal.isVirtualPrincipal(id))
                .as("真实用户 %s 被判为虚拟主体 ⇒ 会被改走 scopes 路径，等于绕过库权限", id)
                .isFalse();
        }
    }

    @Test
    @DisplayName("🔴 Sa-Token 哨兵值 -3/-4/-5 不算虚拟主体（保持既有语义）")
    void sentinelIdsMustNotBeVirtual() {
        for (long sentinel : new long[] {-1L, -2L, -3L, -4L, -5L, -100L}) {
            assertThat(OpenApiPrincipal.isVirtualPrincipal(sentinel))
                .as("哨兵值 %s 被判为虚拟主体会改变其既有语义", sentinel)
                .isFalse();
        }
    }

    @Test
    @DisplayName("保留段（<= -1e9）与边界值判为虚拟主体")
    void virtualSegmentMustBeRecognized() {
        assertThat(OpenApiPrincipal.isVirtualPrincipal(OpenApiPrincipal.VIRTUAL_USER_ID_MAX)).isTrue();
        assertThat(OpenApiPrincipal.isVirtualPrincipal(-1_000_000_001L)).isTrue();
        assertThat(OpenApiPrincipal.isVirtualPrincipal(-9_000_000_000_000L)).isTrue();
        // 恰好大于上界 1 ⇒ 不是虚拟
        assertThat(OpenApiPrincipal.isVirtualPrincipal(OpenApiPrincipal.VIRTUAL_USER_ID_MAX + 1))
            .isFalse();
    }

    @Test
    @DisplayName("null 不是虚拟主体（不炸）")
    void nullMustNotBeVirtual() {
        assertThat(OpenApiPrincipal.isVirtualPrincipal(null)).isFalse();
    }

    @Test
    @DisplayName("🔴 通配符 * 与 *:*:* 必须被剔除（否则 starter 会升级为全权限）")
    void wildcardsMustBeStripped() {
        Set<String> scopes = new LinkedHashSet<>(
            List.of("iot:device:list", "*", "*:*:*", "iot:series:get"));

        List<String> permissions = OpenApiPrincipal.scopesToPermissions(scopes);

        assertThat(permissions).containsExactly("iot:device:list", "iot:series:get");
        assertThat(permissions).doesNotContain("*", "*:*:*");
    }

    @Test
    @DisplayName("通配符单独出现 ⇒ 结果为空（fail-closed，不兜底放行）")
    void onlyWildcardMustYieldEmpty() {
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("*:*:*"))))
            .isEmpty();
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("*"))))
            .isEmpty();
    }

    @Test
    @DisplayName("isWildcard：只认两种通配符形态（普通权限码不算）")
    void wildcardDetection() {
        assertThat(OpenApiPrincipal.isWildcard("*")).isTrue();
        assertThat(OpenApiPrincipal.isWildcard("*:*:*")).isTrue();
        assertThat(OpenApiPrincipal.isWildcard(" *:*:* ")).isTrue();
        assertThat(OpenApiPrincipal.isWildcard("iot:device:list")).isFalse();
        assertThat(OpenApiPrincipal.isWildcard("iot:*:list")).isFalse();
        assertThat(OpenApiPrincipal.isWildcard(null)).isFalse();
    }

    @Test
    @DisplayName("⚠️ isWildcard 只用于日志分类，不是安全判据——真正的守卫是白名单")
    void isWildcardIsOnlyForLoggingNotSecurity() {
        // 独立复核（2026-09-30）指出的关键点：Sa-Token 把账号权限码**当 pattern** 走
        // SaFoxUtil.vagueMatch（仅 pattern 含 * 时才启用模糊匹配）⇒ iot:*:list 这类**段内星号**
        // 同样能命中真实权限码。故：
        //   ① isWildcard 只回答"是不是两种精确通配符"（用于日志归因）；
        //   ② **判"能不能授予"必须用 isAllowedScope（白名单精确匹配）**，绝不能用 !isWildcard()。
        // 这条边界必须钉死：一旦有人把 isWildcard 当安全判据（回到"只挡两类"），
        // patternScopesMustBeDropped 与下面的断言会一起转红。
        assertThat(OpenApiPrincipal.isWildcard("iot:*:list")).isFalse();
        assertThat(OpenApiPrincipal.isAllowedScope("iot:*:list")).isFalse();
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("iot:*:list"))))
            .isEmpty();
    }

    @Test
    @DisplayName("去空白、去重，并保持首次出现顺序（保存/展示稳定）")
    void trimmingAndDedup() {
        Set<String> scopes = new LinkedHashSet<>(
            List.of(" iot:device:list ", "iot:device:list", "  ", "iot:series:get"));

        assertThat(OpenApiPrincipal.scopesToPermissions(scopes))
            .containsExactly("iot:device:list", "iot:series:get");
    }

    @Test
    @DisplayName("空 / null scopes ⇒ 空列表（绝不 null、绝不放行）")
    void emptyScopesMustYieldEmptyList() {
        assertThat(OpenApiPrincipal.scopesToPermissions(null)).isNotNull().isEmpty();
        assertThat(OpenApiPrincipal.scopesToPermissions(Set.of())).isNotNull().isEmpty();
        assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of("   "))))
            .isEmpty();
    }

    @Test
    @DisplayName("🔴 段内星号 pattern（iot:* / iot:device:*）必须被丢弃——只挡 * 与 *:*:* 会漏掉它们")
    void patternScopesMustBeDropped() {
        // Sa-Token 把权限码当 pattern 走 vagueMatch ⇒ iot:* 能命中 iot:device:list、iot:debug:send 等。
        // 若只过滤 * 与 *:*:*，一把 Key 带 iot:* 就能调到命令下发 ⇒ 作用域隔离形同虚设。
        for (String pattern : List.of("iot:*", "iot:device:*", "iot:*:list", "*:device:list")) {
            assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of(pattern))))
                .as("pattern %s 被放行 ⇒ 可越权命中未授予的权限码", pattern)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("白名单内的既定作用域全部放行（一个都不许漏）")
    void allowlistedScopesMustPass() {
        assertThat(OpenApiPrincipal.scopesToPermissions(OpenApiPrincipal.ALLOWED_SCOPES))
            .containsExactlyInAnyOrderElementsOf(OpenApiPrincipal.ALLOWED_SCOPES);
    }

    @Test
    @DisplayName("白名单外的普通权限码同样丢弃（不能凭它是合法权限码就放行）")
    void nonAllowlistedScopeMustBeDropped() {
        // system:user:list 是合法的平台权限码，但不属开放 API 的既定作用域
        assertThat(OpenApiPrincipal.scopesToPermissions(
            new LinkedHashSet<>(List.of("system:user:list")))).isEmpty();
        assertThat(OpenApiPrincipal.isAllowedScope("system:user:list")).isFalse();
        assertThat(OpenApiPrincipal.isAllowedScope("iot:device:list")).isTrue();
    }

    @Test
    @DisplayName("混合输入：只留白名单内的，其余丢弃且能被 droppedScopes 报出")
    void mixedScopesKeepOnlyAllowlisted() {
        Set<String> scopes = new LinkedHashSet<>(List.of(
            "iot:device:list", "iot:*", "*:*:*", "system:user:list", "iot:series:get"));

        assertThat(OpenApiPrincipal.scopesToPermissions(scopes))
            .containsExactly("iot:device:list", "iot:series:get");
        assertThat(OpenApiPrincipal.droppedScopes(scopes))
            .containsExactly("iot:*", "*:*:*", "system:user:list");
    }

    @Test
    @DisplayName("更多 pattern 形态：*:* 与 iot:*:* 也必须被丢弃（独立复核点名漏测）")
    void morePatternFormsMustBeDropped() {
        for (String pattern : List.of("*:*", "iot:*:*", "*:*:*:*")) {
            assertThat(OpenApiPrincipal.scopesToPermissions(new LinkedHashSet<>(List.of(pattern))))
                .as("pattern %s 未被丢弃", pattern)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("宽容首尾空白、严格大小写：IOT:DEVICE:LIST 必须被丢弃")
    void caseSensitiveButWhitespaceTolerant() {
        // 🔴 语义边界（2026-09-30 一试修正）：
        //   ① **宽容首尾空白**（trim 后精确匹配）：带空白的合法码应放行——trim 不放大权限，
        //      仍是白名单内那一码；若连空白都拒，反而会因网关/配置里的多余空格产生"配了却不生效"。
        //   ② **严格大小写**：Sa-Token 权限匹配区分大小写；归一化大小写会造成
        //      "白名单放行 IOT:DEVICE:LIST 但 Sa-Token 不认"的口径分裂 ⇒ 大小写变体必须丢弃。
        // 先证 ①：trim 后精确命中 ⇒ 放行
        for (String padded : List.of("iot:device:list ", " iot:device:list", "iot:device:list\t")) {
            assertThat(OpenApiPrincipal.scopesToPermissions(
                new LinkedHashSet<>(List.of(padded))))
                .as("带首尾空白的合法码 %s 应被 trim 后放行（宽容空白不放大权限）", padded)
                .containsExactly("iot:device:list");
        }
        // 再证 ②：大小写变体 ⇒ 丢弃
        for (String cased : List.of("IOT:DEVICE:LIST", "iot:Device:List")) {
            assertThat(OpenApiPrincipal.scopesToPermissions(
                new LinkedHashSet<>(List.of(cased))))
                .as("大小写变体 %s 被放行 => 与 Sa-Token 的大小写语义分裂", cased)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("虚拟段边界：0 / -1 / -999999999 走库；Long.MIN_VALUE 属虚拟段（不与真实 ID 冲突）")
    void virtualSegmentBoundaries() {
        // 真实用户是正数雪花 ID ⇒ 这些"不走虚拟分支"的边界必须明确
        assertThat(OpenApiPrincipal.isVirtualPrincipal(0L)).isFalse();
        assertThat(OpenApiPrincipal.isVirtualPrincipal(-1L)).isFalse();
        assertThat(OpenApiPrincipal.isVirtualPrincipal(-999_999_999L)).isFalse();
        // Long.MIN_VALUE 落在保留段内 ⇒ 判为虚拟。真实用户 ID 永不为其（雪花为正数），
        // 故这不构成"占用真实用户权限"的风险；且即便构造出来，也仍需过白名单与网关签名。
        assertThat(OpenApiPrincipal.isVirtualPrincipal(Long.MIN_VALUE)).isTrue();
    }

    @Test
    @DisplayName("droppedScopes 边界：空 / 全合法 / null / 纯空白")
    void droppedScopesBoundaries() {
        assertThat(OpenApiPrincipal.droppedScopes(null)).isEmpty();
        assertThat(OpenApiPrincipal.droppedScopes(Set.of())).isEmpty();
        assertThat(OpenApiPrincipal.droppedScopes(Set.of("iot:device:list"))).isEmpty();
        assertThat(OpenApiPrincipal.droppedScopes(new LinkedHashSet<>(List.of("  ")))).isEmpty();
    }

    @Test
    @DisplayName("wildcardsIn 能把被剔除的通配符报出来（供记日志，避免静默）")
    void wildcardsInMustReport() {
        Set<String> scopes = new LinkedHashSet<>(List.of("iot:device:list", "*", "*:*:*"));

        assertThat(OpenApiPrincipal.wildcardsIn(scopes)).containsExactly("*", "*:*:*");
        assertThat(OpenApiPrincipal.wildcardsIn(Set.of("iot:device:list"))).isEmpty();
        assertThat(OpenApiPrincipal.wildcardsIn(null)).isEmpty();
    }

    @Test
    @DisplayName("常量与 starter 的约定同值（改了会被静默放大成超管）")
    void constantsMustMatchStarterConvention() {
        // starter: StpPermissionAdapter.SUPER_ADMIN = "*:*:*"、ANY = "*"
        assertThat(OpenApiPrincipal.SUPER_ADMIN_WILDCARD).isEqualTo("*:*:*");
        assertThat(OpenApiPrincipal.ANY_WILDCARD).isEqualTo("*");
    }
}
