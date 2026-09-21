/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 设备接入单元（第 6 个部署单元，端口 18086）。
 *
 * <p>与其它业务域不同：access 是**有状态**的（持有长连接/会话），因此独立部署、按连接数扩容；
 * 它不直接连库，租约与归属通过 iot 服务的 /internal/lease/** 获取（见 docs/LEASE.md）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@EnableScheduling
@EnableFeignClients(basePackages = {"cn.ypbin.admin.iot.lease", "cn.ypbin.admin.iot.device"})
@SpringBootApplication(scanBasePackages = {"cn.ypbin.admin.access", "cn.ypbin.admin.iot.lease"})
public class AccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }
}
