/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

import java.util.Objects;

/**
 * 一次字节载荷解码的结果：成功 → 带规范值；失败 → 带<b>显式</b>失败原因。
 *
 * <p><b>为什么不用 {@code null} 表示失败</b>：{@code null} 无法区分「解码失败」与「解出的就是空值」，
 * 且会把「失败」静默伪装成「没有数据」。这里用二分形态强制实现者把失败说清楚，宿主据此产出 BAD 质量
 * 与原因码指标。</p>
 *
 * @param value   规范值（失败时为 {@code null}；成功时非空）
 * @param failure 失败原因（成功时为 {@code null}）
 * @author wenbin
 * @since 2026-10-13
 */
public record DecodeOutcome(Object value, DecodeFailure failure) {

    /**
     * 紧凑构造器：成功必须有值、失败必须带原因，二者不可同时出现（也不可同时缺失）。
     */
    public DecodeOutcome {
        if (failure == null && value == null) {
            throw new IllegalArgumentException("解码成功必须带值；空结果请用 DecodeOutcome.failed(...) 显式表达");
        }
        if (failure != null && value != null) {
            throw new IllegalArgumentException("解码失败不得同时带值");
        }
    }

    /**
     * 成功结果。
     *
     * @param value 规范值（非空）
     * @return 成功结果
     */
    public static DecodeOutcome ok(Object value) {
        return new DecodeOutcome(Objects.requireNonNull(value, "value"), null);
    }

    /**
     * 失败结果。
     *
     * @param failure 失败原因（非空）
     * @return 失败结果
     */
    public static DecodeOutcome failed(DecodeFailure failure) {
        return new DecodeOutcome(null, Objects.requireNonNull(failure, "failure"));
    }

    /**
     * 是否成功。
     *
     * @return 成功返回 {@code true}
     */
    public boolean success() {
        return failure == null;
    }
}
