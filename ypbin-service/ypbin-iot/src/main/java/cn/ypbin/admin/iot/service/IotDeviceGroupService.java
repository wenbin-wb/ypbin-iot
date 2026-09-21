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

import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupMemberResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupResp;
import java.util.List;

/**
 * IoT 设备分组服务（§3.11，树形 + 成员多对多）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotDeviceGroupService {

    /**
     * 查询全部分组（树形数据由前端按 parentId 组装）。
     *
     * @return 分组列表
     */
    List<IotDeviceGroupResp> listGroups();

    /**
     * 新增分组。
     *
     * @param req 分组信息
     * @return 新分组主键
     */
    Long create(IotDeviceGroupReq req);

    /**
     * 编辑分组。
     *
     * @param id  分组主键
     * @param req 分组信息
     */
    void update(Long id, IotDeviceGroupReq req);

    /**
     * 删除分组（连同其成员关系）。
     *
     * @param id 分组主键
     */
    void remove(Long id);

    /**
     * 查询分组下的设备成员。
     *
     * @param groupId 分组主键
     * @return 成员列表（含设备快照）
     */
    List<IotDeviceGroupMemberResp> listMembers(Long groupId);

    /**
     * 向分组添加设备成员。
     *
     * @param groupId 分组主键
     * @param req     设备 ID
     * @return 成员主键
     */
    Long addMember(Long groupId, IotDeviceGroupMemberReq req);

    /**
     * 移除分组设备成员。
     *
     * @param memberId 成员主键
     */
    void removeMember(Long memberId);
}
