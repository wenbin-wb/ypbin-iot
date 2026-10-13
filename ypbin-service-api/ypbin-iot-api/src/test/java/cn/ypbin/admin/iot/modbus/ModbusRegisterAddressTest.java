/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 显式寄存器地址算术（v3 泛化：{@code plus(raw, delta)}）。
 *
 * @author wenbin
 * @since 2026-10-13
 */
class ModbusRegisterAddressTest {

    @Test
    @DisplayName("plus：任意非负 delta，规范化为小写类型、无空白；next 等价 plus(...,1)")
    void plusMustOffsetAndNormalize() {
        assertThat(ModbusRegisterAddress.plus("holding:0", 0)).isEqualTo("holding:0");
        assertThat(ModbusRegisterAddress.plus("holding:0", 3)).isEqualTo("holding:3");
        assertThat(ModbusRegisterAddress.plus(" HOLDING : 5 ", 2)).isEqualTo("holding:7");
        assertThat(ModbusRegisterAddress.next("input:9")).isEqualTo("input:10");
    }

    @Test
    @DisplayName("plus：负 delta / 非显式写法 / 越界 ⇒ 显式报错")
    void plusMustRejectInvalid() {
        assertThatThrownBy(() -> ModbusRegisterAddress.plus("holding:0", -1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为负");
        assertThatThrownBy(() -> ModbusRegisterAddress.plus("40001", 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type:offset");
        assertThatThrownBy(() -> ModbusRegisterAddress.plus("holding:65535", 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("越界");
    }
}
