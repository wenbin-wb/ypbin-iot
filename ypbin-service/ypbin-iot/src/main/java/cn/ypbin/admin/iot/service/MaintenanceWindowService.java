/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.availability.MaintenanceWindowDto;
import cn.ypbin.admin.iot.availability.MaintenanceWindowReq;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 维护窗口（M-2 / spec §12.5）：可用率统计要排除的计划停机。
 *
 * @author wenbin
 * @since 2026-09-23
 */
public interface MaintenanceWindowService {

    /**
     * 声明一个维护窗口（租户从上下文取）。
     *
     * @param req 请求（{@code deviceId} 为空=该租户全部设备；{@code startTs} 为空=服务端当前时间）
     * @return 新建窗口 ID
     */
    Long open(MaintenanceWindowReq req);

    /**
     * 关闭一个维护窗口（只关「进行中」的；已结束的不得改写）。
     *
     * @param id 窗口 ID
     * @return 受影响行数（0=不存在或已结束）
     */
    int close(Long id);

    /**
     * 查询与给定窗口重叠的维护窗口。
     *
     * @param deviceId 设备 ID（可空：空=只看租户级窗口 + 全部设备级窗口）
     * @param from     起点（可空=不限）
     * @param to       终点（可空=不限）
     * @return 维护窗口（无则空列表）
     */
    List<MaintenanceWindowDto> list(Long deviceId, LocalDateTime from, LocalDateTime to);
}
