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

import cn.ypbin.admin.iot.entity.IotDashboardDailySnapshot;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.enums.OnlineStatus;
import cn.ypbin.admin.iot.mapper.IotDashboardDailySnapshotMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.service.IotDashboardSnapshotService;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 仪表盘每日在线快照服务实现。
 *
 * <p><b>租户隔离</b>：不手写 {@code tenant_id} 过滤——统计、查重、插入/更新全部由 MyBatis-Plus
 * 租户插件按当前请求租户追加条件；无租户上下文时插件 fail-closed 直接拒绝（不会写成跨租户脏数据）。</p>
 *
 * <p><b>并发</b>：同租户同一天只有一行（唯一键兜底）。「先查后写」在并发首写时会撞唯一键，此时捕获
 * {@link DuplicateKeyException} 回退为更新；若冲突后仍查不到行则原样抛出，不静默吞异常。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Service
@RequiredArgsConstructor
public class IotDashboardSnapshotServiceImpl implements IotDashboardSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(IotDashboardSnapshotServiceImpl.class);

    private final IotDashboardDailySnapshotMapper iotDashboardDailySnapshotMapper;

    private final IotDeviceMapper iotDeviceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void captureCurrentTenant() {
        LocalDate today = LocalDate.now();
        int deviceTotal = toCount(iotDeviceMapper.selectCount(new QueryWrapper<>()));
        int deviceOnline = toCount(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .eq("online_status", OnlineStatus.ONLINE.getCode())));
        int deviceOffline = toCount(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .eq("online_status", OnlineStatus.OFFLINE.getCode())));
        int deviceDisabled = toCount(iotDeviceMapper.selectCount(new QueryWrapper<IotDevice>()
            .eq("status", EntityStatus.DISABLED.getCode())));

        IotDashboardDailySnapshot existing = findToday(today);
        if (existing != null) {
            applyCounts(existing, deviceTotal, deviceOnline, deviceOffline, deviceDisabled);
            iotDashboardDailySnapshotMapper.updateById(existing);
            return;
        }

        IotDashboardDailySnapshot snapshot = new IotDashboardDailySnapshot();
        snapshot.setSnapshotDate(today);
        applyCounts(snapshot, deviceTotal, deviceOnline, deviceOffline, deviceDisabled);
        try {
            iotDashboardDailySnapshotMapper.insert(snapshot);
        } catch (DuplicateKeyException ex) {
            // 唯一键冲突说明并发事务已插入当天行；用锁定读取最新已提交版本（REPEATABLE READ 下
            // 普通 SELECT 可能仍看不到别人刚提交的行，锁读会读最新版本）
            IotDashboardDailySnapshot concurrent = findTodayForUpdate(today);
            if (concurrent == null) {
                // 冲突后仍无当天行：不是「同日快照被并发插入」这一可恢复场景，原样抛出
                throw ex;
            }
            log.info("仪表盘快照并发首写冲突，改为更新：snapshotDate={}", today);
            applyCounts(concurrent, deviceTotal, deviceOnline, deviceOffline, deviceDisabled);
            iotDashboardDailySnapshotMapper.updateById(concurrent);
        }
    }

    @Override
    public List<IotDashboardDailySnapshot> loadRecent(int days) {
        if (days <= 0) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        List<IotDashboardDailySnapshot> rows = iotDashboardDailySnapshotMapper.selectList(
            new QueryWrapper<IotDashboardDailySnapshot>()
                .ge("snapshot_date", from)
                .le("snapshot_date", today)
                .orderByAsc("snapshot_date"));
        return rows == null ? List.of() : rows;
    }

    /**
     * 查当前租户今天的快照行（租户条件由插件追加）。
     *
     * @param today 今天
     * @return 快照行；不存在返回 {@code null}
     */
    private IotDashboardDailySnapshot findToday(LocalDate today) {
        return iotDashboardDailySnapshotMapper.selectOne(new QueryWrapper<IotDashboardDailySnapshot>()
            .eq("snapshot_date", today));
    }

    /**
     * 锁定读当前租户今天的快照行（仅用于唯一键冲突后的回退路径）。
     *
     * @param today 今天
     * @return 快照行；不存在返回 {@code null}
     */
    private IotDashboardDailySnapshot findTodayForUpdate(LocalDate today) {
        return iotDashboardDailySnapshotMapper.selectOne(new QueryWrapper<IotDashboardDailySnapshot>()
            .eq("snapshot_date", today)
            .last("FOR UPDATE"));
    }

    /**
     * 把本次统计结果写入快照对象（不改 snapshotDate / id 等身份字段）。
     *
     * @param snapshot       目标快照
     * @param deviceTotal    设备总数
     * @param deviceOnline   在线设备数
     * @param deviceOffline  离线设备数
     * @param deviceDisabled 停用设备数
     */
    private static void applyCounts(IotDashboardDailySnapshot snapshot, int deviceTotal, int deviceOnline,
                                    int deviceOffline, int deviceDisabled) {
        snapshot.setDeviceTotal(deviceTotal);
        snapshot.setDeviceOnline(deviceOnline);
        snapshot.setDeviceOffline(deviceOffline);
        snapshot.setDeviceDisabled(deviceDisabled);
    }

    /**
     * 计数转换：空值或不适合 {@code int} 时显式失败，不静默截断。
     *
     * @param count {@code selectCount} 返回值
     * @return 计数
     */
    private static int toCount(Long count) {
        if (count == null) {
            throw new IllegalStateException("设备计数查询返回 null");
        }
        return Math.toIntExact(count);
    }
}
