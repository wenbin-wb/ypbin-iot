package cn.ypbin.admin.iot.openapi;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OpenApiKeyService(IotOpenApiKeyMapper keyMapper,
                             @Value("${ypbin.openapi.secret-pepper:}") String pepper) {
        this.keyMapper = keyMapper;
        this.pepper = pepper;
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
        row.setLastUsedAt(LocalDateTime.now());
        keyMapper.updateById(row);
        int qps = row.getRateLimitQps() == null ? OpenApiKeyConstants.DEFAULT_RATE_LIMIT_QPS : row.getRateLimitQps();
        int quota = row.getDailyQuota() == null ? OpenApiKeyConstants.DEFAULT_DAILY_QUOTA : row.getDailyQuota();
        return new OpenApiKeyVerifyDtos.VerifyResp(true, row.getTenantId(),
            OpenApiKeyConstants.virtualUserId(row.getId()), splitScopes(row.getScopes()), qps, quota);
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