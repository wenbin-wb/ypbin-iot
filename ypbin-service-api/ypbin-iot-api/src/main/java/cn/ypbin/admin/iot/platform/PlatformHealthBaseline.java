/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.platform;

/**
 * 上一轮基线（设计 `docs/PLATFORM-ALERTING-DESIGN.md` §4）。
 *
 * <p>计数型规则判"是否增长"，必须有上一轮的值。把这个状态**从判定器里挪出来**，
 * 判定器才能保持纯函数（可穷举单测），而基线如何跨轮维护由调用方决定。</p>
 *
 * <p>各字段为 `null` 表示"上一轮没取到/尚未观测"——此时判定为 `UNKNOWN` 而非"健康"。</p>
 *
 * @param roundFailedCount    上轮评估轮次失败数
 * @param notifyFailedCount   上轮通知投递失败数
 * @param ingestDroppedTotal  上轮入站丢弃合计
 * @author wenbin
 * @since 2026-09-30
 */
public record PlatformHealthBaseline(
    Long roundFailedCount,
    Long notifyFailedCount,
    Long ingestDroppedTotal) {

    /**
     * 无基线（首次运行）。
     *
     * @return 全 `null` 的基线
     */
    public static PlatformHealthBaseline none() {
        return new PlatformHealthBaseline(null, null, null);
    }

    /**
     * 从本轮快照生成下一轮用的基线。
     *
     * @param snapshot 本轮快照
     * @return 基线
     */
    public static PlatformHealthBaseline from(PlatformHealthSnapshot snapshot) {
        if (snapshot == null) {
            return none();
        }
        return new PlatformHealthBaseline(snapshot.roundFailedCount(),
            snapshot.notifyFailedCount(), snapshot.ingestDroppedTotal());
    }
}
