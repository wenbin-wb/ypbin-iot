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

import cn.ypbin.admin.iot.availability.MaintenanceSource;
import cn.ypbin.admin.iot.availability.MaintenanceWindowDto;
import cn.ypbin.admin.iot.availability.MaintenanceWindowReq;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.service.MaintenanceWindowService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护窗口实现（M-2）。
 *
 * @author wenbin
 * @since 2026-09-23
 */
@Service
public class MaintenanceWindowServiceImpl implements MaintenanceWindowService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowServiceImpl.class);

    /** 单次查询返回上限（避免一个长区间把响应撑爆）。 */
    static final int MAX_LIST_ROWS = 200;

    private final MaintenanceWindowMapper mapper;

    /** 租户来源：与 MP 租户插件**完全一致**（ThreadLocal 优先，其次 TenantProvider/IdentityContext）。 */
    private final TenantProvider tenantProvider;

    public MaintenanceWindowServiceImpl(MaintenanceWindowMapper mapper, TenantProvider tenantProvider) {
        this.mapper = mapper;
        this.tenantProvider = tenantProvider;
    }

    /** 当前请求的租户（与插件同源；只读 TenantContext 会在真实请求上误报「缺少租户上下文」）。 */
    private Long currentTenantId() {
        return TenantContext.getTenantId().or(tenantProvider::getCurrentTenantId).orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long open(MaintenanceWindowReq req) {
        Long tenantId = currentTenantId();
        if (tenantId == null) {
            // 维护窗口是租户数据：没有租户上下文绝不猜、不写「无租户」的行
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "缺少租户上下文，无法声明维护窗口");
        }
        // 时间基准取数据库时钟：维护窗口与断档/可用率的比较必须同源（本机钟漂移会把窗口挪位）
        LocalDateTime now = mapper.selectNow();
        LocalDateTime startTs = req.getStartTs() == null ? now : req.getStartTs();
        LocalDateTime endTs = req.getEndTs();
        if (endTs != null && !endTs.isAfter(startTs)) {
            // 给反/相等一律报错，不静默交换（否则会得到「零长度」的窗口，运维以为配上了其实没有）
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "维护窗口结束必须晚于开始：" + startTs + " >= " + endTs);
        }
        // 重叠校验：同设备范围（本设备 + 租户级）内的窗口**不得重叠**——聚合按「逐窗口求交后求和」统计，
        // 重叠会重复计数（分子被断档封顶 ⇒ 可用率偏高；分母重复扣 ⇒ 统计总时长偏小、极端下 fail-open）。
        LocalDateTime probeTo = endTs == null ? LocalDateTime.of(9999, 12, 31, 23, 59, 59) : endTs;
        // ⚠️ 设备范围谓词只在**新窗口是设备级**时收窄：租户级窗口与「该租户的任意窗口」（含各设备级）
        //    都可能重叠——早先只写 `(device_id IS NULL OR device_id = #{deviceId})`，新窗口为租户级时
        //    退化成只看租户级窗口，**看不到已有设备级窗口** ⇒ 先声明设备级、再声明同区间租户级就绕过了不变量
        //    （外委复核实测：重复计数会把可用率抬成 100% 且判达标 = fail-open）。
        List<MaintenanceWindow> overlapped = mapper.selectList(Wrappers.<MaintenanceWindow>lambdaQuery()
            .and(req.getDeviceId() != null, wrapper -> wrapper.isNull(MaintenanceWindow::getDeviceId)
                .or().eq(MaintenanceWindow::getDeviceId, req.getDeviceId()))
            .lt(MaintenanceWindow::getStartTs, probeTo)
            .and(wrapper -> wrapper.isNull(MaintenanceWindow::getEndTs)
                .or().gt(MaintenanceWindow::getEndTs, startTs)));
        if (!overlapped.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "维护窗口与已有窗口重叠（重叠会重复计数导致可用率偏高）：已有窗口 ID="
                    + overlapped.get(0).getId() + " " + overlapped.get(0).getStartTs() + " ~ "
                    + overlapped.get(0).getEndTs());
        }
        MaintenanceWindow row = new MaintenanceWindow();
        row.setTenantId(tenantId);
        row.setDeviceId(req.getDeviceId());
        row.setStartTs(startTs);
        row.setEndTs(endTs);
        row.setSource(MaintenanceSource.MANUAL.getCode());
        row.setReason(req.getReason());
        mapper.insert(row);
        log.info("[iot] 声明维护窗口：tenantId={} deviceId={} from={} to={} reason={}",
            LogSanitizer.sanitize(tenantId), LogSanitizer.sanitize(req.getDeviceId()), startTs, endTs,
            LogSanitizer.sanitize(req.getReason()));
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int close(Long id) {
        if (id == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "维护窗口 ID 不能为空");
        }
        LocalDateTime now = mapper.selectNow();
        // 只关「进行中」的：已完成/已删除的窗口不得被改写（否则会把历史窗口拉长、凭空排除那段时间）
        int rows = mapper.update(null, Wrappers.<MaintenanceWindow>lambdaUpdate()
            .eq(MaintenanceWindow::getId, id)
            .isNull(MaintenanceWindow::getEndTs)
            .set(MaintenanceWindow::getEndTs, now)
            .set(MaintenanceWindow::getUpdateTime, now));
        if (rows == 0) {
            log.warn("[iot] 关闭维护窗口未命中（不存在或已结束）：id={}", LogSanitizer.sanitize(id));
        }
        return rows;
    }

    @Override
    public List<MaintenanceWindowDto> list(Long deviceId, LocalDateTime from, LocalDateTime to) {
        List<MaintenanceWindow> rows = mapper.selectList(Wrappers.<MaintenanceWindow>lambdaQuery()
            .and(deviceId != null, wrapper -> wrapper.isNull(MaintenanceWindow::getDeviceId)
                .or().eq(MaintenanceWindow::getDeviceId, deviceId))
            .lt(to != null, MaintenanceWindow::getStartTs, to)
            .and(from != null, wrapper -> wrapper.isNull(MaintenanceWindow::getEndTs)
                .or().gt(MaintenanceWindow::getEndTs, from))
            .orderByDesc(MaintenanceWindow::getStartTs)
            .last("LIMIT " + MAX_LIST_ROWS));
        return rows.stream().map(row -> {
            MaintenanceWindowDto dto = new MaintenanceWindowDto();
            dto.setId(row.getId());
            dto.setDeviceId(row.getDeviceId());
            dto.setStartTs(row.getStartTs());
            dto.setEndTs(row.getEndTs());
            dto.setSource(row.getSource());
            dto.setReason(row.getReason());
            return dto;
        }).toList();
    }
}
