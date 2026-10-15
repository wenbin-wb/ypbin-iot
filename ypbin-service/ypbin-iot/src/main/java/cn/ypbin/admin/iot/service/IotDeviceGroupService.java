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

import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberBatchReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupReq;
import cn.ypbin.admin.iot.model.resp.BatchMemberAddResp;
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
     * 批量向分组加入设备（单次去重后最多 {@link IotDeviceGroupMemberBatchReq#MAX_BATCH_SIZE} 台）。
     *
     * <p>已存在的成员按幂等命中计入 {@code skipped} 不重复插入；任一设备不存在则**整批拒绝**，
     * 错误信息一次性列出全部缺失 ID（不静默、不部分写入）。新增成员复用
     * {@code IotDeviceGroupMemberMapper.insertBatch}，一次批量落库，不在循环里逐条 insert。</p>
     *
     * @param groupId   分组主键
     * @param deviceIds 设备主键（去重后非空且不超上限）
     * @return 新增/跳过计数（及缺失设备列表，正常成功时为空）
     */
    BatchMemberAddResp addMembers(Long groupId, List<Long> deviceIds);

    /**
     * 移除分组设备成员。
     *
     * @param groupId  分组主键（路径父 ID，成员行必须归属该分组）
     * @param memberId 成员主键
     */
    void removeMember(Long groupId, Long memberId);
}
