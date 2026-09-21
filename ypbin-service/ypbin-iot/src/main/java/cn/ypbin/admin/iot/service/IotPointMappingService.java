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

import cn.ypbin.admin.iot.model.req.IotPointMappingReq;
import cn.ypbin.admin.iot.model.resp.IotPointMappingResp;
import java.util.List;

/**
 * IoT 点位映射服务（§3.9）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotPointMappingService {

    /**
     * 查询设备下的点位映射列表。
     *
     * @param deviceId 设备主键
     * @return 点位映射列表
     */
    List<IotPointMappingResp> listByDevice(Long deviceId);

    /**
     * 新增点位映射（引用已发布版本的属性，§3.9）。
     *
     * @param req 点位映射信息
     * @return 新映射主键
     */
    Long create(IotPointMappingReq req);

    /**
     * 编辑点位映射。
     *
     * @param id  映射主键
     * @param req 点位映射信息
     */
    void update(Long id, IotPointMappingReq req);

    /**
     * 删除点位映射。
     *
     * @param id 映射主键
     */
    void remove(Long deviceId, Long id);
}
