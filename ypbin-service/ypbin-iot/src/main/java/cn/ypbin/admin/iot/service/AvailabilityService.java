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

import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import java.time.LocalDateTime;

/**
 * 断档与可用率服务（M-2 口径的落地）。
 *
 * <p>三件事构成一个闭环：<b>上报</b>刷新「最近有效数据」并在恢复时闭合断档；<b>扫描</b>发现静默设备
 * 并打开断档；<b>查询</b>按窗口现算可用率（不落派生数据，避免与事实漂移）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
public interface AvailabilityService {

    /**
     * 接收一批读数观察：刷新活性状态；有效数据到达时闭合该设备进行中的断档。
     *
     * @param req 上报请求（有界批次）
     * @return 实际处理的观察条数（设备不存在等被丢弃的不计）
     */
    int ingest(ReadingIngestReq req);

    /**
     * 扫描并打开断档（静默设备只能靠它发现）。
     *
     * @return 本次新开的断档数
     */
    int scanAndOpenOutages();

    /**
     * 查询某设备在时间窗口内的可用率与断档明细。
     *
     * @param deviceId 设备 ID
     * @param from     窗口起点（可空=按配置的默认窗口推算）
     * @param to       窗口终点（可空=数据库当前时间）
     * @return 可用率视图
     */
    AvailabilityResp query(Long deviceId, LocalDateTime from, LocalDateTime to);
}
