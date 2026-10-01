
package cn.ypbin.admin.gateway.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 开放 API 限流/配额过滤器（看板 #11 第 2 批）。
 *
 * <p>按 Key（accessKeyId）维度做固定窗口计数（QPS 每秒窗口 + 日配额），超限返回真 429。
 * 维度与算法见设计 §2.4：不引入令牌桶，Redis INCR + EXPIRE 即可；结果由纯函数判定（可单测）。</p>
 */
@Component
public class OpenApiRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OpenApiRateLimitGlobalFilter.class);
    private static final String OPEN_API_PREFIX = "/iot/open-api/v1/";

    private static final String QPS_KEY_PREFIX = "ypbin:openapi:qps:";
    private static final String QUOTA_KEY_PREFIX = "ypbin:openapi:quota:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment environment;

    public OpenApiRateLimitGlobalFilter(ReactiveStringRedisTemplate redis, Environment environment) {
        this.redis = redis;
        this.environment = environment;
    }

    private int windowSeconds() {
        return environment.getProperty("ypbin.openapi.limit-window-seconds", Integer.class, 1);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(OPEN_API_PREFIX)) {
            return chain.filter(exchange);
        }
        String accessKey = exchange.getAttribute(OpenApiKeyAuthFilter.ATTR_ACCESS_KEY);
        log.info("[gateway] rate-limit check: path={} ak={}", path, accessKey);
        if (accessKey == null || accessKey.isEmpty()) {
            return chain.filter(exchange);
        }
        int qpsLimit = attrInt(exchange, OpenApiKeyAuthFilter.ATTR_RATE_QPS, 10);
        int quotaLimit = attrInt(exchange, OpenApiKeyAuthFilter.ATTR_DAILY_QUOTA, 100000);
        long nowSec = System.currentTimeMillis() / 1000L;
        String qpsKey = QPS_KEY_PREFIX + accessKey + ":" + nowSec;
        String quotaKey = QUOTA_KEY_PREFIX + accessKey + ":" + LocalDate.now();
        long quotaTtlSec = ChronoUnit.SECONDS.between(LocalDateTime.now(),
            LocalDate.now().plusDays(1).atStartOfDay()) + 1;

        return incr(qpsKey, windowSeconds() + 1L).flatMap(qpsCount ->
            incr(quotaKey, quotaTtlSec).flatMap(quotaCount -> {
                RateLimitDecision qpsDecision = RateLimitDecision.forCount(qpsCount, qpsLimit);
                RateLimitDecision quotaDecision = RateLimitDecision.forCount(quotaCount, quotaLimit);
                log.info("[gateway] rate-limit counts: ak={} qps={}/{} quota={}/{}", accessKey,
                    qpsCount, qpsLimit, quotaCount, quotaLimit);
                if (!qpsDecision.allowed() || !quotaDecision.allowed()) {
                    log.warn("[gateway] openapi rate limited: ak={} qps={}/{} quota={}/{}", accessKey,
                        qpsCount, qpsLimit, quotaCount, quotaLimit);
                    return reject(exchange, "请求过于频繁或超过日配额，请稍后重试（QPS 上限 " + qpsLimit
                        + "/" + windowSeconds() + "s，日配额 " + quotaLimit + "）");
                }
                return chain.filter(exchange);
            }));
    }

    private Mono<Long> incr(String key, long ttlSeconds) {
        return redis.opsForValue().increment(key)
            .flatMap(count -> count == 1L
                ? redis.expire(key, Duration.ofSeconds(Math.max(1, ttlSeconds))).thenReturn(count)
                : Mono.just(count))
            .onErrorReturn(-1L);
    }

    private static int attrInt(ServerWebExchange exchange, String name, int def) {
        Object v = exchange.getAttribute(name);
        if (v instanceof Integer i) {
            return i;
        }
        return def;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                Map.of("code", 429, "message", message, "success", false));
        } catch (Exception ex) {
            body = "too many requests".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    /** 纯函数判定（可单测）：固定窗口计数是否放行。 */
    record RateLimitDecision(boolean allowed, long count, long limit) {

        static RateLimitDecision forCount(long count, long limit) {
            return limit <= 0 ? new RateLimitDecision(true, count, 0L)
                : new RateLimitDecision(count <= limit, count, limit);
        }
    }
}