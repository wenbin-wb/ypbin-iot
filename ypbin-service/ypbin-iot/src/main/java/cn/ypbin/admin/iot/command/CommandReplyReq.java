/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备命令回执（{@code up/reply} 的载荷；设计 §7.1 契约，评审确认版）。
 *
 * <pre>{@code {"deviceId":9300012,"requestId":"…","code":0,"message":"ok","data":{…},"ts":1758768000000}}</pre>
 *
 * <p><b>成功码口径（评审确认）</b>：{@code code == 0} 判成功，非 0 判失败，并把设备给的
 * {@code code}/{@code message} **原样保存**（不要只存一句"失败"）。{@code data} 是服务的输出，
 * 形态由物模型定义决定，平台**原样存储不解析**。{@code ts} 是设备时间，平台另外记自己的落库时间。</p>
 *
 * <p><b>不信任报文</b>：{@code deviceId} 只用于**反查租户**（不是身份声明），{@code requestId} 必须通过
 * 平台的 requestId 形态校验；回执不能指定租户。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Getter
@Setter
public class CommandReplyReq {

    /** 设备 ID（用于反查租户；与实例上的 device_id 必须一致）。 */
    @NotNull(message = "设备 ID 不能为空")
    private Long deviceId;

    /** 请求 ID（平台下发的幂等键）。 */
    @NotBlank(message = "请求 ID 不能为空")
    private String requestId;

    /** 结果码（{@code 0} = 成功；非 0 = 失败，原样保留）。 */
    @NotNull(message = "结果码不能为空")
    private Integer code;

    /** 结果说明（失败时原样保留进 {@code error_msg}）。 */
    private String message;

    /**
     * 服务输出（任意 JSON；平台**原样存储、不解析**）。
     *
     * <p>同样用 {@code Object}：设备回执里的 {@code data} 通常是 JSON **对象**，声明成 {@code String}
     * 会把回执拒成系统异常（生产端到端实测踩到）。</p>
     */
    private Object data;

    /** 设备时间（epoch 毫秒；平台另记落库时间）。 */
    private Long ts;
}
