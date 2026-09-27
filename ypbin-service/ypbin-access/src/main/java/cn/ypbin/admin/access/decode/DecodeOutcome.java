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

/**
 * 一次读数解码的结果：要么是规范值，要么是**具名失败原因**。
 *
 * <p>为什么不用 {@code null} 表示失败：调用方（{@code PointMappingDataListener}）必须按原因计数与告警，
 * 「值恰好为 null」与「解不出来」是两件事；用记录承载二者可以让状态不可混淆，也不会出现
 * 「空 catch 把失败吞成空值」这类静默降级。</p>
 *
 * @param value   解码后的规范值；失败时为 {@code null}
 * @param failure 失败原因；成功时为 {@code null}
 * @author wenbin
 * @since 2026-09-27
 */
public record DecodeOutcome(Object value, DecodeFailure failure) {

    /**
     * 解码成功。
     *
     * @param value 规范值
     * @return 结果
     */
    public static DecodeOutcome ok(Object value) {
        return new DecodeOutcome(value, null);
    }

    /**
     * 解码失败。
     *
     * @param failure 失败原因
     * @return 结果
     */
    public static DecodeOutcome failed(DecodeFailure failure) {
        return new DecodeOutcome(null, failure);
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
