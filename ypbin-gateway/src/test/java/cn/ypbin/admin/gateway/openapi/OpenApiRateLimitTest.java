
package cn.ypbin.admin.gateway.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开放 API 限流纯函数用例（看板 #11 第 2 批）。
 */
class OpenApiRateLimitTest {

    @Test
    @DisplayName("计数在配额内放行；超限拒绝；0 配额 = 不限")
    void decisionBounds() {
        assertThat(OpenApiRateLimitGlobalFilter.RateLimitDecision.forCount(5, 10).allowed()).isTrue();
        assertThat(OpenApiRateLimitGlobalFilter.RateLimitDecision.forCount(10, 10).allowed()).isTrue();
        assertThat(OpenApiRateLimitGlobalFilter.RateLimitDecision.forCount(11, 10).allowed()).isFalse();
        assertThat(OpenApiRateLimitGlobalFilter.RateLimitDecision.forCount(999, 0).allowed()).isTrue();
        assertThat(OpenApiRateLimitGlobalFilter.RateLimitDecision.forCount(9999, -1).allowed()).isTrue();
    }

    @Test
    @DisplayName("判定结果携带计数与配额（供日志/审计）")
    void decisionCarriesCounts() {
        var d = OpenApiRateLimitGlobalFilter.RateLimitDecision.forCount(3, 5);
        assertThat(d.count()).isEqualTo(3L);
        assertThat(d.limit()).isEqualTo(5L);
    }
}
