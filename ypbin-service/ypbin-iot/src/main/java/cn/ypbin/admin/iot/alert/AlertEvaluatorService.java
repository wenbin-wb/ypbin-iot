/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

/**
 * 告警评估器（设计 §2.2.2 路线 C）：周期扫描「有启用规则覆盖」的设备，用 Redis 最新值做快路判定，
 * 需要「持续 N 秒」时才回查时序库。
 *
 * <p>接口与实现分开是为了让用例可以直接调一轮评估（不依赖调度器），也让
 * {@code AlertEvaluator}（{@code @Scheduled} 壳）保持只有一行。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface AlertEvaluatorService {

    /**
     * 一轮评估的汇总结果（**只用于日志、指标与用例断言**；业务事实在库里）。
     *
     * @param enabled          本轮是否真的执行了（{@code false} = 总开关关闭）
     * @param ruleLoadFailed   规则加载是否失败（失败时本轮**整体放弃**，不产生任何触发与恢复）
     * @param tenants          实际评估的租户数
     * @param failedTenants    评估失败的租户数（该租户本轮放弃，其它租户照常）
     * @param redisFailedTenants 因 Redis 读取失败而跳过判定的租户数
     * @param truncatedTenants 因单轮设备上限被截断的租户数
     * @param candidates       候选数（规则 × 点位条件 × 设备）
     * @param fired            新触发数
     * @param resolved         恢复数
     * @param queued           生成的待投递通知条数
     */
    record AlertRoundOutcome(boolean enabled, boolean ruleLoadFailed, int tenants, int failedTenants,
                             int redisFailedTenants, int truncatedTenants, int candidates, int fired,
                             int resolved, int queued) {

        /** 总开关关闭时的结果（**不是「没有告警」**：调用方必须据此返回失败）。 */
        public static AlertRoundOutcome disabled() {
            return new AlertRoundOutcome(false, false, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** 规则加载失败的结果。 */
        public static AlertRoundOutcome ruleLoadFailure() {
            return new AlertRoundOutcome(true, true, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 执行一轮评估。
     *
     * @return 汇总结果（永不为 {@code null}）
     */
    AlertRoundOutcome evaluateOnce();
}
