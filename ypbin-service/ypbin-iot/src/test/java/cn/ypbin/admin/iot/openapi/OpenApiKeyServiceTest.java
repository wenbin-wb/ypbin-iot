package cn.ypbin.admin.iot.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import cn.ypbin.starter.sign.core.InMemoryNonceStore;
import cn.ypbin.starter.sign.core.NonceStore;
import cn.ypbin.starter.sign.core.SignAlgorithm;
import cn.ypbin.starter.sign.core.SignGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 开放 API Key 服务用例（看板 #11 第 1 批）。 */
class OpenApiKeyServiceTest {

    private static final String TEST_PEPPER = "test-pepper-not-a-real-secret";

    private IotOpenApiKeyMapper mapper;

    private OpenApiKeyService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IotOpenApiKeyMapper.class);
        service = new OpenApiKeyService(mapper, TEST_PEPPER);
    }

    @AfterEach
    void tearDown() {
        IdentityContext.clear();
    }

    private static OpenApiKeyDtos.CreateReq req(String app, String scopes) {
        OpenApiKeyDtos.CreateReq r = new OpenApiKeyDtos.CreateReq();
        r.setAppName(app);
        r.setScopes(scopes);
        return r;
    }

    @Test
    @DisplayName("创建：无 pepper ⇒ 拒绝（fail-closed）")
    void createWithoutPepperMustFail() {
        OpenApiKeyService noPepper = new OpenApiKeyService(mapper, null);
        assertThatThrownBy(() -> noPepper.create(req("a", "iot:series:get")))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("创建：作用域必须在白名单内（pattern / 白名单外一律拒绝）")
    void createWithNonAllowlistedScopeMustFail() {
        assertThatThrownBy(() -> service.create(req("t", "iot:*"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(req("t", "system:user:list")))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(req("t", " "))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("创建：返回明文仅一次，库内只存 hash 与 prefix")
    void createStoresOnlyHashAndPrefix() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp resp = service.create(req("app", "iot:series:get"));
        assertThat(resp.secret()).startsWith(OpenApiKeyConstants.SECRET_PREFIX);
        assertThat(resp.secretPrefix()).isNotNull();
    }

    @Test
    @DisplayName("校验失败统一 INVALID（不存在=x=禁用 不可区分，防枚举）")
    void verifyFailuresAreIndistinguishable() {
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId("ak_missing");
        r.setSecret("whatever");
        when(mapper.selectOne(any())).thenReturn(null);
        OpenApiKeyVerifyDtos.VerifyResp missing = service.verify(r);

        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(5L);
        row.setTenantId(1L);
        row.setAccessKeyId("ak_x");
        row.setStatus(0);
        row.setScopes("iot:series:get");
        when(mapper.selectOne(any())).thenReturn(row);
        r.setAccessKeyId("ak_x");
        OpenApiKeyVerifyDtos.VerifyResp disabled = service.verify(r);

        assertThat(missing).isEqualTo(OpenApiKeyVerifyDtos.VerifyResp.invalid());
        assertThat(disabled).isEqualTo(OpenApiKeyVerifyDtos.VerifyResp.invalid());
    }

    @Test
    @DisplayName("校验：正确 secret 放行并返回租户/虚拟主体/作用域")
    void verifySuccess() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));

        org.mockito.ArgumentCaptor<IotOpenApiKey> cap =
            org.mockito.ArgumentCaptor.forClass(IotOpenApiKey.class);
        org.mockito.Mockito.verify(mapper).insert(cap.capture());
        String hash = cap.getValue().getSecretHash();

        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(5L);
        row.setTenantId(1L);
        row.setAccessKeyId(created.accessKeyId());
        row.setSecretHash(hash);
        row.setSecretPrefix(created.secretPrefix());
        row.setScopes("iot:series:get");
        row.setStatus(1);
        when(mapper.selectOne(any())).thenReturn(row);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        OpenApiKeyVerifyDtos.VerifyResp resp = service.verify(r);

        assertThat(resp.valid()).isTrue();
        assertThat(resp.tenantId()).isEqualTo(1L);
        assertThat(resp.scopes()).containsExactly("iot:series:get");
        assertThat(resp.virtualUserId()).isEqualTo(OpenApiKeyConstants.virtualUserId(5L));
    }

    @Test
    @DisplayName("自检：虚拟主体返回自身信息（不含明文）")
    void whoamiReturnsOwnKeyInfo() {
        long keyRowId = 7L;
        long virtualUserId = OpenApiKeyConstants.virtualUserId(keyRowId);
        givenVirtualIdentity(virtualUserId, 1L);

        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(keyRowId);
        row.setTenantId(1L);
        row.setAppName("erp");
        row.setAccessKeyId("ak_x");
        row.setSecretPrefix("sk_ab12");
        row.setScopes("iot:series:get,iot:alert:list");
        row.setStatus(1);
        row.setRateLimitQps(10);
        row.setDailyQuota(100000);
        when(mapper.selectById(keyRowId)).thenReturn(row);

        OpenApiKeyDtos.WhoamiResp resp = service.whoami();

        assertThat(resp.accessKeyId()).isEqualTo("ak_x");
        assertThat(resp.scopes()).containsExactly("iot:series:get", "iot:alert:list");
        assertThat(resp.tenantId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("自检：非虚拟主体/租户不一致一律拒绝")
    void whoamiRejectsNonVirtualOrTenantMismatch() {
        // 无身份
        IdentityContext.clear();
        assertThatThrownBy(() -> service.whoami()).isInstanceOf(BusinessException.class);

        // 真实用户
        givenVirtualIdentity(99L, 1L);
        assertThatThrownBy(() -> service.whoami()).isInstanceOf(BusinessException.class);

        // 租户不一致
        long keyRowId = 7L;
        givenVirtualIdentity(OpenApiKeyConstants.virtualUserId(keyRowId), 2L);
        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(keyRowId);
        row.setTenantId(1L);
        row.setScopes("iot:series:get");
        when(mapper.selectById(keyRowId)).thenReturn(row);
        assertThatThrownBy(() -> service.whoami()).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("自检：行缺失（硬删除）拒绝，不泄露存在性")
    void whoamiRejectsMissingRow() {
        long keyRowId = 9L;
        givenVirtualIdentity(OpenApiKeyConstants.virtualUserId(keyRowId), 1L);
        when(mapper.selectById(keyRowId)).thenReturn(null);
        assertThatThrownBy(() -> service.whoami()).isInstanceOf(BusinessException.class);
    }

    private static void givenVirtualIdentity(long userId, long tenantId) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setTenantId(tenantId);
        IdentityContext.setLoginUser(user);
    }

    // ==================== 签名校验（看板「开放 API 签名合并」） ====================

    /** 造一条可校验的 Key 行（复用 create 得到真实哈希）。 */
    private IotOpenApiKey seedKey(OpenApiKeyDtos.CreateResp created, long id) {
        org.mockito.ArgumentCaptor<IotOpenApiKey> cap =
            org.mockito.ArgumentCaptor.forClass(IotOpenApiKey.class);
        org.mockito.Mockito.verify(mapper).insert(cap.capture());
        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(id);
        row.setTenantId(1L);
        row.setAccessKeyId(created.accessKeyId());
        row.setSecretHash(cap.getValue().getSecretHash());
        row.setScopes("iot:series:get");
        row.setStatus(1);
        when(mapper.selectOne(any())).thenReturn(row);
        return row;
    }

    private static Map<String, String> signParams() {
        Map<String, String> p = new HashMap<>();
        p.put("orderNo", "A100");
        return p;
    }

    private static String nowTs() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    @Test
    @DisplayName("签名：未带签名参数 ⇒ 放行（灰度兼容既有「仅 Key」接入）")
    void verifyWithoutSignatureStillPasses() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());

        assertThat(service.verify(r).valid()).isTrue();
    }

    @Test
    @DisplayName("签名：参数齐备且签名正确 ⇒ 放行")
    void verifyWithValidSignaturePasses() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setTimestamp(nowTs());
        r.setNonce("nonce-1");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(p);

        assertThat(service.verify(r).valid()).isTrue();
    }

    @Test
    @DisplayName("🔴 签名：只带部分参数 ⇒ 拒绝（防降级绕过）")
    void verifyWithPartialSignatureMustFail() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        String ts = nowTs();

        // 各种"部分携带"组合都必须拒绝
        Object[][] partials = {
            {ts, null, null, null},
            {null, "nonce-1", null, null},
            {null, null, "ABCDEF", null},
            {ts, "nonce-1", null, null},
            {ts, "nonce-1", "ABCDEF", null},
            {ts, null, "ABCDEF", p},
            {null, "nonce-1", "ABCDEF", p},
        };
        for (Object[] combo : partials) {
            OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
            r.setAccessKeyId(created.accessKeyId());
            r.setSecret(created.secret());
            r.setTimestamp((String) combo[0]);
            r.setNonce((String) combo[1]);
            r.setSign((String) combo[2]);
            @SuppressWarnings("unchecked")
            Map<String, String> sp = (Map<String, String>) combo[3];
            r.setSignParams(sp);
            assertThat(service.verify(r).valid())
                .as("部分携带签名参数必须拒绝：ts=%s nonce=%s sign=%s params=%s",
                    combo[0], combo[1], combo[2], combo[3])
                .isFalse();
        }
    }

    @Test
    @DisplayName("签名：错签名 / 篡改参数 ⇒ 拒绝")
    void verifyWithWrongSignatureMustFail() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        Map<String, String> tampered = new HashMap<>(p);
        tampered.put("orderNo", "A999");

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setTimestamp(nowTs());
        r.setNonce("nonce-x");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(tampered);

        assertThat(service.verify(r).valid()).isFalse();
    }

    @Test
    @DisplayName("签名：时间戳过期 ⇒ 拒绝")
    void verifyWithExpiredTimestampMustFail() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setTimestamp(String.valueOf(System.currentTimeMillis() / 1000L - 9999));
        r.setNonce("nonce-old");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(p);

        assertThat(service.verify(r).valid()).isFalse();
    }

    @Test
    @DisplayName("🔴 防重放：同一 nonce 第二次必须拒绝")
    void verifyReplayMustFail() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setTimestamp(nowTs());
        r.setNonce("replay-nonce");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(p);

        assertThat(service.verify(r).valid()).as("首次应放行").isTrue();
        assertThat(service.verify(r).valid()).as("重放必须拒绝").isFalse();
    }

    @Test
    @DisplayName("🔴 防重放：签名非法时不得占用 nonce 名额（否则可被刷满）")
    void invalidSignatureMustNotConsumeNonce() {
        AtomicInteger calls = new AtomicInteger();
        NonceStore counting = (key, ttl) -> {
            calls.incrementAndGet();
            return true;
        };
        OpenApiKeyService svc = new OpenApiKeyService(mapper, TEST_PEPPER, counting);

        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = svc.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setTimestamp(nowTs());
        r.setNonce("bad-sig-nonce");
        r.setSign("DEADBEEF"); // 错签名
        r.setSignParams(p);

        assertThat(svc.verify(r).valid()).isFalse();
        assertThat(calls.get()).as("签名非法时不得调用 nonce 存储").isZero();
    }

    @Test
    @DisplayName("防重放：不同 nonce 互不影响")
    void distinctNoncesBothPass() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        for (String nonce : new String[]{"n-1", "n-2", "n-3"}) {
            OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
            r.setAccessKeyId(created.accessKeyId());
            r.setSecret(created.secret());
            r.setTimestamp(nowTs());
            r.setNonce(nonce);
            r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
            r.setSignParams(p);
            assertThat(service.verify(r).valid()).as("nonce=%s 应放行", nonce).isTrue();
        }
    }

    @Test
    @DisplayName("nonce 存储异常 ⇒ 按拒绝处理（fail-closed，不放行重放）")
    void nonceStoreFailureMustFailClosed() {
        NonceStore broken = (key, ttl) -> {
            throw new IllegalStateException("redis down");
        };
        OpenApiKeyService svc = new OpenApiKeyService(mapper, TEST_PEPPER, new NonceStore() {
            @Override
            public boolean tryUse(String key, java.time.Duration expire) {
                return broken.tryUse(key, expire);
            }
        });

        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = svc.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setTimestamp(nowTs());
        r.setNonce("boom");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(p);

        // 实现在 Redis 异常时返回 false；这里用抛异常的包装验证"异常不会变成放行"
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> svc.verify(r)))
            .as("存储异常应向上暴露（不静默吞掉）")
            .isInstanceOf(IllegalStateException.class);
    }

    // ==================== 强制签名模式（修复"签名可被少发参数绕过"） ====================

    @Test
    @DisplayName("🔴 强制签名模式：不带任何签名参数 ⇒ 拒绝（不得降级放行）")
    void requiredModeMustRejectUnsignedRequest() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(true); // 服务端强制
        // 刻意不带 timestamp/nonce/sign/signParams

        assertThat(service.verify(r).valid())
            .as("强制模式下缺签名必须拒绝，否则攻击者只要少发参数即可绕过验签")
            .isFalse();
    }

    @Test
    @DisplayName("非强制模式（灰度期）：不带签名参数仍放行，兼容既有「仅 Key」接入")
    void optionalModeAllowsUnsignedRequest() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(false);

        assertThat(service.verify(r).valid()).isTrue();
    }

    @Test
    @DisplayName("🔴 强制模式：带了参数但不齐备 ⇒ 同样拒绝（防部分携带降级）")
    void requiredModeMustRejectPartialSignature() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(true);
        r.setTimestamp(nowTs());
        // 缺 nonce / sign / signParams

        assertThat(service.verify(r).valid()).isFalse();
    }

    @Test
    @DisplayName("强制模式：签名齐备且正确 ⇒ 正常放行（强制不等于一律拒绝）")
    void requiredModeAllowsFullyValidSignature() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(true);
        r.setTimestamp(nowTs());
        r.setNonce("req-ok-nonce");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(p);

        assertThat(service.verify(r).valid()).isTrue();
    }

    @Test
    @DisplayName("🔴 强制模式：重放同一请求必须被拒（防重放真的生效）")
    void requiredModeMustRejectReplay() {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = service.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        Map<String, String> p = signParams();
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(true);
        r.setTimestamp(nowTs());
        r.setNonce("req-replay-nonce");
        r.setSign(SignGenerator.generate(p, created.secret(), SignAlgorithm.HMAC_SHA256));
        r.setSignParams(p);

        assertThat(service.verify(r).valid()).as("首次放行").isTrue();
        assertThat(service.verify(r).valid()).as("重放必须拒绝").isFalse();
    }

    // ==================== 纵深防御：本地强制开关（N-1） ====================

    @Test
    @DisplayName("🔴 纵深防御：本地配置强制时，即使请求体说 requireSignature=false 也必须拒绝")
    void localRequireSignatureOverridesRequestBody() {
        // 场景：X-Internal-Token 泄露或 18084 误配对外可达 ⇒ 攻击者直连并自设
        // requireSignature=false 试图降级。本地开关取"逻辑或"后该降级失效。
        OpenApiKeyService svc = new OpenApiKeyService(mapper, TEST_PEPPER,
            new InMemoryNonceStore(), true);

        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = svc.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(false); // 攻击者自设 false
        // 不带任何签名参数

        assertThat(svc.verify(r).valid())
            .as("本地强制开关必须压过请求体自设值（fail-closed）")
            .isFalse();
    }

    @Test
    @DisplayName("本地强制开关为 false 时，请求体的 true 仍然生效（取或语义）")
    void requestTrueStillEffectiveWhenLocalFalse() {
        OpenApiKeyService svc = new OpenApiKeyService(mapper, TEST_PEPPER,
            new InMemoryNonceStore(), false);

        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = svc.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setRequireSignature(true);

        assertThat(svc.verify(r).valid()).isFalse();
    }

    @Test
    @DisplayName("两者都 false ⇒ 灰度放行（既有接入不受影响）")
    void bothFalseAllowsUnsigned() {
        OpenApiKeyService svc = new OpenApiKeyService(mapper, TEST_PEPPER,
            new InMemoryNonceStore(), false);

        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateResp created = svc.create(req("a", "iot:series:get"));
        seedKey(created, 5L);

        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());

        assertThat(svc.verify(r).valid()).isTrue();
    }
}
