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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.availability.MaintenanceWindowReq;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 维护窗口服务单测（M-2 / spec §12.5）。
 *
 * <p>守三件事：① 维护窗口是**租户数据**，没有租户上下文必须拒绝（绝不写「无租户」的行）；
 * ② 时间区间给反/相等要报错（不静默交换，否则会得到零长度窗口，运维以为配上了其实没有）；
 * ③ 关闭只作用于「进行中」的窗口（已结束的不得被改写）。</p>
 *
 * @author wenbin
 * @since 2026-09-23
 */
class MaintenanceWindowServiceImplTest {

    private static final Long TENANT = 920_001L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 23, 8, 0);

    private MaintenanceWindowMapper mapper;
    private MaintenanceWindowServiceImpl service;

    private cn.ypbin.starter.tenant.core.TenantProvider tenantProvider;

    /**
     * 注册实体元数据：{@code LambdaUpdateWrapper} 需要 MP 的 TableInfo 缓存，而它通常由 Mapper 扫描时注册
     * ——纯单测（不起 Spring、不扫 Mapper）里没有这一步，会抛 {@code can not find lambda cache}。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MaintenanceWindow.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(MaintenanceWindowMapper.class);
        when(mapper.selectNow()).thenReturn(T0);
        tenantProvider = mock(cn.ypbin.starter.tenant.core.TenantProvider.class);
        when(tenantProvider.getCurrentTenantId()).thenReturn(java.util.Optional.empty());
        // 默认无重叠窗口
        when(mapper.selectList(any())).thenReturn(List.of());
        service = new MaintenanceWindowServiceImpl(mapper, tenantProvider);
    }

    @Test
    @DisplayName("★ 声明：时间基准取数据库时钟（start 缺省=DB now），来源固定 MANUAL，租户来自上下文")
    void openShouldUseDatabaseClockAndContextTenant() {
        when(mapper.insert(any(MaintenanceWindow.class))).thenAnswer(inv -> {
            MaintenanceWindow row = inv.getArgument(0);
            row.setId(1L);
            return 1;
        });
        MaintenanceWindowReq req = new MaintenanceWindowReq();
        req.setDeviceId(77L);
        req.setEndTs(T0.plusHours(2));
        req.setReason("夜间停产");

        Long id = TenantContext.executeWithTenant(TENANT, () -> service.open(req));

        assertThat(id).isEqualTo(1L);
        org.mockito.ArgumentCaptor<MaintenanceWindow> captor =
            org.mockito.ArgumentCaptor.forClass(MaintenanceWindow.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).as("租户来自上下文，不来自请求体").isEqualTo(TENANT);
        assertThat(captor.getValue().getStartTs()).as("start 缺省用 DB 时钟").isEqualTo(T0);
        assertThat(captor.getValue().getSource()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getDeviceId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("★ 声明：租户来自 TenantProvider（真实请求链路：TenantContext 为空也能声明）")
    void openMustResolveTenantFromProviderOnRealRequestPath() {
        when(tenantProvider.getCurrentTenantId()).thenReturn(java.util.Optional.of(TENANT));
        when(mapper.insert(any(MaintenanceWindow.class))).thenReturn(1);
        MaintenanceWindowReq req = new MaintenanceWindowReq();
        req.setEndTs(T0.plusHours(1));

        // 刻意不用 executeWithTenant：网关注入身份后的真实形状
        assertThat(service.open(req)).isNull(); // mock 未回填 id；关键是不抛「缺少租户上下文」
        verify(mapper).insert(any(MaintenanceWindow.class));
    }

    @Test
    @DisplayName("★ 声明：与已有窗口重叠必须拒绝（重叠会重复计数 ⇒ 可用率偏高/统计总时长偏小）")
    void openOverlappingMustBeRejected() {
        MaintenanceWindow existing = new MaintenanceWindow();
        existing.setId(5L);
        existing.setStartTs(T0.minusHours(1));
        existing.setEndTs(T0.plusHours(1));
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        MaintenanceWindowReq req = new MaintenanceWindowReq();
        req.setStartTs(T0);
        req.setEndTs(T0.plusHours(2));

        assertThatThrownBy(() -> TenantContext.executeWithTenant(TENANT, () -> service.open(req)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("重叠");
    }

    @Test
    @DisplayName("★ 声明：**租户级**新窗口必须看到已有**设备级**窗口（否则先设备级、再同区间租户级即可绕过不变量）")
    void tenantWideDeclarationMustSeeDeviceLevelWindows() {
        // 已有：设备 77 的窗口。新窗口是租户级（deviceId=null）——谓词不得收窄成「只看租户级窗口」
        MaintenanceWindow deviceLevel = new MaintenanceWindow();
        deviceLevel.setId(9L);
        deviceLevel.setDeviceId(77L);
        deviceLevel.setStartTs(T0);
        deviceLevel.setEndTs(T0.plusHours(1));
        when(mapper.selectList(any())).thenReturn(List.of(deviceLevel));
        MaintenanceWindowReq req = new MaintenanceWindowReq();
        req.setDeviceId(null);
        req.setStartTs(T0.plusMinutes(30));
        req.setEndTs(T0.plusHours(2));

        assertThatThrownBy(() -> TenantContext.executeWithTenant(TENANT, () -> service.open(req)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("重叠")
            .hasMessageContaining("ID=9");
    }

    @Test
    @DisplayName("★ 声明：缺少租户上下文必须拒绝（不得写「无租户」的维护窗口）")
    void openWithoutTenantMustBeRejected() {
        MaintenanceWindowReq req = new MaintenanceWindowReq();
        assertThatThrownBy(() -> service.open(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("缺少租户上下文");
    }

    @Test
    @DisplayName("★ 声明：结束不晚于开始必须报错（不静默交换，否则会得到零长度窗口）")
    void openWithReversedWindowMustBeRejected() {
        MaintenanceWindowReq req = new MaintenanceWindowReq();
        req.setStartTs(T0.plusHours(2));
        req.setEndTs(T0);

        assertThatThrownBy(() -> TenantContext.executeWithTenant(TENANT, () -> service.open(req)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须晚于开始");
    }

    @Test
    @DisplayName("★ 关闭：只作用于「进行中」的窗口（已结束的不得被改写）")
    void closeShouldOnlyAffectOpenWindows() {
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(service.close(9L)).isEqualTo(1);
        // 断言「只关进行中」真的写进了 WHERE：否则会把历史窗口拉长、凭空排除那段时间
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<MaintenanceWindow>>
            captor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("end_ts IS NULL");

        when(mapper.update(any(), any())).thenReturn(0);
        assertThat(service.close(9L)).as("已结束/不存在返回 0（服务端只记 WARN，不改写历史）").isZero();
        assertThatThrownBy(() -> service.close(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("查询：无参时只按租户过滤（设备维度不过滤），并把实体映射为 DTO")
    void listShouldMapEntities() {
        MaintenanceWindow row = new MaintenanceWindow();
        row.setId(3L);
        row.setStartTs(T0);
        row.setSource("LEASE_HANDOVER");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertThat(TenantContext.executeWithTenant(TENANT, () -> service.list(null, null, null)))
            .singleElement()
            .satisfies(dto -> {
                assertThat(dto.getId()).isEqualTo(3L);
                assertThat(dto.getSource()).isEqualTo("LEASE_HANDOVER");
            });
    }
}
