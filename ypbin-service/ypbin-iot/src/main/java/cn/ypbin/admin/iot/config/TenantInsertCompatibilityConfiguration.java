/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.config;

import cn.ypbin.starter.data.core.InnerInterceptorProvider;
import cn.ypbin.starter.tenant.autoconfigure.TenantProperties;
import cn.ypbin.starter.tenant.core.TenantProvider;
import cn.ypbin.starter.tenant.handler.DefaultTenantLineHandler;
import cn.ypbin.starter.tenant.handler.StrictTenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.List;
import net.sf.jsqlparser.schema.Column;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * starter 4.4.0 租户 INSERT 的临时兼容层（**4.4.1 正式发布后删除**）。
 *
 * <p><b>为什么需要</b>：4.4.0 起 {@link DefaultTenantLineHandler#ignoreInsert} 恒返回 {@code false}，
 * 只要实体或自定义批量 SQL 在 INSERT 时显式带上 {@code tenant_id}（本仓多处
 * {@code row.setTenantId(...)} 的跨租户/后台写入），租户插件会再追加一次同名列，数据库报
 * {@code Column 'tenant_id' specified twice}，正常写入全部失败（集成测试已复现）。
 * starter 4.4.1 已改为「显式值与上下文一致则不再追加」，但 4.4.1 因 Central 月度文件数配额封锁
 * 暂时无法发布，故在本仓做等价兼容。</p>
 *
 * <p><b>做了什么</b>：按 Bean 名覆盖 starter 的 {@code tenantInnerInterceptorProvider}（starter 侧带
 * {@code @ConditionalOnMissingBean(name = ...)}，宿主 Bean 优先生效），把 {@code ignoreInsert} 恢复为
 * 「SQL 已显式包含租户列时不再追加」的 4.3.x 语义。查询、UPDATE/DELETE 的严格校验（含 WHERE 子查询与
 * 禁止改租户列）完全不变。</p>
 *
 * <p><b>安全性评估</b>：4.4.0 收紧 INSERT 的目的是防止「请求体绑定 {@code TenantBaseEntity.tenantId}
 * 写任意租户」。本仓与 admin 的 Controller 只接收 {@code *Req}/{@code *Dto}，不存在直接绑定实体的入口
 * （{@code @RequestBody} 类型清单已核对），实体上的租户值均由服务端按上下文/行数据显式赋值，
 * 故恢复旧语义不引入可被外部利用的越权面。</p>
 *
 * <p><b>移除条件</b>：starter 4.4.1 发布后，删除本类并把版本升到 4.4.1。</p>
 *
 * @author wenbin
 * @since 2026-11-07
 */
@Configuration(proxyBeanMethods = false)
public class TenantInsertCompatibilityConfiguration {

    @Bean(name = "tenantInnerInterceptorProvider")
    InnerInterceptorProvider tenantInsertCompatibilityProvider(TenantProvider tenantProvider,
                                                               TenantProperties properties) {
        DefaultTenantLineHandler handler = new DefaultTenantLineHandler(tenantProvider, properties) {
            @Override
            public boolean ignoreInsert(List<Column> columns, String tenantIdColumn) {
                return columns != null && columns.stream()
                    .anyMatch(column -> tenantIdColumn.equalsIgnoreCase(column.getColumnName()));
            }
        };
        return new InnerInterceptorProvider() {
            @Override
            public InnerInterceptor getInnerInterceptor() {
                return new StrictTenantLineInnerInterceptor(handler);
            }

            @Override
            public int getOrder() {
                return ORDER_TENANT;
            }
        };
    }
}
