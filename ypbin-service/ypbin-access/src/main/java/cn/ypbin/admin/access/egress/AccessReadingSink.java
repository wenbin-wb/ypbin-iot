/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.egress;

/**
 * 映射后读数出口（M-2 数据面的接入点）。
 *
 * <p>3b-2 只提供日志/内存实现占位；M-2 在这里换成「有界队列 → 微批 → HTTP 上报 /internal/readings」（EMQX 待 Q4）
 * （§5.1：热路径只入队，由固定平台线程消费），并接上两层丢弃计数。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@FunctionalInterface
public interface AccessReadingSink {

    /**
     * 接收一条已映射的读数。
     *
     * @param reading 读数
     */
    void accept(AccessReading reading);
}
