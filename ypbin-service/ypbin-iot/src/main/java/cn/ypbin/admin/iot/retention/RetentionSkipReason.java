/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.retention;

/**
 * 保留清理「本轮未执行」的原因码（#227）。
 *
 * <p><b>为什么要区别于 {@code skipped=true}</b>：手工触发端点的价值在于「点一下知道到底发生了什么」——
 * 只回一个布尔 {@code skipped}，运维分不清「开关关了」「配置非法」「上一轮还在跑」三种含义完全不同的情况：
 * 前两者要改配置，后者只要稍后重试。故用有界的原因码显式表达，并作为**对外契约**（{@link #getCode()}
 * 是稳定标识，改文案不改它）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public enum RetentionSkipReason {

    /** 本轮已执行（未跳过）。 */
    NONE("none", "已执行"),

    /** 运维开关关闭：{@code ypbin.retention.enabled=false}，只打 WARN、不删任何数据。 */
    DISABLED("disabled", "保留清理已关闭（ypbin.retention.enabled=false）"),

    /** 保留天数非法（0/负数会让清理变成清空全表）⇒ 拒绝执行并报错，绝不清空全表。 */
    INVALID_CONFIG("invalid-config", "保留天数配置非法（必须为正数）"),

    /** 上一轮仍在执行 ⇒ 防重入跳过，**未并发跑第二轮**（稍后重试即可）。 */
    ALREADY_RUNNING("already-running", "上一轮清理仍在执行（防重入，本次未执行）"),

    /** 分布式锁被另一实例持有（#220 多节点）⇒ 跳过本轮，**未并发跑第二轮**。 */
    LOCK_HELD_BY_OTHER("lock-held-by-other", "分布式锁被其它实例持有（多节点互斥，本次未执行）");

    private final String code;

    private final String desc;

    RetentionSkipReason(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 原因码（对外契约；小写连字符，稳定标识，改文案不改它）。
     *
     * @return 原因码
     */
    public String getCode() {
        return code;
    }

    /**
     * 原因描述（面向运维的中文说明）。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }
}
