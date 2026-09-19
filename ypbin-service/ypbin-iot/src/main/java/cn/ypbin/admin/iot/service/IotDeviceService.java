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

import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.starter.crud.model.PageResult;

/**
 * IoT 设备台账服务。
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface IotDeviceService {

    /**
     * 分页查询本租户的设备台账。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    PageResult<IotDeviceResp> pageDevices(IotDeviceQuery query);

    /**
     * 新增设备（租户由上下文注入，不接受请求参数指定）。
     *
     * @param req 设备信息
     * @return 新设备主键
     */
    Long createDevice(IotDeviceReq req);

    /**
     * 逻辑删除设备。
     *
     * @param id 设备主键
     */
    void removeDevice(Long id);
}
