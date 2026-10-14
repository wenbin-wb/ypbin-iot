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
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.enums.ModelStatus;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.admin.iot.realtime.IotRealtimeService;
import cn.ypbin.admin.iot.service.IotDeviceService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IoT 设备台账服务实现。
 *
 * <p>租户隔离由 MyBatis-Plus 租户插件与 {@code TenantBaseEntity} 共同保证：本类<b>不手写</b>
 * {@code tenant_id} 过滤条件（手写反而会绕过统一策略）。</p>
 *
 * <p><b>采集配置变更要发信号</b>：设备是「采什么」的输入，增删改都必须推进台账的
 * {@code config_epoch}（与业务写入同一事务）——否则接入侧永远不知道设备变了（M-2 / G7）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Service
public class IotDeviceServiceImpl extends BaseServiceImpl<IotDeviceMapper, IotDevice>
    implements IotDeviceService {

    /** 单次批量操作设备数上限（服务层兜底；请求 DTO 的 {@code @Size} 是接口层第一道闸）。 */
    private static final int MAX_BATCH_SIZE = 500;

    private final IotProductMapper iotProductMapper;
    private final TenantLedgerService tenantLedgerService;

    /** 实时事件流（启停状态更新成功后旁路发布 device-status）。 */
    private final IotRealtimeService realtimeService;

    public IotDeviceServiceImpl(IotProductMapper iotProductMapper,
                                TenantLedgerService tenantLedgerService,
                                IotRealtimeService realtimeService) {
        this.iotProductMapper = iotProductMapper;
        this.tenantLedgerService = tenantLedgerService;
        this.realtimeService = realtimeService;
    }

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
        applyDevice(device, req);
        save(device);
        notifyConfigChanged();
        return device.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDevice(Long id) {
        removeById(id);
        notifyConfigChanged();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDevice(Long id, IotDeviceReq req) {
        IotDevice device = getById(id);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + id);
        }
        if (req.getProductId() != null) {
            requirePublishedProduct(req.getProductId());
        }
        applyDevice(device, req);
        updateById(device);
        notifyConfigChanged();
    }

    /**
     * 采集配置变更后推进台账版本号（同一事务；台账无该租户时为已登记的已知限制，不阻断业务写入）。
     */
    private void notifyConfigChanged() {
        tenantLedgerService.bumpConfigEpochOfCurrentTenant();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        IotDevice device = getById(id);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + id);
        }
        // 只接受 EntityStatus 的两个取值：非法值落到 DB 会变成「既非启用也非停用」的第三态，
        // 而规格下发只认 status=1 ⇒ 等于把设备静默停采。写入侧挡住，不靠下游兜。
        if (!EntityStatus.ENABLED.getCode().equals(status) && !EntityStatus.DISABLED.getCode().equals(status)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "状态取值非法（仅支持 0 停用 / 1 启用）：" + status);
        }
        IotDevice update = new IotDevice();
        update.setId(id);
        update.setStatus(status);
        updateById(update);
        // 停用/启用都改变「采什么」⇒ 必须推进台账版本号，否则接入侧要等安全网周期才发现
        // （见 IotDeviceServiceImpl 类注释与 ROADMAP 的 G8/R8-9）。
        notifyConfigChanged();
        // 实时旁路：状态变更成功后推给同租户订阅者（发布失败不回滚写入，见 IotRealtimeService）
        realtimeService.publishAfterCommit(IotRealtimeService.EVENT_DEVICE_STATUS, device.getTenantId(),
            new IotRealtimeService.DeviceStatusEvent(id, status));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateStatus(List<Long> ids, Integer status) {
        List<Long> idList = requireBatchIds(ids);
        if (!EntityStatus.ENABLED.getCode().equals(status)
            && !EntityStatus.DISABLED.getCode().equals(status)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "状态取值非法（仅支持 0 停用 / 1 启用）：" + status);
        }
        // 一条 UPDATE 覆盖整批：既保证原子性，也避免把 N 台设备放大成 N 次往返。
        int rows = baseMapper.update(new LambdaUpdateWrapper<IotDevice>()
            .in(IotDevice::getId, idList)
            .set(IotDevice::getStatus, status));
        // 整批只推进一次台账版本号（接入侧一次对账即可看到全部变更），不是逐台推进。
        notifyConfigChanged();
        // 实时旁路：批量启停同样在事务提交后推一条批量事件，页面据此刷新总览/列表。
        realtimeService.publishToCurrentTenantAfterCommit(IotRealtimeService.EVENT_DEVICE_STATUS,
            new IotRealtimeService.DeviceStatusBatchEvent(List.copyOf(idList), status));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchRemove(List<Long> ids) {
        List<Long> idList = requireBatchIds(ids);
        // 一条 DELETE（逻辑删除由 MyBatis-Plus 注入为 UPDATE is_deleted=1），不逐台 removeById。
        int rows = baseMapper.delete(new LambdaQueryWrapper<IotDevice>()
            .in(IotDevice::getId, idList));
        notifyConfigChanged();
        realtimeService.publishToCurrentTenantAfterCommit(IotRealtimeService.EVENT_DEVICE_STATUS,
            new IotRealtimeService.DeviceDeletedBatchEvent(List.copyOf(idList)));
        return rows;
    }

    /**
     * 批量主键校验与服务层上限兜底。
     *
     * <p>{@code null}/空集合直接拒绝（空集合进 IN 会拼出非法 SQL）；超过上限拒绝，防止一个请求
     * 把事务与 IN 列表拖垮；含 {@code null} 元素拒绝（否则会写出 {@code id IN (null)} 的歧义条件）。</p>
     *
     * @param ids 设备主键清单
     * @return 去重后的不可变主键清单
     */
    private List<Long> requireBatchIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 列表不能为空");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单次批量操作设备数不能超过 " + MAX_BATCH_SIZE + "：实际 " + ids.size());
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        return ids.stream().distinct().toList();
    }

    /**
     * 校验绑定产品存在且物模型已发布（§4.1：设备绑定的是已发布版本）。
     *
     * @param productId 产品主键
     */
    private void requirePublishedProduct(Long productId) {
        IotProduct product = iotProductMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "产品不存在：" + productId);
        }
        if (!ModelStatus.PUBLISHED.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "仅可绑定已发布物模型的产品：" + productId);
        }
    }

    /**
     * 请求字段应用到实体（新增/编辑共用）。
     *
     * <p>{@code status} 为 {@code null} 时**不动**启停位：新增交给 DB 默认值 {@code 1}，
     * 编辑时保留原值——否则一个不含该字段的旧版表单调用（如只改备注）会把设备静默改成停用。</p>
     *
     * @param device 实体
     * @param req    请求
     */
    private void applyDevice(IotDevice device, IotDeviceReq req) {
        device.setDeviceCode(req.getDeviceCode());
        device.setDeviceName(req.getDeviceName());
        device.setProtocol(req.getProtocol());
        device.setEndpoint(req.getEndpoint());
        device.setProductId(req.getProductId());
        device.setProductVersion(req.getProductVersion());
        device.setRemark(req.getRemark());
        if (req.getStatus() != null) {
            device.setStatus(req.getStatus());
        }
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
        if (query.getProductId() != null) {
            wrapper.eq(IotDevice::getProductId, query.getProductId());
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
        resp.setProductId(entity.getProductId());
        resp.setProductVersion(entity.getProductVersion());
        resp.setOnlineStatus(entity.getOnlineStatus());
        resp.setStatus(entity.getStatus());
        resp.setLastSeenAt(entity.getLastSeenAt());
        resp.setRemark(entity.getRemark());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
