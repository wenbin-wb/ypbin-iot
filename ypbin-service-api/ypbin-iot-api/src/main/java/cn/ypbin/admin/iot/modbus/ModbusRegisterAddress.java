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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modbus 寄存器地址的**显式写法**规则（{@code type:offset}，如 {@code holding:0}）。
 *
 * <p><b>为什么需要它、为什么只做显式写法</b>：{@code word_count=2} 的点位要在宿主侧订阅 {@code base}
 * 与 {@code base+1}（SDK 的 {@code ModbusAddressCodec} 只认单地址），因此宿主必须自己算下一个地址。
 * SDK 的 codec 是包级私有、且还支持传统 5 位编号（含 {@code 0xxxx} 段的 1-based 规则）——在宿主复刻那套
 * 段基址规则等于维护**第二份真源**，一旦漂移就会出现「读到了值但整体偏移一位」。故 v1 只接受**语义无歧义**
 * 的显式 {@code type:offset}；传统编号的多寄存器点位在映射校验期被明确拒绝（见
 * {@code docs/VALUE-DECODE-DESIGN.md} §4.3.2）。</p>
 *
 * <p>正则与 SDK {@code ModbusAddressCodec.EXPLICIT} 对齐：{@code ^([a-zA-Z]+)\s*:\s*(\d{1,5})$}
 * （允许冒号两侧空白、类型大小写不敏感）。本类只做地址串的算术，不校验寄存器区是否支持读/写。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class ModbusRegisterAddress {

    /** 显式写法：{@code type:offset}。 */
    private static final Pattern EXPLICIT = Pattern.compile("^([a-zA-Z]+)\\s*:\\s*(\\d{1,5})$");

    /** 偏移上限（与 SDK 的 5 位编号约定一致）。 */
    public static final int MAX_OFFSET = 65535;

    /** 寄存器区类型（可参与 word_count/signed 的类型）。 */
    public static final Set<String> REGISTER_TYPES = Set.of("holding", "input");

    private ModbusRegisterAddress() {
    }

    /**
     * 是否为显式 {@code type:offset} 写法。
     *
     * @param raw 原始地址（可空）
     * @return 匹配返回 {@code true}
     */
    public static boolean isExplicitForm(String raw) {
        return raw != null && EXPLICIT.matcher(raw.trim()).matches();
    }

    /**
     * 取地址的类型码（小写）。非显式写法返回 {@code null}。
     *
     * @param raw 原始地址
     * @return 小写类型码；非显式写法返回 {@code null}
     */
    public static String typeCode(String raw) {
        Matcher matcher = raw == null ? null : EXPLICIT.matcher(raw.trim());
        return matcher != null && matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 取偏移（0-based）。
     *
     * @param raw 原始地址（必须是显式写法）
     * @return 偏移
     * @throws IllegalArgumentException 非显式写法或偏移越界
     */
    public static int offset(String raw) {
        Matcher matcher = raw == null ? null : EXPLICIT.matcher(raw.trim());
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("不是显式 type:offset 写法：" + raw);
        }
        int offset = Integer.parseInt(matcher.group(2));
        if (offset > MAX_OFFSET) {
            throw new IllegalArgumentException("寄存器偏移越界（> " + MAX_OFFSET + "）：" + raw);
        }
        return offset;
    }

    /**
     * 下一个寄存器地址（{@code type:(offset+1)}，规范化为小写类型、无空白）。
     *
     * @param raw 基址（必须是显式写法）
     * @return 下一个地址
     * @throws IllegalArgumentException 非显式写法或 {@code offset+1} 越界
     */
    public static String next(String raw) {
        int offset = offset(raw);
        if (offset + 1 > MAX_OFFSET) {
            throw new IllegalArgumentException("下一个寄存器偏移越界（> " + MAX_OFFSET + "）：" + raw);
        }
        return typeCode(raw) + ":" + (offset + 1);
    }
}
