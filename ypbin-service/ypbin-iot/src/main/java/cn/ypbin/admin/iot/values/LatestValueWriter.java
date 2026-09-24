/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.values;

import java.util.List;

/**
 * 最新值写入器（Q8/设计 §5.3：最新值走 Redis，与可用率口径解耦）。
 *
 * <p>契约要点：</p>
 * <ol>
 *   <li><b>批量</b>：一次调用写完一批（避免逐条往返；也避免调用方在循环里做外部调用）；</li>
 *   <li><b>失败不回滚上报</b>：最新值是「便利数据」，写失败必须**暴露**（日志 + 指标）但不得让
 *       断档/可用率的落库事务回滚——否则一次 Redis 抖动会连带丢掉可用率数据；</li>
 *   <li><b>顺序</b>：同一 (租户, 设备, 点位) 的多次写入按 {@code ts} 取新（乱序到达不得让旧值覆盖新值）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public interface LatestValueWriter {

    /**
     * 批量写入最新值（空集合直接返回）。
     *
     * @param values 待写入值
     */
    void writeAll(List<LatestValue> values);
}
