/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 告警规则保存请求（新增/修改共用）。
 *
 * <p><b>为什么收件人只用一个字符串字段</b>：设计 §2.1 表 A 就是 {@code notify_targets VARCHAR(512)}，
 * 而「谁是站内信用户、谁是邮箱」由 token 形态表达（含 {@code @} 的是邮箱、纯数字的是用户 ID）。
 * 这样不需要再引入第二张「收件人」表，也不需要在两个渠道间重复配置。</p>
 *
 * <p><b>为什么阈值是字符串</b>：后端全局把 Long/BigDecimal 序列化成字符串（仓内契约）。请求侧统一按
 * 字符串接收再显式解析，可以同时接受前端的 {@code 80} 与 {@code "80"}，并把「不是数字」的失败
 * 变成**一句人话报错**（用户口径 ⑤：输入即时校验 + 人话报错）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
public class AlertRuleSaveReq {

    /** 规则名（展示用）。 */
    @NotBlank(message = "规则名不能为空")
    @Size(max = 128, message = "规则名不能超过 128 个字符")
    private String ruleName;

    /** 作用域码：TENANT / PRODUCT / DEVICE / POINT。 */
    @NotBlank(message = "作用域不能为空")
    private String scopeType;

    /** 作用域产品 ID（PRODUCT/POINT 必填）。 */
    private Long scopeProductId;

    /** 作用域设备 ID（DEVICE/POINT 必填）。 */
    private Long scopeDeviceId;

    /** 级别码：INFO / WARNING / CRITICAL（不填用平台默认「警告」）。 */
    private String severity;

    /** 是否启用（不填按启用）。 */
    private Boolean enabled;

    /** 抖动抑制模式码：IMMEDIATE / CONSECUTIVE_COUNT / DURATION（不填按「连续 N 次」）。 */
    private String triggerMode;

    /** 抖动抑制参数（连续次数或持续秒数；不填用平台默认 3 次）。 */
    private Integer triggerThreshold;

    /** 候选最大挂起秒数（不填用平台默认 300s）。 */
    private Integer pendingTtlSec;

    /** 重复通知间隔秒数（不填用平台默认 1800s）。 */
    private Integer repeatIntervalSec;

    /** 规则静默窗口起（可空）。 */
    private LocalDateTime silenceStart;

    /** 规则静默窗口止（可空）。 */
    private LocalDateTime silenceEnd;

    /** 通知渠道码（逗号分隔，如 {@code INBOX,EMAIL}；不填用平台默认）。 */
    @Size(max = 64, message = "通知渠道过长")
    private String notifyChannels;

    /** 通知对象（逗号分隔：用户 ID 与邮箱混填；可空 = 回落到规则创建者/平台兜底）。 */
    @Size(max = 512, message = "通知对象不能超过 512 个字符")
    private String notifyTargets;

    /** 说明（人会读的那一句）。 */
    @Size(max = 512, message = "说明不能超过 512 个字符")
    private String description;

    /** 点位条件行：**可以为空**——空表示「设备离线/数据中断」类规则（判定复用平台断档链路）。 */
    @Valid
    private List<AlertRulePointReq> points;
}
