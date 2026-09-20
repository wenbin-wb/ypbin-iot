/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IoT 接缝约定门禁：**可替换端口**的实现不得标 {@code @Component}，必须由 {@code @AutoConfiguration}
 * 里的 {@code @Bean @ConditionalOnMissingBean} 提供。
 *
 * <p>为什么需要它：{@code @ConditionalOnMissingBean} 的顺序保证只有自动配置才有。若实现类带
 * {@code @Component}，3b 提供的真实现会被扫描到的日志实现「顶掉」（back off）⇒ **协议栈静默不生效**；
 * 反之若 3b 也标 {@code @Component} 则直接 {@code NoUniqueBeanDefinitionException}。
 * 旧独立栈为此专门修过并把规则做成门禁，本仓是 fork，必须同样守住。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotSeamConventionTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    /** 已知的可替换端口（新增端口时同步扩展本清单，并保留「至少扫到一个」的自检）。 */
    private static final List<String> REPLACEABLE_PORTS = List.of("TenantLinkManager");

    @Test
    @DisplayName("可替换端口的实现不得标 @Component（否则 @ConditionalOnMissingBean 会被静默顶掉）")
    void replaceablePortImplementationsMustNotBeComponents() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> stream = Files.walk(REPO_ROOT)) {
            for (Path file : stream.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.toString().contains("/target/"))
                .toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                boolean implementsPort = lines.stream().anyMatch(line ->
                    REPLACEABLE_PORTS.stream().anyMatch(port -> line.contains("implements " + port)));
                if (!implementsPort) {
                    continue;
                }
                scanned++;
                // 行级判定：只有整行就是 @Component 才算注解（Javadoc 里的提及不算），避免正则开销
                if (lines.stream().anyMatch(line -> line.trim().equals("@Component"))) {
                    violations.add(REPO_ROOT.relativize(file)
                        + " → 端口实现标了 @Component：宿主替换会被静默顶掉（改为 @Bean @ConditionalOnMissingBean）");
                }
            }
        }
        assertThat(scanned).as("一个端口实现都没扫到 ⇒ 本规则是空跑（假绿）").isPositive();
        assertThat(violations).as("可替换端口的实现必须由自动配置装配").isEmpty();
    }

    @Test
    @DisplayName("自检：规则按行识别注解，Javadoc 里的 @Component 提及不算违规")
    void ruleMustDetectViolationSemantically() {
        assertThat(java.util.List.of("class A implements TenantLinkManager { }", "@Component")
            .stream().anyMatch(line -> line.trim().equals("@Component"))).isTrue();
        assertThat(java.util.List.of(" * 不得标 @Component（由 @ConditionalOnMissingBean 装配）",
            "class A implements TenantLinkManager {}")
            .stream().anyMatch(line -> line.trim().equals("@Component"))).isFalse();
    }
}
