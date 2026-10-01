package cn.ypbin.admin.iot.openapi;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 网关鉴权用的**内部校验** DTO（/internal/**，X-Internal-Token 保护）。 */
public final class OpenApiKeyVerifyDtos {

    private OpenApiKeyVerifyDtos() {
    }

    /** 校验请求。 */
    @Getter
    @Setter
    public static class VerifyReq {
        private String accessKeyId;
        private String secret;
    }

    /**
     * 校验响应。
     *
     * <p>失败时 reason 统一为 INVALID（**不区分** Key 不存在/secret 错/禁用/过期 ——
     * 设计 A-2 防枚举；网关不对第三方暴露差异）。</p>
     */
    public record VerifyResp(boolean valid, Long tenantId, Long virtualUserId, List<String> scopes,
                             int rateLimitQps, int dailyQuota) {

        /** 统一失败响应。 */
        public static VerifyResp invalid() {
            return new VerifyResp(false, null, null, List.of(), 0, 0);
        }
    }
}