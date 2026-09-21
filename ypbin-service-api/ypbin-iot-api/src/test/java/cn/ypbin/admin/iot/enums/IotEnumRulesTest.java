/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IoT 枚举规则门禁：{@code code} 唯一且非空、{@code desc} 非空、{@code of} 双向可解析。
 *
 * <p>母仓铁律：枚举禁 {@code ordinal()}，数据库与接口一律存/传 {@code code} ⇒ code 必须稳定唯一；
 * 未匹配返回 {@code null}（不静默兜底）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotEnumRulesTest {

    /** 受检枚举。 */
    private static final Class<?>[] ENUMS = {
        IotProtocol.class,
        ThingModelDataType.class,
        AccessMode.class,
        ModelStatus.class,
        ServiceOption.class,
        OnlineStatus.class,
        PointRefType.class,
        AddressType.class,
    };

    @Test
    @DisplayName("所有 IoT 枚举：code 唯一非空、desc 非空、of(code) 双向可解析")
    void enumsShouldHaveUniqueStableCodes() throws Exception {
        for (Class<?> enumClass : ENUMS) {
            Object[] constants = enumClass.getEnumConstants();
            assertThat(constants).isNotEmpty();
            Method codeGetter = enumClass.getMethod("getCode");
            Method descGetter = enumClass.getMethod("getDesc");
            Method ofMethod = enumClass.getMethod("of", String.class);

            Set<String> codes = new HashSet<>();
            for (Object constant : constants) {
                String code = (String) codeGetter.invoke(constant);
                String desc = (String) descGetter.invoke(constant);
                assertThat(code).as(enumClass.getSimpleName() + " code").isNotBlank();
                assertThat(desc).as(enumClass.getSimpleName() + " desc").isNotBlank();
                assertThat(codes.add(code)).as(enumClass.getSimpleName() + " code 重复: " + code).isTrue();
                // 双向解析：of(code) 必须回到自身
                assertThat(ofMethod.invoke(null, code)).as(enumClass.getSimpleName() + " of(" + code + ")")
                    .isEqualTo(constant);
            }
            // 非法 code 返回 null（不静默兜底）
            assertThat(ofMethod.invoke(null, "no-such-code"))
                .as(enumClass.getSimpleName() + " of(非法) 应为 null")
                .isNull();
        }
    }

    @Test
    @DisplayName("枚举列表本身不变（防删减导致历史 code 漂移）")
    void enumValuesShouldNotShrink() {
        assertThat(Arrays.asList(IotProtocol.values()))
            .extracting(IotProtocol::getCode)
            .containsExactly("tcp", "modbus", "mqtt", "opcua");
        assertThat(Arrays.asList(ThingModelDataType.values()))
            .extracting(ThingModelDataType::getCode)
            .containsExactly("int", "long", "decimal", "string", "bool", "enum", "date_time", "json_object", "array");
        assertThat(Arrays.asList(AddressType.values()))
            .extracting(AddressType::getCode)
            .containsExactly("holding", "input", "coil", "discrete", "nodeid", "topic");
    }
}
