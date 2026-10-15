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
import cn.ypbin.admin.iot.model.resp.BatchTagResp;
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
     * @param deviceId 设备主键（路径父 ID，必须拥有该标签）
     * @param id       标签主键
     * @param req      标签信息
     */
    void update(Long deviceId, Long id, IotDeviceTagReq req);

    /**
     * 删除标签。
     *
     * @param deviceId 设备主键（路径父 ID，必须拥有该标签）
     * @param id       标签主键
     */
    void remove(Long deviceId, Long id);

    /**
     * 批量给多台设备打同一个键值标签（已存在该键则更新值，不存在则新增）。
     *
     * <p>先一次批量校验设备全部存在（缺失则整批拒绝，错误信息列出全部缺失 ID），再一次查已有标签：
     * 已有行一次批量 UPDATE，缺失行用 {@code IdWorker} + 设备 tenantId 组装后一次
     * {@code insertBatch} 落库——不做逐台 select/insert。</p>
     *
     * @param deviceIds 设备主键（去重后非空且不超上限）
     * @param tagKey    标签键
     * @param tagValue  标签值
     * @return 新增/更新行数
     */
    BatchTagResp batchUpsertTag(List<Long> deviceIds, String tagKey, String tagValue);

    /**
     * 批量移除多台设备上的指定标签键（一次逻辑删除）。
     *
     * @param deviceIds 设备主键（去重后非空且不超上限）
     * @param tagKeys   待移除的标签键（去重后非空）
     * @return 删除行数
     */
    BatchTagResp batchRemoveTags(List<Long> deviceIds, List<String> tagKeys);
}
