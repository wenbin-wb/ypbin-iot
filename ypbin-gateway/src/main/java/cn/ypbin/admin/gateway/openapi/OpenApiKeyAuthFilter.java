package cn.ypbin.admin.gateway.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 开放 API Key 认证过滤器（看板 #11 第 1 批网关侧）。
 *
 * <p>只匹配 /iot/open-api/v1/**（设计 §3.4 红线：绝不写成「凡带 Key 即放行」，
 * 否则会给 /internal/** 开旁路）。流程：解析 X-Api-Key: accessKeyId:secret →
 * 调 iot 内部校验端点（单次调用携带全部映射数据）→ 成功则转签既有内部身份头
 * （X-User-Id 虚拟主体 / X-Tenant-Id / X-Roles=scopes / X-Gateway-Signed）→ 继续转发；
 * 失败统一 401（信息与签名错误不可区分，防枚举）。</p>
 */
@Component
public class OpenApiKeyAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OpenApiKeyAuthFilter.class);
    private static final String OPEN_API_PREFIX = "/iot/open-api/v1/";

    /** 签名相关参数名（与客户端/starter 口径一致）。 */
    private static final String PARAM_TIMESTAMP = "timestamp";
    private static final String PARAM_NONCE = "nonce";
    private static final String PARAM_SIGN = "sign";

    /** 限流过滤器读取用的 exchange attribute（OpenApiKeyRateLimitGlobalFilter 依赖）。 */
    public static final String ATTR_ACCESS_KEY = "openapi.accessKeyId";
    public static final String ATTR_RATE_QPS = "openapi.rateLimitQps";
    public static final String ATTR_DAILY_QUOTA = "openapi.dailyQuota";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment environment;

    public OpenApiKeyAuthFilter(Environment environment) {
        this.environment = environment;
        this.webClient = WebClient.builder().build();
    }

    private String verifyUrl() {
        return environment.getProperty("ypbin.openapi.verify-url",
            "http://ypbin-iot:18084/internal/open-api-key/verify");
    }

    private String internalToken() {
        return environment.getProperty("ypbin.openapi.internal-token", "");
    }

    private String gatewaySignToken() {
        return environment.getProperty("ypbin.openapi.gateway-sign-token", "");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(OPEN_API_PREFIX)) {
            return chain.filter(exchange);
        }
        ParsedKey parsed = parseApiKey(exchange.getRequest().getHeaders().getFirst("X-Api-Key"));
        if (parsed == null) {
            return reject(exchange, "api key missing or invalid: send X-Api-Key: accessKeyId:secret");
        }
        log.info("[gateway] openapi auth: path={} ak={} verifyTokens=[{}/{}]", path, parsed.accessKeyId(),
            internalToken().length(), gatewaySignToken().length());
        return verify(exchange, parsed.accessKeyId(), parsed.secret())
            .flatMap(data -> data.valid()
                ? chain.filter(forge(exchange, data, parsed.accessKeyId()))
                : reject(exchange, "api key invalid or revoked"));
    }

    /** 解析 X-Api-Key（格式 accessKeyId:secret）。非法返回 null。 */
    static ParsedKey parseApiKey(String header) {
        if (header == null || !header.contains(":")) {
            return null;
        }
        int sep = header.indexOf(":");
        String accessKeyId = header.substring(0, sep).trim();
        String secret = header.substring(sep + 1).trim();
        if (accessKeyId.isEmpty() || secret.isEmpty()) {
            return null;
        }
        return new ParsedKey(accessKeyId, secret);
    }

    private Mono<VerifyData> verify(ServerWebExchange exchange, String accessKeyId, String secret) {
        // 签名参数由网关**原样透传**给 iot 内部端点，由 iot 侧用 pepper/hash 完成验签
        // （方案 c）：密钥不出 iot，且不在网关碰流式 body。
        Map<String, Object> payload = new HashMap<>();
        payload.put("accessKeyId", accessKeyId);
        payload.put("secret", secret);
        String timestamp = queryParam(exchange, PARAM_TIMESTAMP);
        String nonce = queryParam(exchange, PARAM_NONCE);
        String sign = queryParam(exchange, PARAM_SIGN);
        if (timestamp != null) {
            payload.put(PARAM_TIMESTAMP, timestamp);
        }
        if (nonce != null) {
            payload.put(PARAM_NONCE, nonce);
        }
        if (sign != null) {
            payload.put(PARAM_SIGN, sign);
        }
        Map<String, String> signParams = collectSignParams(exchange);
        if (!signParams.isEmpty()) {
            payload.put("signParams", signParams);
        }
        return webClient.post()
            .uri(verifyUrl())
            .header("X-Internal-Token", internalToken())
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(3))
            .map(body -> {
                try {
                    return objectMapper.readTree(body);
                } catch (Exception ex) {
                    throw new IllegalStateException("openapi verify response parse failed", ex);
                }
            })
            .map(root -> {
                JsonNode data = root.path("data");
                boolean valid = Boolean.TRUE.equals(data.path("valid").asBoolean(false))
                    && data.path("virtualUserId").asLong(0L) != 0L
                    && data.path("tenantId").asLong(0L) != 0L;
                List<String> scopes = new ArrayList<>();
                for (JsonNode scope : data.path("scopes")) {
                    scopes.add(scope.asText());
                }
                return new VerifyData(valid, data.path("virtualUserId").asLong(0L),
                    data.path("tenantId").asLong(0L), scopes,
                    data.path("rateLimitQps").asInt(10), data.path("dailyQuota").asInt(100000));
            })
            .doOnError(ex -> log.error("[gateway] openapi verify failed: {}", ex.getMessage()))
            .onErrorReturn(VerifyData.invalid());
    }

    /**
     * 取 query 参数（签名四件套按约定走 query；不读 body —— 网关 body 流式不可重复读）。
     *
     * @return 非空白值；缺失返回 {@code null}
     */
    private static String queryParam(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getQueryParams().getFirst(name);
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * 收集参与签名的参数（query 全量，排除签名四件套本身）。
     *
     * <p><b>刻意只收 query、不收 body</b>：网关是 WebFlux，请求体是
     * {@code Flux<DataBuffer>}、默认只能消费一次；若在此读取 body 参与验签，
     * 转发给下游时 body 已耗尽 ⇒ 业务侧收到空请求体。故首期签名覆盖范围为
     * query 参数，body 完整性由 TLS 与幂等键保障（见方案说明的取舍）。</p>
     *
     * <p>排序 + 单一取值口径与 starter 的 {@code SignGenerator} 一致：多值参数取**第一个**
     * （避免两侧取值规则不同导致时对时错）。</p>
     *
     * @return 参与签名的参数（**绝不返回 {@code null}**）
     */
    private static Map<String, String> collectSignParams(ServerWebExchange exchange) {
        Map<String, String> out = new TreeMap<>();
        exchange.getRequest().getQueryParams().forEach((name, values) -> {
            if (PARAM_SIGN.equals(name) || PARAM_TIMESTAMP.equals(name) || PARAM_NONCE.equals(name)) {
                return;
            }
            if (values == null || values.isEmpty()) {
                return;
            }
            String first = values.getFirst();
            if (first != null && !first.isEmpty()) {
                out.put(name, first);
            }
        });
        return out;
    }

    private ServerWebExchange forge(ServerWebExchange exchange, VerifyData data, String accessKeyId) {
        String roles = String.join(",", data.scopes());
        exchange.getAttributes().put(ATTR_ACCESS_KEY, accessKeyId);
        exchange.getAttributes().put(ATTR_RATE_QPS, data.rateLimitQps());
        exchange.getAttributes().put(ATTR_DAILY_QUOTA, data.dailyQuota());
        log.info("[gateway] openapi forge: userId={} tenantId={} roles={} qps={} quota={} signLen={}",
            data.virtualUserId(), data.tenantId(), roles, data.rateLimitQps(), data.dailyQuota(),
            gatewaySignToken().length());
        return exchange.mutate()
            .request(builder -> builder
                .header("X-User-Id", String.valueOf(data.virtualUserId()))
                .header("X-Tenant-Id", String.valueOf(data.tenantId()))
                .header("X-Roles", roles)
                .header("X-Gateway-Signed", gatewaySignToken()))
            .build();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                Map.of("code", 401, "message", message, "success", false));
        } catch (Exception ex) {
            String fallback = "{\"code\":401,\"message\":\"unauthorized\",\"success\":false}";
            body = fallback.getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    record ParsedKey(String accessKeyId, String secret) {
    }

    record VerifyData(boolean valid, long virtualUserId, long tenantId, List<String> scopes,
                       int rateLimitQps, int dailyQuota) {
        static VerifyData invalid() {
            return new VerifyData(false, 0L, 0L, List.of(), 0, 0);
        }
    }
}
