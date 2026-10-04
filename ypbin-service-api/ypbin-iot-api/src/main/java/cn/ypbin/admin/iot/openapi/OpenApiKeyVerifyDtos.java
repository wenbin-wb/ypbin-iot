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
         * 是否强制要求签名（由网关注入，来自部署配置）。
         *
         * <p><b>为什么必须由网关显式下发、而不是"看参数是否齐全"来推断</b>：
         * 若仅凭"请求里有没有签名参数"判断，攻击者只要<b>不发</b>这些参数就能落进
         * "未启用签名"分支被放行 —— 签名形同虚设（这正是本批次修复前的问题）。
         * 强制模式必须由服务端配置决定，客户端无法通过少发参数降级。</p>
         */
        private boolean requireSignature;

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

        /**
         * 客户端来源 IP（由网关注入：连接远端地址）。
         *
         * <p><b>刻意取连接远端，不取 `X-Forwarded-For`</b>：网关前无可信代理
         * （openresty 只是默认 stub，不代理网关端口），XFF 完全由客户端可控、
         * 可伪造。用连接地址则伪造成本 = 真实网络位置（TCP 握手绑定）。</p>
         *
         * <p>供 `IotOpenApiKey.ipWhitelist`（CIDR 白名单）校验；空表示网关未提供。</p>
         */
        private String clientIp;
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
