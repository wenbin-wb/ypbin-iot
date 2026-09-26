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
import java.util.Map;

/**
 * 一台设备在一批上报里要合并进影子 {@code reported} 的增量（G2）。
 *
 * <p>形态刻意是「增量」而不是「整份影子」：{@code reported} 的其余键必须由存储层合并保留，
 * 若这里携带整份 JSON 再整体覆盖，多副本并发上报时后写的那份会把先写的点位丢掉。</p>
 *
 * <p>{@code reported} 的值是读数**原值字符串**：上报契约（{@code ReadingObservationDto}）明确
 * 「类型由物模型属性定义，服务端不做隐式转换」，影子这一层同样不做窄化，避免把 {@code 25.5} 存成
 * 数值、把 {@code "OFF"} 存成字符串这类随属性而变的分叉判断散落在写入路径上。</p>
 *
 * @param tenantId  租户 ID（上报路径没有租户上下文，必须显式带上）
 * @param deviceId  设备 ID
 * @param reported  本次要合并的点位增量（propertyId → 读数原值；非空、值为非 null）
 * @param reportTs  本次增量的最新读数时刻（由上报 epoch 毫秒转成平台时区的 {@link LocalDateTime}）
 * @author wenbin
 * @since 2026-09-25
 */
public record ShadowReportedUpdate(Long tenantId, Long deviceId, Map<String, String> reported,
                                   LocalDateTime reportTs) {
}
