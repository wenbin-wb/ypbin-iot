package cn.ypbin.admin.iot.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.security.identity.IdentityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源 IP 白名单用例：字段建了必须真生效（此前是"假的已实现"：DDL + 实体有字段，
 * 零校验代码——运维会误以为已生效）。
 */
class OpenApiIpWhitelistTest {

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

    private OpenApiKeyDtos.CreateResp createKey(String scopes, String ipWhitelist) {
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);
        OpenApiKeyDtos.CreateReq req = new OpenApiKeyDtos.CreateReq();
        req.setAppName("ip-test");
        req.setScopes(scopes);
        req.setIpWhitelist(ipWhitelist);
        return service.create(req);
    }

    private void stubRow(OpenApiKeyDtos.CreateResp created, String secretHash, String ipWhitelist) {
        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(9L);
        row.setTenantId(1L);
        row.setAccessKeyId(created.accessKeyId());
        row.setSecretHash(secretHash);
        row.setScopes("iot:series:get");
        row.setStatus(1);
        row.setIpWhitelist(ipWhitelist);
        when(mapper.selectOne(any())).thenReturn(row);
    }

    private String secretHashOf(OpenApiKeyDtos.CreateResp created) {
        org.mockito.ArgumentCaptor<IotOpenApiKey> cap =
            org.mockito.ArgumentCaptor.forClass(IotOpenApiKey.class);
        org.mockito.Mockito.verify(mapper).insert(cap.capture());
        return cap.getValue().getSecretHash();
    }

    private OpenApiKeyVerifyDtos.VerifyReq verifyReq(OpenApiKeyDtos.CreateResp created, String clientIp) {
        OpenApiKeyVerifyDtos.VerifyReq r = new OpenApiKeyVerifyDtos.VerifyReq();
        r.setAccessKeyId(created.accessKeyId());
        r.setSecret(created.secret());
        r.setClientIp(clientIp);
        return r;
    }

    @Test
    @DisplayName("创建：非法白名单条目直接拒绝（不在库里留静默失效）")
    void createWithIllegalWhitelistMustFail() {
        OpenApiKeyDtos.CreateReq req = new OpenApiKeyDtos.CreateReq();
        req.setAppName("a");
        req.setScopes("iot:series:get");
        req.setIpWhitelist("not-an-ip");

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not-an-ip");
    }

    @Test
    @DisplayName("创建：合法 CIDR 被归一化落库（去空白去重）")
    void createWithValidWhitelistStored() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", " 10.0.0.0/8 , 10.0.0.0/8, 192.168.1.10 ");

        org.mockito.ArgumentCaptor<IotOpenApiKey> cap =
            org.mockito.ArgumentCaptor.forClass(IotOpenApiKey.class);
        org.mockito.Mockito.verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getIpWhitelist()).isEqualTo("10.0.0.0/8,192.168.1.10");
        assertThat(created.accessKeyId()).isNotBlank();
    }

    @Test
    @DisplayName("创建：不填白名单 = 不限（存 null，保持旧行为）")
    void createWithoutWhitelistStoresNull() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", null);

        org.mockito.ArgumentCaptor<IotOpenApiKey> cap =
            org.mockito.ArgumentCaptor.forClass(IotOpenApiKey.class);
        org.mockito.Mockito.verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getIpWhitelist()).isNull();
    }

    @Test
    @DisplayName("校验：白名单命中 ⇒ 放行")
    void verifyIpInsideWhitelistPasses() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", "10.0.0.0/8");
        stubRow(created, secretHashOf(created), "10.0.0.0/8");

        assertThat(service.verify(verifyReq(created, "10.1.2.3")).valid()).isTrue();
    }

    @Test
    @DisplayName("校验：白名单外 ⇒ invalid（与密钥错不可区分，防枚举）")
    void verifyIpOutsideWhitelistIsInvalid() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", "10.0.0.0/8");
        stubRow(created, secretHashOf(created), "10.0.0.0/8");

        OpenApiKeyVerifyDtos.VerifyResp resp = service.verify(verifyReq(created, "192.168.1.5"));
        assertThat(resp).isEqualTo(OpenApiKeyVerifyDtos.VerifyResp.invalid());
    }

    @Test
    @DisplayName("校验：配了白名单但拿不到来源 IP ⇒ 拒绝（fail-closed）")
    void verifyMissingClientIpIsInvalid() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", "10.0.0.0/8");
        stubRow(created, secretHashOf(created), "10.0.0.0/8");

        assertThat(service.verify(verifyReq(created, null)).valid()).isFalse();
        assertThat(service.verify(verifyReq(created, "  ")).valid()).isFalse();
    }

    @Test
    @DisplayName("校验：未配白名单 ⇒ 不限来源（旧行为不变）")
    void verifyWithoutWhitelistIgnoresIp() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", null);
        stubRow(created, secretHashOf(created), null);

        assertThat(service.verify(verifyReq(created, "8.8.8.8")).valid()).isTrue();
    }

    @Test
    @DisplayName("校验：精确 IP 条目精确匹配")
    void verifyExactIpMatches() {
        OpenApiKeyDtos.CreateResp created = createKey("iot:series:get", "192.168.1.10");
        stubRow(created, secretHashOf(created), "192.168.1.10");

        assertThat(service.verify(verifyReq(created, "192.168.1.10")).valid()).isTrue();
        assertThat(service.verify(verifyReq(created, "192.168.1.11")).valid()).isFalse();
    }
}
