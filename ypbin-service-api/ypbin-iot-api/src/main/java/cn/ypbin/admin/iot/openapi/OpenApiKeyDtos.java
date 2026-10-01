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
}