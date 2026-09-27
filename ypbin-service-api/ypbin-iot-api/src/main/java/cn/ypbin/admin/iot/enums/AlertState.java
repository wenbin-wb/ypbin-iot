/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

import java.util.Map;
import java.util.Set;

/**
 * 告警实例的**四态状态机**（设计 §2.3）。
 *
 * <pre>
 *               ┌──────────── 越界（首次）────────────┐
 *               ▼                                     │
 *         ┌───────────┐  连续 N 次 / 持续 T 秒满足   ┌──┴───────┐
 *  未越界 │  PENDING  │ ───────────────────────────► │  FIRING  │
 *         └─────┬─────┘                              └──┬───────┘
 *               │ 未满条件即回落（抖动被吸收）           │ 人工确认
 *               │ 或 pending_ttl_sec 超时               ▼
 *               ▼                                   ┌──────────┐
 *            （丢弃候选，不产生实例）                  │  ACKED   │ 仍是活动告警
 *                                                   └────┬─────┘
 *               ┌──────── 读到「合格的未越界值」 ────────┘
 *               ▼
 *         ┌────────────┐
 *         │  RESOLVED  │  终态（保留记录，不删除）
 *         └────────────┘
 * </pre>
 *
 * <p><b>{@code SUPPRESSED} 不是状态</b>（设计 §2.3）：静默是**通知侧**的判定，与判定/状态机正交。
 * 把静默做成状态会让「静默中的告警」在页面上显得像「不存在」，且静默结束后状态该回哪个值无法定义
 * ——所以本枚举刻意**只有四个值**。</p>
 *
 * <p><b>{@link #ACKED} 仍是活动告警</b>：「已确认」表达的是「我看到了，正在处理」，不是「问题没了」，
 * 因此它**不能**被当作 {@link #RESOLVED}，也不能因为确认就停止评估（恢复仍由合格的未越界值驱动）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public enum AlertState {

    /** 已越界，但尚未满足持续条件（连续 N 次 / 持续 T 秒）。抖动抑制就发生在这里。 */
    PENDING("PENDING", "待确认条件"),

    /** 满足持续条件，**活动告警**。去重键在此状态生效。 */
    FIRING("FIRING", "已触发"),

    /** 人工已确认，**但仍是活动告警**（未恢复）。不再按 repeat_interval 重复打扰，只在恢复时通知一次。 */
    ACKED("ACKED", "已确认"),

    /** 已恢复。终态；记录保留（**清除 ≠ 删除**，设计 §1.5.3）。 */
    RESOLVED("RESOLVED", "已恢复");

    /** 允许的状态转换（**穷举**：不在这张表里的转换一律拒绝，服务层与单测都用它，不各自手写 if）。 */
    private static final Map<AlertState, Set<AlertState>> ALLOWED = Map.of(
        PENDING, Set.of(FIRING),
        FIRING, Set.of(ACKED, RESOLVED),
        ACKED, Set.of(RESOLVED),
        RESOLVED, Set.of());

    private final String code;

    private final String desc;

    AlertState(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 稳定码（落库/传输用）。 */
    public String getCode() {
        return code;
    }

    /** 中文说明。 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}，不静默兜底）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static AlertState of(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (AlertState value : values()) {
            if (value.code.equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return null;
    }

    /** 是否活动（占用去重键：{@link #RESOLVED} 之外都是活动）。 */
    public boolean isActive() {
        return this != RESOLVED;
    }

    /** 是否终态（自动转换已停止）。 */
    public boolean isTerminal() {
        return this == RESOLVED;
    }

    /**
     * 是否允许转换到目标态（**唯一判据**）。
     *
     * @param target 目标态
     * @return 允许返回 {@code true}
     */
    public boolean canTransitionTo(AlertState target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /**
     * 允许的转换集合（只读；供测试穷举与文档生成）。
     *
     * @param from 来源态
     * @return 允许的目标态集合
     */
    public static Set<AlertState> allowedTargets(AlertState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
