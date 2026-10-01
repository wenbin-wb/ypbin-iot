package cn.ypbin.admin.iot.openapi;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 开放 API Key 的请求/响应 DTO（聚合一处，避免文件碎片）。 */
public final class OpenApiKeyDtos {

    private OpenApiKeyDtos() {
    }

    /** 创建请求。 */
    @Getter
    @Setter
    public static class CreateReq {
        /** 应用名（必填）。 */
        private String appName;
        /** 作用域（逗号分隔；每个值必须在 ALLOWED_SCOPES 白名单内）。 */
        private String scopes;
        /** QPS 配额（空 = 默认 10）。 */
        private Integer rateLimitQps;
        /** 日配额（空 = 默认 100_000；0 = 不限）。 */
        private Integer dailyQuota;
        /** 过期时间（空 = 永不过期）。 */
        private LocalDateTime expireAt;
    }

    /** 创建响应：secret 仅此一次。 */
    public record CreateResp(String id, String accessKeyId, String secret, String secretPrefix,
                             List<String> scopes, LocalDateTime expireAt) {
    }

    /** 列表项（只回 prefix，不给明文）。 */
    public record ListItemResp(String id, String appName, String accessKeyId, String secretPrefix,
                               List<String> scopes, Integer status, LocalDateTime expireAt,
                               LocalDateTime lastUsedAt, LocalDateTime createTime) {
    }

    /**
     * 自检响应（GET /open-api/v1/whoami：只回当前 Key 自身信息，不含明文 secret）。
     *
     * <p>用途：第三方接入第一件事就是自检（我是谁、有几个 scope、配额剩多少），大幅降低
     * 「为什么查不到/调不通」的沟通成本；同时把已授予 scopes 摆明，天然解释作用域隔离。</p>
     */
    public record WhoamiResp(String accessKeyId, String appName, Long tenantId, List<String> scopes,
                             Integer status, Integer rateLimitQps, Integer dailyQuota,
                             LocalDateTime expireAt, LocalDateTime lastUsedAt) {
    }
}