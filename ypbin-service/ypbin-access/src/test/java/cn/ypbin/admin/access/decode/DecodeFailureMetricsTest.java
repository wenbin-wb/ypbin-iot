/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.access.config.AccessMetricsConfiguration;
import cn.ypbin.admin.access.link.PointMappingDataListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 解码失败指标的**零值可观测性**与装配门禁。
 *
 * <p>为什么必须有这组用例：Micrometer 计数器是惰性创建的，把「建 meter」留在失败路径里，
 * 生产上「零失败」与「指标缺失」都查不到（都回 404），值班无法区分——而 instrumentation 写错
 * <b>不会报错、只会静默少一行数据</b>。所以两件事都要钉住：
 * ① {@link DecodeFailureMetrics#registerAll} 真的把<b>每个</b>原因码都建成 0 值计数器；
 * ② {@link AccessMetricsConfiguration} 真的把这个动作挂进了启动流程（只测①会漏掉「忘了装」——
 * 那种情况下单测绿、生产仍 404，属教训八「0 违规可能是没跑到」的形态）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class DecodeFailureMetricsTest {

    @Test
    @DisplayName("预注册：每个 DecodeFailure 原因码都必须有一个值为 0 的计数器（含 reason 标签）")
    void registerAllMustPreRegisterEveryReasonWithZeroCount() {
        MeterRegistry registry = new SimpleMeterRegistry();

        DecodeFailureMetrics.registerAll(registry);

        List<DecodeFailure> reasons = Arrays.asList(DecodeFailure.values());
        assertThat(reasons)
            .as("原因码集合为空 ⇒ 本用例失去意义（枚举被清空时不得静默通过）")
            .isNotEmpty();
        for (DecodeFailure failure : reasons) {
            Double count = registry.get(DecodeFailureMetrics.METRIC_NAME)
                .tag(DecodeFailureMetrics.TAG_REASON, failure.getCode())
                .counter()
                .count();
            assertThat(count)
                .as("原因码 %s 的计数器未预注册或值不为 0 ⇒ 该原因零发生时生产查不到"
                    + "（/actuator/metrics 回 404 而不是 0）", failure.getCode())
                .isZero();
        }
        assertThat(registry.find(DecodeFailureMetrics.METRIC_NAME).counters())
            .as("预注册的计数器个数必须等于原因码个数（少一个就是一种失败原因不可观测）")
            .hasSize(reasons.size());
    }

    @Test
    @DisplayName("预注册：重复调用不产生重复计数器（启动期幂等，且能与失败路径共存）")
    void registerAllMustBeIdempotent() {
        MeterRegistry registry = new SimpleMeterRegistry();

        DecodeFailureMetrics.registerAll(registry);
        DecodeFailureMetrics.registerAll(registry);
        DecodeFailureMetrics.counter(registry, DecodeFailure.EMPTY_PAYLOAD).increment();

        assertThat(registry.find(DecodeFailureMetrics.METRIC_NAME).counters())
            .hasSize(DecodeFailure.values().length);
        assertThat(registry.get(DecodeFailureMetrics.METRIC_NAME)
            .tag(DecodeFailureMetrics.TAG_REASON, DecodeFailure.EMPTY_PAYLOAD.getCode())
            .counter()
            .count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("装配：AccessMetricsConfiguration 产出的启动 runner 必须真的预注册（否则生产仍 404）")
    void configurationMustActuallyPreRegister() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(AccessMetricsConfiguration.class);
            context.refresh();

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(context.getBeansOfType(ApplicationRunner.class))
                .as("AccessMetricsConfiguration 没有产出 ApplicationRunner ⇒ 预注册不会执行"
                    + "（单元用例①仍会绿，生产仍 404：典型的假绿）")
                .isNotEmpty();
            assertThat(registry.find(DecodeFailureMetrics.METRIC_NAME).counters())
                .as("runner 还没跑就已有计数器 ⇒ 断言无法证明 runner 起了作用（教训二十七：先钉住起点）")
                .isEmpty();

            context.getBean(ApplicationRunner.class).run(null);

            for (DecodeFailure failure : DecodeFailure.values()) {
                assertThat(registry.find(DecodeFailureMetrics.METRIC_NAME)
                    .tag(DecodeFailureMetrics.TAG_REASON, failure.getCode())
                    .counter())
                    .as("runner 执行后仍缺原因码 %s 的计数器 ⇒ 预注册没真正生效", failure.getCode())
                    .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("契约：指标名与监听器常量必须一致（改名会让看板/告警静默失联）")
    void metricNameMustMatchListenerConstant() {
        assertThat(PointMappingDataListener.METRIC_DECODE_FAILURE)
            .isEqualTo(DecodeFailureMetrics.METRIC_NAME);
    }
}
