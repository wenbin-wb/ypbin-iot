/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import lombok.Getter;
import lombok.Setter;

/**
 * 批量下发的**单台设备**结果。
 *
 * <p><b>为什么 {@code accepted} 与 {@code ok} 是两个字段</b>：一次下发有两件事会各自失败——
 * ①「平台是否受理」（落库成命令实例，可查询、可重发）；②「设备是否真的收到」（EMQX 投递后实例是否为
 * {@code sent}）。设备离线（{@code failed/NO_SUBSCRIBER}）属于"已受理但未送达"：它不是平台拒绝，
 * 实例已落库、运维可以重发，因此必须能与"根本没受理"（设备不存在/停用/物模型不可写）区分开——
 * 否则运维不知道该去修网络还是去修配置。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class CommandBatchItemResult {

    /** 设备主键。 */
    private Long deviceId;

    /** 是否受理成功（命令实例已创建；无论投递结果如何，都可通过 requestId 查询/重发）。 */
    private Boolean accepted;

    /** 是否投递成功（实例状态为 {@code sent}）。{@code false} 时看 {@link #requestId}/{@link #code} 分支。 */
    private Boolean ok;

    /** 命令实例请求 ID（受理成功时必有；前端据此跳转详情或重发）。 */
    private String requestId;

    /** 命令实例状态码（受理成功时必有：{@code pending|sent|failed}）。 */
    private String statusCode;

    /** 命令实例失败原因码（{@code NO_SUBSCRIBER|EMQX_ERROR|…}；成功时为 {@code null}）。 */
    private String errorCode;

    /**
     * 未受理原因码（受理成功时为 {@code null}）。
     *
     * <p>取值如 {@code DEVICE_NOT_FOUND} / {@code DEVICE_DISABLED} /
     * {@code DEVICE_WITHOUT_PRODUCT} / {@code THING_MODEL_EMPTY} / {@code THING_MODEL_LOAD_FAILED} /
     * {@code COMMAND_REJECTED} / {@code BATCH_BUDGET_EXCEEDED} / {@code INTERNAL_ERROR}。</p>
     */
    private String code;

    /** 人话说明（未受理原因、或投递结果说明；前端直接展示给运维）。 */
    private String message;
}
