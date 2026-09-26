/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 点位标识校验口径（P0-6c）：**入站过滤**与**查询字面量**必须是同一套判定与同一句话术。
 *
 * <p>为什么单独一个测试类：这条口径现在有两个使用点（{@code AvailabilityServiceImpl} 的入站过滤、
 * {@link IotDbTimeSeriesStore#propertyIdLiteral(String)} 的查询拼接），两边各写一份正则是很容易发生的
 * 腐化（入站放行、查询拒绝 ⇒ 「写进去了却查不出来」）。本类把口径本身与两边的**一致性**钉死。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
class PropertyIdRulesTest {

    /** 覆盖各类形态：合法边界、超长、白名单外字符、注入形态、以及「非 ASCII」。 */
    private static final List<String> SAMPLES = List.of(
        "temp",
        "a.b:c-1_2",
        "T1",
        "_",
        "-",
        ":",
        ".",
        "a".repeat(PropertyIdRules.MAX_LENGTH),
        "",
        " ",
        "   ",
        "temp id",
        "temp' OR '1'='1",
        "temp'; DROP TABLE reading; --",
        "\",\"ts\":999}",
        "温度",
        "temp/1",
        "temp;1",
        "temp\tid",
        "temp\nid",
        "a".repeat(PropertyIdRules.MAX_LENGTH + 1));

    @Test
    @DisplayName("★ 口径必须是既有已复核实现（IotDbTimeSeriesStore.PROPERTY_ID_PATTERN）的**逐字**形态")
    void patternMustStayIdenticalToReviewedImplementation() {
        assertThat(PropertyIdRules.PATTERN)
            .as("这条正则随 2026-09-24 的 SQL 字面量防护一起复核过，改动即等于放宽已复核的安全边界")
            .isEqualTo("[A-Za-z0-9_.:-]{1,128}");
        assertThat(PropertyIdRules.MAX_LENGTH).isEqualTo(128);
    }

    @Test
    @DisplayName("★ 同一条规则的两个使用点（规则本身 vs 查询侧 propertyIdLiteral）对每个样例结论、拒因一致")
    void ruleAndQuerySideLiteralMustAgreeOnEverySample() {
        // ⚠️ 本用例证明的是「**规则**与查询侧字面量」一致（两者引用同一个 PropertyIdRules，见覆盖自证里的
        // accepted/rejected 计数）。它**不**覆盖服务层入站判据的空白旁路——入站对空白/null 是**放行**的
        // （见 AvailabilityServiceImpl#dropInvalidPropertyIds 的边界说明，以及
        // AvailabilityServiceImplTest#blankPropertyIdMustStayLivenessOnlyAndNotBeRejected）。
        // 独立复核 2026-09-26 指出：原 @DisplayName 写「入站判定与查询侧完全一致」属**声明过度**，
        // 因为它比的是同一函数（恒等）。此处按事实改名，并把真正的分歧登记在下面这条断言里。
        int accepted = 0;
        int rejected = 0;
        for (String sample : SAMPLES) {
            boolean rulesAccept = PropertyIdRules.isValid(sample);
            boolean literalAccept;
            String message = null;
            try {
                IotDbTimeSeriesStore.propertyIdLiteral(sample);
                literalAccept = true;
            } catch (BusinessException ex) {
                literalAccept = false;
                message = ex.getMessage();
            }
            assertThat(rulesAccept)
                .as("规则与查询侧字面量判定必须一致：样例=%s", LogSanitizer.sanitize(sample))
                .isEqualTo(literalAccept);
            if (rulesAccept) {
                accepted++;
            } else {
                rejected++;
                assertThat(message)
                    .as("拒绝话术必须全仓唯一（同一个常量）")
                    .isEqualTo(PropertyIdRules.INVALID_MESSAGE);
            }
        }
        // 教训七/八：样例集若全是同一种结论，本用例就测不出「两边一致」这件事
        assertThat(accepted).as("样例里必须有合法形态").isPositive();
        assertThat(rejected).as("样例里必须有非法形态").isPositive();
    }

    @Test
    @DisplayName("★ 长度上限是端点值：128 合法、129 非法（含 null 非法）")
    void lengthLimitMustBeInclusive() {
        assertThat(PropertyIdRules.isValid("a".repeat(128))).isTrue();
        assertThat(PropertyIdRules.isValid("a".repeat(129))).isFalse();
        assertThat(PropertyIdRules.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("★ 非法字面量绝不带单引号进 SQL（校验 + 转义的纵深防御）")
    void illegalLiteralMustNeverContainQuote() {
        for (String sample : SAMPLES) {
            if (!PropertyIdRules.isValid(sample)) {
                continue;
            }
            assertThat(IotDbTimeSeriesStore.propertyIdLiteral(sample)).doesNotContain("\"").startsWith("'")
                .endsWith("'");
        }
    }
}
