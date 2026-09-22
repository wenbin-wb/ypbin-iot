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
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.enums.AccessMode;
import cn.ypbin.admin.iot.enums.ModelStatus;
import cn.ypbin.admin.iot.enums.PointRefType;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.IotPointMappingReq;
import cn.ypbin.admin.iot.model.resp.IotPointMappingResp;
import cn.ypbin.admin.iot.service.IotPointMappingService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 点位映射服务实现（§3.9）。
 *
 * <p>约束：映射必须引用<b>已发布版本</b>的属性（设备绑定产品+版本，属性经产品/服务可达）；
 * 读写权限与属性 {@code access_mode} 联动校验；协议能力不支持时由 iot-starter 显式失败（I6）。</p>
 *
 * <p><b>采集配置变更要发信号</b>：点位映射是「采什么点位」的输入，增删改都必须推进台账的
 * {@code config_epoch}（与业务写入同一事务）——否则接入侧永远不知道点位变了（M-2 / G7）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotPointMappingServiceImpl extends BaseServiceImpl<IotPointMappingMapper, IotPointMapping>
    implements IotPointMappingService {

    private final IotDeviceMapper iotDeviceMapper;
    private final IotPropertyMapper iotPropertyMapper;
    private final IotProductMapper iotProductMapper;
    private final IotServiceMapper iotServiceMapper;
    private final TenantLedgerService tenantLedgerService;

    public IotPointMappingServiceImpl(IotDeviceMapper iotDeviceMapper,
                                      IotPropertyMapper iotPropertyMapper,
                                      IotProductMapper iotProductMapper,
                                      IotServiceMapper iotServiceMapper,
                                      TenantLedgerService tenantLedgerService) {
        this.iotDeviceMapper = iotDeviceMapper;
        this.iotPropertyMapper = iotPropertyMapper;
        this.iotProductMapper = iotProductMapper;
        this.iotServiceMapper = iotServiceMapper;
        this.tenantLedgerService = tenantLedgerService;
    }

    @Override
    public List<IotPointMappingResp> listByDevice(Long deviceId) {
        requireDevice(deviceId);
        LambdaQueryWrapper<IotPointMapping> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotPointMapping::getDeviceId, deviceId)
            .orderByAsc(IotPointMapping::getId);
        return baseMapper.selectList(wrapper).stream().map(this::toResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IotPointMappingReq req) {
        IotDevice device = requireDevice(req.getDeviceId());
        validateReference(device, req);
        IotPointMapping mapping = new IotPointMapping();
        apply(mapping, req);
        save(mapping);
        notifyConfigChanged();
        return mapping.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotPointMappingReq req) {
        IotPointMapping mapping = requireMapping(id);
        // 映射归属由路径设备决定：请求体的 deviceId 不得把映射迁到别的设备
        // （否则可用一个已发布产品的设备路径去改挂到未发布产品的设备上，绕过 §3.9）
        if (!mapping.getDeviceId().equals(req.getDeviceId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射不属于该设备，禁止跨设备迁移：" + id);
        }
        IotDevice device = requireDevice(req.getDeviceId());
        validateReference(device, req);
        apply(mapping, req);
        updateById(mapping);
        notifyConfigChanged();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long deviceId, Long id) {
        IotPointMapping mapping = requireMapping(id);
        if (!mapping.getDeviceId().equals(deviceId)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射不属于该设备：" + id);
        }
        removeById(id);
        notifyConfigChanged();
    }

    /**
     * 采集配置变更后推进台账版本号（同一事务；台账无该租户时为已登记的已知限制，不阻断业务写入）。
     */
    private void notifyConfigChanged() {
        tenantLedgerService.bumpConfigEpochOfCurrentTenant();
    }

    /**
     * 校验属性引用：属性存在且所属产品为已发布（§3.9 约束），并联动校验读写权限。
     *
     * @param device 设备
     * @param req    请求
     */
    private void validateReference(IotDevice device, IotPointMappingReq req) {
        if (!PointRefType.PROPERTY.getCode().equals(req.getRefType())) {
            // M-1 点位映射仅支持属性；命令点位在 M-3 控制面落地（§3.9 ref_type 字段预留）
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "M-1 点位映射仅支持关联属性（refType=property），命令点位在 M-3 提供");
        }
        IotProperty property = iotPropertyMapper.selectById(req.getPropertyId());
        if (property == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "属性不存在：" + req.getPropertyId());
        }
        IotProduct product = iotProductMapper.selectById(device.getProductId());
        if (product == null || !ModelStatus.PUBLISHED.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射必须引用已发布版本的属性：请先给设备绑定产品并发布物模型");
        }
        // 属性必须经「服务的产品」可达：否则可引用其它产品（甚至其它租户产品）的属性
        IotService service = iotServiceMapper.selectById(property.getServiceId());
        if (service == null || !product.getId().equals(service.getProductId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "属性不属于设备所绑定产品的物模型：" + req.getPropertyId());
        }
        if (!AccessMode.READ_WRITE.getCode().equals(property.getAccessMode())
            && !property.getAccessMode().equals(req.getRw())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "映射读写权限与属性 access_mode 不一致：属性=" + property.getAccessMode()
                    + " 映射=" + req.getRw());
        }
    }

    private void apply(IotPointMapping mapping, IotPointMappingReq req) {
        mapping.setDeviceId(req.getDeviceId());
        mapping.setPropertyId(req.getPropertyId());
        mapping.setRefType(req.getRefType());
        mapping.setRawAddress(req.getRawAddress());
        mapping.setAddressType(req.getAddressType());
        mapping.setPollIntervalMs(req.getPollIntervalMs() == null ? 0 : req.getPollIntervalMs());
        mapping.setScaleFactor(req.getScaleFactor());
        mapping.setOffsetValue(req.getOffsetValue());
        mapping.setByteOrder(req.getByteOrder());
        mapping.setRw(req.getRw());
        mapping.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
    }

    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = iotDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    private IotPointMapping requireMapping(Long id) {
        IotPointMapping mapping = getById(id);
        if (mapping == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "点位映射不存在：" + id);
        }
        return mapping;
    }

    /**
     * 实体转响应（包内可见以便单测）。
     *
     * @param entity 实体
     * @return 响应
     */
    IotPointMappingResp toResp(IotPointMapping entity) {
        IotPointMappingResp resp = new IotPointMappingResp();
        resp.setId(entity.getId());
        resp.setDeviceId(entity.getDeviceId());
        resp.setPropertyId(entity.getPropertyId());
        resp.setRefType(entity.getRefType());
        resp.setRawAddress(entity.getRawAddress());
        resp.setAddressType(entity.getAddressType());
        resp.setPollIntervalMs(entity.getPollIntervalMs());
        resp.setScaleFactor(entity.getScaleFactor());
        resp.setOffsetValue(entity.getOffsetValue());
        resp.setByteOrder(entity.getByteOrder());
        resp.setRw(entity.getRw());
        resp.setEnabled(entity.getEnabled());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
