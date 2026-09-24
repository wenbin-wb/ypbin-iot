/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史时序查询服务（§5.2.1 查询路径）。
 *
 * <p>职责边界：参数校验 + 租户解析 + 把「存储不可用」如实报错；**具体查询交给 {@link TimeSeriesStore}**
 * （IoTDB 实现待补）。这样在存储未就位时，端点不会返回空列表假装"这段时间没数据"。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Service
public class TimeSeriesQueryService {

    private static final Logger log = LoggerFactory.getLogger(TimeSeriesQueryService.class);

    private final TimeSeriesStore store;

    private final IotDeviceMapper deviceMapper;

    public TimeSeriesQueryService(TimeSeriesStore store, IotDeviceMapper deviceMapper) {
        this.store = store;
        this.deviceMapper = deviceMapper;
    }

    /**
     * 查询历史时序。
     *
     * @param deviceId 设备 ID
     * @param req      查询条件
     * @return 时序点（升序；无数据为空列表）
     */
    public List<TimeSeriesPointResp> query(Long deviceId, TimeSeriesQueryReq req) {
        if (deviceId == null) {
            throw new BusinessException("设备 ID 不能为空");
        }
        if (req == null || req.getPropertyId() == null || req.getPropertyId().isBlank()) {
            throw new BusinessException("点位不能为空（不按点位查会把设备的全部点位混在一起）");
        }
        if (req.getFrom() != null && req.getTo() != null && req.getFrom() > req.getTo()) {
            // 区间给反不静默交换：给反就是调用方写错了，静默交换会让前端拿到看似正常的结果
            throw new BusinessException("起始时刻不能晚于结束时刻");
        }
        int limit = req.getLimit() == null ? TimeSeriesQueryReq.DEFAULT_LIMIT : req.getLimit();
        if (limit < 1 || limit > TimeSeriesQueryReq.MAX_LIMIT) {
            throw new BusinessException("limit 必须在 1~" + TimeSeriesQueryReq.MAX_LIMIT + " 之间");
        }
        if (!store.available()) {
            // 关键：**报错而不是返回空**——空列表会被读成"这段时间没数据"，与"时序库没启用"是两回事
            throw new BusinessException("历史时序查询未启用：时序库（IoTDB）尚未接入，请联系平台管理员");
        }
        Long tenantId = resolveTenant(deviceId);
        List<TimeSeriesPointResp> points = store.query(tenantId, deviceId, req.getPropertyId(),
            req.getFrom(), req.getTo(), limit);
        // 契约：无数据返回空列表（不返回 null）
        return points == null ? List.of() : points;
    }

    /** 设备 → 租户（查询必须显式带租户：本方法在管理面调用，租户来自身份上下文）。 */
    private Long resolveTenant(Long deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null || device.getTenantId() == null) {
            log.warn("[iot] 时序查询的设备不存在（或不属于当前租户），按拒绝处理：deviceId={}", deviceId);
            throw new BusinessException("设备不存在");
        }
        return device.getTenantId();
    }

    /** 供装配自检使用：存储是否可用（不触发查询）。 */
    public boolean storeAvailable() {
        return store.available();
    }

    /** 占位：保持与租户上下文一致的引用（查询路径同样要求租户隔离由插件承担）。 */
    static boolean tenantContextPresent() {
        return TenantContext.getTenantId().isPresent();
    }
}
