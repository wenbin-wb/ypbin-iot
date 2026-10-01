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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 下发命令/属性设置的请求（设计 §7.4，评审确认版）。
 *
 * <p><b>payload 契约（评审确认 2026-09-27）</b>：</p>
 * <ul>
 *   <li>{@code property_set}：{@code identifier} = 单个属性标识（必填，须为该产品可写属性），
 *       {@code params} 形如 {@code {"value":25.0}} ⇒ 平台生成下行 payload
 *       {@code {"requestId":…,"properties":{"temperature":25.0}}}；</li>
 *   <li>{@code property_get}：{@code identifier} 可空（空 = 读该设备**全部可读属性**），
 *       {@code params} 忽略 ⇒ payload {@code {"requestId":…,"properties":["temperature"]}}（空则 {@code []}）；</li>
 *   <li>{@code service_call}：{@code identifier} = 物模型命令标识（必填，须在该产品物模型内），
 *       {@code params} = 服务入参对象 ⇒ payload {@code {"requestId":…,"params":{…}}}。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Getter
@Setter
public class CommandSendReq {

    /** 超时上限（毫秒；1 小时。下行命令等到 1 小时才判失败基本已无意义）。 */
    public static final int MAX_TIMEOUT_MS = 60 * 60 * 1000;

    /** 类型码（{@code property_set|property_get|service_call}）。 */
    @NotBlank(message = "命令类型不能为空")
    private String kind;

    /** 目标标识（属性标识或命令标识；{@code property_get} 可空 = 全部可读属性）。 */
    private String identifier;

    /**
     * 参数（**JSON 对象**；{@code property_set} 需含 {@code value}，{@code service_call} 为服务入参）。
     *
     * <p><b>为什么是 {@code Object} 而不是 {@code String}</b>：契约里的 {@code params} 是 JSON **对象**
     * （设计 §7.6 B2 的 payload 就是 {@code {"requestId":…,"params":{…}}}）。若 Java 侧声明成 {@code String}，
     * 客户端发对象会被 Jackson 拒成**系统异常（R.code=500）**——生产端到端实测踩到过（验收脚本发的就是对象）。
     * 声明成 {@code Object} 后由服务层统一序列化并**显式校验必须是对象**（字符串/数组给出明确业务错误）。</p>
     */
    private Object params;

    /** 超时（毫秒；可空 = 用全局默认，见 {@code ypbin.emqx.default-command-timeout-ms}）。 */
    @Min(value = 1, message = "超时必须为正数")
    @Max(value = MAX_TIMEOUT_MS, message = "超时超过上限")
    private Integer timeoutMs;

    /**
     * 是否同时写入影子 {@code desired}。
     *
     * <p><b>本轮不支持</b>：置 {@code true} 会被**显式拒绝**（不是静默忽略），并指引改用既有
     * {@code PUT /devices/{id}/shadow}（设计 §7.3 ① 已注明该能力既有）。</p>
     */
    private Boolean writeDesired;

    /**
     * 客户端幂等键（看板 #11 O-7 C2；可选）。
     *
     * <p>同一租户+同一设备下重复提交同键 ⇒ 返回已有实例、<b>不二次下发</b>（形态见
     * {@code RequestIdRules}：字母/数字/下划线/点/冒号/连字符，1~64 字符；非法直接拒绝，
     * 不截断——截断会把两个不同的键压成同一个，造成"以为去重、实际串单"）。
     * 为空 ⇒ 每次调用都是独立命令（平台生成 requestId）。</p>
     */
    private String clientRequestId;
}
