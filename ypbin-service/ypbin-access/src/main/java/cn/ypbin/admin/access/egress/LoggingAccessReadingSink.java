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

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读数出口的**日志占位**实现（3b-2 范围）。
 *
 * <p>M-2 会把它换成「有界队列 → 微批 → EMQX」（§5.1），并在此处接两层丢弃计数。
 * 这里刻意**不做静默丢弃也不做业务落库**：只记日志与计数，使「采集链路是否真的在出数」
 * 在联调阶段可见。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class LoggingAccessReadingSink implements AccessReadingSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingAccessReadingSink.class);

    private final AtomicLong total = new AtomicLong();

    private final AtomicLong good = new AtomicLong();

    @Override
    public void accept(AccessReading reading) {
        total.incrementAndGet();
        if (reading.isGood()) {
            good.incrementAndGet();
        }
        log.info("[access] 读数：device={} property={} value={} quality={} ts={}",
            reading.deviceId(), reading.propertyId(), reading.value(), reading.quality(),
            reading.timestamp());
    }

    /** 累计读数条数（供自检/测试）。 */
    public long totalCount() {
        return total.get();
    }

    /** 累计有效读数条数（quality=GOOD，断档判定的分子）。 */
    public long goodCount() {
        return good.get();
    }
}
