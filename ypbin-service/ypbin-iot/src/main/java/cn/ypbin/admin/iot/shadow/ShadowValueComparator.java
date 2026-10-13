/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.shadow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 影子 {@code desired} 与 {@code reported} 的**值比较口径**（delta 的唯一判据）。
 *
 * <p><b>为什么不能直接用 {@code equals}</b>：两条写入路径的值类型不同——{@code reported} 由
 * {@link ShadowReportedUpdate} 规定「值统一为字符串」（{@code {"temperature":"25.0"}}），而
 * {@code desired} 来自命令 {@code property_set} 的 {@code params.value}（可能是 {@code 25.0} 数值、
 * {@code true} 布尔或字符串）。若按原始类型比较，「设备已上报 25.0、平台期望 25.0」会被判成不一致，
 * 于是 delta 永远清不掉——那不是设备的问题，是比较口径的问题。</p>
 *
 * <p><b>归一化规则</b>：null 视为同一哨兵；布尔与 {@code "true"}/{@code "false"}（忽略大小写）归一；
 * 数字与**可解析为数字的字符串**归一为 {@link BigDecimal} 且按数值比较（{@code "25.0"} == {@code 25}）；
 * 其余字符串原样比较；对象/数组递归归一后逐项比较。刻意不做 trim（属性值里的前后空格可能有语义）。</p>
 *
 * <p>本类无状态、线程安全，只被影子读路径（delta 计算）调用。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class ShadowValueComparator {

    /** null 的哨兵（区分「值为 null」与「键不存在」；本类只比较值，键存在性由调用方判）。 */
    private static final Object NULL = new Object();

    private ShadowValueComparator() {
    }

    /**
     * 两个影子值是否等价（类型宽容，语义见类注释）。
     *
     * @param left  左值（可为 null）
     * @param right 右值（可为 null）
     * @return 等价返回 {@code true}
     */
    public static boolean equal(Object left, Object right) {
        return deepEquals(normalize(left), normalize(right));
    }

    /**
     * 归一化（供测试直接断言口径，避免测试复制实现）。
     *
     * @param value 原值
     * @return 归一化值
     */
    static Object normalize(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return decimal(number.toString());
        }
        if (value instanceof CharSequence text) {
            String raw = text.toString();
            if ("true".equalsIgnoreCase(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return Boolean.FALSE;
            }
            BigDecimal number = tryDecimal(raw);
            return number == null ? raw : number;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> normalized = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object element : iterable) {
                normalized.add(normalize(element));
            }
            return normalized;
        }
        // 兜底：未知类型退回字符串口径（不抛异常——读路径不该因为一个怪值整个 GET 失败）
        return value.toString();
    }

    /** 归一化后的深比较（数字按数值而非 scale 比较）。 */
    private static boolean deepEquals(Object left, Object right) {
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        if (left instanceof Map<?, ?> lm && right instanceof Map<?, ?> rm) {
            if (lm.size() != rm.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : lm.entrySet()) {
                if (!rm.containsKey(entry.getKey())
                    || !deepEquals(entry.getValue(), rm.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> ll && right instanceof List<?> rl) {
            if (ll.size() != rl.size()) {
                return false;
            }
            for (int i = 0; i < ll.size(); i++) {
                if (!deepEquals(ll.get(i), rl.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    /** 数字字符串 → BigDecimal（不可解析返回 null）。 */
    private static BigDecimal tryDecimal(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return decimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 去尾零，使 {@code 25.0} 与 {@code 25} 归一为同一个值。 */
    private static BigDecimal decimal(String raw) {
        return new BigDecimal(raw).stripTrailingZeros();
    }
}
