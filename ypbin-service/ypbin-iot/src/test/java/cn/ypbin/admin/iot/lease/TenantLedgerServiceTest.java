/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.TenantLedger;
import cn.ypbin.admin.iot.mapper.IotDeviceCredentialMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.TenantLedgerMapper;
import cn.ypbin.admin.iot.model.resp.TenantLedgerResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户台账服务单测（M-2：配置版本号的推进语义 + 运维写入口）。
 *
 * <p>守两条：① 台账没有该租户时 {@code bumpConfigEpoch} 必须是 no-op（绝不顺手 insert——那会把
 * 「可分配来源」从配置兜底静默切成台账）；② 写入口返回的是**回读**的落库状态，而不是调用方的意图
 * （本仓有过「声称改了、产物没落地」的教训）。真库语义由 {@code TenantLedgerIT} 覆盖。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
class TenantLedgerServiceTest {

    private static final Long TENANT = 11L;

    private final TenantLedgerMapper ledgerMapper = mock(TenantLedgerMapper.class);
    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotDeviceCredentialMapper credentialMapper = mock(IotDeviceCredentialMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TenantLedgerService service = new TenantLedgerService(
        ledgerMapper, deviceMapper, credentialMapper, meterRegistry);

    @BeforeEach
    void setUp() {
        // 台账有行时 listAll/回读会各打一条批量聚合 SQL；默认空统计 = 无设备/无有效凭据（事实零值）
        when(deviceMapper.selectMaps(any())).thenReturn(List.of());
        when(credentialMapper.selectMaps(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 配置版本号推进：台账有该租户才推进；无该租户/无租户上下文一律 no-op（不得 insert）")
    void bumpConfigEpochMustBeNoopWhenLedgerRowMissing() {
        when(ledgerMapper.bumpConfigEpoch(TENANT)).thenReturn(1);
        assertThat(service.bumpConfigEpoch(TENANT)).isTrue();

        when(ledgerMapper.bumpConfigEpoch(22L)).thenReturn(0);
        assertThat(service.bumpConfigEpoch(22L)).as("台账无该租户 ⇒ 未发信号").isFalse();

        assertThat(service.bumpConfigEpoch(null)).as("无租户上下文 ⇒ 直接 no-op").isFalse();
        verify(ledgerMapper, never()).insert(any(TenantLedger.class));
    }

    @Test
    @DisplayName("按当前租户上下文推进：有上下文才打库，没有上下文（未开租户插件）不得抛错")
    void bumpOfCurrentTenantMustFollowTenantContext() {
        assertThat(service.bumpConfigEpochOfCurrentTenant()).isFalse();
        verify(ledgerMapper, never()).bumpConfigEpoch(any());

        when(ledgerMapper.bumpConfigEpoch(TENANT)).thenReturn(1);
        Boolean bumped = TenantContext.executeWithTenant(TENANT,
            service::bumpConfigEpochOfCurrentTenant);
        assertThat(bumped).isTrue();
        verify(ledgerMapper).bumpConfigEpoch(TENANT);
    }

    @Test
    @DisplayName("★ 写入口返回**回读**的落库状态：版本号与变更类型都来自数据库")
    void setAssignableAndGetMustReturnPersistedState() {
        // 第一次查（写之前）为空 ⇒ 走新建；第二次查（回读）返回落库后的行
        when(ledgerMapper.selectIncludingDeleted(TENANT))
            .thenReturn(null, persistedRow(1L, Boolean.TRUE));

        TenantLedgerResp resp = service.setAssignableAndGet(TENANT, true);

        assertThat(resp.getTenantId()).isEqualTo(TENANT);
        assertThat(resp.getAssignable()).isTrue();
        assertThat(resp.getConfigEpoch()).as("版本号必须来自回读的落库行").isEqualTo(1L);
        assertThat(resp.getChange()).isEqualTo("created");
        verify(ledgerMapper).insert(any(TenantLedger.class));
    }

    @Test
    @DisplayName("★ 写完却读不到 ⇒ 抛错，不得返回 null/半份状态")
    void setAssignableAndGetMustFailWhenReadBackMissing() {
        when(ledgerMapper.selectIncludingDeleted(TENANT)).thenReturn(null, (TenantLedger) null);

        assertThatThrownBy(() -> service.setAssignableAndGet(TENANT, true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("回读失败");
    }

    @Test
    @DisplayName("台账全量：逐行映射为契约模型，按租户排序由 SQL 负责；空结果返回空集合")
    void listAllShouldMapRows() {
        when(ledgerMapper.selectList(any())).thenReturn(List.of(persistedRow(1L, Boolean.TRUE)));
        List<TenantLedgerResp> rows = service.listAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getTenantId()).isEqualTo(TENANT);

        when(ledgerMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listAll()).as("查询类不得返回 null").isEmpty();
    }

    @Test
    @DisplayName("★ 台账全量：设备数与最近上报按 tenant_id 批量聚合，凭据只认 password_hash 非空的行")
    void listAllShouldAggregateDeviceAndCredentialStats() {
        TenantLedger first = persistedRow(1L, Boolean.TRUE);
        TenantLedger second = new TenantLedger();
        second.setTenantId(22L);
        second.setAssignable(Boolean.FALSE);
        second.setConfigEpoch(3L);
        when(ledgerMapper.selectList(any())).thenReturn(List.of(first, second));

        LocalDateTime lastReportAt = LocalDateTime.of(2026, 10, 14, 10, 30);
        when(deviceMapper.selectMaps(any())).thenReturn(List.of(
            Map.of("tenant_id", TENANT, "device_count", 3L, "last_report_at", lastReportAt),
            // 22 号租户用大写/无下划线别名，且无 last_report_at 列：一起覆盖 key 兼容与「从未上报」
            Map.of("TENANT_ID", 22L, "DEVICECOUNT", 5L)));
        when(credentialMapper.selectMaps(any())).thenReturn(List.of(
            Map.of("tenant_id", TENANT, "credential_count", 1L)));

        List<TenantLedgerResp> rows = service.listAll();

        assertThat(rows).hasSize(2);
        TenantLedgerResp withStats = rows.getFirst();
        assertThat(withStats.getDeviceCount()).isEqualTo(3);
        assertThat(withStats.getLastReportAt()).isEqualTo(lastReportAt);
        assertThat(withStats.getHasCredential()).as("存在未删除且哈希非空的凭据行").isTrue();

        TenantLedgerResp uppercaseKeys = rows.get(1);
        assertThat(uppercaseKeys.getDeviceCount()).as("别名大小写/下划线差异必须兼容").isEqualTo(5);
        assertThat(uppercaseKeys.getLastReportAt()).as("从未上报 ⇒ null，不是 0").isNull();
        assertThat(uppercaseKeys.getHasCredential()).as("无凭据行 ⇒ false，是事实不是未统计").isFalse();
    }

    @Test
    @DisplayName("★ 聚合空统计回落为事实零值：deviceCount=0、lastReportAt=null、hasCredential=false")
    void listAllShouldFallBackToFactDefaultsWithoutStats() {
        when(ledgerMapper.selectList(any())).thenReturn(List.of(persistedRow(1L, Boolean.TRUE)));
        when(deviceMapper.selectMaps(any())).thenReturn(List.of());
        when(credentialMapper.selectMaps(any())).thenReturn(List.of());

        TenantLedgerResp resp = service.listAll().getFirst();

        assertThat(resp.getDeviceCount()).isZero();
        assertThat(resp.getLastReportAt()).isNull();
        assertThat(resp.getHasCredential()).isFalse();
    }

    @Test
    @DisplayName("最近上报时刻兼容驱动返回 java.sql.Timestamp 的形态")
    void listAllShouldAcceptTimestampLastReportAt() {
        when(ledgerMapper.selectList(any())).thenReturn(List.of(persistedRow(1L, Boolean.TRUE)));
        LocalDateTime lastReportAt = LocalDateTime.of(2026, 10, 12, 8, 15, 30);
        when(deviceMapper.selectMaps(any())).thenReturn(List.of(
            Map.<String, Object>of("tenant_id", TENANT, "device_count", 1L,
                "last_report_at", Timestamp.valueOf(lastReportAt))));

        assertThat(service.listAll().getFirst().getLastReportAt()).isEqualTo(lastReportAt);
    }

    @Test
    @DisplayName("台账无行时不得空打聚合查询（批量查询前先短路）")
    void listAllShouldNotQueryAggregatesWhenLedgerEmpty() {
        when(ledgerMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listAll()).isEmpty();
        verify(deviceMapper, never()).selectMaps(any());
        verify(credentialMapper, never()).selectMaps(any());
    }

    @Test
    @DisplayName("★ 写入口回读也填充聚合列（与列表同一口径），且不破坏「回读落库状态」")
    void setAssignableAndGetShouldFillAggregatesOnReadBack() {
        when(ledgerMapper.selectIncludingDeleted(TENANT))
            .thenReturn(null, persistedRow(1L, Boolean.TRUE));
        LocalDateTime lastReportAt = LocalDateTime.of(2026, 10, 13, 9, 0);
        when(deviceMapper.selectMaps(any())).thenReturn(List.of(
            Map.of("tenant_id", TENANT, "device_count", 2L, "last_report_at", lastReportAt)));
        when(credentialMapper.selectMaps(any())).thenReturn(List.of(
            Map.of("tenant_id", TENANT, "credential_count", 4L)));

        TenantLedgerResp resp = service.setAssignableAndGet(TENANT, true);

        assertThat(resp.getDeviceCount()).isEqualTo(2);
        assertThat(resp.getLastReportAt()).isEqualTo(lastReportAt);
        assertThat(resp.getHasCredential()).isTrue();
        assertThat(resp.getConfigEpoch()).as("版本号仍必须来自回读的落库行").isEqualTo(1L);
        assertThat(resp.getChange()).isEqualTo("created");
    }

    private static TenantLedger persistedRow(Long configEpoch, Boolean assignable) {
        TenantLedger row = new TenantLedger();
        row.setTenantId(TENANT);
        row.setAssignable(assignable);
        row.setConfigEpoch(configEpoch);
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }

    @Test
    @DisplayName("★ R8-8 方案 B：台账写失败**不得**向上抛（否则会连带回滚设备/点位业务写入），但必须计数")
    void bumpFailureMustNotPropagateButMustBeCounted() {
        when(ledgerMapper.bumpConfigEpoch(any())).thenThrow(
            new DataAccessResourceFailureException("ledger table unavailable"));

        boolean bumped = service.bumpConfigEpoch(7L);

        assertThat(bumped).as("失败按「未推进」返回 false，绝不向上抛（可用性优先）").isFalse();
        assertThat(meterRegistry.get("iot.ledger.bump.failure").counter().count())
            .as("失败必须计数（版本号不推进 ⇒ 接入侧只能等周期安全网，必须可告警）").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ R8-8 边界：锁类失败（死锁/锁等待超时）**必须重新抛出**——DB 已回滚整事务，吞掉会「报成功但写入已丢」")
    void lockFailureMustBeRethrown() {
        when(ledgerMapper.bumpConfigEpoch(any())).thenThrow(
            new DeadlockLoserDataAccessException("deadlock", new SQLException("1213")));

        assertThatThrownBy(() -> service.bumpConfigEpoch(7L))
            .as("锁类失败整事务已被 DB 回滚，必须上抛（否则外层提交会谎报成功）")
            .isInstanceOf(DeadlockLoserDataAccessException.class);
        assertThat(meterRegistry.get("iot.ledger.bump.failure").counter().count())
            .as("上抛同样要计数（失败确实发生过）").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("R8-8：成功与 no-op 路径都不得误增失败计数（否则指标失去判别力）")
    void successAndNoopMustNotCountFailure() {
        when(ledgerMapper.bumpConfigEpoch(any())).thenReturn(1);
        assertThat(service.bumpConfigEpoch(7L)).isTrue();
        assertThat(meterRegistry.get("iot.ledger.bump.failure").counter().count()).isZero();

        when(ledgerMapper.bumpConfigEpoch(any())).thenReturn(0);
        assertThat(service.bumpConfigEpoch(8L)).as("台账无该租户 ⇒ no-op").isFalse();
        assertThat(meterRegistry.get("iot.ledger.bump.failure").counter().count())
            .as("no-op 不是失败，不得计数").isZero();
    }

    @Test
    @DisplayName("R8-8：MyBatis 的 PersistenceException 形态同样被捕获（不穿透）")
    void mybatisPersistenceExceptionMustBeSwallowed() {
        when(ledgerMapper.bumpConfigEpoch(any())).thenThrow(
            new PersistenceException("mapper boom"));

        assertThat(service.bumpConfigEpoch(7L)).isFalse();
        assertThat(meterRegistry.get("iot.ledger.bump.failure").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 声明门禁：bumpConfigEpochOfCurrentTenant 必须带 @Transactional（自调用路径的唯一事务来源）")
    void bumpOfCurrentTenantMustDeclareTransactional() throws Exception {
        // 为什么用反射钉住：该方法是**自调用** bumpConfigEpoch 的唯一入口，其 @Transactional 是
        // 非事务上下文下唯一的开事务来源；删掉它不会有任何测试变红（复核实测 M2），故用声明门禁兜住。
        // 更强的「代理级」验证需要 Spring 上下文 + DataSource，属未做项（见台账 §2.6）。
        assertThat(TenantLedgerService.class.getMethod("bumpConfigEpochOfCurrentTenant")
            .getAnnotation(Transactional.class))
            .as("bumpConfigEpochOfCurrentTenant 必须带 @Transactional：非事务上下文调用时靠它开事务")
            .isNotNull();
    }
}
