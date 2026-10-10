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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * access 服务**调度线程池**的配置门禁（R8-2 方案 E）。
 *
 * <p>为什么需要门禁：access 有且只有两个 {@code @Scheduled} —— 租约 tick（10s，内含最多
 * {@code ypbin.access.reconcile-budget-ms} 的串行远端调用）与 egress 读数微批上报（1s）。
 * Spring 默认调度池是 **1 个线程** ⇒ 一次长 tick 会把 1s 的上报整段排在其后（读数入队到出站被拉长）。
 * 拆成 ≥2 后二者不再互相排队；两个任务不共享可变状态（上报队列是 {@code ArrayBlockingQueue}，
 * 其生产者本就是协议线程；tick 只动链路/订阅状态），故拆分是安全的。</p>
 *
 * <p>门禁的作用是防止这条配置被「顺手删掉/改小」而没人知道代价；同时自检「理由仍成立」
 * （access 仍有两个 {@code @Scheduled}）。</p>
 *
 * @author wenbin
 * @since 2026-10-09
 */
class AccessSchedulingConfigTest {

    /** 从模块目录（{@code ypbin-service/ypbin-access}）回到仓库根。 */
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path ACCESS_YAML = REPO_ROOT.resolve("deploy/nacos/ypbin-access.yaml");

    private static final Path ACCESS_MAIN = REPO_ROOT.resolve("ypbin-service/ypbin-access/src/main/java");

    @Test
    @DisplayName("★ R8-2 方案 E：调度线程池必须 ≥2（否则 1s 的 egress 上报会被 10s 的长 tick 排队）")
    void schedulingPoolMustHaveAtLeastTwoThreads() throws IOException {
        Map<String, Object> root = loadYaml(ACCESS_YAML);
        Integer size = poolSize(root);

        assertThat(size)
            .as("deploy/nacos/ypbin-access.yaml 缺少 spring.task.scheduling.pool.size ⇒ 默认只有 1 个调度线程，"
                + "10s 租约 tick（内含串行远端对账）会把 1s 的读数上报整段排在后面")
            .isNotNull();
        assertThat(size)
            .as("调度线程池 = %s：必须 ≥2 才能让 egress 上报与租约 tick 不互相排队（R8-2 方案 E）", size)
            .isGreaterThanOrEqualTo(2);

        // 自检：这条配置的理由必须仍然成立——access 至少还有两个 @Scheduled（否则需重新评估该配置与注释）
        long scheduled = countScheduledMethods();
        assertThat(scheduled)
            .as("access 的 @Scheduled 只有 %s 个 ⇒ 拆池的理由已变，请重新评估本配置与注释", scheduled)
            .isGreaterThanOrEqualTo(2L);
    }

    /** 读取 YAML（UTF-8）。 */
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        assertThat(Files.exists(path)).as("配置文件不存在：%s", path).isTrue();
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        }
    }

    /** 取 {@code spring.task.scheduling.pool.size}；任一层缺失返回 {@code null}。 */
    private static Integer poolSize(Map<String, Object> root) {
        Object spring = root.get("spring");
        Object task = child(spring, "task");
        Object scheduling = child(task, "scheduling");
        Object pool = child(scheduling, "pool");
        Object size = child(pool, "size");
        return size instanceof Number number ? number.intValue() : null;
    }

    private static Object child(Object node, String key) {
        return node instanceof Map<?, ?> map ? map.get(key) : null;
    }

    /** 统计 access 主源码里的 {@code @Scheduled} 数量（自检用）。 */
    private static long countScheduledMethods() throws IOException {
        try (Stream<Path> files = Files.walk(ACCESS_MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        throw new IllegalStateException("读源码失败：" + path, ex);
                    }
                })
                .mapToLong(code -> code.split("@Scheduled", -1).length - 1)
                .sum();
        }
    }
}
