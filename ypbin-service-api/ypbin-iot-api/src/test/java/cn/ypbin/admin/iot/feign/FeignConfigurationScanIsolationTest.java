/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.feign;

import static org.assertj.core.api.Assertions.assertThat;

import feign.Request;
import feign.Retryer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 验收测试（issue #298）：三份 Feign 配置类**不得**再向主上下文贡献全局
 * {@code Request.Options} / {@code Retryer}。
 *
 * <p><b>为什么这条测试必须存在</b>：这三份类本是「单个 {@code @FeignClient} 的专属配置」，一旦带上
 * {@code @Configuration} 且位于宿主 {@code scanBasePackages} 覆盖的包内，就会被当普通配置类装进
 * <b>主上下文</b>；而 Feign 的 {@code Request.Options}/{@code Retryer} 在主上下文里是**全局默认值**，
 * 于是它们的超时会外溢给**所有** Feign 客户端，并静默覆盖 Nacos
 * {@code spring.cloud.openfeign.client.config.*} 的配置——实测（修复前）主上下文取到
 * {@code [readingRequestOptions, deviceSpecRequestOptions, leaseRequestOptions]}，超时 1000/3000。
 * 这类「配了但不生效」没有任何编译期或运行期告警，只能靠断言主上下文为空来守住。</p>
 *
 * <p><b>测试手法</b>：用 {@link AnnotationConfigApplicationContext} 对三个 config 包做
 * {@code scan(...)}——这正是 Spring 组件扫描的入口（宿主 {@code @SpringBootApplication} 用的也是它）。
 * 不起完整应用上下文：本模块的整套上下文依赖 Nacos/DB/远端 iot，单测不该也不需要起它；
 * 而本缺陷的**判定点就是「扫描这几个包后主上下文里有没有这两个类型的 Bean」**，被扫的包与判据都
 * 与实际装配同源，故这是强证据而非近似。</p>
 *
 * <p>测试同时断言 {@link FeignClientProperties} 支撑「超时改由配置中心提供」这一新口径：修复后
 * 超时不再是 Java Bean，而是 {@code spring.cloud.openfeign.client.config.<contextId>.*}。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class FeignConfigurationScanIsolationTest {

    /** 三份配置类所在的包（= 宿主组件扫描会覆盖到的包）。 */
    private static final String[] FEIGN_CONFIG_PACKAGES = {
        "cn.ypbin.admin.iot.availability.config",
        "cn.ypbin.admin.iot.device.config",
        "cn.ypbin.admin.iot.lease.config"
    };

    @Test
    @DisplayName("★ 组件扫描这三个 config 包后，主上下文**不得**出现全局 Request.Options")
    void scanMustNotContributeGlobalRequestOptions() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(FEIGN_CONFIG_PACKAGES);
            context.refresh();

            String[] beanNames = context.getBeanNamesForType(Request.Options.class);
            assertThat(beanNames)
                .as("主上下文出现 Request.Options %s ⇒ 它会被所有 Feign 客户端继承，静默覆盖 Nacos 的 "
                    + "spring.cloud.openfeign.client.config.* 超时。请确认这三份配置类没有 @Configuration，"
                    + "且没有在会被组件扫描的包里声明 @Bean（issue #298）",
                    java.util.Arrays.toString(beanNames))
                .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 组件扫描后主上下文**不得**出现全局 Retryer（OpenFeign 默认已 NEVER_RETRY）")
    void scanMustNotContributeGlobalRetryer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(FEIGN_CONFIG_PACKAGES);
            context.refresh();

            String[] beanNames = context.getBeanNamesForType(Retryer.class);
            assertThat(beanNames)
                .as("主上下文出现 Retryer %s：OpenFeign 的 FeignClientsConfiguration#feignRetryer 已提供 "
                    + "@ConditionalOnMissingBean 的 NEVER_RETRY 默认值，宿主再声明一份纯属污染主上下文"
                    + "（issue #298）", java.util.Arrays.toString(beanNames))
                .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 修复后三份配置类不再声明任何 @Bean（只剩超时常量），故扫描不会产出 Bean")
    void scannedPackagesMustNotDeclareAnyBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(FEIGN_CONFIG_PACKAGES);
            context.refresh();

            // 三个包下目前只有这三份「配置锚点」类 + 无其它组件：若有人以后往这些包里加 @Component/@Bean，
            // 这条断言会先红，提醒「这个包会被扫进主上下文」这个前提仍然成立。
            // 只排除 Spring 自身注册的内置 Bean（context.annotation.* / event.*）——它们与本缺陷无关。
            List<String> businessBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> !name.startsWith("org.springframework.context."))
                .toList();
            assertThat(businessBeans)
                .as("这三个 config 包被组件扫描时不应产出任何业务 Bean（它们只承载 client 子上下文配置与常量）：%s",
                    businessBeans)
                .isEmpty();
            assertThat(context.getBeansOfType(FeignClientProperties.class))
                .as("不经 @EnableFeignClients 时不应有 FeignClientProperties（证明本测试没有暗中起 Feign 装配）")
                .isEmpty();
        }
    }
}
