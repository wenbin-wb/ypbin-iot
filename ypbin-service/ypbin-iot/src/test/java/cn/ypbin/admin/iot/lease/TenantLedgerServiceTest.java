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
import cn.ypbin.admin.iot.mapper.TenantLedgerMapper;
import cn.ypbin.admin.iot.model.resp.TenantLedgerResp;
import cn.ypbin.starter.core.exception.BusinessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import cn.ypbin.starter.tenant.core.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TenantLedgerService service = new TenantLedgerService(ledgerMapper, meterRegistry);

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
            new DeadlockLoserDataAccessException("deadlock", new java.sql.SQLException("1213")));

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
            new org.apache.ibatis.exceptions.PersistenceException("mapper boom"));

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
