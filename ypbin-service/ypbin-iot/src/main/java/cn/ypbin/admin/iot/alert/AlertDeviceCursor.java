/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 「单轮设备上限 + 跨轮滚动」的**游标**（设计 §2.2.2）。
 *
 * <p><b>为什么必须有它</b>：设备数超过 {@code ypbin.alert.evaluate-batch-size}（默认 500）时，
 * 如果每轮都从第一页开始查，ID 较大的那批设备就**永远不会被评估**——那不是「截断」，是**静默漏报**，
 * 而且页面上看不出任何异常。独立复核（2026-10-03）正是把这个缺陷判为阻断项，故这里把「滚动」做成
 * 有状态游标，并用用例把「跨轮覆盖全部设备」钉住。</p>
 *
 * <p><b>语义</b>：每轮取当前页；若该页**取满**（说明后面还有），下一轮进步到下一页；
 * 若取不满（已到末尾）或取空（数据集变小），回到第一页。因此任意连续 N 轮（N = 总页数）内，
 * 所有设备都会被覆盖到。</p>
 *
 * <p><b>键的设计</b>：{@code 用途:租户:作用域指纹}。带「用途」是因为同一轮里评估器与断档映射
 * 会各自解析一次设备（两者的规则集合不同、但都可能命中 TENANT 作用域）——若共用键，一轮就会推两次游标，
 * 反而漏页。</p>
 *
 * <p><b>内存边界</b>：键的数量与「租户 × 作用域指纹 × 用途」同阶，正常情况很小；超过
 * {@value #MAX_KEYS} 时整体清空（游标是尽力而为的滚动提示，清空只意味着从第一页重新开始，
 * 不会漏报、只影响一轮的覆盖面）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertDeviceCursor {

    /** 键数量上限（超出即清空，防「规则频繁变更导致键无限增长」）。 */
    static final int MAX_KEYS = 5_000;

    private final Map<String, Integer> pages = new ConcurrentHashMap<>();

    /**
     * 当前页号（1 起）。
     *
     * @param key 游标键
     * @return 页号（从未查询过时为 1）
     */
    public int currentPage(String key) {
        Integer page = pages.get(key);
        return page == null || page < 1 ? 1 : page;
    }

    /**
     * 依据本轮取回的行数推进游标。
     *
     * @param key      游标键
     * @param fetched  本轮取回行数
     * @param pageSize 单页容量
     */
    public void advance(String key, int fetched, int pageSize) {
        if (pages.size() > MAX_KEYS) {
            pages.clear();
        }
        if (fetched <= 0) {
            // 数据集变小或已清空：回到第一页，避免永久空转（否则每轮都在一个不存在的页上取空）
            pages.put(key, 1);
            return;
        }
        if (fetched >= pageSize) {
            // 取满 ⇒ 下一轮进页。注意必须基于「当前页」自增：早期实现写成 merge(key, 1, sum)，
            // 键不存在时只会写入 1（= 仍停在第 1 页），那正是独立复核判为阻断的「永远取第 1 页」
            pages.compute(key, (ignored, current) -> (current == null ? 1 : current) + 1);
        } else {
            pages.put(key, 1);
        }
    }

    /** 显式回到第一页（取空页时由调用方用于重试）。 */
    public void reset(String key) {
        pages.put(key, 1);
    }

    /** 清空全部游标（用例与排障用）。 */
    public void clear() {
        pages.clear();
    }

    /** 当前游标快照（排障用；键 → 页号）。 */
    public Map<String, Integer> snapshot() {
        return new LinkedHashMap<>(pages);
    }
}
