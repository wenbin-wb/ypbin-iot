/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.command;

import cn.ypbin.admin.iot.service.CommandInstanceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 命令超时扫描（周期、批量；**不自动重试**）。
 *
 * <p>判据与周期都是具名常量 + 可配（{@code ypbin.emqx.default-command-timeout-ms} 对应每条实例的超时，
 * {@code ypbin.emqx.command-scan-interval-ms} 是本扫描周期，{@code ...-batch-size} 是单轮上限）。
 * <b>不做自动重试</b>：MQTT 设备的属性写/服务调用不保证幂等，自动重试会把"设备已执行但回执丢了"
 * 变成"执行两次"（设计 §7.2）——重发是人在页面上的动作（同 requestId）。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Component
public class CommandTimeoutScanner {

    private final CommandInstanceService commandInstanceService;

    /**
     * 构造扫描器。
     *
     * @param commandInstanceService 命令实例服务
     */
    public CommandTimeoutScanner(CommandInstanceService commandInstanceService) {
        this.commandInstanceService = commandInstanceService;
    }

    /**
     * 周期扫描（间隔由 {@code ypbin.emqx.command-scan-interval-ms} 控制，默认 15s）。
     */
    @Scheduled(fixedDelayString = "${ypbin.emqx.command-scan-interval-ms:15000}")
    public void scan() {
        commandInstanceService.scanTimeouts();
    }
}
