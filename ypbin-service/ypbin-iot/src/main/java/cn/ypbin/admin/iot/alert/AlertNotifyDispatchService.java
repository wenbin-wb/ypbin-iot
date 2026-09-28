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
 * 通知投递编排（设计 §2.4 投递模型）：扫描到期的投递记录、按租户进入上下文投递、按结果推进状态。
 *
 * <p><b>「延后不丢弃」</b>：被全局限流拦下的通知保持原状态、只把 {@code next_retry_ts} 推到下一个时间窗，
 * 后续轮次仍会投递它。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface AlertNotifyDispatchService {

    /**
     * 一轮投递的汇总结果。
     *
     * @param enabled   告警总开关是否打开（{@code false} ⇒ 未执行）
     * @param skipped   是否因「通知投递开关」关闭而跳过
     * @param due       本轮到期条数
     * @param sent      成功条数
     * @param failed    失败且尚可重试条数
     * @param givenUp   重试耗尽放弃条数
     * @param throttled 被限流**延后**条数
     * @param tenants   本轮涉及的租户数
     */
    record DispatchOutcome(boolean enabled, boolean skipped, int due, int sent, int failed, int givenUp,
                           int throttled, int tenants) {

        /** 未执行。 */
        public static DispatchOutcome skippedOutcome(boolean enabled) {
            return new DispatchOutcome(enabled, true, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 执行一轮投递。
     *
     * @return 汇总结果（永不为 {@code null}）
     */
    DispatchOutcome dispatchOnce();
}
