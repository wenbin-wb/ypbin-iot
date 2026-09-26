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
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史时序查询服务（§5.2.1 查询路径）。
 *
 * <p>职责边界：参数校验 + 租户解析 + **坐标形态解析** + 把「存储不可用」如实报错；
 * **具体查询交给 {@link TimeSeriesStore}**。这样在存储未就位时，端点不会返回空列表假装"这段时间没数据"。</p>
 *
 * <p><b>过渡期的坐标形态兼容</b>：坐标统一（2026-09-26）之前，access 链路往 IoTDB 的
 * {@code property_id} 写的是**属性主键字符串**，而查询侧给的是**属性标识** ⇒ 那批历史行按标识查不出来。
 * 本服务用 {@link PointMappingIndex} 把「标识」解析成「该点位的全部存储形态」（标识 + 历史主键字符串），
 * 一次查回。形态解析**只影响读**；新写入一律是标识。
 * 历史行 backfill 完成后，把形态解析收缩为「只返回标识」即可（本类无需改动）。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
@Service
public class TimeSeriesQueryService {

    private static final Logger log = LoggerFactory.getLogger(TimeSeriesQueryService.class);

    private final TimeSeriesStore store;

    private final IotDeviceMapper deviceMapper;

    /** 坐标形态解析（标识 → 该点位的全部历史存储形态）。 */
    private final PointMappingIndex pointMappingIndex;

    public TimeSeriesQueryService(TimeSeriesStore store, IotDeviceMapper deviceMapper,
                                  PointMappingIndex pointMappingIndex) {
        this.store = store;
        this.deviceMapper = deviceMapper;
        this.pointMappingIndex = pointMappingIndex;
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
        Set<String> forms = resolveCoordinateForms(req.getPropertyId(), deviceId);
        if (forms.size() > 1) {
            log.info("[iot] 历史时序查询命中**过渡期坐标兼容**（同时按属性标识与历史主键字符串形态查询）："
                    + "deviceId={} 点位={} 形态数={}",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(req.getPropertyId()), forms.size());
        }
        List<TimeSeriesPointResp> points = store.query(tenantId, deviceId, List.copyOf(forms),
            req.getFrom(), req.getTo(), limit);
        // 契约：无数据返回空列表（不返回 null）
        return points == null ? List.of() : points;
    }

    /**
     * 请求里的点位标识 → 该点位在存储里的全部形态（含标识自身；过渡期含历史主键字符串）。
     *
     * <p><b>解析不出来时只按请求的形态查</b>：查询语义是「查得到就返回，查不到就空」，与入站的
     * 「未映射即丢弃」不同——这里不能把「没配映射」升级成报错，否则配置变更后回查历史数据会直接失败。
     * 「查不到」与「存储里确实没有数据」对调用方是同一件事。</p>
     *
     * @param propertyId 请求的点位标识
     * @param deviceId   设备 ID
     * @return 存储形态集合（至少含请求的形态）
     */
    private Set<String> resolveCoordinateForms(String propertyId, Long deviceId) {
        // 设备行已由 resolveTenant 按租户过滤过 ⇒ 这里按 deviceId 忽略租户查映射不会跨租户
        PointMappingIndex.DeviceCoordinates coordinates = TenantContext.executeIgnore(
                () -> pointMappingIndex.loadCoordinates(List.of(deviceId)))
            .getOrDefault(deviceId, PointMappingIndex.DeviceCoordinates.empty());
        if (!coordinates.ambiguousForms().isEmpty()) {
            log.warn("[iot] 点位坐标形态撞名（历史主键字符串与属性标识同名，查询结果可能混入另一个点位）："
                    + "deviceId={} 形态={}",
                LogSanitizer.sanitize(deviceId),
                LogSanitizer.sanitize(String.join(",", coordinates.ambiguousForms())));
        }
        Set<String> forms = coordinates.aliasesOf(propertyId);
        return forms.isEmpty() ? Set.of(propertyId) : forms;
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
