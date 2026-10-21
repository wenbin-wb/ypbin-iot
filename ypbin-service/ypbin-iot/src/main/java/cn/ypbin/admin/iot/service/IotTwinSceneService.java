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

import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.admin.iot.model.resp.IotTwinSceneResp;
import java.util.List;

/**
 * 数字孪生场景服务（树形 + 父链校验）。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinSceneService {

    /**
     * 查询全部场景（树形数据由调用方按 parentId 组装）。
     *
     * @return 场景列表（按 parentId、sort、id 升序；无数据为空列表）
     */
    List<IotTwinSceneResp> listScenes();

    /**
     * 查询单个场景。
     *
     * @param id 场景主键
     * @return 场景详情
     */
    IotTwinSceneResp getScene(Long id);

    /**
     * 新建场景。
     *
     * @param req 场景信息
     * @return 新场景主键
     */
    Long create(IotTwinSceneReq req);

    /**
     * 编辑场景。
     *
     * @param id  场景主键
     * @param req 场景信息
     */
    void update(Long id, IotTwinSceneReq req);

    /**
     * 删除场景（存在子场景或点位时拒绝，不级联删除）。
     *
     * @param id 场景主键
     */
    void remove(Long id);
}
