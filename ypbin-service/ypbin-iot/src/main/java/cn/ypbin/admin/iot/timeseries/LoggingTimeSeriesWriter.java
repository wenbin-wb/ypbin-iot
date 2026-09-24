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

import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 未启用/未实现时序库时的写入器：**只记一条 WARN + 丢弃**。
 *
 * <p>为什么不静默：时序数据缺失会让「历史曲线」长期空白，运维必须能一眼看出是配置问题而不是设备问题；
 * WARN 只打一次（按实例），避免每批刷屏。</p>
 *
 * <p>⚠️ <b>当前装配下它不会被调用</b>（默认关闭时不收集点位；`enabled=true` 时启动自检直接拒绝）——
 * 它是**防御性实现**，「不静默」在当前版本由启动 INFO（"时序写入未启用"）+ fail-fast 承担。
 * 下一段接入 JDBC 实现后，它才会成为真正的降级路径（届时若仍启用失败，WARN 才有意义）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class LoggingTimeSeriesWriter implements TimeSeriesWriter {

    private static final Logger log = LoggerFactory.getLogger(LoggingTimeSeriesWriter.class);

    private final String reason;

    private final AtomicBoolean warned = new AtomicBoolean();

    public LoggingTimeSeriesWriter(String reason) {
        this.reason = reason;
    }

    @Override
    public void writeAll(List<TimeSeriesPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        if (warned.compareAndSet(false, true)) {
            log.warn("[iot] 时序数据未落库（{}），本次丢弃 {} 条；断档/可用率口径不受影响（只看质量与时刻）。"
                + "启用方式见 docs/IOT-PLATFORM-DESIGN.md §5.2.1（{}）",
                LogSanitizer.sanitize(reason), LogSanitizer.sanitize(points.size()),
                TimeSeriesProperties.PREFIX);
        }
    }
}
