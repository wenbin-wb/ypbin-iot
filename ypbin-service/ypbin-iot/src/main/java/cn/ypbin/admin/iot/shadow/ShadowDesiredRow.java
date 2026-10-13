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

import java.time.LocalDateTime;

/**
 * 一条待合并的影子期望行（{@code INSERT ... ON DUPLICATE KEY UPDATE} 的入参）。
 *
 * <p>与 {@link ShadowReportedRow} 分开的原因：两者写的是同一行的不同列（{@code desired} 与
 * {@code reported}），但 {@code desired_ts} 只前进、{@code report_ts} 只前进各自独立；用同一条语句
 * 同时写两列会让两条写入路径互相覆盖对方的「最近时刻」。</p>
 *
 * @param id          影子行主键（不存在则插入时使用）
 * @param tenantId    租户 ID
 * @param deviceId    设备 ID
 * @param desiredJson 本次期望增量（点位置换映射的 JSON 文本，形如 {@code {"temperature":25.0}}）
 * @param desiredTs   本次期望时刻
 * @author wenbin
 * @since 2026-10-13
 */
public record ShadowDesiredRow(Long id, Long tenantId, Long deviceId, String desiredJson,
                               LocalDateTime desiredTs) {
}
