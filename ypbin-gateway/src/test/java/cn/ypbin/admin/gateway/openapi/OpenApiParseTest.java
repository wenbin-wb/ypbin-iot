package cn.ypbin.admin.gateway.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * X-Api-Key 头解析用例（网关侧，看板 #11 第 1 批）。
 */
class OpenApiParseTest {

    @Test
    @DisplayName("合法格式 accessKeyId:secret 解析成功")
    void validHeaderParses() {
        OpenApiKeyAuthFilter.ParsedKey parsed =
            OpenApiKeyAuthFilter.parseApiKey(" ak_abc123 : sk_secret");
        assertThat(parsed).isNotNull();
        assertThat(parsed.accessKeyId()).isEqualTo("ak_abc123");
        assertThat(parsed.secret()).isEqualTo("sk_secret");
    }

    @Test
    @DisplayName("缺失 / 无冒号 / 空段 一律拒绝")
    void invalidHeadersReject() {
        assertThat(OpenApiKeyAuthFilter.parseApiKey(null)).isNull();
        assertThat(OpenApiKeyAuthFilter.parseApiKey("")).isNull();
        assertThat(OpenApiKeyAuthFilter.parseApiKey("ak_only")).isNull();
        assertThat(OpenApiKeyAuthFilter.parseApiKey("ak:")).isNull();
        assertThat(OpenApiKeyAuthFilter.parseApiKey(":sk")).isNull();
        assertThat(OpenApiKeyAuthFilter.parseApiKey(" : ")).isNull();
    }
}
