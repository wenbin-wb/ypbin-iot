/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.access.config.AccessProperties;
import cn.ypbin.admin.iot.lease.config.LeaseFeignConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * access 启动自检单测（issue #298 验收要求的一部分）。
 *
 * <p><b>为什么现在才补</b>：issue #298 的验收清单里写了
 * {@code mvn ... -Dtest=AccessStartupValidatorTest}，但本仓此前**没有这个测试类**
 * （{@code AccessStartupValidator} 的超时口径只被运行时自检隐式覆盖）⇒ 验收命令会因
 * 「找不到测试」而失败，spec 与事实不符。本类补上它，并把「超时常量不被删除、
 * 自检口径不被改小」钉成构建失败。</p>
 *
 * <p><b>与本 issue 的关系</b>：#298 把三份 Feign 配置类的 `@Configuration` 摘掉、只保留
 * {@code CONNECT_TIMEOUT_MS}/{@code READ_TIMEOUT_MS} 常量。若有人「顺手」把常量也删了，
 * 或把 {@code renew-interval-ms} 的校验基数改小，启动自检就会失效 ⇒ 一次远端卡顿即可让
 * 节点把自己 fencing（租约归属错乱）。下面的断言把这两点都守住。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class AccessStartupValidatorTest {

    /** 合法的最小续约周期：最坏耗时 × 安全倍数（由常量推导，不硬编码 8000，避免与常量脱钩）。 */
    private static final long MIN_SAFE_RENEW_MS =
        (long) AccessStartupValidator.worstCaseMs() * AccessStartupValidator.SAFETY_FACTOR;

    @Test
    @DisplayName("★ 最坏耗时口径 = 租约客户端 connect + read 常量（常量被删/改小则本断言先红）")
    void worstCaseMustFollowLeaseTimeoutConstants() {
        assertThat(AccessStartupValidator.worstCaseMs())
            .as("最坏耗时必须是 LeaseFeignConfiguration 的 connect+read 之和（issue #298 明确要求保留这两个常量）")
            .isEqualTo(LeaseFeignConfiguration.CONNECT_TIMEOUT_MS
                + LeaseFeignConfiguration.READ_TIMEOUT_MS)
            .isEqualTo(4_000);
        assertThat(AccessStartupValidator.SAFETY_FACTOR)
            .as("安全倍数是「续约周期 ≥ 最坏耗时 × N」的口径常量")
            .isEqualTo(2);
        assertThat(MIN_SAFE_RENEW_MS)
            .as("由常量推导出的最小安全续约周期（= 8000ms，与 deploy/nacos 的 renew-interval-ms 同值）")
            .isEqualTo(8_000L);
    }

    @Test
    @DisplayName("★ 合法配置：node-id 非空 + 续约周期 = 最坏耗时×2 的边界值 ⇒ 恰好通过（严格小于才拒绝）")
    void exactlyMinSafeRenewIntervalMustPass() {
        AccessStartupValidator validator = validator("access-1", MIN_SAFE_RENEW_MS, 15_000L);

        assertThat(validator.errors()).as("刚好等于下界必须放行（边界是「≥」而非「>」）").isEmpty();
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 续约周期比安全下界少 1ms ⇒ 启动即失败，且报错写明 required 值")
    void renewIntervalJustBelowFloorMustFailFast() {
        AccessStartupValidator validator = validator("access-1", MIN_SAFE_RENEW_MS - 1, 15_000L);

        List<String> issues = validator.errors();
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
            .as("报错必须给出具体下界与原因，而不是笼统的「配置非法」")
            .contains("renew-interval-ms")
            .contains(String.valueOf(MIN_SAFE_RENEW_MS));
        assertThatThrownBy(validator::afterPropertiesSet)
            .as("自检不通过必须 fail-fast（禁止静默降级）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("access 启动自检未通过");
    }

    @Test
    @DisplayName("★ node-id 为空 ⇒ 启动即失败（多副本会同名注册，租约归属直接失效）")
    void blankNodeIdMustFailFast() {
        AccessStartupValidator validator = validator("  ", MIN_SAFE_RENEW_MS, 15_000L);

        assertThat(validator.errors()).singleElement().asString().contains("node-id");
        assertThatThrownBy(validator::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★ 重领周期：非正数、或小于一次最坏耗时 ⇒ 各自报错（不早退，一次列全）")
    void acquireIntervalMustBePositiveAndNotShorterThanWorstCase() {
        assertThat(validator("access-1", MIN_SAFE_RENEW_MS, 0L).errors())
            .singleElement().asString().contains("acquire-interval-ms");
        assertThat(validator("access-1", MIN_SAFE_RENEW_MS, AccessStartupValidator.worstCaseMs() - 1).errors())
            .singleElement().asString().contains("acquire-interval-ms");
        assertThat(validator("access-1", MIN_SAFE_RENEW_MS, AccessStartupValidator.worstCaseMs()).errors())
            .as("恰好等于一次最坏耗时必须放行").isEmpty();
    }

    @Test
    @DisplayName("★ 多条问题同时存在时一次列全（不早退，便于运维一次改完）")
    void multipleIssuesMustBeReportedTogether() {
        AccessStartupValidator validator = validator("", 1_000L, -1L);

        assertThat(validator.errors())
            .as("node-id / renew-interval-ms / acquire-interval-ms 三类问题应同时报出")
            .hasSizeGreaterThanOrEqualTo(3);
    }

    /** 构造一个被测自检器（只设本类关心的三项，其余走 AccessProperties 默认值）。 */
    private static AccessStartupValidator validator(String nodeId, long renewIntervalMs,
                                                    long acquireIntervalMs) {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId(nodeId);
        properties.setRenewIntervalMs(renewIntervalMs);
        properties.setAcquireIntervalMs(acquireIntervalMs);
        return new AccessStartupValidator(properties);
    }
}
