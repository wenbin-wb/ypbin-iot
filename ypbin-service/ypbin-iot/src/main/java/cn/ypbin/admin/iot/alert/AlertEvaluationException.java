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
 * 告警评估**不可继续**时抛出的异常（设计 §2.2.4）。
 *
 * <p>它存在的意义是**把「读不到」与「不越界」在类型上分开**：评估器捕获它之后只做一件事——本轮
 * 跳过判定（不产生触发、**更不产生恢复**），并计入对应的失败计数。若没有这个类型，实现者很容易把
 * 「Redis 读失败」写成「返回空集合」，而空集合在下一行代码里就等价于「这些点位都没有越界」
 * ⇒ 会**静默地把所有活动告警判成已恢复**（设计 §2.2.4 明确禁止）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public class AlertEvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param message 原因说明（面向人，进日志）
     */
    public AlertEvaluationException(String message) {
        super(message);
    }

    /**
     * 构造（带根因，**必须保留完整堆栈**——本仓禁止吞异常）。
     *
     * @param message 原因说明
     * @param cause   根因
     */
    public AlertEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
