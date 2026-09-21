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
import cn.ypbin.admin.iot.entity.IotDeviceGroup;
import cn.ypbin.admin.iot.entity.IotDeviceGroupMember;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMemberMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupMemberResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupResp;
import cn.ypbin.admin.iot.service.IotDeviceGroupService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 设备分组服务实现（§3.11，树形 + 成员多对多）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotDeviceGroupServiceImpl extends BaseServiceImpl<IotDeviceGroupMapper, IotDeviceGroup>
    implements IotDeviceGroupService {

    private final IotDeviceGroupMemberMapper iotDeviceGroupMemberMapper;
    private final IotDeviceMapper iotDeviceMapper;

    public IotDeviceGroupServiceImpl(IotDeviceGroupMemberMapper iotDeviceGroupMemberMapper,
                                     IotDeviceMapper iotDeviceMapper) {
        this.iotDeviceGroupMemberMapper = iotDeviceGroupMemberMapper;
        this.iotDeviceMapper = iotDeviceMapper;
    }

    @Override
    public List<IotDeviceGroupResp> listGroups() {
        LambdaQueryWrapper<IotDeviceGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(IotDeviceGroup::getSort)
            .orderByAsc(IotDeviceGroup::getId);
        return baseMapper.selectList(wrapper).stream().map(this::toResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IotDeviceGroupReq req) {
        IotDeviceGroup group = new IotDeviceGroup();
        apply(group, req);
        save(group);
        return group.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotDeviceGroupReq req) {
        IotDeviceGroup group = requireGroup(id);
        if (req.getParentId() != null && req.getParentId().equals(id)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "分组不能作为自己的父分组");
        }
        apply(group, req);
        updateById(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireGroup(id);
        iotDeviceGroupMemberMapper.delete(new LambdaQueryWrapper<IotDeviceGroupMember>()
            .eq(IotDeviceGroupMember::getGroupId, id));
        removeById(id);
    }

    @Override
    public List<IotDeviceGroupMemberResp> listMembers(Long groupId) {
        requireGroup(groupId);
        List<IotDeviceGroupMember> members = iotDeviceGroupMemberMapper.selectList(
            new LambdaQueryWrapper<IotDeviceGroupMember>()
                .eq(IotDeviceGroupMember::getGroupId, groupId)
                .orderByAsc(IotDeviceGroupMember::getId));
        return members.stream().map(member -> {
            IotDeviceGroupMemberResp resp = new IotDeviceGroupMemberResp();
            resp.setId(member.getId());
            resp.setGroupId(member.getGroupId());
            resp.setDeviceId(member.getDeviceId());
            resp.setCreateTime(member.getCreateTime());
            IotDevice device = iotDeviceMapper.selectById(member.getDeviceId());
            if (device != null) {
                resp.setDeviceCode(device.getDeviceCode());
                resp.setDeviceName(device.getDeviceName());
            }
            return resp;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addMember(Long groupId, IotDeviceGroupMemberReq req) {
        requireGroup(groupId);
        IotDevice device = iotDeviceMapper.selectById(req.getDeviceId());
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + req.getDeviceId());
        }
        Long existing = iotDeviceGroupMemberMapper.selectCount(new LambdaQueryWrapper<IotDeviceGroupMember>()
            .eq(IotDeviceGroupMember::getGroupId, groupId)
            .eq(IotDeviceGroupMember::getDeviceId, req.getDeviceId()));
        if (existing != null && existing > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备已在分组中：" + req.getDeviceId());
        }
        IotDeviceGroupMember member = new IotDeviceGroupMember();
        member.setGroupId(groupId);
        member.setDeviceId(req.getDeviceId());
        iotDeviceGroupMemberMapper.insert(member);
        return member.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long memberId) {
        iotDeviceGroupMemberMapper.deleteById(memberId);
    }

    private void apply(IotDeviceGroup group, IotDeviceGroupReq req) {
        group.setGroupName(req.getGroupName());
        group.setParentId(req.getParentId());
        group.setSort(req.getSort() == null ? 0 : req.getSort());
        group.setRemark(req.getRemark());
    }

    private IotDeviceGroup requireGroup(Long id) {
        IotDeviceGroup group = getById(id);
        if (group == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "分组不存在：" + id);
        }
        return group;
    }

    /**
     * 实体转响应（包内可见以便单测）。
     *
     * @param entity 实体
     * @return 响应
     */
    IotDeviceGroupResp toResp(IotDeviceGroup entity) {
        IotDeviceGroupResp resp = new IotDeviceGroupResp();
        resp.setId(entity.getId());
        resp.setGroupName(entity.getGroupName());
        resp.setParentId(entity.getParentId());
        resp.setSort(entity.getSort());
        resp.setRemark(entity.getRemark());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
