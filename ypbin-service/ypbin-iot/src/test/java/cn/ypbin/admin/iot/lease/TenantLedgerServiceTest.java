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
import cn.ypbin.starter.tenant.core.TenantContext;
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
    private final TenantLedgerService service = new TenantLedgerService(ledgerMapper);

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
}
