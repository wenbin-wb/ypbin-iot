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
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 组态看板配置响应（{@code GET /dashboard/board}）。
 *
 * <p><b>字段契约（与前端 {@code board-utils.ts} 的 {@code BoardWidget} 逐一同名，不得改名）</b>：
 * 每个元素至少含 {@code id}、{@code type}、{@code title}、{@code deviceId}、{@code propertyId}
 * 五个字符串字段；{@code type} 当前取值 {@code value/gauge/line/status}，前端是组件类型的唯一权威
 * （后端不封闭枚举，前端加新类型无需后端发版）。其余字段（{@code unit/decimals/max/min/windowMinutes}
 * 以及前端未来新增字段）按写入时的原文返回。</p>
 *
 * <p><b>无配置时返回空数组，永不为 null</b>：前端 {@code sanitizeBoardConfig} 把
 * {@code widgets: null} 视为结构不兼容并重置看板，返回 null 会让「刚上线、还没人配置」的租户
 * 每次打开看板都被判定为「配置损坏」。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class DashboardBoardResp {

    /** 组件列表；没有配置时为空数组（永不为 null）。 */
    private List<Map<String, Object>> widgets = List.of();
}
