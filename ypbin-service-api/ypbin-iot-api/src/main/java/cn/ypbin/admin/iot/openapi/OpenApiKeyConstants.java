package cn.ypbin.admin.iot.openapi;

import java.time.LocalDate;

/**
 * 开放 API 具名常量（看板 #11 第 1 批；对照设计 §2.4/§3.3）。
 *
 * 避免魔法值：虚拟主体保留段、Key 前缀、默认限流/配额均在此集中。
 */
public final class OpenApiKeyConstants {

    private OpenApiKeyConstants() {
    }

    /**
     * 虚拟主体 ID 上界（复用保留段：X-User-Id <= 本值走 scopes；与
     * starter `VirtualPrincipalScopes.DEFAULT_VIRTUAL_USER_ID_MAX` 同值）。
     * 单 Key 虚拟 ID = 上界 - 行 id（iot_open_api_key.id 递增为正数，永不重叠真实用户）。
     *
     * <p>本模块不依赖 starter-security，故保留字面量而非直接引用；
     * 一致性由 `OpenApiPrincipalVirtualIdConsistencyTest` 钉住（三处同值，漂移即转红）。</p>
     */
    public static final long VIRTUAL_USER_ID_BASE = -1_000_000_000L;

    /** 公开标识前缀（明文可存，定位行用）。 */
    public static final String ACCESS_KEY_PREFIX = "ak_";

    /** 密钥前缀（仅回显辨识；前缀不足以还原明文）。 */
    public static final String SECRET_PREFIX = "sk_";

    /** 密钥回显前缀长度（仅展示用）。 */
    public static final int SECRET_PREFIX_LENGTH = 8;

    /** 生成密钥的随机字节数（32 字节 = 256 位熵，与设备口令口径一致）。 */
    public static final int SECRET_BYTE_LENGTH = 32;

    /** 生成公开标识的随机字节数（12 字节 = 96 位熵，定位用不需要太高）。 */
    public static final int ACCESS_KEY_BYTE_LENGTH = 12;

    /** 密钥校验 pepper 的环境变量名（真值不入库、不入配置文件）。 */
    public static final String SECRET_PEPPER_ENV = "YPBIN_OPENAPI_SECRET_PEPPER";

    /** 单 Key 默认 QPS（设计 §2.4）。 */
    public static final int DEFAULT_RATE_LIMIT_QPS = 10;

    /** 单 Key 默认日配额（0 = 不限）。 */
    public static final int DEFAULT_DAILY_QUOTA = 100_000;

    /** 签名有效期（秒）：与 starter 默认一致，供 iot 侧内部验签使用。 */
    public static final long SIGN_TIMEOUT_SECONDS = 60L;

    /** 允许的未来时钟偏移（秒）：容忍客户端与服务端小幅不同步，但不接受明显来自未来的时间戳。 */
    public static final long SIGN_CLOCK_SKEW_SECONDS = 5L;

    /** nonce 防重放计数 Redis key 前缀（按 Key + nonce 唯一）。 */
    public static final String SIGN_NONCE_KEY_PREFIX = "ypbin:openapi:sign:nonce:";

    /**
     * 日配额计数 Redis key 前缀（按 Key + 自然日唯一）。
     *
     * <p><b>必须与网关侧限流写入的前缀一致</b>：`deploy/nacos/ypbin-gateway.yaml` 的
     * `ypbin.gateway.rate-limit.quota-key-prefix`。两处各写一遍字面量，
     * 一致性由 `OpenApiQuotaUsageConsistencyTest` 钉住（漂移即转红；
     * 漂移的后果是"用量查询永远 0 / 限流计数另起一套"，静默错）。</p>
     */
    public static final String QUOTA_KEY_PREFIX = "ypbin:openapi:quota:";

    /**
     * 当日配额计数 key。
     *
     * @param accessKeyId 公开标识（定位行用，不含密钥）
     * @param date        自然日（与网关侧 `LocalDate.now()` 同口径：服务端时区自然日）
     * @return Redis key
     */
    public static String quotaKey(String accessKeyId, LocalDate date) {
        return QUOTA_KEY_PREFIX + accessKeyId + ":" + date;
    }

    /** 计算单 Key 虚拟主体 ID（保留段内、不重叠真实用户）。 */
    public static long virtualUserId(long keyRowId) {
        return VIRTUAL_USER_ID_BASE - keyRowId;
    }
}