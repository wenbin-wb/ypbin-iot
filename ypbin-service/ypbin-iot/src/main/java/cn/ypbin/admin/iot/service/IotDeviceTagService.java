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

import cn.ypbin.admin.iot.model.req.IotDeviceTagReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceTagResp;
import java.util.List;

/**
 * IoT 设备标签服务（§3.11，key/value）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotDeviceTagService {

    /**
     * 查询设备下的标签列表。
     *
     * @param deviceId 设备主键
     * @return 标签列表
     */
    List<IotDeviceTagResp> listByDevice(Long deviceId);

    /**
     * 新增标签（同设备同键唯一）。
     *
     * @param deviceId 设备主键
     * @param req      标签信息
     * @return 新标签主键
     */
    Long create(Long deviceId, IotDeviceTagReq req);

    /**
     * 编辑标签值。
     *
     * @param id  标签主键
     * @param req 标签信息
     */
    void update(Long id, IotDeviceTagReq req);

    /**
     * 删除标签。
     *
     * @param id 标签主键
     */
    void remove(Long id);
}
