package cn.ypbin.admin.gateway.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
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
public class OpenApiKeyAuthFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OpenApiKeyAuthFilter.class);
    private static final String OPEN_API_PREFIX = "/iot/open-api/v1/";

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
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(OPEN_API_PREFIX)) {
            return chain.filter(exchange);
        }
        ParsedKey parsed = parseApiKey(exchange.getRequest().getHeaders().getFirst("X-Api-Key"));
        if (parsed == null) {
            return reject(exchange, "api key missing or invalid: send X-Api-Key: accessKeyId:secret");
        }
        return verify(parsed.accessKeyId(), parsed.secret())
            .flatMap(data -> data.valid()
                ? chain.filter(forge(exchange, data))
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

    private Mono<VerifyData> verify(String accessKeyId, String secret) {
        return webClient.post()
            .uri(verifyUrl())
            .header("X-Internal-Token", internalToken())
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(Map.of("accessKeyId", accessKeyId, "secret", secret))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(3))
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
                    data.path("tenantId").asLong(0L), scopes);
            })
            .doOnError(ex -> log.error("[gateway] openapi verify failed: {}", ex.getMessage()))
            .onErrorReturn(VerifyData.invalid());
    }

    private ServerWebExchange forge(ServerWebExchange exchange, VerifyData data) {
        String roles = String.join(",", data.scopes());
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

    record VerifyData(boolean valid, long virtualUserId, long tenantId, List<String> scopes) {
        static VerifyData invalid() {
            return new VerifyData(false, 0L, 0L, List.of());
        }
    }
}
