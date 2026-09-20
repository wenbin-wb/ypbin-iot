/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot;

import cn.ypbin.admin.common.config.InternalProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * IoT 业务服务启动类（admin 基座上的第 3 个业务域）。
 *
 * <p>与其它业务域同构：独立部署单元、共享 admin 的鉴权/租户上下文/权限体系；
 * 端口 18084（gateway 18080 / auth 18081 / system 18082 / ai 18083）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@EnableFeignClients(basePackages = "cn.ypbin.admin.system.api.feign")
@EnableConfigurationProperties(InternalProperties.class)
@MapperScan("cn.ypbin.admin.iot.mapper")
@SpringBootApplication(scanBasePackages = {"cn.ypbin.admin.iot", "cn.ypbin.admin.system.api"})
public class IotServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IotServiceApplication.class, args);
    }
}
