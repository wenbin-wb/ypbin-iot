package cn.ypbin.admin.gateway.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/**
 * 网关 → iot 的**签名参数透传契约**用例。
 *
 * <p><b>本测试的存在理由（防回归）</b>：独立复核曾发现网关在"signParams 为空时省略该字段"，
 * 导致 iot 侧无法区分"客户端没打算签名"与"网关把参数弄丢了"，进而使签名校验在真实链路上
 * 恒被跳过（作者的单测直连 Service、绕过了网关，故测不到）。本用例锁死契约：
 * <b>{@code signParams} 与 {@code requireSignature} 必须始终存在</b>。</p>
 *
 * @author wenbin
 * @since 2026-10-05
 */
class OpenApiSignPayloadContractTest {

    private static ServerWebExchange exchange(String query) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/iot/open-api/v1/devices");
        if (query != null && !query.isEmpty()) {
            builder = MockServerHttpRequest.get("/iot/open-api/v1/devices?" + query);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    @DisplayName("🔴 signParams 必须始终存在（即便为空 Map），否则 iot 无法区分「未签名」与「参数丢失」")
    void signParamsAlwaysPresentEvenWhenEmpty() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(exchange(null), false, false);

        assertThat(payload).containsKey("signParams");
        assertThat(payload.get("signParams")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> signParams = (Map<String, String>) payload.get("signParams");
        assertThat(signParams).isEmpty();
    }

    @Test
    @DisplayName("🔴 requireSignature 必须始终存在且反映服务端配置（客户端无法少发参数降级）")
    void requireSignatureAlwaysPresent() {
        assertThat(OpenApiKeyAuthFilter.buildSignPayload(exchange(null), true))
            .containsEntry("requireSignature", true);
        assertThat(OpenApiKeyAuthFilter.buildSignPayload(exchange(null), false))
            .containsEntry("requireSignature", false);
    }

    @Test
    @DisplayName("签名四件套存在时被透传")
    void signaturePartsForwarded() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(
            exchange("timestamp=1800000000&nonce=n1&sign=ABCDEF"), false);

        assertThat(payload).containsEntry("timestamp", "1800000000");
        assertThat(payload).containsEntry("nonce", "n1");
        assertThat(payload).containsEntry("sign", "ABCDEF");
    }

    @Test
    @DisplayName("签名四件套缺失时不写入对应键（保持「未携带」语义，不发空串）")
    void absentSignaturePartsOmitted() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(exchange(null), false);

        assertThat(payload).doesNotContainKey("timestamp");
        assertThat(payload).doesNotContainKey("nonce");
        assertThat(payload).doesNotContainKey("sign");
    }

    @Test
    @DisplayName("业务 query 参数进入 signParams，且排除签名四件套自身")
    void businessParamsCollectedExcludingSignatureParts() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(
            exchange("orderNo=A100&amount=99.5&timestamp=1800000000&nonce=n1&sign=ABCDEF"), false);

        @SuppressWarnings("unchecked")
        Map<String, String> signParams = (Map<String, String>) payload.get("signParams");
        assertThat(signParams).containsEntry("orderNo", "A100");
        assertThat(signParams).containsEntry("amount", "99.5");
        // 签名字段本身不参与签名计算
        assertThat(signParams).doesNotContainKeys("timestamp", "nonce", "sign");
    }

    @Test
    @DisplayName("多值参数取第一个（与 starter 取值口径一致，避免两侧对不上）")
    void multiValueParamTakesFirst() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(
            exchange("tag=a&tag=b"), false);

        @SuppressWarnings("unchecked")
        Map<String, String> signParams = (Map<String, String>) payload.get("signParams");
        assertThat(signParams).containsEntry("tag", "a");
    }

    @Test
    @DisplayName("空值参数不进入 signParams（与 starter 的「空值不参与签名」一致）")
    void emptyValuedParamExcluded() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(
            exchange("blank=&filled=v"), false);

        @SuppressWarnings("unchecked")
        Map<String, String> signParams = (Map<String, String>) payload.get("signParams");
        assertThat(signParams).containsEntry("filled", "v");
        assertThat(signParams).doesNotContainKey("blank");
    }

    @Test
    @DisplayName("返回的 signParams 按键有序（TreeMap），保证与客户端规范化口径稳定")
    void signParamsDeterministicallyOrdered() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(
            exchange("z=1&a=2&m=3"), false, false);

        @SuppressWarnings("unchecked")
        Map<String, String> signParams = (Map<String, String>) payload.get("signParams");
        assertThat(signParams.keySet()).containsExactly("a", "m", "z");
    }

    @Test
    @DisplayName("全空 query 也要产出可序列化的 signParams（不得为 null 或抛异常）")
    void neverReturnsNullForEmptyQuery() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(exchange(""), true);

        assertThat(payload).isNotNull();
        assertThat(payload.get("signParams")).isNotNull();
    }

    @Test
    @DisplayName("clientIp 透传连接远端地址（供 iot 侧白名单校验）")
    void clientIpForwardedFromRemoteAddress() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/iot/open-api/v1/devices")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 54321));
        ServerWebExchange exchange = MockServerWebExchange.from(builder.build());

        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(exchange, false);

        assertThat(payload).containsEntry("clientIp", "10.1.2.3");
    }

    @Test
    @DisplayName("取不到远端地址时不放 clientIp 键（iot 侧按无法判定拒绝，不伪造）")
    void missingRemoteAddressOmitsClientIp() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(exchange(null), false);

        assertThat(payload).doesNotContainKey("clientIp");
    }


    // ==================== M1：method/path 纳入签名 ====================

    /**
     * 与 iot 侧契约测试共用的固定向量。
     *
     * <p>网关侧**刻意不用 starter-sign**：用被测方自己的库复算等于自证同义反复，且会把
     * starter-sign 变成网关 pom 的新依赖（与 upstream 的分歧面 +1）。这里用 JDK 原生
     * {@code URLEncoder} + {@code Mac(HmacSHA256)} 独立实现同一口径，既独立、又不引入依赖。
     * iot 侧继续用 starter-sign 复算，两侧断言同一个硬编码 payload 串与 hex 向量。</p>
     */
    static final String CONTRACT_SECRET = "sk_contract_secret_001";

    /** 期望规范串：键字典序 + 键/值 percent-encode（与 starter SignGenerator 口径一致）。 */
    static final String CONTRACT_PAYLOAD =
        "__method=POST&__path=%2Fiot%2Fopen-api%2Fv1%2Fdevices&amount=99.5&orderNo=A100";

    /** 期望签名（HMAC-SHA256(secret, CONTRACT_PAYLOAD) 的大写十六进制）。 */
    static final String CONTRACT_VECTOR =
        "EC0AAA65C18A1396083A8A4A3A28D045B3EA8FCB4BD472FB18990340B8ABDB50";

    /**
     * 独立于被测实现的规范串复算（键字典序 + URLEncoder；空值跳过）。
     *
     * @param params 参与签名的参数
     * @return 规范串
     */
    static String canonicalize(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = params.get(key);
            if (value != null && !value.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    /** 用 JDK 原生 HmacSHA256 对规范串签名（大写十六进制）。 */
    static String sign(Map<String, String> params) {
        return hmacSha256Hex(canonicalize(params), CONTRACT_SECRET);
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 计算失败", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> signParams(ServerWebExchange exchange) {
        return (Map<String, String>) OpenApiKeyAuthFilter
            .buildSignPayload(exchange, false, true).get("signParams");
    }

    @Test
    @DisplayName("🔴 M1：默认（严格）把大写 method 与原始 path 注入 signParams")
    void methodAndPathInjectedByDefault() {
        ServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/iot/open-api/v1/devices?orderNo=A100&amount=99.5").build());

        Map<String, String> params = signParams(exchange);

        assertThat(params).containsEntry(OpenApiKeyAuthFilter.SIGN_METHOD_KEY, "POST");
        assertThat(params).containsEntry(OpenApiKeyAuthFilter.SIGN_PATH_KEY, "/iot/open-api/v1/devices");
        assertThat(params).containsEntry("orderNo", "A100");
    }

    @Test
    @DisplayName("M1：客户端自带的 __method/__path 被忽略，由网关按真实请求覆盖（防伪造）")
    void clientSuppliedReservedKeysAreIgnored() {
        ServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/iot/open-api/v1/devices?__method=DELETE&__path=/evil").build());

        Map<String, String> params = signParams(exchange);

        assertThat(params).containsEntry(OpenApiKeyAuthFilter.SIGN_METHOD_KEY, "GET");
        assertThat(params).containsEntry(OpenApiKeyAuthFilter.SIGN_PATH_KEY, "/iot/open-api/v1/devices");
    }

    @Test
    @DisplayName("M1：过渡开关关闭时 signParams 不含 method/path（兼容旧客户端）")
    void methodAndPathOmittedWhenSwitchOff() {
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(
            exchange("orderNo=A100"), false, false);

        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) payload.get("signParams");
        assertThat(params).containsEntry("orderNo", "A100");
        assertThat(params).doesNotContainKeys(OpenApiKeyAuthFilter.SIGN_METHOD_KEY,
            OpenApiKeyAuthFilter.SIGN_PATH_KEY);
    }

    @Test
    @DisplayName("🔴 M1：篡改 method 或 path 后签名必然不同（防拦截后改端点重放）")
    void tamperedMethodOrPathChangesSignature() {
        ServerWebExchange original = MockServerWebExchange.from(
            MockServerHttpRequest.post("/iot/open-api/v1/devices?orderNo=A100").build());
        String originalSign = sign(signParams(original));

        ServerWebExchange tamperedPath = MockServerWebExchange.from(
            MockServerHttpRequest.post("/iot/open-api/v1/products?orderNo=A100").build());
        assertThat(sign(signParams(tamperedPath))).isNotEqualTo(originalSign);

        ServerWebExchange tamperedMethod = MockServerWebExchange.from(
            MockServerHttpRequest.get("/iot/open-api/v1/devices?orderNo=A100").build());
        assertThat(sign(signParams(tamperedMethod))).isNotEqualTo(originalSign);
    }

    @Test
    @DisplayName("🔴 M1 跨模块契约向量：网关侧独立复算的规范串与签名值必须与 iot 侧一致")
    void contractVectorMatchesIotSide() {
        ServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/iot/open-api/v1/devices?orderNo=A100&amount=99.5").build());

        Map<String, String> params = signParams(exchange);
        assertThat(params).hasSize(4);
        assertThat(canonicalize(params)).as("规范串必须与 iot 侧/文档口径逐字符一致")
            .isEqualTo(CONTRACT_PAYLOAD);
        assertThat(sign(params)).as("HMAC-SHA256 结果必须与 iot 侧硬编码向量一致")
            .isEqualTo(CONTRACT_VECTOR);
    }
}
