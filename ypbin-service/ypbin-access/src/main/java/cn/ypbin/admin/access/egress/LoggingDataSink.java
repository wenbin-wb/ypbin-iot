/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.spi.DataSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 协议栈批量出口的**日志占位**实现（3b-2 范围）。
 *
 * <p>ROADMAP 明确 3b-2 只要求日志/内存占位；真实落库（IoTDB/Redis）属 M-2 数据面。
 * 这里不吞批次：空批次不打印，有值批次逐条计数并记日志，便于确认「框架确实在投递」。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class LoggingDataSink implements DataSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingDataSink.class);

    @Override
    public String name() {
        return "logging";
    }

    @Override
    public void write(DataBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        log.info("[access] 协议栈批量投递：device={} protocol={} 点位数={}",
            batch.deviceId(), batch.protocol().value(), batch.size());
    }
}
