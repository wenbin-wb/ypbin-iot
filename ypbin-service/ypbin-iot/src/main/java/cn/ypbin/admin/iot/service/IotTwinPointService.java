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

import cn.ypbin.admin.iot.model.req.IotTwinPointReq;
import cn.ypbin.admin.iot.model.resp.IotTwinDevicePropertyResp;
import cn.ypbin.admin.iot.model.resp.IotTwinPointResp;
import java.util.List;

/**
 * 数字孪生点位服务（锚点 + 属性绑定）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinPointService {

    /**
     * 查询场景下全部点位（含绑定，一次批量取回）。
     *
     * @param sceneId 场景主键
     * @return 点位列表（按 sort、id 升序；无数据为空列表）
     */
    List<IotTwinPointResp> listByScene(Long sceneId);

    /**
     * 新增点位（可同时带绑定）。
     *
     * @param sceneId 场景主键
     * @param req     点位信息
     * @return 新点位主键
     */
    Long create(Long sceneId, IotTwinPointReq req);

    /**
     * 编辑点位并**整体替换**其绑定。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     * @param req     点位信息
     */
    void update(Long sceneId, Long pointId, IotTwinPointReq req);

    /**
     * 删除点位（连同其全部绑定）。
     *
     * @param sceneId 场景主键
     * @param pointId 点位主键
     */
    void remove(Long sceneId, Long pointId);

    /**
     * 查询设备可绑定的物模型属性（经设备绑定产品的服务与属性）。
     *
     * <p>设备未绑定产品 ⇒ 返回空列表（**不是错误**：这是正常的空态，页面据此提示
     * 「该设备未绑定产品」）。设备不存在/不属于本租户 ⇒ 抛业务异常。</p>
     *
     * @param deviceId 设备主键
     * @return 属性列表（无数据为空列表）
     */
    List<IotTwinDevicePropertyResp> listDeviceProperties(Long deviceId);
}
