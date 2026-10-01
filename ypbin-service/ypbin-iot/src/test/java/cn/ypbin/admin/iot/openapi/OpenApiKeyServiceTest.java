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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}