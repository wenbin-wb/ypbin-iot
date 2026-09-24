/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

/**
 * 一条待写入时序库的读数（§5.2.1）。
 *
 * @param tenantId   租户 ID
 * @param deviceId   设备 ID（文本形态，避免跨系统大整数语义差异）
 * @param propertyId 点位标识
 * @param value      读数原值（字符串化；列的选择由写入器按 §5.2.1 的类型映射规则决定）
 * @param quality    质量码
 * @param ts         读数时刻（epoch 毫秒）
 * @author wenbin
 * @since 2026-09-24
 */
public record TimeSeriesPoint(Long tenantId, Long deviceId, String propertyId, String value, String quality,
                              long ts) {
}
