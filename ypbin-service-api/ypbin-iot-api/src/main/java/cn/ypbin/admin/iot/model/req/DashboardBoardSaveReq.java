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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 组态看板保存请求（整体替换本租户配置）。
 *
 * <p><b>为什么 {@code widgets} 是 {@code List<Object>} 而不是逐字段 DTO</b>：看板组件是
 * <b>前端定义</b>的展示配置，字段集合随前端组件类型演进（当前 {@code value/gauge/line/status}，
 * 后续会增加开关、多序列折线、表格等）。若在契约层把组件类型与字段钉成封闭枚举，前端每加一种
 * 组件都要后端改代码 + 发版，两仓被迫同步升级；本接口不解释这些字段、只负责原样存取，
 * 与前端 {@code sanitizeBoardConfig} 的兼容策略一致（前端是组件字段的唯一权威）。</p>
 *
 * <p><b>为什么不是 {@code List<Map<String, Object>>}</b>：那样非对象元素（如数字、字符串）会在
 * 框架反序列化层直接失败，用户拿到的是 JSON 解析堆栈而不是人话；用 {@code List<Object>} 把
 * 元素级校验留在服务侧（{@code DashboardBoardWidgets#normalize}），报错能精确到「第 N 个组件」。</p>
 *
 * <p>组件核心字段与前端 {@code BoardWidget} 逐一同名（{@code id/type/title/deviceId/propertyId}），
 * 其余字段（{@code unit/decimals/max/min/windowMinutes} 以及前端未来新增的字段）原样保留、
 * 原样返回，不做改名与裁剪。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class DashboardBoardSaveReq {

    /** 单个租户的看板组件上限：一次整体替换的条目数上限，防单请求写入超大配置。 */
    public static final int MAX_WIDGETS = 50;

    /**
     * 组件列表（整体替换语义）。
     *
     * <p>非 null：清空看板请传空数组 {@code []}；传 {@code null} 是调用方漏字段，必须显式报错，
     * 否则一次漏传就会把租户看板清空。</p>
     */
    @NotNull(message = "组件列表不能为空（清空看板请传空数组）")
    @Size(max = MAX_WIDGETS, message = "看板组件不能超过 " + MAX_WIDGETS + " 个")
    private List<Object> widgets;
}
