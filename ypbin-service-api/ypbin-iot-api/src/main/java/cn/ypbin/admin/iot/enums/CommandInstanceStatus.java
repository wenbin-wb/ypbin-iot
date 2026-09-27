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
 * 运行期命令实例的状态机（设计 §7.2，**六态**）。
 *
 * <pre>
 *   pending ──► sent ──► succeeded
 *      │          │──► failed ──┐
 *      │          │──► timeout ─┤ 人工重发（同 requestId）
 *      │          └──► cancelled │
 *      └──► failed / timeout / cancelled
 *              ▲          ▲
 *              └──────────┴── 重发把 failed/timeout 重新打开为 sent（retry_count+1）
 * </pre>
 *
 * <p><b>"终态"的确切含义</b>：终态是「**自动转换**已停止」，不是「不可再变」——设计 §7.2 明确要求
 * 「不自动重试，只由人在页面上手动重发同一条实例（requestId 不变）」⇒ {@link #FAILED}/{@link #TIMEOUT}
 * 可被人工重新打开为 {@link #SENT}。{@link #SUCCEEDED}/{@link #CANCELLED} 不在此列（成功不该重发、
 * 取消是人的终止意图）。</p>
 *
 * <p><b>为什么 {@link #PENDING} 也能直接到 succeeded/failed</b>（与设计示意图相比是**有意放宽**）：
 * 平台"插入 pending → 投递 → 改 sent"之间有一个真实窗口，设备可能在平台改状态**之前**就回执
 * （本机实测回执可 <100ms 到达）。回执路径因此接受 pending/sent 两个来源态（CAS 更新），
 * 否则这条回执会被当成"重复"丢弃、命令随后被判超时——那是**假失败**。放宽只影响"接受哪个来源态"，
 * 不改变终态语义。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
public enum CommandInstanceStatus {

    /** 平台已受理、尚未投递到 EMQX。 */
    PENDING("pending", "待投递"),

    /** 已投递到 EMQX（publish 返回 200 = 至少一个订阅者）。 */
    SENT("sent", "已投递"),

    /** 设备回执成功（回执 {@code code == 0}）。 */
    SUCCEEDED("succeeded", "成功"),

    /** 失败（无订阅者 / EMQX 错误 / 设备回执失败）。 */
    FAILED("failed", "失败"),

    /** 等待回执超时（周期扫描判定；**不自动重试**）。 */
    TIMEOUT("timeout", "超时"),

    /** 人工取消。 */
    CANCELLED("cancelled", "已取消");

    /** 允许的状态转换（**穷举**：不在这张表里的转换一律拒绝，见 {@link #canTransitionTo}）。 */
    private static final Map<CommandInstanceStatus, Set<CommandInstanceStatus>> ALLOWED = Map.of(
        PENDING, Set.of(SENT, SUCCEEDED, FAILED, TIMEOUT, CANCELLED),
        SENT, Set.of(SUCCEEDED, FAILED, TIMEOUT, CANCELLED),
        SUCCEEDED, Set.of(),
        FAILED, Set.of(SENT),
        TIMEOUT, Set.of(SENT),
        CANCELLED, Set.of());

    /** 可被人工重发的来源态（设计 §7.2：只有失败与超时允许人工重发）。 */
    private static final Set<CommandInstanceStatus> RESENDABLE = Set.of(FAILED, TIMEOUT);

    /**
     * 终态集合（**显式列出**，而不是"没有出边就算终态"）。
     *
     * <p>两者不等价：{@link #FAILED}/{@link #TIMEOUT} 仍有一条出边（人工重发回 {@link #SENT}），
     * 但它们的**自动流程已停止**——这正是"终态"要表达的意思。用「没有出边」当判据会把 {@code failed}
     * 算成非终态，前端会把一条已失败的命令当成"还在跑"。</p>
     */
    private static final Set<CommandInstanceStatus> TERMINAL = Set.of(SUCCEEDED, FAILED, TIMEOUT,
        CANCELLED);

    private final String code;

    private final String desc;

    CommandInstanceStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 状态码（**存库与接口一律用它，绝不用 ordinal**）。
     *
     * @return 码
     */
    public String getCode() {
        return code;
    }

    /**
     * 说明。
     *
     * @return 说明
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 自动转换是否已停止（终态；{@link #FAILED}/{@link #TIMEOUT} 仍是终态——它们只允许**人工**重发）。
     *
     * @return 终态返回 {@code true}
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 是否允许转换到目标态（**唯一判据**，服务层与单测都用它，不各自手写 if）。
     *
     * @param target 目标态
     * @return 允许返回 {@code true}
     */
    public boolean canTransitionTo(CommandInstanceStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /**
     * 是否可人工重发（失败/超时）。
     *
     * @return 可重发返回 {@code true}
     */
    public boolean isResendable() {
        return RESENDABLE.contains(this);
    }

    /**
     * 按码解析（忽略大小写；未知返回 {@code null}）。
     *
     * @param code 码
     * @return 枚举；未知返回 {@code null}
     */
    public static CommandInstanceStatus ofCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        for (CommandInstanceStatus status : values()) {
            if (status.code.equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 允许的转换集合（只读；供测试穷举与文档生成）。
     *
     * @param from 来源态
     * @return 允许的目标态集合
     */
    public static Set<CommandInstanceStatus> allowedTargets(CommandInstanceStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
