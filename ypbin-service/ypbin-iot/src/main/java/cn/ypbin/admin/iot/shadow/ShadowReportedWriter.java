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

import java.util.List;

/**
 * 影子上报值（{@code reported}）写入器（G2：让「设备当前状态」由上报驱动）。
 *
 * <p>契约要点（与 {@link cn.ypbin.admin.iot.values.LatestValueWriter} 同一套失败语义）：</p>
 * <ol>
 *   <li><b>批量</b>：一次调用写完一批设备（调用方不得在循环里逐条调用，避免 N+1 往返）；</li>
 *   <li><b>失败不上抛</b>：影子是上报的「派生视图」，写失败必须**暴露**（计数 + 带堆栈的 ERROR 日志）
 *       但不得让上报主流程回滚或报错——否则一次影子写入抖动会连带丢掉断档/可用率数据；</li>
 *   <li><b>按点位合并不整体覆盖</b>：合并语义由存储层的单条原子语句保证（见
 *       {@link cn.ypbin.admin.iot.mapper.IotShadowMapper#mergeReported}），本接口的入参只带增量。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-25
 */
public interface ShadowReportedWriter {

    /**
     * 批量合并影子上报值（空集合直接返回）。
     *
     * @param updates 待合并的增量（每个 (租户, 设备) 至多一条）
     */
    void writeAll(List<ShadowReportedUpdate> updates);
}
