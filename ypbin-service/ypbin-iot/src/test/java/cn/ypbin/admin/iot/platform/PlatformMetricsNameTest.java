/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.alert.AlertMetrics;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 平台健康判定的**指标名一致性门禁**（看板 #10，设计 §1.4）。
 *
 * <p><b>为什么需要它</b>：判定器按**指标名**从 `MeterRegistry` 取值。名字若与产出侧
 * 不一致（改了产出侧常量、或这边拼错一个字符），取值会返回 `null`，
 * 而 `null` 在判定器里表现为 `UNKNOWN` ⇒ **不告警**。</p>
 *
 * <p>⇒ 后果是**监控静默失效**：平台看起来一切正常，实际规则从未生效。
 * 这类"沉默的坏"用运行期观察极难发现（不会报错、不会有日志异常），
 * 因此必须在**编译期/测试期**钉死。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class PlatformMetricsNameTest {

    /**
     * 从产出侧常量类读同名常量的值（反射取 `static final String`）。
     *
     * @param owner      常量所在类
     * @param fieldName  常量名
     * @return 常量值
     */
    private static String constantOf(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    @DisplayName("🔴 判定器用到的指标名必须与产出侧（AlertMetrics）逐字一致")
    void metricNamesMustMatchProducer() throws Exception {
        List<String> mismatches = new ArrayList<>();

        // 评估滞后 + 轮次失败：产出侧在 AlertMetrics
        assertSame(AlertMetrics.METRIC_LAG, PlatformMetrics.METRIC_EVALUATE_LAG, mismatches);
        assertSame(AlertMetrics.METRIC_ROUND_FAILED, PlatformMetrics.METRIC_ROUND_FAILED,
            mismatches);
        assertSame(AlertMetrics.METRIC_NOTIFY_FAILED, PlatformMetrics.METRIC_NOTIFY_FAILED,
            mismatches);
        // 入站丢弃两项：产出在增量模块，这里只钉住"与产出侧常量同值"的断言由各自门禁负责；
        // 本用例至少保证**判定器侧不是随手拼的字符串**（有 description 可读性要求）
        assertThat(PlatformMetrics.METRIC_INGEST_UNMAPPED)
            .startsWith("iot.ingest.propertyid.");
        assertThat(PlatformMetrics.METRIC_INGEST_ORPHAN)
            .startsWith("iot.ingest.propertyid.");

        assertThat(mismatches)
            .as("指标名漂移会让判定器永远取不到值 ⇒ 静默不告警（监控失效且无人发现）")
            .isEmpty();
    }

    /**
     * 断言两处常量同值。
     *
     * @param producerName 产出侧值
     * @param consumerName 判定器侧值
     * @param mismatches   差异收集器
     */
    private static void assertSame(String producerName, String consumerName,
                                   List<String> mismatches) {
        if (!producerName.equals(consumerName)) {
            mismatches.add("产出侧=" + producerName + " 判定器侧=" + consumerName);
        }
    }

    @Test
    @DisplayName("指标名常量不可为空白，且必须带 iot. 前缀（避免误用成别的域的指标）")
    void metricNamesMustBeWellFormed() throws Exception {
        for (String name : new String[] {
            PlatformMetrics.METRIC_EVALUATE_LAG,
            PlatformMetrics.METRIC_ROUND_FAILED,
            PlatformMetrics.METRIC_NOTIFY_FAILED,
            PlatformMetrics.METRIC_INGEST_UNMAPPED,
            PlatformMetrics.METRIC_INGEST_ORPHAN}) {
            assertThat(name).isNotBlank().startsWith("iot.");
        }
        // 反射读取产出侧常量，顺带证明它确实存在（防"常量被改名后本门禁空转"）
        assertThat(constantOf(AlertMetrics.class, "METRIC_LAG")).isNotBlank();
    }
}
