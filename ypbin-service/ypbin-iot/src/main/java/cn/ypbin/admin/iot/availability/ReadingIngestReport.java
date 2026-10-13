/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import java.util.List;

/**
 * 一次读数上报的**处理报告**（消息跟踪二批新增）。
 *
 * <p><b>为什么需要它</b>：MQTT 入站回执要记录「丢弃明细（未映射 pointId）」，而
 * {@link cn.ypbin.admin.iot.service.AvailabilityService#ingest} 只返回受理条数。与其在入站层
 * 复制一份点位映射判断（会形成第二份真源、两处口径漂移），不如让落库链路在一次处理里顺带
 * 把被丢弃的点位标识带回来——这也是「不复制校验」原则的落地。</p>
 *
 * <p><b>只含映射类丢弃</b>：droppedPropertyIds 仅收集**未映射/孤儿映射**被丢弃的点位；
 * 「点位标识形态非法」那一道（{@code dropInvalidPropertyIds}）属另一个原因码，不混入本清单。</p>
 *
 * @param processed          实际处理的条数（等价于 {@code ingest} 的返回值）
 * @param droppedPropertyIds 被点位映射校验丢弃的点位标识（保持原顺序；无丢弃时为空列表）
 * @author wenbin
 * @since 2026-10-13
 */
public record ReadingIngestReport(int processed, List<String> droppedPropertyIds) {

    /**
     * 规范构造：null 归一为空列表（**查询/列表类返回值不得用 null 表示空结果**）。
     */
    public ReadingIngestReport {
        droppedPropertyIds = droppedPropertyIds == null ? List.of() : List.copyOf(droppedPropertyIds);
    }

    /**
     * 本批是否有读数被点位映射丢弃。
     *
     * @return 有丢弃返回 {@code true}
     */
    public boolean hasDropped() {
        return !droppedPropertyIds.isEmpty();
    }
}
