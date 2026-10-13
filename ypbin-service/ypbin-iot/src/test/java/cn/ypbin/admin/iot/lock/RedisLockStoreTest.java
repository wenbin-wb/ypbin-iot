/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 锁存储的适配层（#220）：Lua 返回值到布尔语义的映射、参数形态。
 *
 * <p>不连真实 Redis：脚本本身的原子性由 Redis 保证，这里要钉住的是「null/非 1 一律按未成功」，
 * 以及传给 Lua 的 value 形态（{@code token@expireAt}）与参数顺序——脚本依赖它们，
 * 写错不会编译失败，只会在生产上静默抢不到或释放不掉。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
class RedisLockStoreTest {

    private static final String KEY = "ypbin:iot:lock:test";

    private StringRedisTemplate redisTemplate;

    private RedisLockStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        store = new RedisLockStore(redisTemplate);
    }

    @Test
    @DisplayName("脚本返回 1 ⇒ 抢到；返回 0/null ⇒ 未抢到（不确定结果按未持有），不冒充持有者")
    void mapsScriptResultToAcquireSemantics() {
        assertThat(store.tryLock(KEY, "token", 2_000L, 1_000L, 1_000L)).isTrue();

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(store.tryLock(KEY, "token", 2_000L, 1_000L, 1_000L)).isFalse();

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertThat(store.tryLock(KEY, "token", 2_000L, 1_000L, 1_000L)).isFalse();
    }

    @Test
    @DisplayName("续期与释放同样按 1 判定")
    void mapsScriptResultToRenewAndUnlock() {
        assertThat(store.renew(KEY, "token", 3_000L, 1_000L)).isTrue();
        assertThat(store.unlock(KEY, "token")).isTrue();

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(store.renew(KEY, "token", 3_000L, 1_000L)).isFalse();
        assertThat(store.unlock(KEY, "token")).isFalse();
    }

    @Test
    @DisplayName("抢锁传给 Lua 的参数：value=token@expireAt、ttl、now，键带前缀")
    void acquirePassesExpectedArguments() {
        store.tryLock(KEY, "node-1:abc", 9_000L, 4_000L, 5_000L);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(KEY);
        assertThat((String) argsCaptor.getValue()[0]).isEqualTo("node-1:abc@9000");
        assertThat(argsCaptor.getValue()[1]).isEqualTo("5000");
        assertThat(argsCaptor.getValue()[2]).isEqualTo("4000");
    }

    @Test
    @DisplayName("续期传给 Lua 的参数：token、newExpireAt、ttl")
    void renewPassesExpectedArguments() {
        store.renew(KEY, "node-1:abc", 9_000L, 5_000L);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());

        assertThat(argsCaptor.getValue()).containsExactly("node-1:abc", "9000", "5000");
    }
}
