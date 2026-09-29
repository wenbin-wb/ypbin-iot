/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.trace;

import java.time.Duration;

/**
 * 消息跟踪的**边界常量**（设计 `docs/MESSAGE-TRACE-DESIGN.md` §6 R5/§7.2）。
 *
 * <p>这些值不是"调优参数"，而是**正确性边界**：跨源合并排序在内存里做，时间窗与条数
 * 是唯一能约束它的闸门。集中在这里是为了让"改闸门"这件事有唯一入口，且能被用例断言。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class DeviceTraceLimits {

    /** 默认时间窗（未传 `from` 时取"当前时刻往前"这么久）——足够覆盖一次排障。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(1);

    /** 时间窗上限：超过则**显式报错**（不静默截断，见设计 §7.2 判据 6）。 */
    public static final Duration MAX_WINDOW = Duration.ofDays(7);

    /** 单次返回的**条目上限**（跨源合并后的最终条数）。 */
    public static final int MAX_ITEMS = 500;

    /**
     * 每个来源表各自的取数上限。
     *
     * <p>取 {@link #MAX_ITEMS} 的 2 倍：合并后要按时间排序再截断，若每源只取
     * {@code MAX_ITEMS}，可能出现"某一源把窗口内的条目全占了、另一源被挤掉"，
     * 而实际排序后前 N 条本该更多来自另一源。2 倍是**启发式余量**，不是精确保证——
     * 真正的精确做法需数据库级全局分页（该方案在跨源场景不成立，见设计 §6 R5）。</p>
     */
    public static final int PER_SOURCE_SCAN = MAX_ITEMS * 2;

    /** 明细里允许原样返回 `payload` 的字段上限（防御性；payload 可含用户自由文本）。 */
    public static final int MAX_RAW_FIELD_CHARS = 8192;

    private DeviceTraceLimits() {
    }
}
