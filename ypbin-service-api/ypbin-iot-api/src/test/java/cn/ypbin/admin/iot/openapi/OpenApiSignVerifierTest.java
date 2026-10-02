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

import cn.ypbin.starter.sign.core.SignAlgorithm;
import cn.ypbin.starter.sign.core.SignGenerator;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开放 API 签名校验纯函数测试（不起 Spring、不连库）。
 *
 * <p>覆盖：灰度分流、齐备性、时间戳边界、签名比对、nonce TTL 边界。</p>
 *
 * @author wenbin
 * @since 2026-10-05
 */
class OpenApiSignVerifierTest {

    private static final String SECRET = "sk_test_secret_value";
    private static final long NOW = 1_800_000_000L;

    private static Map<String, String> params() {
        Map<String, String> params = new HashMap<>();
        params.put("orderNo", "A100");
        params.put("amount", "99.5");
        return params;
    }

    private static String validSign(Map<String, String> params) {
        return SignGenerator.generate(params, SECRET, SignAlgorithm.HMAC_SHA256);
    }

    // ==================== 灰度分流：intendsSignature ====================

    @Test
    @DisplayName("四个参数全空 = 未意图签名（灰度期放行既有请求）")
    void shouldNotIntendSignatureWhenAllBlank() {
        assertThat(OpenApiSignVerifier.intendsSignature(null, null, null, null)).isFalse();
        assertThat(OpenApiSignVerifier.intendsSignature("", "", "", Map.of())).isFalse();
        assertThat(OpenApiSignVerifier.intendsSignature("  ", "\t", " ", Map.of())).isFalse();
        assertThat(OpenApiSignVerifier.intendsSignature(null, null, null, Map.of())).isFalse();
    }

    @Test
    @DisplayName("🔴 任一参数非空即意图签名（防「只带一个参数」降级绕过）")
    void shouldIntendSignatureWhenAnyPresent() {
        assertThat(OpenApiSignVerifier.intendsSignature("1", null, null, null)).isTrue();
        assertThat(OpenApiSignVerifier.intendsSignature(null, "n", null, null)).isTrue();
        assertThat(OpenApiSignVerifier.intendsSignature(null, null, "s", null)).isTrue();
        assertThat(OpenApiSignVerifier.intendsSignature(null, null, null, Map.of("a", "b"))).isTrue();
    }

    // ==================== 齐备性 ====================

    @Test
    @DisplayName("四件套缺一即不齐备")
    void shouldRequireAllParts() {
        Map<String, String> p = params();
        assertThat(OpenApiSignVerifier.complete("1", "n", "s", p)).isTrue();

        assertThat(OpenApiSignVerifier.complete(null, "n", "s", p)).isFalse();
        assertThat(OpenApiSignVerifier.complete("1", null, "s", p)).isFalse();
        assertThat(OpenApiSignVerifier.complete("1", "n", null, p)).isFalse();
        assertThat(OpenApiSignVerifier.complete("1", "n", "s", null)).isFalse();
        assertThat(OpenApiSignVerifier.complete("1", "n", "s", Map.of())).isFalse();
        assertThat(OpenApiSignVerifier.complete("1", "n", "s", Map.of("", ""))).isFalse();
    }

    // ==================== 时间戳 ====================

    @Test
    @DisplayName("时间戳在 [now-60, now+5] 内合法")
    void shouldAcceptTimestampWithinWindow() {
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(NOW), NOW)).isTrue();
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(NOW - 60), NOW)).isTrue();
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(NOW + 5), NOW)).isTrue();
    }

    @Test
    @DisplayName("时间戳超出有效期即拒绝（过去 61 秒 / 未来 6 秒）")
    void shouldRejectTimestampOutsideWindow() {
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(NOW - 61), NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(NOW + 6), NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(NOW - 86400), NOW)).isFalse();
    }

    @Test
    @DisplayName("🔴 极值时间戳不得因溢出绕过（Long.MIN/MAX）")
    void shouldRejectExtremeTimestamps() {
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(Long.MIN_VALUE), NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid(String.valueOf(Long.MAX_VALUE), NOW)).isFalse();
    }

    @Test
    @DisplayName("非数字/空白时间戳一律拒绝")
    void shouldRejectMalformedTimestamp() {
        assertThat(OpenApiSignVerifier.timestampValid("abc", NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid("1.5", NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid("", NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid(null, NOW)).isFalse();
        assertThat(OpenApiSignVerifier.timestampValid("   ", NOW)).isFalse();
    }

    // ==================== 签名比对 ====================

    @Test
    @DisplayName("正确签名通过；且容忍大小写十六进制")
    void shouldMatchValidSignature() {
        Map<String, String> p = params();
        String sign = validSign(p);
        assertThat(OpenApiSignVerifier.signatureMatches(p, SECRET, sign, SignAlgorithm.HMAC_SHA256)).isTrue();
        assertThat(OpenApiSignVerifier.signatureMatches(p, SECRET, sign.toLowerCase(),
            SignAlgorithm.HMAC_SHA256)).isTrue();
    }

    @Test
    @DisplayName("错密钥/错签名/篡改参数均不通过")
    void shouldRejectWrongSignature() {
        Map<String, String> p = params();
        String sign = validSign(p);

        assertThat(OpenApiSignVerifier.signatureMatches(p, "wrong-secret", sign,
            SignAlgorithm.HMAC_SHA256)).isFalse();
        assertThat(OpenApiSignVerifier.signatureMatches(p, SECRET, "DEADBEEF",
            SignAlgorithm.HMAC_SHA256)).isFalse();

        // 篡改业务参数后原签名必须失效（防"改参数复用签名"）
        Map<String, String> tampered = new HashMap<>(p);
        tampered.put("amount", "0.01");
        assertThat(OpenApiSignVerifier.signatureMatches(tampered, SECRET, sign,
            SignAlgorithm.HMAC_SHA256)).isFalse();

        // 增加参数同样失效
        Map<String, String> extra = new HashMap<>(p);
        extra.put("admin", "true");
        assertThat(OpenApiSignVerifier.signatureMatches(extra, SECRET, sign,
            SignAlgorithm.HMAC_SHA256)).isFalse();
    }

    @Test
    @DisplayName("缺失入参不抛异常、直接判不通过（含空密钥）")
    void shouldNotThrowOnMissingInputs() {
        Map<String, String> p = params();
        assertThat(OpenApiSignVerifier.signatureMatches(null, SECRET, "x", SignAlgorithm.HMAC_SHA256)).isFalse();
        assertThat(OpenApiSignVerifier.signatureMatches(Map.of(), SECRET, "x",
            SignAlgorithm.HMAC_SHA256)).isFalse();
        assertThat(OpenApiSignVerifier.signatureMatches(p, null, "x", SignAlgorithm.HMAC_SHA256)).isFalse();
        assertThat(OpenApiSignVerifier.signatureMatches(p, "", "x", SignAlgorithm.HMAC_SHA256)).isFalse();
        // 空密钥会让 HMAC 抛 IllegalArgumentException —— 必须被吞成 false 而非冒泡
        assertThat(OpenApiSignVerifier.signatureMatches(p, " ", "x", SignAlgorithm.HMAC_SHA256)).isFalse();
        assertThat(OpenApiSignVerifier.signatureMatches(p, SECRET, null, SignAlgorithm.HMAC_SHA256)).isFalse();
        assertThat(OpenApiSignVerifier.signatureMatches(p, SECRET, "  ", SignAlgorithm.HMAC_SHA256)).isFalse();
    }

    @Test
    @DisplayName("规范化口径与 starter 一致：参数顺序不影响签名")
    void shouldBeOrderInsensitive() {
        Map<String, String> a = new HashMap<>();
        a.put("b", "2");
        a.put("a", "1");
        Map<String, String> b = new HashMap<>();
        b.put("a", "1");
        b.put("b", "2");

        String signA = validSign(a);
        assertThat(OpenApiSignVerifier.signatureMatches(b, SECRET, signA, SignAlgorithm.HMAC_SHA256)).isTrue();
    }

    @Test
    @DisplayName("值含 & 与 = 时不得被用来伪造规范串（注入对抗）")
    void shouldResistCanonicalizationInjection() {
        // 攻击者在单个参数值里塞 "&admin=true" 试图伪造出额外的参数对
        Map<String, String> honest = new HashMap<>();
        honest.put("user", "bob");

        Map<String, String> attack = new HashMap<>();
        attack.put("user", "bob&admin=true");

        String honestSign = validSign(honest);
        assertThat(OpenApiSignVerifier.signatureMatches(honest, SECRET, honestSign,
            SignAlgorithm.HMAC_SHA256)).isTrue();
        // 攻击载荷不得复用诚实签名
        assertThat(OpenApiSignVerifier.signatureMatches(attack, SECRET, honestSign,
            SignAlgorithm.HMAC_SHA256)).isFalse();

        // 且两者规范串确实不同（诚实值经 percent-encode）
        String attackSign = validSign(attack);
        assertThat(attackSign).isNotEqualTo(honestSign);
    }

    // ==================== nonce TTL ====================

    @Test
    @DisplayName("nonce TTL 覆盖时间戳整个有效期末尾（未来时间戳时更长）")
    void shouldComputeNonceTtlCoveringTimestampWindow() {
        // 当前时间戳：60 + 0 + 1 = 61
        assertThat(OpenApiSignVerifier.nonceTtlSeconds(String.valueOf(NOW), NOW)).isEqualTo(61L);
        // 未来 5 秒：60 + 5 + 1 = 66（保证覆盖到该时间戳失效之后）
        assertThat(OpenApiSignVerifier.nonceTtlSeconds(String.valueOf(NOW + 5), NOW)).isEqualTo(66L);
        // 过去 60 秒：60 - 60 + 1 = 1
        assertThat(OpenApiSignVerifier.nonceTtlSeconds(String.valueOf(NOW - 60), NOW)).isEqualTo(1L);
    }

    @Test
    @DisplayName("nonce TTL 永不为负（非法/极值时间戳回落为安全默认）")
    void shouldNeverReturnNegativeTtl() {
        assertThat(OpenApiSignVerifier.nonceTtlSeconds(String.valueOf(NOW - 99999), NOW)).isEqualTo(1L);
        assertThat(OpenApiSignVerifier.nonceTtlSeconds(String.valueOf(Long.MIN_VALUE), NOW)).isGreaterThan(0L);
        assertThat(OpenApiSignVerifier.nonceTtlSeconds("abc", NOW)).isGreaterThan(0L);
        assertThat(OpenApiSignVerifier.nonceTtlSeconds(null, NOW)).isGreaterThan(0L);
    }
}
