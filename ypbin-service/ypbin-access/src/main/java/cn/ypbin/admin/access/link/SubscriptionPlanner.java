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

import cn.ypbin.iot.core.model.DeviceSpec;
import java.util.List;

/**
 * 订阅规划端口：给定已绑定设备，建立采集订阅。
 *
 * <p>抽成接口是为了让 {@link IotProtocolTenantLinkManager} 可单测（注入假实现），
 * 同时把「怎么订阅」与「什么时候订阅」分开。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@FunctionalInterface
public interface SubscriptionPlanner {

    /**
     * 为一批已绑定的设备建立订阅。
     *
     * @param devices 设备规格（框架应已建链）
     * @return 成功建立订阅的设备数
     */
    int subscribe(List<DeviceSpec> devices);
}
