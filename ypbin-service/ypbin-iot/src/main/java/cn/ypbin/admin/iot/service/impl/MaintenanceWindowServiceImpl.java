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

    public MaintenanceWindowServiceImpl(MaintenanceWindowMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long open(MaintenanceWindowReq req) {
        Long tenantId = TenantContext.getTenantId().orElse(null);
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
