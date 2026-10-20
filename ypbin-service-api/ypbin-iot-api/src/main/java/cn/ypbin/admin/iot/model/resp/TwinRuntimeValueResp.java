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
 * 运行态单个属性的最新值。
 *
 * <p><b>语义边界</b>：{@code value}/{@code quality}/{@code ts} 三者同时为 null 表示
 * 「该属性从未上报」，不是 0、不是错误、也不是设备离线。页面必须把它渲染成「--」而不是 0，
 * 否则运维会把「没有数据」当成「读数为 0」——这是这类大屏最危险的一种假数据。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class TwinRuntimeValueResp {

    /** 读数原值（字符串化；写入侧原值为 null 时此处为 null）。 */
    private String value;

    /** 质量码（写入侧未给时为 null）。 */
    private String quality;

    /** 读数时刻（epoch 毫秒，Long 全局序列化为字符串）。 */
    private Long ts;

    /** 该绑定是否已失效（属性在物模型里已不存在）。 */
    private Boolean stale;
}
