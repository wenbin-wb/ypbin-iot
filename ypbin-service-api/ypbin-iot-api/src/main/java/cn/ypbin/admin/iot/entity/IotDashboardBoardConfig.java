/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.tenant.core.TenantBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 组态看板配置（每租户一行）。
 *
 * <p>看板组件此前只存在浏览器 {@code localStorage}，换设备/换浏览器即丢；本表把配置提升为
 * **租户级共享事实**：同一租户内任意用户看到的看板一致，也为后续「按角色/按用户多份看板」打基础。</p>
 *
 * <p><b>为什么只有一个 {@code config_json}</b>：组件集合由前端定义且会持续演进（当前
 * value/gauge/line/status，后续会加开关、多序列折线、表格等）。拆成列等于把前端的展示配置
 * 钉进后端表结构，前端每加一种组件都要改库、发版；存 JSON 文本则让「后端只负责按租户存取、
 * 前端负责解释字段」这一边界保持稳定。校验在写入时完成（见
 * {@code cn.ypbin.admin.iot.service.impl.DashboardBoardWidgets}）：结构、必填、上限、去重。</p>
 *
 * <p><b>为什么唯一键是 tenant_id</b>：接口是「整体替换本租户配置」语义，每租户只应有一行；
 * 应用层「先查后写」在并发首写下存在竞态，唯一键 {@code uk_iot_dashboard_board_config(tenant_id)}
 * 让「每租户一份」成为库级事实（冲突时回退为更新）。</p>
 *
 * <p>继承 {@link TenantBaseEntity} ⇒ 落库自动带 {@code tenant_id}，查询由租户插件自动追加条件，
 * 业务代码不手写租户过滤（与 {@code iot_device} 同口径）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
@TableName("iot_dashboard_board_config")
public class IotDashboardBoardConfig extends TenantBaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 看板组件配置：{@code widgets} 数组的 JSON 文本（空看板存 {@code []}，永不为 null）。 */
    private String configJson;
}
