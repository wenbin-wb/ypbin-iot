/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lock;

/**
 * 一次「带锁执行」的结果（#220）。
 *
 * @param executed 是否执行了任务（{@code false} = 锁被其它实例持有，本轮跳过）
 * @param degraded 是否在锁存储不可用时降级执行（fail-open；{@code true} 表示多副本可能重复执行）
 * @param result   任务返回值（跳过时为 {@code null}）
 * @param <T>      任务返回值类型
 * @author wenbin
 * @since 2026-10-13
 */
public record LockOutcome<T>(boolean executed, boolean degraded, T result) {

    /**
     * 跳过的结果（未取得锁）。
     *
     * @param <T> 任务返回值类型
     * @return 跳过结果
     */
    public static <T> LockOutcome<T> skipped() {
        return new LockOutcome<>(false, false, null);
    }
}
