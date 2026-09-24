/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.values;

import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未配置 Redis 时的最新值写入器：**只记一条 WARN + 丢弃**。
 *
 * <p>为什么允许这个实现存在：最新值是「便利数据」（时序库/可用率都不依赖它），而 IoT 服务可能在
 * 没有 Redis 的环境里单跑（例如只做断档判定）。但**必须暴露**——第一次写入时打一条 WARN 说明
 * 「最新值不会落库」，绝不静默；配好 Redis 后自动切换为 {@link RedisLatestValueWriter}。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class LoggingLatestValueWriter implements LatestValueWriter {

    private static final Logger log = LoggerFactory.getLogger(LoggingLatestValueWriter.class);

    private final AtomicBoolean warned = new AtomicBoolean();

    @Override
    public void writeAll(List<LatestValue> values) {
        if (values.isEmpty()) {
            return;
        }
        if (warned.compareAndSet(false, true)) {
            log.warn("[iot] 未配置 Redis（缺少 StringRedisTemplate）⇒ 最新值不会落库，本次丢弃 {} 条；"
                + "可用率/断档不受影响（二者只看质量与时刻）。配置 spring.data.redis.* 后自动启用写入。",
                LogSanitizer.sanitize(values.size()));
        }
    }
}
