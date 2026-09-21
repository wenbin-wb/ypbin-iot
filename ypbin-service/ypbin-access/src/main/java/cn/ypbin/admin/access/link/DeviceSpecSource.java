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
import cn.ypbin.iot.core.model.DeviceSpec;
import java.util.List;
import java.util.Optional;

/**
 * 设备/连接规格来源（协议栈接入的取数端口）。
 *
 * <p>为什么要有这层端口：access **没有 JDBC**（见 3b-1 的部署取舍），设备与点位配置只能来自
 * iot 服务的内部接口；把「取数」收敛成一个端口，可以让协议栈装配与 {@link TenantLinkManager}
 * 的实现不依赖具体传输方式（HTTP/Feign/本地缓存），也便于用假实现在单测里驱动。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public interface DeviceSpecSource {

    /**
     * 取某租户当前**启用**的全部设备规格（含连接标识与点位属性）。
     *
     * @param tenantId 租户 ID
     * @return 设备规格列表；无设备返回空集合，绝不返回 {@code null}
     */
    List<DeviceSpec> loadByTenant(Long tenantId);

    /**
     * 按连接标识取连接参数（端点、协议、凭据引用）。
     *
     * @param connectionId 连接标识
     * @return 连接参数；不存在时返回 {@link Optional#empty()}——**必须让框架走「跳过并告警」**，
     *         而不是抛异常中断整轮引导
     */
    Optional<ConnectionSpec> findConnection(String connectionId);
}
