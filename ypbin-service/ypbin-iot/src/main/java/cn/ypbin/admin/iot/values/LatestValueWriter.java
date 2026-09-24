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
 *   <li><b>顺序（批次作用域，必须如实理解）</b>：<b>同一次</b> {@code writeAll} 内，同一
 *       (租户, 设备, 点位) 只保留 {@code ts} 最新的那条；<b>跨批次</b>的乱序（更早的批次晚到）
 *       **当前实现会覆盖**已存的较新值——本仓库与本仓 CI 都**没有真 Redis**，做不了 Lua/CAS 的原子
 *       「读 ts 比较再写」，因此不在这里承诺做不到的事（见 ROADMAP 四点十七）。
 *       消费方义务：value 里带 {@code ts}，按它判新旧；升级为原子实现的条件与做法登记在四点十七。</li>
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
