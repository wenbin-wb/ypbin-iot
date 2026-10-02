package cn.ypbin.admin.gateway.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
        Map<String, Object> payload = OpenApiKeyAuthFilter.buildSignPayload(exchange(null), false);

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
            exchange("z=1&a=2&m=3"), false);

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
}
