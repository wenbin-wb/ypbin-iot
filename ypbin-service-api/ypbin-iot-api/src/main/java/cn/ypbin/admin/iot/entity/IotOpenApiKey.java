package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import java.io.Serial;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 开放 API Key（看板 #11 第 1 批）。
 *
 * 安全约定（设计 §2.2/§2.5）：只存 secret_hash（HMAC-SHA256(pepper, secret)）与
 * secret_prefix（明文前若干位，仅回显辨识，不足以还原）；明文仅创建时返回一次。
 * status 用 EntityStatus 码（ENABLED=1/DISABLED=0），不用裸字面量。
 */
@Getter
@Setter
@TableName("iot_open_api_key")
public class IotOpenApiKey extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 应用名（人可读，便于辨识这是谁的 Key）。 */
    private String appName;

    /** 公开标识（明文可存，如 ak_xxxx，定位行用）。 */
    private String accessKeyId;

    /** 密钥哈希（HMAC-SHA256(pepper, secret) 的 hex；永不可逆推明文）。 */
    private String secretHash;

    /** 密钥明文前若干位（仅控制台回显辨识）。 */
    private String secretPrefix;

    /** 作用域集合（逗号分隔，映射到 iot:* 权限码；创建时校验在白名单内）。 */
    private String scopes;

    /** 状态码（EntityStatus：1 启用 / 0 禁用）。 */
    private Integer status;

    /** 按 Key 维度的 QPS 配额。 */
    private Integer rateLimitQps;

    /** 日调用配额（0 = 不限）。 */
    private Integer dailyQuota;

    /** 可选 CIDR 白名单（逗号分隔；空 = 不限来源）。 */
    private String ipWhitelist;

    /** 过期时间（空 = 永不过期）。 */
    private LocalDateTime expireAt;

    /** 最近调用时刻（运维可见）。 */
    private LocalDateTime lastUsedAt;
}