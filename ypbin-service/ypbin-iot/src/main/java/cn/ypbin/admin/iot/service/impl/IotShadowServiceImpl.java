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
import cn.ypbin.admin.iot.entity.IotShadow;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import cn.ypbin.admin.iot.model.req.IotShadowReq;
import cn.ypbin.admin.iot.model.resp.IotShadowResp;
import cn.ypbin.admin.iot.service.IotShadowService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 设备影子服务实现（§3.10，M-1 落库分支）。
 *
 * <p>合并视图：reported 优先、无则回退 desired；字段永不为 null（未初始化时为空 Map）。
 * Redis 化与最新值一起在 M-2（§5.3）落地，本实现保持键语义 {@code (tenant_id, device_id)}。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotShadowServiceImpl extends BaseServiceImpl<IotShadowMapper, IotShadow>
    implements IotShadowService {

    private final IotDeviceMapper iotDeviceMapper;
    private final ObjectMapper objectMapper;

    public IotShadowServiceImpl(IotDeviceMapper iotDeviceMapper, ObjectMapper objectMapper) {
        this.iotDeviceMapper = iotDeviceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public IotShadowResp get(Long deviceId) {
        requireDevice(deviceId);
        IotShadow shadow = findShadow(deviceId);
        IotShadowResp resp = new IotShadowResp();
        resp.setDeviceId(deviceId);
        if (shadow == null) {
            resp.setReported(Map.of());
            resp.setDesired(Map.of());
            resp.setMerged(Map.of());
            return resp;
        }
        Map<String, Object> reported = parse(shadow.getReported());
        Map<String, Object> desired = parse(shadow.getDesired());
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(desired);
        merged.putAll(reported);
        resp.setReported(reported);
        resp.setDesired(desired);
        resp.setMerged(merged);
        resp.setReportTs(shadow.getReportTs());
        resp.setDesiredTs(shadow.getDesiredTs());
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDesired(IotShadowReq req) {
        requireDevice(req.getDeviceId());
        IotShadow shadow = findShadow(req.getDeviceId());
        if (shadow == null) {
            shadow = new IotShadow();
            shadow.setDeviceId(req.getDeviceId());
            shadow.setDesired(write(req.getDesired()));
            shadow.setDesiredTs(LocalDateTime.now());
            save(shadow);
            return;
        }
        shadow.setDesired(write(req.getDesired()));
        shadow.setDesiredTs(LocalDateTime.now());
        updateById(shadow);
    }

    private IotShadow findShadow(Long deviceId) {
        LambdaQueryWrapper<IotShadow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotShadow::getDeviceId, deviceId);
        return getOne(wrapper);
    }

    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = iotDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "影子 JSON 解析失败");
        }
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "影子 JSON 序列化失败");
        }
    }
}
