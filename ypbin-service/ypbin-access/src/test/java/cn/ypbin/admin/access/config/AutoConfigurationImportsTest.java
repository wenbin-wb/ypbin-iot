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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自动配置登记表门禁：{@code META-INF/spring/...AutoConfiguration.imports} 里登记的每个类
 * <b>都必须真实存在</b>。
 *
 * <p><b>为什么需要它</b>（2026-10-20 dev 真实启动失败）：本模块的登记表里残留了
 * {@code cn.ypbin.admin.access.config.AccessDecodeConfiguration}，而该类早已在重构中删除
 * （git 里已无此文件）。Spring Boot 启动期读取登记表时会尝试加载每个名字 ⇒
 * {@code Unable to read meta-data for class ...} ⇒ <b>服务起不来</b>。</p>
 *
 * <p><b>为什么会漏</b>：删类时 IDE 的「安全删除」只看 Java 引用，不会检查
 * {@code META-INF/spring/*.imports} 这种纯文本登记；而编译期也不会校验——只有真正启动才炸。
 * 故钉成仓内测试：{@code mvn test} 即可发现，且新增/重命名自动配置类时同样受保护。</p>
 *
 * @author wenbin
 * @since 2026-10-20
 */
class AutoConfigurationImportsTest {

    private static final Path IMPORTS = Path.of("src", "main", "resources", "META-INF", "spring",
        "org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    @Test
    @DisplayName("★ 登记表里的每个自动配置类都必须存在于 classpath（防「删了类没删登记」启动失败）")
    void everyRegisteredAutoConfigurationMustExist() throws IOException {
        assertThat(IMPORTS)
            .as("自动配置登记表必须存在（本模块的装配入口依赖它）")
            .exists();

        List<String> registered = new ArrayList<>();
        for (String line : Files.readAllLines(IMPORTS, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            registered.add(trimmed);
        }
        assertThat(registered).as("登记表不应为空").isNotEmpty();

        Map<String, String> missing = new LinkedHashMap<>();
        ClassLoader loader = getClass().getClassLoader();
        for (String className : registered) {
            if (!exists(className, loader)) {
                missing.put(className, "classpath 上找不到该类");
            }
        }

        assertThat(missing)
            .as("登记表里的类必须真实存在：删类/改名时必须同步删掉登记行，否则应用启动期 "
                + "`Unable to read meta-data` 直接失败")
            .isEmpty();
    }

    /**
     * 判断类是否存在（不初始化、不触发静态块）。
     *
     * @param className 全限定类名
     * @param loader    类加载器
     * @return 是否存在对应字节码
     */
    private static boolean exists(String className, ClassLoader loader) {
        String resource = className.replace('.', '/') + ".class";
        URL url = loader.getResource(resource);
        return url != null;
    }
}
