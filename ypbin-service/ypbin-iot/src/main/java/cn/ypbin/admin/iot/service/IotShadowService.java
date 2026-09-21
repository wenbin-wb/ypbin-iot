/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.req.IotShadowReq;
import cn.ypbin.admin.iot.model.resp.IotShadowResp;

/**
 * IoT 设备影子服务（§3.10）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotShadowService {

    /**
     * 读取影子（reported 优先、无则回退 desired 的合并视图）。
     *
     * @param deviceId 设备主键
     * @return 影子响应（未初始化时返回空 Map，字段不返回 null）
     */
    IotShadowResp get(Long deviceId);

    /**
     * 写入期望值（desired；reported 由设备上报，不在本方法写入）。
     *
     * @param req 期望值
     */
    void updateDesired(IotShadowReq req);
}
