/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import cn.ypbin.admin.access.egress.AccessReadingSink;
import cn.ypbin.admin.access.egress.HttpAccessReadingSink;
import cn.ypbin.admin.access.egress.LoggingAccessReadingSink;
import cn.ypbin.admin.iot.availability.IReadingClient;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 读数出口装配（M-2）：默认把读数**真有界队列 + 微批**上报给 iot，而不是只打日志。
 *
 * <p><b>这是 S6（「出口是日志占位，生产会逐条 INFO 且不落库」）的收口</b>：出口换成 HTTP 上报后，
 * 断档/可用率才拿得到输入。EMQX 传输依赖 Q4 决策，届时只需替换本实现——`AccessReadingSink`
 * 本就是设计里给的替换缝。</p>
 *
 * <p>为什么放独立自动配置（而不是塞进 {@code AccessIotProtocolConfiguration}）：装配条件不同——
 * 出口只需要「有 IReadingClient」，与是否装了协议栈取数无关。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@AutoConfiguration(before = AccessIotProtocolConfiguration.class)
@EnableConfigurationProperties(AccessEgressProperties.class)
public class AccessEgressConfiguration implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AccessEgressConfiguration.class);

    private final AccessEgressProperties properties;

    public AccessEgressConfiguration(AccessEgressProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> issues = new ArrayList<>();
        if (properties.getQueueCapacity() <= 0) {
            issues.add(AccessEgressProperties.PREFIX + ".queue-capacity 必须为正数");
        }
        if (properties.getBatchSize() <= 0) {
            issues.add(AccessEgressProperties.PREFIX + ".batch-size 必须为正数");
        }
        if (properties.getBatchSize() > ReadingIngestReq.MAX_BATCH_SIZE) {
            issues.add(AccessEgressProperties.PREFIX + ".batch-size(" + properties.getBatchSize()
                + ") 不能超过服务端单批上限 " + ReadingIngestReq.MAX_BATCH_SIZE + "（否则每批都被拒）");
        }
        if (properties.getFlushIntervalMs() <= 0) {
            issues.add(AccessEgressProperties.PREFIX + ".flush-interval-ms 必须为正数");
        }
        if (!issues.isEmpty()) {
            throw new IllegalStateException("[access] 读数出口参数自检未通过：" + String.join("；", issues));
        }
        log.info("[access] 读数出口参数自检通过：enabled={} queueCapacity={} batchSize={} flushIntervalMs={}",
            properties.isEnabled(), properties.getQueueCapacity(), properties.getBatchSize(),
            properties.getFlushIntervalMs());
    }

    /**
     * 读数出口：默认 HTTP 上报；没有内部客户端或显式关闭时退化为日志占位（并打 WARN）。
     *
     * @param readingClientProvider 内部上报客户端（缺失说明装配有问题）
     * @param properties            出口参数
     * @param meterRegistry         指标注册表
     * @return 读数出口
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessReadingSink accessReadingSink(ObjectProvider<IReadingClient> readingClientProvider,
                                               AccessEgressProperties properties,
                                               MeterRegistry meterRegistry) {
        IReadingClient client = readingClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("[access] 没有可用的读数上报客户端（IReadingClient）⇒ 退化为日志占位："
                + "断档/可用率将收不到任何数据（iot.access.egress.* 指标也不会增长）");
            return new LoggingAccessReadingSink();
        }
        if (!properties.isEnabled()) {
            log.warn("[access] {}.enabled=false ⇒ 读数只打日志不上报：断档/可用率将收不到任何数据",
                AccessEgressProperties.PREFIX);
            return new LoggingAccessReadingSink();
        }
        return new HttpAccessReadingSink(client, properties, meterRegistry);
    }
}
