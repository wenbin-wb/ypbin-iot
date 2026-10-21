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
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.resp.IotTwinRuntimeResp;
import cn.ypbin.admin.iot.model.resp.LatestValueResp;
import cn.ypbin.admin.iot.model.resp.TwinRuntimePointResp;
import cn.ypbin.admin.iot.model.resp.TwinRuntimeValueResp;
import cn.ypbin.admin.iot.service.AlertInstanceService;
import cn.ypbin.admin.iot.service.IotTwinRuntimeService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.admin.iot.values.LatestValueQueryService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数字孪生运行态服务实现。
 *
 * <p><b>整个组装过程只有固定次数的查询</b>（与点位数无关）：
 * ① 场景 1 次；② 点位 1 次；③ 绑定 1 次；④ 设备 1 次；⑤ 最新值 1 次 pipeline；
 * ⑥ 活动告警 1 次分组。500 个点位的场景与 1 个点位的场景查询次数相同。</p>
 *
 * <p><b>不宣称「原子快照」</b>：{@code serverTime} 是开始组装的时刻；各设备读数来自不同
 * 时刻的上报。需要严格同时刻切片时走时序库，不是本服务。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinRuntimeServiceImpl implements IotTwinRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(IotTwinRuntimeServiceImpl.class);

    private final IotTwinSceneMapper twinSceneMapper;

    private final IotTwinPointMapper twinPointMapper;

    private final IotTwinPointBindingMapper twinPointBindingMapper;

    private final IotDeviceMapper deviceMapper;

    private final LatestValueQueryService latestValueQueryService;

    private final AlertInstanceService alertInstanceService;

    private final TwinBindingResolver bindingResolver;

    public IotTwinRuntimeServiceImpl(IotTwinSceneMapper twinSceneMapper,
                                     IotTwinPointMapper twinPointMapper,
                                     IotTwinPointBindingMapper twinPointBindingMapper,
                                     IotDeviceMapper deviceMapper,
                                     LatestValueQueryService latestValueQueryService,
                                     AlertInstanceService alertInstanceService,
                                     TwinBindingResolver bindingResolver) {
        this.twinSceneMapper = twinSceneMapper;
        this.twinPointMapper = twinPointMapper;
        this.twinPointBindingMapper = twinPointBindingMapper;
        this.deviceMapper = deviceMapper;
        this.latestValueQueryService = latestValueQueryService;
        this.alertInstanceService = alertInstanceService;
        this.bindingResolver = bindingResolver;
    }

    @Override
    public IotTwinRuntimeResp runtime(Long sceneId) {
        IotTwinScene scene = twinSceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不存在：" + sceneId);
        }
        IotTwinRuntimeResp resp = new IotTwinRuntimeResp();
        resp.setSceneId(scene.getId());
        resp.setServerTime(LocalDateTime.now());

        List<IotTwinPoint> points = twinPointMapper.selectList(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, sceneId)
            .orderByAsc(IotTwinPoint::getSort)
            .orderByAsc(IotTwinPoint::getId));
        if (points.isEmpty()) {
            resp.setPointCount(0);
            resp.setTruncated(Boolean.FALSE);
            resp.setPoints(List.of());
            return resp;
        }
        boolean truncated = points.size() > TwinLimits.MAX_POINTS_PER_SCENE;
        if (truncated) {
            log.warn("[iot] 场景点位数超过上限，运行态已截断：sceneId={} count={} limit={}",
                sceneId, points.size(), TwinLimits.MAX_POINTS_PER_SCENE);
            points = points.subList(0, TwinLimits.MAX_POINTS_PER_SCENE);
        }
        List<Long> pointIds = points.stream().map(IotTwinPoint::getId).toList();
        List<Long> deviceIds = points.stream().map(IotTwinPoint::getDeviceId).distinct().toList();

        // ③ 一次取本场景全部绑定，按点位分组（供逐点位组装 values 与 stale）
        List<IotTwinPointBinding> bindings = twinPointBindingMapper.selectList(
            new LambdaQueryWrapper<IotTwinPointBinding>()
                .in(IotTwinPointBinding::getPointId, pointIds));
        Map<Long, TwinBindingResolver.ResolvedBinding> resolvedBindings =
            bindingResolver.resolve(bindings);
        Map<Long, List<IotTwinPointBinding>> bindingsByPoint = bindings.stream()
            .collect(Collectors.groupingBy(IotTwinPointBinding::getPointId));

        // ④ 一次取设备（同时完成「属于本租户」的过滤：跨租户设备查不到）
        Map<Long, IotDevice> deviceById = new HashMap<>();
        for (IotDevice device : deviceMapper.selectBatchIds(deviceIds)) {
            deviceById.put(device.getId(), device);
        }

        // ⑤ 一次 Redis pipeline 取全部设备最新值
        Map<Long, Map<String, LatestValueResp>> latestByDevice =
            toLatestByIdentifier(latestValueQueryService.listLatestBatch(deviceIds));

        // ⑥ 一次分组统计活动告警数
        Map<Long, Integer> alertCountByDevice = loadAlertCounts(deviceIds);

        List<TwinRuntimePointResp> pointResps = new ArrayList<>(points.size());
        for (IotTwinPoint point : points) {
            IotDevice device = deviceById.get(point.getDeviceId());
            if (device == null) {
                // 设备已在台账里被删除（或不属于本租户）：点位仍在配置里。
                // 不伪造设备信息、也不丢弃点位——页面需要看到「这里有锚点但设备没了」才能去修。
                log.info("[iot] 孪生点位的设备已不存在，按空设备信息返回：pointId={} deviceId={}",
                    point.getId(), point.getDeviceId());
            }
            pointResps.add(toPointResp(point, device,
                bindingsByPoint.getOrDefault(point.getId(), List.of()),
                latestByDevice.getOrDefault(point.getDeviceId(), Map.of()),
                resolvedBindings,
                alertCountByDevice.getOrDefault(point.getDeviceId(), 0)));
        }
        resp.setPointCount(pointResps.size());
        resp.setTruncated(truncated);
        resp.setPoints(pointResps);
        return resp;
    }

    /**
     * 取设备的活动告警数。
     *
     * <p>告警能力未启用时 {@code AlertInstanceService#activeCountsByDevice} 会抛业务异常
     * （该门禁的设计意图是「返回空结果会被误读成没有告警」）。孪生页的语义不同：
     * 设备状态与实时值仍然有效，不能因为告警未启用就整页打不开 ⇒ 这里显式降级为
     * 「全 0」并记 WARN（**不静默**：用户会在页面上看到告警数恒为 0，同时日志里有原因）。</p>
     *
     * @param deviceIds 设备主键
     * @return 设备主键 → 活动告警数（缺失设备视为 0）
     */
    private Map<Long, Integer> loadAlertCounts(List<Long> deviceIds) {
        try {
            return alertInstanceService.activeCountsByDevice(deviceIds);
        } catch (BusinessException ex) {
            log.warn("[iot] 活动告警数不可用（孪生运行态按 0 处理，设备状态与实时值不受影响）",
                ex);
            return Map.of();
        }
    }

    /**
     * 把批量最新值按「设备 → 属性标识 → 最新值」重建索引。
     *
     * <p>批量服务返回的是每台设备的列表，逐点位取值需要按属性标识查；在这里重建一次索引，
     * 避免点位循环里对列表做线性查找（500 点位 × 12 属性时那是平方级）。</p>
     */
    private Map<Long, Map<String, LatestValueResp>> toLatestByIdentifier(
        Map<Long, List<LatestValueResp>> latestByDevice) {
        Map<Long, Map<String, LatestValueResp>> result = new HashMap<>(latestByDevice.size());
        for (Map.Entry<Long, List<LatestValueResp>> entry : latestByDevice.entrySet()) {
            Map<String, LatestValueResp> byIdentifier = new HashMap<>();
            for (LatestValueResp value : entry.getValue()) {
                if (value.getPropertyId() != null) {
                    byIdentifier.put(value.getPropertyId(), value);
                }
            }
            result.put(entry.getKey(), byIdentifier);
        }
        return result;
    }

    private TwinRuntimePointResp toPointResp(IotTwinPoint point, IotDevice device,
                                             List<IotTwinPointBinding> pointBindings,
                                             Map<String, LatestValueResp> latestByProperty,
                                             Map<Long, TwinBindingResolver.ResolvedBinding> resolved,
                                             int alertCount) {
        TwinRuntimePointResp resp = new TwinRuntimePointResp();
        resp.setPointId(point.getId());
        resp.setDeviceId(point.getDeviceId());
        if (device != null) {
            resp.setDeviceCode(device.getDeviceCode());
            resp.setDeviceName(device.getDeviceName());
            resp.setOnlineStatus(device.getOnlineStatus());
            resp.setStatus(device.getStatus());
            resp.setLastSeenAt(device.getLastSeenAt());
        } else {
            resp.setOnlineStatus(null);
            resp.setStatus(null);
            resp.setLastSeenAt(null);
        }
        resp.setAlertCount(alertCount);
        Map<String, TwinRuntimeValueResp> values = new LinkedHashMap<>();
        for (IotTwinPointBinding binding : pointBindings) {
            String propertyIdentifier = binding.getPropertyIdentifier();
            TwinRuntimeValueResp value = values.computeIfAbsent(propertyIdentifier, key -> {
                TwinRuntimeValueResp created = new TwinRuntimeValueResp();
                LatestValueResp latest = latestByProperty.get(key);
                if (latest != null) {
                    created.setValue(latest.getValue());
                    created.setQuality(latest.getQuality());
                    created.setTs(latest.getTs());
                }
                created.setStale(Boolean.FALSE);
                return created;
            });
            // 同一属性可能被两条绑定引用（不同展示形态），stale 结论相同：任一条失效即标记失效
            TwinBindingResolver.ResolvedBinding hit = resolved.get(binding.getId());
            if (hit != null && Boolean.TRUE.equals(hit.getStale())) {
                value.setStale(Boolean.TRUE);
            }
        }
        resp.setValues(values);
        return resp;
    }
}
