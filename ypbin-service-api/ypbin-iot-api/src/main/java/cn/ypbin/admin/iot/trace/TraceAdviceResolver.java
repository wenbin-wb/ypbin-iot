/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.trace;

import cn.ypbin.admin.iot.enums.CommandErrorCode;
import java.util.List;

/**
 * 「定位建议」规则引擎（设计 `docs/MESSAGE-TRACE-DESIGN.md` §4）——**纯函数，零 IO**。
 *
 * <p><b>三条设计原则（照设计 §4.1 落地）</b>：</p>
 * <ol>
 *   <li><b>只从结构化字段推导</b>：判据一律是既有的**枚举码**
 *       （{@link CommandErrorCode}），**不做日志文本挖掘**（脆弱且不可测）。</li>
 *   <li><b>建议必须是可执行动作</b>：反例「命令超时」✗；正例给出"先确认设备在线 → 再核对订阅
 *       topic → 用『在线调试』重发对照"。</li>
 *   <li><b>不匹配就返回 `null`</b>：前端显示「暂无定位建议」+ **原始错误码**。
 *       **绝不硬凑**——错误建议比没有建议更糟（会把排障引向错误方向）。</li>
 * </ol>
 *
 * <p><b>为什么不查库、不调服务</b>：纯函数才能被穷举单测（每条规则一正一反），
 * 也才能在"规则改坏"时用变异验证抓到。任何 IO 都会让这两件事做不到。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class TraceAdviceResolver {

    private TraceAdviceResolver() {
    }

    /**
     * 解析一条时间线条目的定位建议。
     *
     * @param item 条目（其 `outcome`/`stage`/`errorCode` 为判据来源）
     * @return 建议；**无匹配规则时返回 `null`**（前端须同时显示原始错误码）
     */
    public static TraceAdvice resolve(TraceItem item) {
        if (item == null || item.outcome() == null || !item.outcome().needsAdvice()) {
            return null;
        }
        return switch (item.stage()) {
            case DOWN_ENQUEUED, DOWN_PUBLISHED, DOWN_ACK -> resolveCommand(item);
            // 上行受理本身是"成功"的语义（能出现在时间线上就说明已受理）；
            // 一期没有"被丢弃/未落库"的痕迹可用（设计 §3.3.1/§3.3.2）⇒ 不给建议，也不编。
            case UP_RECEIVED -> null;
            case EVENT_REPORTED -> null;
            case DEVICE_OFFLINE -> offlineAdvice();
        };
    }

    /**
     * 命令类条目的建议（判据优先取 {@link CommandErrorCode} 的码）。
     *
     * <p>🔴 <b>为什么优先看归因码而不是状态码</b>：设计 §3.3.2b 指出 `status_code = failed` 有
     * **两个来源**——publish 阶段失败（**根本没送到设备**）与设备回执失败（**设备收到了并拒绝**）。
     * 只看状态码会把前者误报成后者，把用户引向错误方向。归因码（`NO_SUBSCRIBER` vs
     * `DEVICE_REJECTED`）才是可区分的判据。</p>
     *
     * @param item 条目
     * @return 建议；无匹配返回 `null`
     */
    private static TraceAdvice resolveCommand(TraceItem item) {
        CommandErrorCode errorCode = parseErrorCode(item.errorCode());
        if (errorCode != null) {
            return switch (errorCode) {
                case NO_SUBSCRIBER -> new TraceAdvice("CMD_NO_SUBSCRIBER",
                    "设备当前未连接（EMQX 报告无订阅者），命令没有送到设备。",
                    List.of(
                        "先看本页的『设备离线』条目，确认下发时设备是否在线；",
                        "确认设备订阅的下行主题是 {productKey}/{deviceCode}/cmd/down（下发靠主题定向）；",
                        "设备恢复在线后用『在线调试』重发这条命令。"));
                case EMQX_ERROR -> new TraceAdvice("CMD_EMQX_ERROR",
                    "平台侧投递失败（EMQX 不可达/被拒/鉴权失败），与设备无关。",
                    List.of(
                        "这是平台侧问题：检查 EMQX 服务状态与平台到 EMQX 的网络；",
                        "看平台指标里 EMQX 调用失败计数是否同时在涨；",
                        "恢复后重发这条命令即可（设备侧无感知）。"));
                case DEVICE_REJECTED -> new TraceAdvice("CMD_DEVICE_REJECTED",
                    "设备收到了命令但明确拒绝执行。",
                    List.of(
                        "展开本条明细看设备回执原文，里面通常带设备给出的拒绝原因；",
                        "核对命令参数是否符合物模型定义（类型/取值范围/枚举）；",
                        "确认设备当前状态是否允许执行该命令（例如设备处于故障态）。"));
                case TIMEOUT -> new TraceAdvice("CMD_TIMEOUT",
                    "在超时时间内没有收到设备回执。",
                    List.of(
                        "先看本页在超时时刻附近是否有『设备离线』条目，排除设备不在线这一根因；",
                        "用『在线调试』重发一次做对照：若重发成功，说明是偶发链路问题；",
                        "若持续超时，核对设备订阅的主题与设备侧处理耗时。"));
                case DEVICE_OFFLINE -> new TraceAdvice("CMD_DEVICE_OFFLINE",
                    "下发时设备处于离线状态。",
                    List.of(
                        "先恢复设备连接（检查供电与网络）；",
                        "设备上线后重发这条命令。"));
            };
        }
        // 没有归因码 ⇒ 只能按结果大类给"降级建议"，并明确告知"平台未归因"这件事本身
        if (item.outcome() == TraceOutcome.TIMEOUT) {
            return new TraceAdvice("CMD_TIMEOUT_UNATTRIBUTED",
                "在超时时间内没有收到设备回执（平台未记录更细的失败原因）。",
                List.of(
                    "先看本页在超时时刻附近是否有『设备离线』条目，排除设备不在线这一根因；",
                    "用『在线调试』重发一次做对照；",
                    "若持续超时，核对设备订阅的主题与设备侧处理耗时。"));
        }
        if (item.outcome() == TraceOutcome.FAILED) {
            return new TraceAdvice("CMD_FAILED_UNATTRIBUTED",
                "命令失败，但平台没有记录可区分的失败原因（这本身值得关注）。",
                List.of(
                    "凭本条的命令标识（requestId）查应用日志，定位当时的失败点；",
                    "若此类失败反复出现，请反馈平台侧补归因码。"));
        }
        return null;
    }

    /**
     * 设备离线条目的建议。
     *
     * @return 建议
     */
    private static TraceAdvice offlineAdvice() {
        return new TraceAdvice("DEVICE_OFFLINE_WINDOW",
            "该时段设备处于离线/断档状态。",
            List.of(
                "先排除『设备离线』这一根因，再看同一时间窗内其它失败条目；",
                "确认设备供电与网络；",
                "若离线频繁发生，检查设备侧心跳/保活配置。"));
    }

    /**
     * 把存库的错误码字符串解析为 {@link CommandErrorCode}。
     *
     * <p><b>找不到时返回 `null` 而不是抛异常</b>：错误码是**外部输入**（存库的历史数据，
     * 可能来自更早/更新的版本），抛出会让整条时间线查询失败——用户会因为一个不认识的码
     * 而看不到任何消息。返回 `null` 时调用方走"未归因"分支，**用户仍能看到原始错误码**。</p>
     *
     * @param code 存库的码（可为 `null`）
     * @return 枚举；未知返回 `null`
     */
    private static CommandErrorCode parseErrorCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (CommandErrorCode candidate : CommandErrorCode.values()) {
            if (candidate.getCode().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
