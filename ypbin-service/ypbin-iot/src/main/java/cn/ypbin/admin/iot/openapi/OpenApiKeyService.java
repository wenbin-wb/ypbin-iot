package cn.ypbin.admin.iot.openapi;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.sign.core.ApiKeyCredentials;
import cn.ypbin.starter.sign.core.InMemoryNonceStore;
import cn.ypbin.starter.sign.core.IpWhitelist;
import cn.ypbin.starter.sign.core.NonceStore;
import cn.ypbin.starter.sign.core.SignAlgorithm;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 开放 API Key 服务（看板 #11 第 1 批）。
 *
 * 安全要点（设计 §2.5）：只存 HMAC-SHA256(pepper, secret) 哈希与前 8 位明文前缀；
 * 明文仅创建响应返回一次；校验用常量时间比较；失败统一 INVALID 防枚举。
 * 凭证原语（随机串/哈希/比对/回显）委托 starter {@code ApiKeyCredentials}（3.7.0），
 * 本类只留租户/作用域/配额/落库等纯业务。
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

    /**
     * Redis 访问（配额用量查询用；可空）。
     *
     * <p>为 {@code null} 表示调用方未提供（如单测的简化构造）⇒ 用量一律回"未知"，
     * 不影响鉴权主链路。用量查询是**可观测性**，绝不能因为 Redis 缺失而拒绝合法请求。</p>
     */
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Autowired
    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper,
                             @Value("${ypbin.openapi.secret-pepper:}") String pepper,
                             @Value("${ypbin.openapi.require-signature:false}") boolean localRequireSignature,
                             ObjectProvider<NonceStore> nonceStoreProvider,
                             ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(keyMapper, pepper, nonceStoreProvider.getIfAvailable(
            InMemoryNonceStore::new), localRequireSignature, redisTemplateProvider);
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
        this(keyMapper, pepper, nonceStore, localRequireSignature, null);
    }

    /**
     * 全参构造（供需要用量查询的调用方传入 Redis）。
     *
     * @param keyMapper              Key 表 Mapper
     * @param pepper                 pepper
     * @param nonceStore             nonce 存储
     * @param localRequireSignature  iot 自身配置的强制签名开关
     * @param redisTemplateProvider  Redis 访问（可为 null；为 null 时用量一律回"未知"）
     */
    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper, String pepper, NonceStore nonceStore,
                             boolean localRequireSignature,
                             ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.keyMapper = keyMapper;
        this.pepper = pepper;
        this.nonceStore = nonceStore;
        this.localRequireSignature = localRequireSignature;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * 读当日配额用量（可观测性，fail-soft）。
     *
     * <p>计数键与网关限流器同源（`OpenApiKeyConstants#quotaKey`）：网关每次放行 `INCR`，
     * 故该值 = 当日实际放行数。Redis 不可用 / 值非法时回 {@code null}（未知），
     * **绝不因此拒绝请求** —— 用量查询挂了不该把鉴权链路一起带崩。</p>
     *
     * @param accessKeyId 公开标识
     * @return 当日已用次数；未知返回 {@code null}
     */
    Long readQuotaUsage(String accessKeyId) {
        ObjectProvider<StringRedisTemplate> provider = redisTemplateProvider;
        if (provider == null || accessKeyId == null || accessKeyId.isBlank()) {
            return null;
        }
        StringRedisTemplate redisTemplate = provider.getIfAvailable();
        if (redisTemplate == null) {
            return null;
        }
        try {
            String key = OpenApiKeyConstants.quotaKey(accessKeyId.trim(),
                LocalDate.now());
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                // 键不存在 = 今日尚无放行调用（计数器是放行时才 INCR 创建的）
                return 0L;
            }
            return Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            log.warn("[iot] 开放 API 配额用量读取失败，按未知处理（不影响鉴权）：ak={}",
                LogSanitizer.sanitize(accessKeyId));
            return null;
        }
    }

    /**
     * 配额重置时刻（次日零点，服务端时区自然日；与网关配额 TTL 口径一致）。
     *
     * @return 次日零点
     */
    static LocalDateTime quotaResetAt() {
        return LocalDate.now().plusDays(1).atStartOfDay();
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
        String ipWhitelist = normalizeIpWhitelist(req.getIpWhitelist());
        String accessKey = ApiKeyCredentials.generateSecret(OpenApiKeyConstants.ACCESS_KEY_BYTE_LENGTH);
        String secret = ApiKeyCredentials.generateSecret(OpenApiKeyConstants.SECRET_BYTE_LENGTH);

        IotOpenApiKey row = new IotOpenApiKey();
        row.setAppName(req.getAppName().trim());
        row.setAccessKeyId(OpenApiKeyConstants.ACCESS_KEY_PREFIX + accessKey);
        // 完整密钥串 = PREFIX + secret（第三方持有的就是完整串）⇒ hash 也按完整串计算，与校验口径一致
        row.setSecretHash(ApiKeyCredentials.hashSecret(pepper, OpenApiKeyConstants.SECRET_PREFIX + secret));
        row.setSecretPrefix(ApiKeyCredentials.displayPrefix(
            OpenApiKeyConstants.SECRET_PREFIX + secret, OpenApiKeyConstants.SECRET_PREFIX,
            OpenApiKeyConstants.SECRET_PREFIX_LENGTH));
        row.setScopes(String.join(",", scopes));
        row.setStatus(EntityStatus.ENABLED.getCode());
        row.setRateLimitQps(req.getRateLimitQps() == null
            ? OpenApiKeyConstants.DEFAULT_RATE_LIMIT_QPS : req.getRateLimitQps());
        row.setDailyQuota(req.getDailyQuota() == null
            ? OpenApiKeyConstants.DEFAULT_DAILY_QUOTA : req.getDailyQuota());
        row.setIpWhitelist(ipWhitelist);
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
                row.getCreateTime(), readQuotaUsage(row.getAccessKeyId()),
                row.getRateLimitQps(), row.getDailyQuota()));
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
            row.getExpireAt(), row.getLastUsedAt(),
            readQuotaUsage(row.getAccessKeyId()), quotaResetAt());
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
        boolean matches = ApiKeyCredentials.matches(pepper, req.getSecret(), row.getSecretHash());
        if (!matches) {
            return OpenApiKeyVerifyDtos.VerifyResp.invalid();
        }
        // 来源 IP 白名单（配了才查；放在签名校验之前：IP 不对连 HMAC 都不必算，
        // 且失败同样统一 invalid，不泄露"哪一关没过"）。
        if (!checkIpWhitelist(req, row)) {
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

    /**
     * 来源 IP 白名单校验（未配置白名单时直接通过）。
     *
     * <p>调用方（网关）传入的是<b>连接远端地址</b>，不是 `X-Forwarded-For`
     * （网关前无可信代理，XFF 完全可伪造）。匹配逻辑委托 starter `IpWhitelist`
     * （精确 IP + CIDR，非法条目 fail-closed）。</p>
     *
     * @return 通过返回 {@code true}
     */
    private boolean checkIpWhitelist(OpenApiKeyVerifyDtos.VerifyReq req, IotOpenApiKey row) {
        String whitelist = row.getIpWhitelist();
        if (whitelist == null || whitelist.isBlank()) {
            // 未配置 = 不限来源（保持旧行为；存量 Key 全是空）
            return true;
        }
        List<String> invalid = IpWhitelist.invalidEntries(whitelist);
        if (!invalid.isEmpty()) {
            // 配置了却写错 ⇒ 若静默会变成"以为有限制、实际全放行"；
            // 故记 WARN 让运维可见（校验本身仍按 fail-closed 执行）。
            log.warn("[iot] 开放 API 的 IP 白名单含无法解析的条目（已忽略，不匹配任何地址）：ak={}, invalid={}",
                LogSanitizer.sanitize(row.getAccessKeyId()), invalid);
        }
        if (!IpWhitelist.matches(whitelist, req.getClientIp())) {
            log.warn("[iot] 开放 API 来源 IP 不在白名单：ak={}, ip={}",
                LogSanitizer.sanitize(row.getAccessKeyId()), LogSanitizer.sanitize(req.getClientIp()));
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

    /**
     * IP 白名单归一化 + 合法性校验（空 = 不限来源）。
     *
     * <p>非法条目在**创建时直接拒绝**（而不是存进去、校验时静默忽略）：
     * 否则就是"配了限制、实际没限制"的静默失效 —— 与 `normalizeScopes` 同一纪律。</p>
     *
     * @return 归一化后的白名单（去空白、去重；未配置返回 {@code null}）
     */
    private static String normalizeIpWhitelist(String ipWhitelist) {
        if (ipWhitelist == null || ipWhitelist.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String raw : ipWhitelist.split(",")) {
            String token = raw.trim();
            if (token.isEmpty() || out.contains(token)) {
                continue;
            }
            if (!IpWhitelist.isValidEntry(token)) {
                throw new BusinessException("IP 白名单条目非法（需为 IP 或 CIDR，如 10.0.0.0/8）：" + token);
            }
            out.add(token);
        }
        return out.isEmpty() ? null : String.join(",", out);
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
}