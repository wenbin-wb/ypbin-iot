/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.link;

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * access 侧的 {@link ConnectionSpecProvider} 实现：把连接参数交给协议栈框架。
 *
 * <p>契约要点（框架源码核实）：<b>找不到必须返回 {@link Optional#empty()}</b>，框架会走
 * 「跳过该设备并告警」，而不是中断整轮引导。因此这里只做日志与透传，绝不抛异常。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class AccessConnectionSpecProvider implements ConnectionSpecProvider {

    private static final Logger log = LoggerFactory.getLogger(AccessConnectionSpecProvider.class);

    private final DeviceSpecSource source;

    public AccessConnectionSpecProvider(DeviceSpecSource source) {
        this.source = source;
    }

    @Override
    public Optional<ConnectionSpec> find(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        Optional<ConnectionSpec> spec = source.findConnection(connectionId);
        if (spec.isEmpty()) {
            // 不静默：设备存在但连接参数缺失属配置不完整，要能从日志里看出来
            log.warn("[access] 连接参数缺失，协议栈将跳过该设备：connectionId={}", connectionId);
        }
        return spec;
    }
}
