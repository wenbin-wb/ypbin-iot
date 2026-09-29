/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 部署配置门禁：**身份模式密钥齐备 + 网关清洗表覆盖身份头**（#6b / SF-5）。
 *
 * <p><b>为什么需要这条测试（一手核实，2026-09-29）</b>：{@code ypbin-starter} 3.6.0 起，
 * {@code cn.ypbin.starter.security.identity.IdentityAutoConfiguration#
 * identityHeaderFilterRegistration} 在 {@code ypbin.security.identity.enabled=true} 且
 * {@code ypbin.security.identity.trusted-source-token} 为空时抛 {@code IllegalStateException}
 * ⇒ <b>应用启动即失败</b>。而本仓 {@code deploy/nacos/ypbin-common.yaml} 恰好显式开了
 * {@code enabled: true}、却只配了<b>旧命名空间</b>的 {@code ypbin.cloud.feign.trusted-source-token}
 * （那是 Feign <i>出站透传</i>开关，不是入口侧校验密钥）。</p>
 *
 * <p><b>为什么靠人眼审不出来</b>：① 两个键名高度相似、只差命名空间；
 * ② 失败只在<b>真正部署</b>时出现——本仓升到 3.6.0 后长期没部署过，直到部署才暴露；
 * ③ {@code install.sh} 会把 {@code deploy/nacos/*.yaml} <b>整体覆盖</b>到 Nacos
 * ⇒ 只在运行实例上手工补键必然在下次重跑时退化。故必须钉在<b>仓内模板</b>上。</p>
 *
 * <p><b>本测试与 {@code tools/check-identity-config.sh} 的关系</b>：两者校验同一约束，
 * 但入口不同——shell 版跑在 {@code Sync Whitelist} 工作流（含"渲染后"口径），本 Java 版跑在
 * {@code mvn test}（开发者本机即可发现）。刻意保留双入口：CI 与本地都不会漏。</p>
 *
 * @author wenbin
 * @since 2026-09-29
 */
class NacosIdentityConfigTest {

    /** 入口侧校验密钥的键名（jar 内 {@code IdentityProperties.PREFIX} + 字段名，一手核实）。 */
    private static final String IDENTITY_TOKEN_KEY = "trusted-source-token";

    /** 网关签发侧密钥相对 {@code ypbin} 的路径。 */
    private static final String GATEWAY_AUTH_PATH = "gateway.auth.";

    /** 期望两个键都渲染自同一个 {@code .env} 变量（同串来源）。 */
    private static final String EXPECTED_PLACEHOLDER = "${GATEWAY_SIGN_TOKEN}";

    /** 网关必须清洗的身份类头（starter 默认 5 个）。 */
    private static final List<String> DEFAULT_IDENTITY_HEADERS =
        List.of("X-User-Id", "X-User-Name", "X-Tenant-Id", "X-Dept-Id", "X-Roles");

    /** 来源标记头名（jar 内 {@code IdentityHeaders.GATEWAY_SIGNED}，一手核实）。 */
    private static final String GATEWAY_SIGNED_HEADER = "X-Gateway-Signed";

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path COMMON_YAML = REPO_ROOT.resolve("deploy/nacos/ypbin-common.yaml");
    private static final Path GATEWAY_YAML = REPO_ROOT.resolve("deploy/nacos/ypbin-gateway.yaml");

    @Test
    @DisplayName("#6b：identity 开启时必须配 trusted-source-token（缺失 ⇒ starter 3.6.0 启动失败）")
    void identityEnabledRequiresTrustedSourceToken() throws IOException {
        Map<String, Object> identity = dig(loadYaml(COMMON_YAML),
            "ypbin", "security", "identity");

        assertThat(identity).as("ypbin-common.yaml 缺少 ypbin.security.identity 段").isNotNull();
        assertThat(identity.get("enabled"))
            .as("本测试的前置：identity 段应当是开启状态（若已改为 false，本用例需同步调整）")
            .isEqualTo(true);
        assertThat(identity)
            .as("ypbin.security.identity.enabled=true 时必须给出 %s —— starter 3.6.0 起"
                + "IdentityAutoConfiguration 会在此为空时抛 IllegalStateException（启动失败）。"
                + "注意：ypbin.cloud.feign.trusted-source-token 是 Feign 出站透传开关，"
                + "不能替代本键", IDENTITY_TOKEN_KEY)
            .containsKey(IDENTITY_TOKEN_KEY);
        assertThat(String.valueOf(identity.get(IDENTITY_TOKEN_KEY)))
            .as("identity 密钥必须是 .env 占位符（真值禁入仓；由 install.sh 渲染时替换）")
            .isEqualTo(EXPECTED_PLACEHOLDER);
    }

    @Test
    @DisplayName("#6b：网关签发侧与下游入口侧必须同串（不同串 ⇒ 全站 403）")
    void gatewayAndDownstreamTokensMustShareOneSource() throws IOException {
        Map<String, Object> gatewayAuth = dig(loadYaml(GATEWAY_YAML),
            "ypbin", "gateway", "auth");
        Map<String, Object> identity = dig(loadYaml(COMMON_YAML),
            "ypbin", "security", "identity");

        assertThat(gatewayAuth).as("ypbin-gateway.yaml 缺少 ypbin.gateway.auth 段").isNotNull();
        assertThat(gatewayAuth)
            .as("网关不签发来源标记 ⇒ 下游会把所有经网关的合法请求判为非法来源并拒绝（全站 403）")
            .containsKey(IDENTITY_TOKEN_KEY);
        assertThat(identity).isNotNull();

        // 两者都取自 ${GATEWAY_SIGN_TOKEN} ⇒ 渲染后必然同串（这是"同串"的可校验代理）
        assertThat(String.valueOf(gatewayAuth.get(IDENTITY_TOKEN_KEY)))
            .as("网关签发侧 identity 密钥应为 %s（与下游同源，保证渲染后同串）", EXPECTED_PLACEHOLDER)
            .isEqualTo(EXPECTED_PLACEHOLDER);
        assertThat(String.valueOf(identity.get(IDENTITY_TOKEN_KEY)))
            .as("下游入口侧与网关侧必须渲染自同一变量，否则下游拒绝一切网关请求")
            .isEqualTo(String.valueOf(gatewayAuth.get(IDENTITY_TOKEN_KEY)));
    }

    @Test
    @DisplayName("SF-5 纵深防御：网关清洗表必须含 X-Gateway-Signed 与全部默认身份头")
    void gatewaySanitizeMustCoverIdentityHeaders() throws IOException {
        Map<String, Object> sanitize = dig(loadYaml(GATEWAY_YAML),
            "ypbin", "gateway", "header-sanitize");

        assertThat(sanitize)
            .as("未配置 ypbin.gateway.header-sanitize：走 starter 默认表，而默认表**不含** "
                + "%s（jar 内 GatewayProperties$HeaderSanitize:180-181）⇒ 客户端可自带该头穿透到下游",
                GATEWAY_SIGNED_HEADER)
            .isNotNull();
        List<String> headers = asStringList(sanitize.get("headers"));
        assertThat(headers)
            .as("清洗表必须覆盖来源标记头，否则「外部头不得直达下游」只能靠「密钥没泄露」成立")
            .contains(GATEWAY_SIGNED_HEADER);
        assertThat(headers)
            .as("header-sanitize.headers 是**整体覆盖**语义（不是追加）⇒ 必须列全默认身份头，漏一个"
                + "等于取消对该头的清洗")
            .containsAll(DEFAULT_IDENTITY_HEADERS);
    }

    @Test
    @DisplayName("★ 自检：本门禁读的键名必须真的在 starter 契约里（防键名笔误造成假绿）")
    void keyNamesMustMatchStarterContract() throws IOException {
        // 键名以 jar 内一手核实为准；这里锚定"两个文件都用了同一个键名"这一事实，
        // 避免把 trusted_source_token / trustedSourceToken 之类的笔误当成"已配置"。
        Pattern keyPattern = Pattern.compile("^\\s{2,}" + Pattern.quote(IDENTITY_TOKEN_KEY) + ":\\s*\\S",
            Pattern.MULTILINE);
        for (Path file : List.of(COMMON_YAML, GATEWAY_YAML)) {
            Matcher m = keyPattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(m.find())
                .as("%s 里找不到形如「%s: <值>」的配置行（键名笔误？写进注释了？）",
                    file.getFileName(), IDENTITY_TOKEN_KEY)
                .isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dig(Map<String, Object> root, String... path) {
        Object cur = root;
        StringBuilder walked = new StringBuilder();
        for (String key : path) {
            walked.append(walked.isEmpty() ? "" : ".").append(key);
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(key);
        }
        assertThat(cur).as("配置缺少 %s 段", walked).isInstanceOf(Map.class);
        return (Map<String, Object>) cur;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        return value == null
            ? List.of()
            : ((List<Object>) value).stream().map(String::valueOf).toList();
    }

    private Map<String, Object> loadYaml(Path path) throws IOException {
        assertThat(Files.exists(path)).as("找不到配置文件：%s（测试的工作目录假设被改动？）", path)
            .isTrue();
        return new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
    }
}
