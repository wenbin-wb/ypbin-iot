/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标清单**双向**门禁：`docs/METRICS.md` ↔ 源码注册点。
 *
 * <p>R8-9 的病根是「指标注册了，但没人知道该看什么」——修法是一份人工清单（口径/阈值/注册文件）。
 * 但纯文档会烂：代码改了名或删了指标，文档不会红。故这里加机器判据：</p>
 *
 * <ul>
 *   <li><b>清单 → 代码</b>：清单里每个 `iot.access.*` 名都能在它声明的注册文件里找到
 *       （接受「全名」或「作为后缀的片段」，因为仓内多数指标是 `METRIC_PREFIX + "suffix"` 拼出来的）；</li>
 *   <li><b>代码 → 清单</b>：源码里出现的**完整** `iot.access.*` 字面量必须都在清单里（新指标不许不登记）。</li>
 * </ul>
 *
 * <p>两侧都带自检（清单非空、注册文件必须存在且确实含该名字），避免退化成「0 违规 = 没跑到」。
 * 注意：本门禁只校验**名字与注册位置**，口径/阈值是否准确仍需人工评审。</p>
 *
 * @author wenbin
 * @since 2026-10-09
 */
class MetricsRegistryGateTest {

    /** 从模块目录（{@code ypbin-service/ypbin-access}）回到仓库根。 */
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path METRICS_DOC = REPO_ROOT.resolve("docs/METRICS.md");

    private static final Path ACCESS_MAIN = REPO_ROOT.resolve("ypbin-service/ypbin-access/src/main/java");

    /** 清单表格行：`| \`iot.access.xxx\` | … | <注册文件>.java |`。 */
    private static final Pattern DOC_ROW = Pattern.compile(
        "^\\|\\s*`(iot\\.access\\.[a-zA-Z0-9._-]+)`\\s*\\|.*\\|\\s*([A-Za-z0-9_]+\\.java)\\s*\\|\\s*$");

    /** 源码里的完整指标名（至少三段，且不以 `.` 结尾 ⇒ 排除 `iot.access.egress.` 这类前缀常量）。 */
    private static final Pattern FULL_METRIC_LITERAL = Pattern.compile(
        "\"(iot\\.access(?:\\.[a-z0-9_]+){2,})\"");

    /** 前缀常量声明：{@code static final String METRIC_PREFIX = "iot.access.xxx.";}。 */
    private static final Pattern PREFIX_CONSTANT = Pattern.compile(
        "String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"(iot\\.access\\.[a-z0-9._-]+\\.)\"");

    /** 前缀拼接：{@code METRIC_PREFIX + "reconcile.deferred"}。 */
    private static final Pattern PREFIX_CONCAT = Pattern.compile(
        "([A-Z][A-Z0-9_]*)\\s*\\+\\s*\"([a-z][a-z0-9._-]*)\"");

    @Test
    @DisplayName("★ 清单里的每个指标名都必须能在其注册文件里找到（防文档写了代码删了）")
    void everyListedMetricMustExistInItsSourceFile() throws IOException {
        Map<String, String> listed = listedMetrics();
        // 自检：清单被清空或解析器与文档形态脱节时，本门禁必须失败而不是"0 违规"
        assertThat(listed).as("清单里没解析到任何 iot.access.* 指标 ⇒ 本门禁空跑（检查 docs/METRICS.md 的表格形态）")
            .hasSizeGreaterThan(20);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : listed.entrySet()) {
            Path source = resolveSource(entry.getValue());
            if (source == null) {
                missing.add(entry.getKey() + " → 注册文件不存在：" + entry.getValue());
                continue;
            }
            if (!sourceDeclaresMetric(source, entry.getKey())) {
                missing.add(entry.getKey() + " → " + entry.getValue() + " 里找不到该名字（改名/删除后未同步清单？）");
            }
        }
        assertThat(missing).as("清单里的指标在源码里找不到（文档与代码漂移）：%s", missing).isEmpty();
    }

    @Test
    @DisplayName("★ 源码里的完整指标名必须都登记进清单（防新增指标不登记）")
    void everyRegisteredMetricMustBeListed() throws IOException {
        Map<String, String> listed = listedMetrics();
        assertThat(listed).as("清单里没解析到任何指标 ⇒ 本门禁空跑").isNotEmpty();

        Set<String> registered = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(ACCESS_MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file, StandardCharsets.UTF_8);
                // 风格一：完整字面量（meterRegistry.counter("iot.access.spec.empty")）
                Matcher full = FULL_METRIC_LITERAL.matcher(code);
                while (full.find()) {
                    registered.add(full.group(1));
                }
                // 风格二：前缀常量 + 后缀片段（METRIC_PREFIX + "reconcile.deferred"）——仓内主流写法，
                // 只看完整字面量会**漏掉新指标**（本门禁初版就漏过 reconcile.deferred）
                Map<String, String> prefixes = new LinkedHashMap<>();
                Matcher prefix = PREFIX_CONSTANT.matcher(code);
                while (prefix.find()) {
                    prefixes.put(prefix.group(1), prefix.group(2));
                }
                Matcher concat = PREFIX_CONCAT.matcher(code);
                while (concat.find()) {
                    String base = prefixes.get(concat.group(1));
                    if (base != null) {
                        registered.add(base + concat.group(2));
                    }
                }
            }
        }
        // 自检：源码里应该能扫到若干完整指标名，否则解析器失效
        assertThat(registered).as("源码里没扫到任何完整 iot.access.* 字面量 ⇒ 本门禁空跑").isNotEmpty();

        List<String> undocumented = registered.stream().filter(name -> !listed.containsKey(name)).sorted().toList();
        assertThat(undocumented)
            .as("这些指标在源码里注册了但没登记进 docs/METRICS.md（口径/阈值/注册位置缺一不可）：%s", undocumented)
            .isEmpty();
    }

    /** 解析清单：指标名 → 注册文件名（后者取表格最后一列）。 */
    private static Map<String, String> listedMetrics() throws IOException {
        Map<String, String> listed = new LinkedHashMap<>();
        for (String line : Files.readAllLines(METRICS_DOC, StandardCharsets.UTF_8)) {
            Matcher matcher = DOC_ROW.matcher(line.trim());
            if (matcher.matches()) {
                listed.put(matcher.group(1), matcher.group(2));
            }
        }
        return listed;
    }

    /** 在 access 主源码树里按文件名定位唯一源文件（找不到或重名返回 null）。 */
    private static Path resolveSource(String fileName) throws IOException {
        try (Stream<Path> files = Files.walk(ACCESS_MAIN)) {
            List<Path> matched = files.filter(path -> path.getFileName().toString().equals(fileName)).toList();
            return matched.size() == 1 ? matched.get(0) : null;
        }
    }

    /**
     * 该源文件是否声明了这个指标名：接受「完整字面量」或「作为字面量后缀的片段」。
     *
     * <p>为什么要接受后缀：仓内多数指标写成 {@code METRIC_PREFIX + "reconcile.forced"}，
     * 完整名在源码里并不作为整体出现。后缀匹配能同时覆盖两种写法，且不会因为前缀常量改名而误报。</p>
     */
    private static boolean sourceDeclaresMetric(Path source, String metricName) throws IOException {
        String code = Files.readString(source, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(code);
        while (matcher.find()) {
            String literal = matcher.group(1);
            if (!literal.isEmpty() && metricName.endsWith(literal)) {
                return true;
            }
        }
        return false;
    }
}
