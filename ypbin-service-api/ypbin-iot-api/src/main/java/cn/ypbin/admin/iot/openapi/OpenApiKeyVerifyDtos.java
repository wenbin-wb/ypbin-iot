package cn.ypbin.admin.iot.openapi;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 网关鉴权用的**内部校验** DTO（/internal/**，X-Internal-Token 保护）。 */
public final class OpenApiKeyVerifyDtos {

    private OpenApiKeyVerifyDtos() {
    }

    /**
     * 校验请求。
     *
     * <p><b>签名参数（看板「开放 API 签名合并」批次新增）</b>：{@link #timestamp}/{@link #nonce}/
     * {@link #sign}/{@link #signPayload} 由网关从第三方请求中透传。四者<b>全空</b>表示该请求
     * 未启用签名（灰度 OPTIONAL 期放行，语义与原行为一致）；<b>任一带值</b>即要求 iot 侧完成验签
     * + 防重放，缺失其余参数一律判 invalid（fail-closed，防"部分携带"降级绕过）。</p>
     *
     * <p>密钥（pepper/hash）<b>不出 iot</b>：网关只转发参数，验签在本服务内完成。</p>
     */
    @Getter
    @Setter
    public static class VerifyReq {
        private String accessKeyId;
        private String secret;

        /** 签名时间戳（秒级 epoch；空 = 未启用签名）。 */
        private String timestamp;

        /** 一次性随机串（防重放；空 = 未启用签名）。 */
        private String nonce;

        /** 客户端签名值（空 = 未启用签名）。 */
        private String sign;

        /**
         * 参与签名的参数（由网关按客户端口径收集，空 = 未启用签名）。
         *
         * <p>刻意传<b>参数 Map</b>而非"已拼好的规范串"：签名计算方（{@code SignGenerator}）
         * 会对参数做<b>按键排序 + percent-encode</b>，若传已规范化的串再被规范化一次，
         * 等于二次编码 ⇒ 与客户端永远不匹配。故此处传原始参数，由 iot 侧统一规范化。</p>
         *
         * <p>同理也规避了网关的 body 重复读问题：网关从 query/header 收集参数即可，
         * 不强制读取流式 body（见方案 c 的取舍说明）。</p>
         */
        private Map<String, String> signParams;
    }

    /**
     * 校验响应。
     *
     * <p>失败时 reason 统一为 INVALID（**不区分** Key 不存在/secret 错/禁用/过期/签名错 ——
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
