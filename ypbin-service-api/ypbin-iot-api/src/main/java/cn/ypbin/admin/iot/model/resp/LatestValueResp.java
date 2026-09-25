/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import lombok.Getter;
import lombok.Setter;

/**
 * 一条点位最新值（读自 Redis 最新值哈希）。
 *
 * <p><b>语义边界</b>：这是「最新一条」，不是历史序列——历史一律走
 * {@code GET /iot/devices/{deviceId}/series}。同一设备多次上报只保留读数时刻最新的那条，
 * 消费方必须按 {@code ts} 判新旧（写入器只保证批内取新，见 {@code LatestValueWriter}）。</p>
 *
 * <p>{@code ts} 是读数时刻的 epoch 毫秒（与上报契约一致），由全局 Long 序列化规则按字符串输出。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Getter
@Setter
public class LatestValueResp {

    /** 点位标识（Redis 哈希的 field）。 */
    private String propertyId;

    /** 读数原值（字符串化；写入侧原值为 null 时此处为 null）。 */
    private String value;

    /** 质量码（写入侧未给时为 null）。 */
    private String quality;

    /** 读数时刻（epoch 毫秒）。 */
    private Long ts;
}
