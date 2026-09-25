/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.event;

import lombok.Getter;
import lombok.Setter;

/**
 * 运行期事件上报结果（G6）。
 *
 * <p><b>为什么把 {@code duplicated} 单独报出来</b>：幂等去重是**正常路径**而非错误——上报方重试时
 * 服务端必须回 200 并告诉它「这条已经收过了」。若只返回「处理条数」，重试方无法区分
 * 「收到并落库」与「被去重」，也就无法安全地清理自己的重放队列。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
@Getter
@Setter
public class EventIngestResult {

    /**
     * 本次上报**被接受**（通过幂等预查、已交给数据库做幂等 upsert）的事件条数。
     *
     * <p><b>已声明的语义边界</b>：并发同键投递时，多个调用方可能各自把同一条算进自己的
     * {@code accepted}——「谁算新落库」在无锁前提下不可精确归属（各方的预查都发生在插入之前）。
     * 这类场景<b>唯一</b>可保证、也是真正要保证的不变量是「库里只有一行」，由
     * {@code uk_iot_event_log_idem} 唯一键提供，真库并发用例 {@code EventLogIngestIT} 钉住它。
     * 调用方若要严格计数，应以库中行数为准，不要把多方返回的 {@code accepted} 相加。</p>
     */
    private int accepted;

    /** 本次被幂等去重（此前已落库）的条数。 */
    private int duplicated;

    /** 因设备不存在/不属于任何租户而被丢弃的条数（服务端暴露，不静默当成成功）。 */
    private int discarded;

    /**
     * 全量构造。
     *
     * @param accepted   被接受的条数（语义与边界见 {@link #accepted} 字段的说明：
     *                   并发同键投递时多方可能各自计入同一条，唯一保证是库里只有一行）
     * @param duplicated 幂等命中去重条数
     * @param discarded  设备不可用被丢弃条数
     */
    public EventIngestResult(int accepted, int duplicated, int discarded) {
        this.accepted = accepted;
        this.duplicated = duplicated;
        this.discarded = discarded;
    }

    /** 收到的事件总条数。 */
    public int total() {
        return accepted + duplicated + discarded;
    }
}
