/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mqtt;

/**
 * MQTT 入站整批拒绝（承载原因码，供控制器转成**真 HTTP 4xx**）。
 *
 * <p><b>为什么用异常而不是返回值</b>：入站的校验点分散在「结构 → 逐条字段 → 点位映射」三处，
 * 每一处都必须**在动库之前**整批拒绝（设计 D2 附带要求 3）。用返回值表达会让每个调用点都要
 * 手动向上传递失败；用异常能让「拒绝 = 抛」这件事在调用链上不可忽略。</p>
 *
 * <p><b>为什么继承 {@code RuntimeException} 却不让它落到全局异常处理器</b>：本仓全局异常处理器
 * 会把异常统一转成 HTTP 200 + {@code R.code}，那正是入站链路要避免的静默丢数据形态（设计 §6.5）。
 * 因此控制器**必须**捕获本异常并自行写原始状态码——捕获点就在 {@code InternalMqttReadingController}
 * 里，只有一处，且不经过 {@code @RestControllerAdvice}。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public class MqttIngestRejectionException extends RuntimeException {

    /** 拒绝原因码（进响应体）。 */
    private final transient MqttIngestRejectReason reason;

    /**
     * 构造拒绝异常。
     *
     * @param reason  原因码
     * @param message 细节（仅进服务端日志，**不得**含凭据/输入原文）
     */
    public MqttIngestRejectionException(MqttIngestRejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 原因码。
     *
     * @return 原因码
     */
    public MqttIngestRejectReason getReason() {
        return reason;
    }
}
