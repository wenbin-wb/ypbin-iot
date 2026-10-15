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
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMemberMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberBatchRemoveReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberBatchReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupMemberReq;
import cn.ypbin.admin.iot.model.req.IotDeviceGroupReq;
import cn.ypbin.admin.iot.model.resp.BatchMemberAddResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupMemberResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupResp;
import cn.ypbin.admin.iot.service.IotDeviceGroupService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 设备分组服务实现（§3.11，树形 + 成员多对多）。
 *
 * <p>树形写入必须守好层级：新增/编辑的父分组必须存在，编辑还必须沿父链检查，禁止自引用或挂到
 * 自己的后代下形成环——否则前端组树会无限递归、成员归属也会变得不可判定。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotDeviceGroupServiceImpl extends BaseServiceImpl<IotDeviceGroupMapper, IotDeviceGroup>
    implements IotDeviceGroupService {

    /**
     * 父链最大上溯层数。
     *
     * <p>树校验必须上溯父链：正常树远达不到 100 层；一旦遇到历史脏数据留下的环，超过上限即拒绝，
     * 保证查询次数有界、不会无界循环。</p>
     */
    private static final int MAX_PARENT_DEPTH = 100;

    /** 单次批量加入设备数上限（与请求 DTO 的 {@code @Size} 同一口径）。 */
    private static final int MAX_BATCH_SIZE = IotDeviceGroupMemberBatchReq.MAX_BATCH_SIZE;

    /** 单次批量移除成员数上限（与请求 DTO 的 {@code @Size} 同一口径）。 */
    private static final int MAX_REMOVE_BATCH_SIZE = IotDeviceGroupMemberBatchRemoveReq.MAX_BATCH_SIZE;

    private final IotDeviceGroupMemberMapper iotDeviceGroupMemberMapper;
    private final IotDeviceMapper iotDeviceMapper;
    private final IotProductMapper iotProductMapper;

    public IotDeviceGroupServiceImpl(IotDeviceGroupMemberMapper iotDeviceGroupMemberMapper,
                                     IotDeviceMapper iotDeviceMapper, IotProductMapper iotProductMapper) {
        this.iotDeviceGroupMemberMapper = iotDeviceGroupMemberMapper;
        this.iotDeviceMapper = iotDeviceMapper;
        this.iotProductMapper = iotProductMapper;
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
        if (req.getParentId() != null) {
            requireGroup(req.getParentId());
        }
        IotDeviceGroup group = new IotDeviceGroup();
        apply(group, req);
        save(group);
        return group.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotDeviceGroupReq req) {
        IotDeviceGroup group = requireGroup(id);
        Long parentId = req.getParentId();
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "分组不能作为自己的父分组");
            }
            // 候选父必须存在；再从它沿父链上溯，防止把当前分组挂到自己的后代下形成环。
            requireNoCycle(id, requireGroup(parentId));
        }
        apply(group, req);
        updateById(group);
    }

    /**
     * 从候选父分组沿 {@code parentId} 链向上检查：遇到当前分组即判定成环。
     *
     * <p>父链是链式结构，只能逐层上溯；这里用 {@link #MAX_PARENT_DEPTH} 给查询次数封顶——
     * 历史脏数据中的环若不经过当前 id（无法在成环判定时提前退出），到上限同样抛业务异常，
     * 不静默放行、也不无界循环。</p>
     *
     * @param id    当前分组主键
     * @param start 候选父分组（调用方已校验其存在）
     */
    private void requireNoCycle(Long id, IotDeviceGroup start) {
        IotDeviceGroup current = start;
        int depth = 0;
        while (current != null) {
            if (id.equals(current.getId())) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "分组层级不能形成环");
            }
            depth++;
            if (depth > MAX_PARENT_DEPTH) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "分组层级超过 " + MAX_PARENT_DEPTH + " 层，拒绝保存（疑似存在环）");
            }
            Long parentId = current.getParentId();
            current = parentId == null ? null : getById(parentId);
        }
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
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> deviceIds = members.stream()
            .map(IotDeviceGroupMember::getDeviceId)
            .distinct()
            .toList();
        Map<Long, IotDevice> devices = iotDeviceMapper.selectBatchIds(deviceIds).stream()
            .collect(Collectors.toMap(IotDevice::getId, device -> device));
        // 产品名**一次批量解析**（不是每条成员一次：禁循环内 DB）；成员列表要能直接显示「所属产品」
        Map<Long, String> productNames = resolveProductNames(devices.values());
        return members.stream().map(member -> {
            IotDeviceGroupMemberResp resp = new IotDeviceGroupMemberResp();
            resp.setId(member.getId());
            resp.setGroupId(member.getGroupId());
            resp.setDeviceId(member.getDeviceId());
            resp.setCreateTime(member.getCreateTime());
            IotDevice device = devices.get(member.getDeviceId());
            if (device != null) {
                resp.setDeviceCode(device.getDeviceCode());
                resp.setDeviceName(device.getDeviceName());
                resp.setProductId(device.getProductId());
                if (device.getProductId() != null) {
                    // 产品已删除/跨租户不可见时保持为空，由页面显示「未绑定产品」，不编造名字
                    resp.setProductName(productNames.get(device.getProductId()));
                }
            }
            return resp;
        }).toList();
    }

    /**
     * 批量解析「设备绑定产品」的名称（成员列表展示用）。
     *
     * <p>空集合**短路返回空 Map**：批量 IN 传空集合会拼出非法 SQL（本仓铁律），且没有产品绑定是常态。</p>
     *
     * @param devices 本轮成员对应的设备
     * @return 产品 ID → 产品名；没有可解析的产品时为空 Map
     */
    private Map<Long, String> resolveProductNames(Collection<IotDevice> devices) {
        Set<Long> productIds = devices.stream()
            .map(IotDevice::getProductId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return iotProductMapper.selectBatchIds(productIds).stream()
            .collect(Collectors.toMap(IotProduct::getId, IotProduct::getProductName));
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
    public BatchMemberAddResp addMembers(Long groupId, List<Long> deviceIds) {
        IotDeviceGroup group = requireGroup(groupId);
        List<Long> idList = requireDeviceIds(deviceIds);
        // 批量 insertBatch 走原生 SQL，不经注入器补 tenant_id；用分组行自身的租户（与查询同源）显式赋值，
        // 缺租户直接拒绝，绝不写出 tenant_id 为空的成员行（DB 列 NOT NULL，静默半写更糟）。
        Long tenantId = group.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "分组缺少租户信息，拒绝批量加入设备");
        }
        // 一次 IN 确认设备都存在（租户插件会追加 tenant_id 条件，跨租户设备等同缺失）。
        Set<Long> foundDeviceIds = iotDeviceMapper.selectBatchIds(idList).stream()
            .map(IotDevice::getId)
            .collect(Collectors.toSet());
        List<Long> missingIds = idList.stream()
            .filter(id -> !foundDeviceIds.contains(id))
            .toList();
        if (!missingIds.isEmpty()) {
            // 整批拒绝：列出全部缺失 ID，不做「部分加入」——部分成功会让调用方误以为整批成功。
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "设备不存在（共 " + missingIds.size() + " 台）：" + missingIds);
        }
        // 一次 IN 查已有成员，过滤已加入（幂等命中）——不逐台 selectCount。
        Set<Long> memberDeviceIds = iotDeviceGroupMemberMapper.selectList(
                new LambdaQueryWrapper<IotDeviceGroupMember>()
                    .eq(IotDeviceGroupMember::getGroupId, groupId)
                    .in(IotDeviceGroupMember::getDeviceId, idList))
            .stream()
            .map(IotDeviceGroupMember::getDeviceId)
            .collect(Collectors.toSet());
        List<Long> newDeviceIds = idList.stream()
            .filter(id -> !memberDeviceIds.contains(id))
            .toList();
        BatchMemberAddResp resp = new BatchMemberAddResp();
        resp.setSkipped(memberDeviceIds.size());
        resp.setMissingDeviceIds(List.of());
        if (newDeviceIds.isEmpty()) {
            resp.setAdded(0);
            return resp;
        }
        List<IotDeviceGroupMember> members = newDeviceIds.stream().map(deviceId -> {
            IotDeviceGroupMember member = new IotDeviceGroupMember();
            member.setId(IdWorker.getId());
            member.setTenantId(tenantId);
            member.setGroupId(groupId);
            member.setDeviceId(deviceId);
            return member;
        }).toList();
        // 一次批量插入（原生 SQL 显式写 create_time/update_time/status/is_deleted 由 Mapper 负责）
        iotDeviceGroupMemberMapper.insertBatch(members);
        resp.setAdded(members.size());
        return resp;
    }

    /**
     * 批量设备 ID 校验：非空、去重后不超上限、无 {@code null} 元素。
     *
     * @param deviceIds 原始设备 ID 清单
     * @return 去重后的不可变清单（保持首次出现顺序）
     */
    private List<Long> requireDeviceIds(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 列表不能为空");
        }
        if (deviceIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        List<Long> distinct = deviceIds.stream().distinct().toList();
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单次批量加入设备数不能超过 " + MAX_BATCH_SIZE + "：实际 " + distinct.size());
        }
        return distinct;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeMembers(Long groupId, List<Long> memberIds) {
        requireGroup(groupId);
        List<Long> idList = requireRemoveMemberIds(memberIds);
        // 一次 IN 查成员行（@TableLogic 自动过滤已删除行）；有缺失则整批拒绝并列全缺失主键。
        List<IotDeviceGroupMember> members = iotDeviceGroupMemberMapper.selectBatchIds(idList);
        Set<Long> foundIds = members.stream()
            .map(IotDeviceGroupMember::getId)
            .collect(Collectors.toSet());
        List<Long> missingIds = idList.stream()
            .filter(id -> !foundIds.contains(id))
            .toList();
        if (!missingIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "分组成员不存在（共 " + missingIds.size() + " 个）：" + missingIds);
        }
        // 一次内存校验全部成员归属路径分组；跨组整批拒绝，绝不「按路径分组删别人组成员」。
        List<Long> crossGroupIds = members.stream()
            .filter(member -> !groupId.equals(member.getGroupId()))
            .map(IotDeviceGroupMember::getId)
            .toList();
        if (!crossGroupIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "分组成员不属于当前分组（共 " + crossGroupIds.size() + " 个）：" + crossGroupIds);
        }
        // 一条 DELETE（逻辑删除由 MyBatis-Plus 注入为 UPDATE is_deleted=1）；WHERE 同时限定分组做纵深防御。
        return iotDeviceGroupMemberMapper.delete(new LambdaQueryWrapper<IotDeviceGroupMember>()
            .eq(IotDeviceGroupMember::getGroupId, groupId)
            .in(IotDeviceGroupMember::getId, idList));
    }

    /**
     * 待移除成员 ID 校验：非空、去重后不超上限、无 {@code null} 元素。
     *
     * @param memberIds 原始成员主键清单
     * @return 去重后的不可变清单（保持首次出现顺序）
     */
    private List<Long> requireRemoveMemberIds(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "成员 ID 列表不能为空");
        }
        if (memberIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "成员 ID 不能为空");
        }
        List<Long> distinct = memberIds.stream().distinct().toList();
        if (distinct.size() > MAX_REMOVE_BATCH_SIZE) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单次批量移除成员数不能超过 " + MAX_REMOVE_BATCH_SIZE + "：实际 " + distinct.size());
        }
        return distinct;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long groupId, Long memberId) {
        // 父子一致性：成员行必须归属路径上的分组 —— 否则经任意分组路径可删任意成员行。
        // 先查后删（两条语句同一事务；成员行无并发变更场景，check-then-act 可接受）。
        IotDeviceGroupMember member = iotDeviceGroupMemberMapper.selectById(memberId);
        if (member == null || !groupId.equals(member.getGroupId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "分组成员不存在：" + memberId);
        }
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
