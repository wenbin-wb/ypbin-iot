/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.values;

/**
 * 一条待写入的「最新值」（读数上报的一等公民形态）。
 *
 * <p>不可变：值本身没有生命周期，写完即弃；{@code ts} 是读数时刻（epoch 毫秒，与上报契约一致），
 * 不是写入时刻——「最新」的判据是读数时刻，不是到达顺序。</p>
 *
 * <p>顺序保证的**作用域**：写入器在**同一批次**内按 {@code ts} 取新，跨批次由服务端 Lua 逐 field
 * 比较后写入（更旧的批次晚到不会覆盖已存值）。消费方仍可按 {@code ts} 自行判新旧
 * （见 {@link LatestValueWriter} 与 ROADMAP 四点十七）。</p>
 *
 * @param tenantId   租户 ID（跨租户写入必须显式带上：租约/内部上报路径没有租户上下文）
 * @param deviceId   设备 ID
 * @param propertyId 点位标识
 * @param value      读数原值（字符串化）
 * @param quality    质量码
 * @param ts         读数时刻（epoch 毫秒）
 * @author wenbin
 * @since 2026-09-24
 */
public record LatestValue(Long tenantId, Long deviceId, String propertyId, String value, String quality, long ts) {
}
