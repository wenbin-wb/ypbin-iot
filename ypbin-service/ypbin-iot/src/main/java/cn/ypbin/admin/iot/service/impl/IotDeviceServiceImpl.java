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
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.admin.iot.service.IotDeviceService;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IoT 设备台账服务实现。
 *
 * <p>租户隔离由 MyBatis-Plus 租户插件与 {@code TenantBaseEntity} 共同保证：本类<b>不手写</b>
 * {@code tenant_id} 过滤条件（手写反而会绕过统一策略）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Service
public class IotDeviceServiceImpl extends BaseServiceImpl<IotDeviceMapper, IotDevice>
    implements IotDeviceService {

    @Override
    public PageResult<IotDeviceResp> pageDevices(IotDeviceQuery query) {
        PageResult<IotDevice> source = page(query, buildWrapper(query));
        List<IotDeviceResp> items = source.getItems().stream().map(this::toResp).toList();
        return PageResult.of(items, source.getTotal(), source.getPage(), source.getPageSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDevice(IotDeviceReq req) {
        IotDevice device = new IotDevice();
        device.setDeviceCode(req.getDeviceCode());
        device.setDeviceName(req.getDeviceName());
        device.setProtocol(req.getProtocol());
        device.setEndpoint(req.getEndpoint());
        device.setRemark(req.getRemark());
        save(device);
        return device.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDevice(Long id) {
        removeById(id);
    }

    /**
     * 构造查询条件（包内可见以便单测直接验证，不必起 Spring 上下文）。
     *
     * @param query 查询条件
     * @return MyBatis-Plus 条件构造器
     */
    LambdaQueryWrapper<IotDevice> buildWrapper(IotDeviceQuery query) {
        LambdaQueryWrapper<IotDevice> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(inner -> inner.like(IotDevice::getDeviceName, keyword)
                .or()
                .like(IotDevice::getDeviceCode, keyword));
        }
        if (StringUtils.hasText(query.getProtocol())) {
            wrapper.eq(IotDevice::getProtocol, query.getProtocol());
        }
        return wrapper;
    }

    /**
     * 实体转响应模型（包内可见以便单测）。
     *
     * @param entity 实体
     * @return 响应模型
     */
    IotDeviceResp toResp(IotDevice entity) {
        IotDeviceResp resp = new IotDeviceResp();
        resp.setId(entity.getId());
        resp.setDeviceCode(entity.getDeviceCode());
        resp.setDeviceName(entity.getDeviceName());
        resp.setProtocol(entity.getProtocol());
        resp.setEndpoint(entity.getEndpoint());
        resp.setRemark(entity.getRemark());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
