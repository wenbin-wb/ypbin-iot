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
import cn.ypbin.admin.iot.shadow.ShadowDesiredRow;
import cn.ypbin.admin.iot.shadow.ShadowValueComparator;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
            resp.setDelta(Map.of());
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
        resp.setDelta(delta(desired, reported));
        resp.setReportTs(shadow.getReportTs());
        resp.setDesiredTs(shadow.getDesiredTs());
        return resp;
    }

    /**
     * 期望与上报的差异（{@code delta = desired \ reported}，类型宽容比较）。
     *
     * <p>只含「期望里有、但上报缺该键或值不等价」的键；值取期望值（页面据此展示「期望设备变成什么」）。
     * 设备补齐上报后该键自动从 delta 消失（不需要任何后台任务）。</p>
     *
     * @param desired  期望值
     * @param reported 上报值
     * @return 差异（永不为 null）
     */
    private static Map<String, Object> delta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            String key = entry.getKey();
            if (!reported.containsKey(key)
                || !ShadowValueComparator.equal(entry.getValue(), reported.get(key))) {
                delta.put(key, entry.getValue());
            }
        }
        return delta;
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

    @Override
    public void mergeDesired(Long deviceId, Map<String, Object> desired) {
        if (desired == null || desired.isEmpty()) {
            // 空增量短路：没有键要合并，省一次往返（也避免把空对象写进影子）
            return;
        }
        IotDevice device = requireDevice(deviceId);
        // 与 DbShadowReportedWriter 同一做法：显式带租户 + runIgnore（该语句自带 tenant_id，
        // 不交给租户插件注入；更关键的是命令链路已有租户上下文，这里不能反过来依赖它）。
        ShadowDesiredRow row = new ShadowDesiredRow(IdWorker.getId(), device.getTenantId(), deviceId,
            write(desired), LocalDateTime.now());
        TenantContext.runIgnore(() -> baseMapper.mergeDesired(row));
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
