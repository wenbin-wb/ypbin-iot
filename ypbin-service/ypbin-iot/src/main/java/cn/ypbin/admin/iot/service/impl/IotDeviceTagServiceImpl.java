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
import cn.ypbin.admin.iot.model.req.IotDeviceBatchTagReq;
import cn.ypbin.admin.iot.model.req.IotDeviceTagReq;
import cn.ypbin.admin.iot.model.resp.BatchTagResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceTagResp;
import cn.ypbin.admin.iot.service.IotDeviceTagService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IoT 设备标签服务实现（§3.11，key/value）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotDeviceTagServiceImpl extends BaseServiceImpl<IotDeviceTagMapper, IotDeviceTag>
    implements IotDeviceTagService {

    /** 单次批量操作设备数上限（与请求 DTO 的 {@code @Size} 同一口径）。 */
    private static final int MAX_BATCH_SIZE = IotDeviceBatchTagReq.MAX_BATCH_SIZE;

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
    public void update(Long deviceId, Long id, IotDeviceTagReq req) {
        IotDeviceTag tag = requireTagInDevice(deviceId, id);
        tag.setTagKey(req.getTagKey());
        tag.setTagValue(req.getTagValue());
        updateById(tag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long deviceId, Long id) {
        requireTagInDevice(deviceId, id);
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchTagResp batchUpsertTag(List<Long> deviceIds, String tagKey, String tagValue) {
        List<Long> idList = requireBatchDeviceIds(deviceIds);
        String key = requireTagKey(tagKey);
        String value = requireTagValue(tagValue);
        // 一次 IN 确认设备全部存在；缺失则整批拒绝并一次列出全部缺失 ID（不部分写入、不静默）。
        Map<Long, Long> deviceTenants = new HashMap<>();
        for (IotDevice device : iotDeviceMapper.selectBatchIds(idList)) {
            deviceTenants.put(device.getId(), device.getTenantId());
        }
        List<Long> missingIds = idList.stream()
            .filter(id -> !deviceTenants.containsKey(id))
            .toList();
        if (!missingIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "设备不存在（共 " + missingIds.size() + " 台）：" + missingIds);
        }
        // 一次查已有标签（按设备 + 键），据此区分 created 与 updated；不逐台 selectCount。
        List<IotDeviceTag> existing = baseMapper.selectList(new LambdaQueryWrapper<IotDeviceTag>()
            .in(IotDeviceTag::getDeviceId, idList)
            .eq(IotDeviceTag::getTagKey, key));
        Set<Long> existingDeviceIds = existing.stream()
            .map(IotDeviceTag::getDeviceId)
            .collect(Collectors.toSet());
        int updated = 0;
        if (!existing.isEmpty()) {
            // 已有行一次 UPDATE 新值（按标签主键 IN，不重新按业务键扫全表）。
            updated = baseMapper.update(new LambdaUpdateWrapper<IotDeviceTag>()
                .in(IotDeviceTag::getId, existing.stream().map(IotDeviceTag::getId).toList())
                .set(IotDeviceTag::getTagValue, value));
        }
        List<Long> newDeviceIds = idList.stream()
            .filter(id -> !existingDeviceIds.contains(id))
            .toList();
        if (!newDeviceIds.isEmpty()) {
            List<IotDeviceTag> rows = new ArrayList<>(newDeviceIds.size());
            for (Long deviceId : newDeviceIds) {
                Long tenantId = deviceTenants.get(deviceId);
                if (tenantId == null) {
                    // 原生 insertBatch 不经注入器补 tenant_id；缺租户会写出非法行，直接拒绝。
                    throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                        "设备缺少租户信息，拒绝批量打标签：deviceId=" + deviceId);
                }
                IotDeviceTag tag = new IotDeviceTag();
                tag.setId(IdWorker.getId());
                tag.setTenantId(tenantId);
                tag.setDeviceId(deviceId);
                tag.setTagKey(key);
                tag.setTagValue(value);
                rows.add(tag);
            }
            // 缺失行一次批量插入（不在循环里逐条 insert）。
            baseMapper.insertBatch(rows);
        }
        BatchTagResp resp = new BatchTagResp();
        resp.setCreated(newDeviceIds.size());
        resp.setUpdated(updated);
        resp.setRemoved(0);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchTagResp batchRemoveTags(List<Long> deviceIds, List<String> tagKeys) {
        List<Long> idList = requireBatchDeviceIds(deviceIds);
        List<String> keys = requireTagKeys(tagKeys);
        // 一条 DELETE（@TableLogic 由 MyBatis-Plus 翻译为 UPDATE is_deleted=1），不逐台/逐键删。
        int removed = baseMapper.delete(new LambdaQueryWrapper<IotDeviceTag>()
            .in(IotDeviceTag::getDeviceId, idList)
            .in(IotDeviceTag::getTagKey, keys));
        BatchTagResp resp = new BatchTagResp();
        resp.setCreated(0);
        resp.setUpdated(0);
        resp.setRemoved(removed);
        return resp;
    }

    /**
     * 批量设备 ID 校验：非空、去重后不超上限、无 {@code null} 元素。
     *
     * @param deviceIds 原始设备 ID 清单
     * @return 去重后的不可变清单（保持首次出现顺序）
     */
    private List<Long> requireBatchDeviceIds(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 列表不能为空");
        }
        if (deviceIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        List<Long> distinct = deviceIds.stream().distinct().toList();
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单次批量操作设备数不能超过 " + MAX_BATCH_SIZE + "：实际 " + distinct.size());
        }
        return distinct;
    }

    private String requireTagKey(String tagKey) {
        if (!StringUtils.hasText(tagKey)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签键不能为空");
        }
        if (tagKey.length() > 64) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签键长度不能超过 64");
        }
        return tagKey;
    }

    private String requireTagValue(String tagValue) {
        if (!StringUtils.hasText(tagValue)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签值不能为空");
        }
        if (tagValue.length() > 255) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签值长度不能超过 255");
        }
        return tagValue;
    }

    /**
     * 待移除标签键校验：非空、单个键非空白且不超长、去重。
     *
     * @param tagKeys 原始标签键清单
     * @return 去重后的不可变清单
     */
    private List<String> requireTagKeys(List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签键列表不能为空");
        }
        if (tagKeys.stream().anyMatch(key -> !StringUtils.hasText(key))) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签键不能为空");
        }
        if (tagKeys.stream().anyMatch(key -> key.length() > 64)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "标签键长度不能超过 64");
        }
        return tagKeys.stream().distinct().toList();
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
     * 父子一致性：标签必须归属指定设备（路径父 ID 不是装饰）。
     *
     * @param deviceId 路径上的设备主键
     * @param id       标签主键
     * @return 标签实体
     */
    private IotDeviceTag requireTagInDevice(Long deviceId, Long id) {
        IotDeviceTag tag = requireTag(id);
        if (!deviceId.equals(tag.getDeviceId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "标签不存在：" + id);
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
