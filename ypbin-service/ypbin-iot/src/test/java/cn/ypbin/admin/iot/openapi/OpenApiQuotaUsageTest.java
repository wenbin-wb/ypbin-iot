package cn.ypbin.admin.iot.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotOpenApiKey;
import cn.ypbin.admin.iot.mapper.IotOpenApiKeyMapper;
import cn.ypbin.starter.sign.core.NonceStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 配额用量查询用例：`usedToday` 必须真实反映网关计数器的值。
 *
 * <p>关键约束：用量读的是**网关限流器写入的同一个 key**
 * （`OpenApiKeyConstants#quotaKey`）。若两边前缀漂移，用量恒 0 而限流另起一套 ——
 * 静默错。本文件末尾有 yaml 一致性门禁钉住它。</p>
 */
class OpenApiQuotaUsageTest {

    private static final String TEST_PEPPER = "test-pepper-not-a-real-secret";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final IotOpenApiKeyMapper mapper = mock(IotOpenApiKeyMapper.class);

    private final NonceStore nonceStore = (key, expire) -> true;

    private OpenApiKeyService service() {
        return new OpenApiKeyService(mapper, TEST_PEPPER, nonceStore, false, redisProvider);
    }

    private void stubRedis(String value) {
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(value);
    }

    @Test
    @DisplayName("用量 = 当日配额计数键的值")
    void usageEqualsQuotaCounter() {
        stubRedis("42");

        assertThat(service().readQuotaUsage("ak_x")).isEqualTo(42L);
    }

    @Test
    @DisplayName("计数键不存在 = 今日尚无调用（0，不是未知）")
    void missingKeyMeansZero() {
        stubRedis(null);

        assertThat(service().readQuotaUsage("ak_x")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Redis 不可用 = 未知（null），且不抛异常")
    void redisDownMeansUnknown() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertThat(service().readQuotaUsage("ak_x")).isNull();
    }

    @Test
    @DisplayName("Redis 抛异常 = 未知（null），不影响鉴权主链路")
    void redisErrorMeansUnknown() {
        stubRedis(null);
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));

        assertThat(service().readQuotaUsage("ak_x")).isNull();
    }

    @Test
    @DisplayName("脏数据（非数字）= 未知，不崩")
    void corruptValueMeansUnknown() {
        stubRedis("not-a-number");

        assertThat(service().readQuotaUsage("ak_x")).isNull();
    }

    @Test
    @DisplayName("未提供 Redis（简化构造）= 未知")
    void noProviderMeansUnknown() {
        OpenApiKeyService svc = new OpenApiKeyService(mapper, TEST_PEPPER, nonceStore, false, null);

        assertThat(svc.readQuotaUsage("ak_x")).isNull();
    }

    @Test
    @DisplayName("quotaKey 口径：前缀 + ak + 当日（与网关写入一致）")
    void quotaKeyFormat() {
        assertThat(OpenApiKeyConstants.quotaKey("ak_x", LocalDate.of(2026, 10, 4)))
            .isEqualTo("ypbin:openapi:quota:ak_x:2026-10-04");
    }

    @Test
    @DisplayName("quotaResetAt = 次日零点")
    void quotaResetAtIsNextMidnight() {
        assertThat(OpenApiKeyService.quotaResetAt())
            .isEqualTo(LocalDate.now().plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("whoami 回传用量与重置时刻")
    void whoamiReturnsUsage() {
        stubRedis("7");
        when(mapper.insert(any(IotOpenApiKey.class))).thenReturn(1);

        OpenApiKeyDtos.CreateReq req = new OpenApiKeyDtos.CreateReq();
        req.setAppName("a");
        req.setScopes("iot:series:get");
        OpenApiKeyDtos.CreateResp created = service().create(req);

        org.mockito.ArgumentCaptor<IotOpenApiKey> cap =
            org.mockito.ArgumentCaptor.forClass(IotOpenApiKey.class);
        org.mockito.Mockito.verify(mapper).insert(cap.capture());

        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(5L);
        row.setTenantId(1L);
        row.setAccessKeyId(created.accessKeyId());
        row.setSecretHash(cap.getValue().getSecretHash());
        row.setScopes("iot:series:get");
        row.setStatus(1);
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.selectById(5L)).thenReturn(row);

        cn.ypbin.starter.security.core.LoginUser user = new cn.ypbin.starter.security.core.LoginUser();
        user.setId(OpenApiKeyConstants.virtualUserId(5L));
        user.setTenantId(1L);
        cn.ypbin.starter.security.identity.IdentityContext.setLoginUser(user);
        try {
            OpenApiKeyDtos.WhoamiResp resp = service().whoami();
            assertThat(resp.usedToday()).isEqualTo(7L);
            assertThat(resp.quotaResetAt()).isEqualTo(OpenApiKeyService.quotaResetAt());
        } finally {
            cn.ypbin.starter.security.identity.IdentityContext.clear();
        }
    }

    @Test
    @DisplayName("listAll 每行带当日用量")
    void listAllReturnsUsage() {
        stubRedis("3");

        IotOpenApiKey row = new IotOpenApiKey();
        row.setId(5L);
        row.setAppName("a");
        row.setAccessKeyId("ak_x");
        row.setSecretPrefix("sk_xxx");
        row.setScopes("iot:series:get");
        row.setStatus(1);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<OpenApiKeyDtos.ListItemResp> items = service().listAll();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).usedToday()).isEqualTo(3L);
    }

    /** 仓库根（沿用 OpenApiAllowlistConsistencyGateTest 的定位口径）。 */
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path GATEWAY_YAML = REPO_ROOT.resolve("deploy/nacos/ypbin-gateway.yaml");

    @Test
    @DisplayName("网关 yaml 的 quota-key-prefix 必须与本常量一致（漂移即转红）")
    void gatewayYamlQuotaPrefixMustMatch() throws IOException {
        assertThat(Files.exists(GATEWAY_YAML))
            .as("读不到 %s => 本门禁空跑，不能算通过", GATEWAY_YAML)
            .isTrue();
        String yaml = Files.readString(GATEWAY_YAML, StandardCharsets.UTF_8);

        assertThat(yaml)
            .as("网关限流写入前缀与用量读取前缀不一致 => 用量恒 0 / 限流另起一套（静默错）")
            .contains("quota-key-prefix: \"" + OpenApiKeyConstants.QUOTA_KEY_PREFIX + "\"");
    }

    @Test
    @DisplayName("网关 yaml 必须启用限流并限定开放路径（缺一即静默不限流）")
    void gatewayYamlRateLimitMustBeEnabled() throws IOException {
        assertThat(Files.exists(GATEWAY_YAML)).isTrue();
        String yaml = Files.readString(GATEWAY_YAML, StandardCharsets.UTF_8);

        assertThat(yaml).contains("enabled: true");
        assertThat(yaml).contains("/iot/open-api/v1/");
        assertThat(yaml).contains("key-attribute: openapi.accessKeyId");
    }

    @Test
    @DisplayName("Redis TTL 语义：用量键由网关写时带过期，用量读只读不写（不续期）")
    void usageReadDoesNotExtendTtl() {
        stubRedis("5");
        // readQuotaUsage 只做 GET（ValueOperations.get），从不 SET/EXPIRE：
        // 用量查询不得给计数键续命，否则"今日用量"会漏到明天。
        service().readQuotaUsage("ak_x");

        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never())
            .expire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }
}
