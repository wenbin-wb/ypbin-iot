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

import java.util.List;

/**
 * 时序写入器（IoTDB 表模型，§5.2.1）。
 *
 * <p>与 {@code LatestValueWriter} 同一取向：批量、失败计数 + error 日志、**绝不让上报事务回滚**
 * （Redis/IoTDB 都不参与数据库事务；写失败只影响历史曲线，不影响断档与可用率口径）。</p>
 *
 * <p>顺序：同一 (设备, 点位, 读数时刻) 天然由时间列区分，写入器**不做**批内去重（时序库按时间序列存储，
 * 重复写入同一时刻以最后一次为准是 IoTDB 的既定语义，由表模型承担）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public interface TimeSeriesWriter {

    /**
     * 批量写入（空集合直接返回）。
     *
     * @param points 待写入读数
     */
    void writeAll(List<TimeSeriesPoint> points);
}
