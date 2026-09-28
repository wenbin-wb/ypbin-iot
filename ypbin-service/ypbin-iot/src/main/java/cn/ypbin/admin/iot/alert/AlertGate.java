/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import org.springframework.stereotype.Component;

/**
 * 告警能力的**总开关门禁**（设计 §3.6.2）。
 *
 * <p>🔴 本类存在的唯一理由是那条最容易做错的口径：{@code ypbin.alert.enabled=false} 时接口必须
 * **返回失败而不是空列表**。空列表会被前端（以及任何调用方）读成「没有告警」——
 * 那是**最危险的一种假阴性**：平台已经不具备告警能力，而使用者以为一切正常。
 * 这与本仓在时序查询上的既有口径同源（{@code TimeSeriesQueryService} 在库不可用时抛异常而不是
 * 返回空列表，注释原话「空列表会被读成『这段时间没数据』」）。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertGate {

    private final AlertProperties properties;

    public AlertGate(AlertProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验**写操作**可用：能力已启用 **且** 当前身份有租户上下文。
     *
     * <p>为什么写操作要单独校租户：MyBatis-Plus 的租户插件在租户上下文缺失时会给 INSERT **跳过**
     * {@code tenant_id} 列（对 SELECT 则是跳过租户条件），而四张告警表的 {@code tenant_id} 是
     * {@code NOT NULL} ⇒ 数据库以 {@code Column 'tenant_id' cannot be null} 拒绝，最终对用户表现为
     * **裸 500「系统内部错误」**（生产实测过这一形态）。这里提前拦下并给出**人话**，符合本仓
     * 「HTTP 200 + {@code R.code} 信封」的惯例。</p>
     *
     * <p>读操作**不**做此校验：平台身份在无租户上下文时按设计可跨租户只读（插件跳过条件），
     * 若一并拒绝会改变既有读行为。</p>
     */
    public void requireWritable() {
        requireEnabled();
        if (TenantContext.getTenantId().isEmpty()) {
            throw new BusinessException("当前登录身份没有租户上下文，无法保存："
                + "请先在右上角选择/切换到你管理的租户（平台管理员需要指定租户后才能配置该租户的告警）");
        }
    }

    /**
     * 校验告警能力已启用。
     *
     * @throws BusinessException 未启用时抛出（**明确失败，不返回空**）
     */
    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException("告警能力未启用（" + AlertProperties.PREFIX
                + ".enabled=false）：接口不会返回空结果，因为空结果会被误读成「没有告警」；"
                + "如需启用请联系平台管理员打开该开关");
        }
    }
}
