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

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量下发的**逐设备结果回执**（部分失败如实逐条返回，绝不整体成功/整体失败）。
 *
 * <p><b>三个计数的口径（{@code accepted + rejected == total}）</b>：</p>
 * <ul>
 *   <li>{@code total}：去重后的设备数；</li>
 *   <li>{@code accepted}：**受理成功**数（命令实例已落库，可查询/可重发；含投递到设备失败的台数）；</li>
 *   <li>{@code rejected}：**未受理**数（设备不存在/不属于本租户/已停用/物模型不可用/模板不可用/单台异常/
 *       超过「继续下发」阈值）；</li>
 *   <li>{@code delivered}：受理且投递成功的台数（{@code statusCode = sent}，{@code delivered <= accepted}）。</li>
 * </ul>
 *
 * <p>「设备离线」计入 {@code accepted} 而不计入 {@code rejected}：平台受理并未被拒，实例已落库可重发，
 * 把它算成"拒绝"会让运维误以为配置有问题。它的未送达由对应条目的 {@code ok=false} +
 * {@code errorCode=NO_SUBSCRIBER} 表达。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class CommandBatchResp {

    /** 去重后的设备总数。 */
    private Integer total;

    /** 受理成功台数（实例已创建）。 */
    private Integer accepted;

    /** 未受理台数（未创建实例）。 */
    private Integer rejected;

    /** 受理且投递成功台数（状态 {@code sent}）。 */
    private Integer delivered;

    /** 逐台结果（与去重后的入参顺序一致，便于前端一一对应展示）。 */
    private List<CommandBatchItemResult> results;
}
