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
 * 一条待合并的影子上报行（{@code INSERT ... ON DUPLICATE KEY UPDATE} 的入参）。
 *
 * <p>与 {@link ShadowReportedUpdate} 分开的原因：主键（雪花 ID）由写入器在真正要落库时才生成，
 * 采集阶段（纯计算）不该假装知道存储层的主键。</p>
 *
 * @param id           影子行主键（不存在则插入时使用）
 * @param tenantId     租户 ID
 * @param deviceId     设备 ID
 * @param reportedJson 本次增量的 JSON 文本（形如 {@code {"temperature":"25.5"}}）
 * @param reportTs     本次增量的最新读数时刻
 * @author wenbin
 * @since 2026-09-25
 */
public record ShadowReportedRow(Long id, Long tenantId, Long deviceId, String reportedJson,
                                LocalDateTime reportTs) {
}
