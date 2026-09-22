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
import java.util.Set;

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
     * @return 本次**发起**订阅的设备数（订阅完成是异步的，成功与否看 subscribe.success/failure 指标与日志；失败不记录跟踪，下一个租约周期会对账重试）
     */
    int subscribe(List<DeviceSpec> devices);

    /**
     * 当前**还没有会话**的设备 ID（N-2）：只有订阅规划器知道会话表，宿主据此决定是否重发 ADD。
     *
     * <p>为什么需要它：框架只在 {@code bind()} **成功**后才挂重连监听——启动瞬间设备离线时，
     * ADD 发出去但建链失败，框架**不会**自己重试；宿主若不重发 ADD，该设备会永久零数据
     * （日志里只有一行「暂无会话」的 DEBUG）。默认空实现给不持有会话表的实现（日志桩/测试替身）。</p>
     *
     * @param devices 本轮关心的设备
     * @return 无会话的设备 ID（绝不返回 null）
     */
    default Set<String> devicesWithoutSession(List<DeviceSpec> devices) {
        return Set.of();
    }

    /**
     * 忘记某设备的订阅跟踪（设备被移除时调用；M-2 配置变更对账的一部分）。
     *
     * <p>为什么需要：设备被下架后框架会关掉它的会话，跟踪表里的旧条目再也不会被复用；
     * 不清掉就是一处只增不减的引用（且「设备重新出现时该不该重订阅」会退化成靠会话实例变化兜底）。
     * 默认空实现是给<b>不跟踪会话</b>的实现留的（如日志桩与测试替身），
     * 真正跟踪的实现必须覆写。</p>
     *
     * @param deviceId 设备标识
     */
    default void forget(String deviceId) {
        // 默认不做事：只有跟踪会话的实现才需要清理
    }
}
