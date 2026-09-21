/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceTag;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceTagMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceTagReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceTagResp;
import cn.ypbin.admin.iot.service.IotDeviceTagService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 设备标签服务实现（§3.11，key/value）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotDeviceTagServiceImpl extends BaseServiceImpl<IotDeviceTagMapper, IotDeviceTag>
    implements IotDeviceTagService {

    private final IotDeviceMapper iotDeviceMapper;

    public IotDeviceTagServiceImpl(IotDeviceMapper iotDeviceMapper) {
        this.iotDeviceMapper = iotDeviceMapper;
    }

    @Override
    public List<IotDeviceTagResp> listByDevice(Long deviceId) {
        requireDevice(deviceId);
        LambdaQueryWrapper<IotDeviceTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotDeviceTag::getDeviceId, deviceId)
            .orderByAsc(IotDeviceTag::getTagKey);
        return baseMapper.selectList(wrapper).stream().map(this::toResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long deviceId, IotDeviceTagReq req) {
        requireDevice(deviceId);
        Long existing = baseMapper.selectCount(new LambdaQueryWrapper<IotDeviceTag>()
            .eq(IotDeviceTag::getDeviceId, deviceId)
            .eq(IotDeviceTag::getTagKey, req.getTagKey()));
        if (existing != null && existing > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "同设备标签键已存在：" + req.getTagKey());
        }
        IotDeviceTag tag = new IotDeviceTag();
        tag.setDeviceId(deviceId);
        tag.setTagKey(req.getTagKey());
        tag.setTagValue(req.getTagValue());
        save(tag);
        return tag.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotDeviceTagReq req) {
        IotDeviceTag tag = requireTag(id);
        tag.setTagKey(req.getTagKey());
        tag.setTagValue(req.getTagValue());
        updateById(tag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireTag(id);
        removeById(id);
    }

    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = iotDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    private IotDeviceTag requireTag(Long id) {
        IotDeviceTag tag = getById(id);
        if (tag == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签不存在：" + id);
        }
        return tag;
    }

    /**
     * 实体转响应（包内可见以便单测）。
     *
     * @param entity 实体
     * @return 响应
     */
    IotDeviceTagResp toResp(IotDeviceTag entity) {
        IotDeviceTagResp resp = new IotDeviceTagResp();
        resp.setId(entity.getId());
        resp.setDeviceId(entity.getDeviceId());
        resp.setTagKey(entity.getTagKey());
        resp.setTagValue(entity.getTagValue());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
