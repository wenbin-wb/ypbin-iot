/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import cn.ypbin.admin.access.decode.ByteDecodeMetrics;
import cn.ypbin.admin.access.egress.PointValueNormalizeMetrics;
import cn.ypbin.admin.access.link.ModbusRegisterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 采集侧指标装配。
 *
 * <p>当前只有一件事：**启动期预注册物模型类型规范化失败计数器**（值 0）。理由见
 * {@link PointValueNormalizeMetrics} 的类注释——Micrometer 计数器惰性创建，不做预注册就无法把
 * 「零失败」与「指标缺失」区分开（生产上 {@code /actuator/metrics/...} 会回 404 而不是 0）。</p>
 *
 * <p>注意：帧解析/字段选择失败（非 UTF-8、无键值字段、selector 未命中）由 starter 0.3.0 的
 * {@code TcpValueSelector} 以 BAD 质量与 {@code iot.tcp.*} 指标承接，不在本类预注册范围内。</p>
 *
 * <p>为什么用 {@code ApplicationRunner} 而不是在失败路径里建 meter：本 bean 不依赖任何设备/点位，
 * 只要进程起来了就一定执行；{@code MeterRegistry} 作为方法参数保证注册表已就绪。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Configuration
public class AccessMetricsConfiguration {

    /**
     * 预注册类型规范化失败计数器（全部原因码，值 0）。
     *
     * @param meterRegistry 指标注册表
     * @return 启动运行器
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner accessValueNormalizeFailureMetricPreRegistration(
            MeterRegistry meterRegistry) {
        return args -> {
            PointValueNormalizeMetrics.registerAll(meterRegistry);
            // 宿主字节载荷解码失败：同样预注册为 0（零失败与「指标缺失」要能区分）
            ByteDecodeMetrics.registerAll(meterRegistry);
            // Modbus 半字配对陈旧计数：同样预注册为 0（零失败与「指标缺失」要能区分）
            ModbusRegisterMetrics.pairStaleCounter(meterRegistry);
        };
    }
}
