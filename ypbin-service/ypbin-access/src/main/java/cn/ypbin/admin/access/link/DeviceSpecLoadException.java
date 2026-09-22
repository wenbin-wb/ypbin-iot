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

/**
 * 设备规格取数失败（可重试）。
 *
 * <p><b>为什么需要这个类型</b>：此前的实现把「传输异常 / 内部接口返回失败信封」与「该租户确实没有设备」
 * 都表达为空集合，调用方无法区分 ⇒ ① 取数失败与真空走同一条退避路径，把「内部接口持续失败」的恢复时延
 * 从 ≤ 一个租约周期放大到退避上限；② 配置变更对账会把「本轮没取到」误记为「已按最新配置对账」，
 * 导致一次失败永久吞掉一次配置变更（静默零数据）。</p>
 *
 * <p>语义边界：<b>只有失败才抛</b>；返回空集合的含义是「这个租户确实没有设备」
 * （设备全部停用/删除），调用方可以据此下架全部设备。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
public class DeviceSpecLoadException extends RuntimeException {

    /**
     * 构造取数异常。
     *
     * @param message 说明（含 tenantId 等定位信息）
     */
    public DeviceSpecLoadException(String message) {
        super(message);
    }

    /**
     * 构造取数异常（保留根因，日志必须能打出完整堆栈）。
     *
     * @param message 说明
     * @param cause   根因
     */
    public DeviceSpecLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
