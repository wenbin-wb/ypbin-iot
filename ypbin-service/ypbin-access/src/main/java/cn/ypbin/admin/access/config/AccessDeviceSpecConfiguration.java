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

import cn.ypbin.admin.access.link.DeviceSpecSource;
import cn.ypbin.admin.access.link.HttpDeviceSpecSource;
import cn.ypbin.admin.iot.device.IDeviceSpecClient;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 取数实现装配：把内部接口客户端接成 {@link DeviceSpecSource}。
 *
 * <p>必须在 {@link AccessIotProtocolConfiguration} <b>之前</b>注册（见 AutoConfiguration.imports 顺序）：
 * 后者用 {@code @ConditionalOnBean(DeviceSpecSource.class)} 判定是否启用协议栈，
 * 而 {@code @ConditionalOnBean} 只能看到**先前**自动配置注册的 Bean。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@AutoConfiguration(before = AccessIotProtocolConfiguration.class)
public class AccessDeviceSpecConfiguration {

    /**
     * 经内部接口取设备与点位规格。
     *
     * @param client       设备规格内部客户端
     * @param objectMapper 点位清单序列化
     * @return 取数实现
     */
    @Bean
    @ConditionalOnMissingBean
    public DeviceSpecSource httpDeviceSpecSource(IDeviceSpecClient client, ObjectMapper objectMapper) {
        return new HttpDeviceSpecSource(client, objectMapper);
    }
}
