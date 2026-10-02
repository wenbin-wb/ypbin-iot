package cn.ypbin.admin.iot.openapi;

import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.sign.core.NonceStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis 的 nonce 防重放存储（多实例安全）。
 *
 * <p><b>为什么不用 starter 的 {@code InMemoryNonceStore}</b>：它是进程内
 * {@code ConcurrentHashMap}，多实例部署下"A 实例用过、B 实例不知道"⇒ 重放只会在
 * <b>同一实例内</b>被拦，跨实例可无限重放。开放 API Key 是外部可持久的凭据，
 * 必须集群级去重。</p>
 *
 * <p><b>原子性</b>：用 {@code SET key value NX EX ttl} 单命令完成"不存在才写入 + 设过期"，
 * 不做"先 GET 再 SET"（那是竞态：两个并发请求可能都通过）。</p>
 *
 * <p><b>fail-closed</b>：Redis 异常时<b>返回 false（判为重放/拒绝）</b>，而不是放行。
 * 与限流器的 fail-open 取舍<b>刻意相反</b>：限流失效只是放量，防重放失效等于防重放完全失效。</p>
 *
 * @author wenbin
 * @since 2026-10-05
 */
public class RedisNonceStore implements NonceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisNonceStore.class);

    private final StringRedisTemplate redisTemplate;

    public RedisNonceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryUse(String key, Duration expire) {
        long ttlSeconds = Math.max(1L, expire.toSeconds());
        try {
            Boolean firstUse = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
            // setIfAbsent 返回 null 属异常语义（连接层面的不确定结果）⇒ 按拒绝处理，不静默放行
            return Boolean.TRUE.equals(firstUse);
        } catch (RuntimeException ex) {
            // key 含第三方提供的 nonce ⇒ 必须 sanitize，防换行/控制字符跨行伪造日志
            // （CodeQL: "Log Injection"）
            log.error("[iot] nonce 防重放存储不可用，按拒绝处理（fail-closed，不放行）：key={}",
                LogSanitizer.sanitize(key), ex);
            return false;
        }
    }
}
