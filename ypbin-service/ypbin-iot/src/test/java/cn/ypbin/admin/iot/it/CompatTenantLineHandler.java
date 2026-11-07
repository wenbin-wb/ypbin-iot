/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.it;

import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.core.TenantProvider;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import java.util.List;
import net.sf.jsqlparser.schema.Column;

/**
 * 与生产 {@code TenantInsertCompatibilityConfiguration} 同语义的租户处理器（IT 专用）。
 *
 * <p>IT 不启动 Spring 容器、手工装配 MyBatis，必须显式复用生产同款处理器，否则会出现
 * 「生产已兼容、IT 仍按 4.4.0 严格语义重复追加 tenant_id」的假红。starter 4.4.1 发布后，
 * 与生产配置类一起删除。</p>
 *
 * @author wenbin
 * @since 2026-11-07
 */
final class CompatTenantLineHandler extends DefaultTenantLineHandler {

    CompatTenantLineHandler(TenantProvider tenantProvider, TenantProperties properties) {
        super(tenantProvider, properties);
    }

    @Override
    public boolean ignoreInsert(List<Column> columns, String tenantIdColumn) {
        return columns != null && columns.stream()
            .anyMatch(column -> tenantIdColumn.equalsIgnoreCase(column.getColumnName()));
    }
}
