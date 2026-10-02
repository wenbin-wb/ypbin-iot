package cn.ypbin.admin.iot.openapi;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.sign.core.InMemoryNonceStore;
import cn.ypbin.starter.sign.core.NonceStore;
import cn.ypbin.starter.sign.core.SignAlgorithm;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 开放 API Key 服务（看板 #11 第 1 批）。
 *
 * 安全要点（设计 §2.5）：只存 HMAC-SHA256(pepper, secret) 哈希与前 8 位明文前缀；
 * 明文仅创建响应返回一次；校验用常量时间比较；失败统一 INVALID 防枚举。
 *
 * <p>pepper 由环境变量 YPBIN_OPENAPI_SECRET_PEPPER 经 relaxed binding 注入
 * （ypbin.openapi.secret-pepper），真值不入库不入配置文件；iot 与网关两侧必须同值。</p>
 */
@Service
public class OpenApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiKeyService.class);

    private final IotOpenApiKeyMapper keyMapper;

    /** pepper（环境变量注入；缺失时创建/校验 fail-closed）。 */
    private final String pepper;

    /** nonce 防重放存储（签名校验用；多实例须 Redis）。 */
    private final NonceStore nonceStore;

    /**
     * iot 侧自身的强制签名开关（纵深防御）。
     *
     * <p><b>为什么不能只信网关传来的 requireSignature</b>：该字段经内部 HTTP 请求体传递，
     * 其可信度完全依赖"只有网关能调 /internal/**"这一假设（保护手段是一个**共享静态**
     * X-Internal-Token）。若 token 泄露或 18084 被误配为对外可达，攻击者即可自设
     * requireSignature=false 降级，使强制签名失效。</p>
     *
     * <p>故本服务另读<b>自身配置</b>，与请求值取<b>逻辑或</b>（任一说要强制就强制）——
     * 这样即便请求体被篡改也无法关闭强制模式（fail-closed）。</p>
     */
    private final boolean localRequireSignature;

    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper,
                             @Value("${ypbin.openapi.secret-pepper:}") String pepper,
                             @Value("${ypbin.openapi.require-signature:false}") boolean localRequireSignature,
                             ObjectProvider<NonceStore> nonceStoreProvider) {
        this(keyMapper, pepper, nonceStoreProvider.getIfAvailable(
            InMemoryNonceStore::new), localRequireSignature);
    }

    /**
     * 简化构造：使用内存 nonce 存储（**仅单实例正确**，供单测/单机场景）。
     *
     * @param keyMapper Key 表 Mapper
     * @param pepper    pepper
     */
    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper, String pepper) {
        this(keyMapper, pepper, new InMemoryNonceStore());
    }

    /**
     * 显式传入 nonce 存储（供单测与自定义装配使用）。
     *
     * @param keyMapper  Key 表 Mapper
     * @param pepper     pepper（可为空，为空时创建/校验 fail-closed）
     * @param nonceStore nonce 防重放存储（不得为 null）
     */
    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper, String pepper, NonceStore nonceStore) {
        this(keyMapper, pepper, nonceStore, false);
    }

    /**
     * 全参构造（供装配层传入本地强制开关）。
     *
     * @param keyMapper            Key 表 Mapper
     * @param pepper               pepper
     * @param nonceStore           nonce 存储
     * @param localRequireSignature iot 自身配置的强制签名开关
     */
    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper, String pepper, NonceStore nonceStore,
                             boolean localRequireSignature) {
        this.keyMapper = keyMapper;
        this.pepper = pepper;
        this.nonceStore = nonceStore;
        this.localRequireSignature = localRequireSignature;
    }

    /** 创建 Key：明文仅此一次。 */
    public OpenApiKeyDtos.CreateResp create(OpenApiKeyDtos.CreateReq req) {
        assertPepper();
        if (req.getAppName() == null || req.getAppName().isBlank()) {
            throw new BusinessException("应用名不能为空");
        }
        List<String> scopes = normalizeScopes(req.getScopes());
        if (scopes.isEmpty()) {
            throw new BusinessException("至少需要一个作用域（如 iot:series:get）");
        }
        String accessKey = randomToken(OpenApiKeyConstants.ACCESS_KEY_BYTE_LENGTH);
        String secret = randomToken(OpenApiKeyConstants.SECRET_BYTE_LENGTH);

        IotOpenApiKey row = new IotOpenApiKey();
        row.setAppName(req.getAppName().trim());
        row.setAccessKeyId(OpenApiKeyConstants.ACCESS_KEY_PREFIX + accessKey);
        // 完整密钥串 = PREFIX + secret（第三方持有的就是完整串）⇒ hash 也按完整串计算，与校验口径一致
        row.setSecretHash(hmacHex(pepper, OpenApiKeyConstants.SECRET_PREFIX + secret));
        row.setSecretPrefix(OpenApiKeyConstants.SECRET_PREFIX
            + secret.substring(0, OpenApiKeyConstants.SECRET_PREFIX_LENGTH));
        row.setScopes(String.join(",", scopes));
        row.setStatus(EntityStatus.ENABLED.getCode());
        row.setRateLimitQps(req.getRateLimitQps() == null
            ? OpenApiKeyConstants.DEFAULT_RATE_LIMIT_QPS : req.getRateLimitQps());
        row.setDailyQuota(req.getDailyQuota() == null
            ? OpenApiKeyConstants.DEFAULT_DAILY_QUOTA : req.getDailyQuota());
        row.setExpireAt(req.getExpireAt());
        keyMapper.insert(row);
        return new OpenApiKeyDtos.CreateResp(String.valueOf(row.getId()), row.getAccessKeyId(),
            OpenApiKeyConstants.SECRET_PREFIX + secret, row.getSecretPrefix(), scopes, row.getExpireAt());
    }

    /** 列表（只回 prefix，绝不给明文）。 */
    public List<OpenApiKeyDtos.ListItemResp> listAll() {
        List<IotOpenApiKey> rows = keyMapper.selectList(
            new LambdaQueryWrapper<IotOpenApiKey>().orderByDesc(IotOpenApiKey::getId));
        List<OpenApiKeyDtos.ListItemResp> out = new ArrayList<>();
        for (IotOpenApiKey row : rows) {
            out.add(new OpenApiKeyDtos.ListItemResp(
                String.valueOf(row.getId()), row.getAppName(), row.getAccessKeyId(), row.getSecretPrefix(),
                splitScopes(row.getScopes()), row.getStatus(), row.getExpireAt(), row.getLastUsedAt(),
                row.getCreateTime()));
        }
        return out;
    }

    /** 吊销（禁用即生效：校验每请求查库，无长 TTL 缓存）。 */
    public void revoke(Long id) {
        IotOpenApiKey row = keyMapper.selectById(id);
        if (row == null) {
            throw new BusinessException("Key 不存在");
        }
        row.setStatus(EntityStatus.DISABLED.getCode());
        keyMapper.updateById(row);
    }

    /**
     * 自检（GET /open-api/v1/whoami：只回当前 Key 自身信息，不含明文 secret）。
     *
     * <p>无 {@code @SaCheckPermission}（任何 scope 组合的 Key 都应能自检），改为按身份分流：
     * 非虚拟主体一律拒绝（fail-closed，管理面用户走管理端点，不走这里）。行定位由虚拟 ID
     * 反推（单 Key 虚拟 ID = 上界 - 行 id），读库实时反映吊销/配额变更。</p>
     */
    public OpenApiKeyDtos.WhoamiResp whoami() {
        LoginUser loginUser = IdentityContext.getLoginUser().orElse(null);
        Long userId = loginUser == null ? null : loginUser.getId();
        if (!OpenApiPrincipal.isVirtualPrincipal(userId)) {
            throw new BusinessException("非开放 API Key 身份");
        }
        long keyRowId = OpenApiKeyConstants.VIRTUAL_USER_ID_BASE - userId;
        IotOpenApiKey row = keyMapper.selectById(keyRowId);
        if (row == null) {
            throw new BusinessException("Key 不存在");
        }
        Long tenantId = IdentityContext.getTenantId().orElse(null);
        if (tenantId == null || !tenantId.equals(row.getTenantId())) {
            log.warn("[iot] 开放 API 自检租户不一致，按拒绝处理：userId={}", userId);
            throw new BusinessException("非开放 API Key 身份");
        }
        return new OpenApiKeyDtos.WhoamiResp(row.getAccessKeyId(), row.getAppName(), row.getTenantId(),
            splitScopes(row.getScopes()), row.getStatus(), row.getRateLimitQps(), row.getDailyQuota(),
            row.getExpireAt(), row.getLastUsedAt());
    }

    /** 内部校验（网关调用）。失败统一 invalid()，不区分不存在/secret 错/禁用/过期（防枚举）。 */
    public OpenApiKeyVerifyDtos.VerifyResp verify(OpenApiKeyVerifyDtos.VerifyReq req) {
        if (req.getAccessKeyId() == null || req.getSecret() == null
            || req.getAccessKeyId().isBlank() || req.getSecret().isBlank()) {
            return OpenApiKeyVerifyDtos.VerifyResp.invalid();
        }
        if (pepper == null || pepper.isEmpty()) {
            log.error("[iot] 开放 API 校验缺少 pepper（ypbin.openapi.secret-pepper），拒绝放行");
            return OpenApiKeyVerifyDtos.VerifyResp.invalid();
        }
        IotOpenApiKey row = keyMapper.selectOne(
            new LambdaQueryWrapper<IotOpenApiKey>()
                .eq(IotOpenApiKey::getAccessKeyId, req.getAccessKeyId().trim()));
        if (row == null || !EntityStatus.ENABLED.getCode().equals(row.getStatus())
            || (row.getExpireAt() != null && row.getExpireAt().isBefore(LocalDateTime.now()))) {
            return OpenApiKeyVerifyDtos.VerifyResp.invalid();
        }
        boolean matches = constantTimeEquals(hmacHex(pepper, req.getSecret().trim()),
            row.getSecretHash());
        if (!matches) {
            return OpenApiKeyVerifyDtos.VerifyResp.invalid();
        }
        // 密钥已确认属于该 Key ⇒ 此时才具备"验签"的前提（密钥错就没必要继续）
        if (!verifySignatureIfPresent(req, row)) {
            return OpenApiKeyVerifyDtos.VerifyResp.invalid();
        }
        row.setLastUsedAt(LocalDateTime.now());
        keyMapper.updateById(row);
        int qps = row.getRateLimitQps() == null ? OpenApiKeyConstants.DEFAULT_RATE_LIMIT_QPS : row.getRateLimitQps();
        int quota = row.getDailyQuota() == null ? OpenApiKeyConstants.DEFAULT_DAILY_QUOTA : row.getDailyQuota();
        return new OpenApiKeyVerifyDtos.VerifyResp(true, row.getTenantId(),
            OpenApiKeyConstants.virtualUserId(row.getId()), splitScopes(row.getScopes()), qps, quota);
    }

    /**
     * 按灰度口径校验请求签名（未意图签名时直接放行 = 既有行为）。
     *
     * <p>三分支（**顺序即安全语义，不可重排**）：</p>
     * <ol>
     *     <li>四参数<b>全无</b> ⇒ 未启用签名 ⇒ 放行（灰度 OPTIONAL 兼容期）；</li>
     *     <li>带了参数但<b>不齐备</b> ⇒ 拒绝（fail-closed，防"部分携带"绕过）；</li>
     *     <li>齐备 ⇒ 校验时间戳 + 重算签名 + nonce 防重放，任一不过即拒绝。</li>
     * </ol>
     *
     * @return 通过返回 {@code true}
     */
    private boolean verifySignatureIfPresent(OpenApiKeyVerifyDtos.VerifyReq req, IotOpenApiKey row) {
        String timestamp = req.getTimestamp();
        String nonce = req.getNonce();
        String sign = req.getSign();
        Map<String, String> params = req.getSignParams();

        if (!OpenApiSignVerifier.intendsSignature(timestamp, nonce, sign, params)) {
            // 四参数全无：即"未意图签名"。是否放行**只能由服务端强制模式决定**——
            // 绝不能无条件放行，否则攻击者只要不带这些参数即可绕过验签（降级攻击）。
            // 逻辑或：请求方（网关）与 iot 自身配置，任一说强制就强制 ⇒ 改请求体也无法关闭
            if (req.isRequireSignature() || localRequireSignature) {
                log.warn("[iot] 开放 API 强制签名模式下请求未携带签名参数，拒绝：ak={}",
                    LogSanitizer.sanitize(row.getAccessKeyId()));
                return false;
            }
            // 非强制模式（灰度兼容期）：放行"仅 Key"的既有接入方式
            return true;
        }
        if (!OpenApiSignVerifier.complete(timestamp, nonce, sign, params)) {
            // 带了参数却不齐备：无论是否强制模式都拒绝（fail-closed，防"部分携带"降级）
            log.warn("[iot] 开放 API 请求携带了不完整的签名参数，拒绝：ak={}",
                LogSanitizer.sanitize(row.getAccessKeyId()));
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (!OpenApiSignVerifier.timestampValid(timestamp, now)) {
            log.warn("[iot] 开放 API 签名时间戳无效或已过期：ak={}",
                LogSanitizer.sanitize(row.getAccessKeyId()));
            return false;
        }
        if (!OpenApiSignVerifier.signatureMatches(params, req.getSecret(), sign, SignAlgorithm.HMAC_SHA256)) {
            log.warn("[iot] 开放 API 签名校验失败：ak={}", LogSanitizer.sanitize(row.getAccessKeyId()));
            return false;
        }
        // 防重放：nonce 只能用一次。放在最后——只有签名合法才占用 nonce 名额，
        // 否则攻击者可用无效签名刷满 nonce 空间（等于另一种 DoS 面）。
        if (!nonceStore.tryUse(OpenApiKeyConstants.SIGN_NONCE_KEY_PREFIX
            + row.getAccessKeyId() + ":" + nonce.trim(),
            Duration.ofSeconds(OpenApiSignVerifier.nonceTtlSeconds(timestamp, now)))) {
            log.warn("[iot] 开放 API 请求重放（nonce 已使用）：ak={}",
                LogSanitizer.sanitize(row.getAccessKeyId()));
            return false;
        }
        return true;
    }

    /** 作用域归一化 + 白名单校验（只允许既有开放作用域）。 */
    private static List<String> normalizeScopes(String scopes) {
        List<String> out = new ArrayList<>();
        if (scopes == null) {
            return out;
        }
        for (String raw : scopes.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (!OpenApiPrincipal.isAllowedScope(token)) {
                throw new BusinessException("作用域不在开放白名单：" + token);
            }
            if (!out.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    private static List<String> splitScopes(String scopes) {
        List<String> out = new ArrayList<>();
        if (scopes == null) {
            return out;
        }
        for (String raw : scopes.split(",")) {
            String token = raw.trim();
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private void assertPepper() {
        if (pepper == null || pepper.isEmpty()) {
            throw new BusinessException("缺少密钥 pepper（ypbin.openapi.secret-pepper），拒绝创建");
        }
    }

    private static String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hmacHex(String pepper, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC 初始化失败", ex);
        }
    }

    /** 常量时间比较（防时序侧信道）。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
            b.getBytes(StandardCharsets.US_ASCII));
    }
}