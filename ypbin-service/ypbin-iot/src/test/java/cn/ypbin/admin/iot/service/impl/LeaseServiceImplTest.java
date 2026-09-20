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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.TenantNodeAssignment;
import cn.ypbin.admin.iot.lease.AccessNodeRegisterReq;
import cn.ypbin.admin.iot.lease.AccessNodeRegistry;
import cn.ypbin.admin.iot.lease.AssignmentQueryReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireReq;
import cn.ypbin.admin.iot.lease.LeaseAcquireResp;
import cn.ypbin.admin.iot.lease.LeaseEpochRules;
import cn.ypbin.admin.iot.lease.LeaseProperties;
import cn.ypbin.admin.iot.lease.LeaseReleaseReq;
import cn.ypbin.admin.iot.lease.LeaseRenewItem;
import cn.ypbin.admin.iot.lease.LeaseRenewReq;
import cn.ypbin.admin.iot.lease.LeaseRenewResp;
import cn.ypbin.admin.iot.lease.LeaseState;
import cn.ypbin.admin.iot.lease.TenantEpochBatchResp;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 租约服务测试：**归属变更的原子性语义**（CAS 守卫条件、epoch 推进、容量、回收）。
 *
 * <p>为什么用 mock 而不是真库：本机不跑容器/MySQL（项目约定），而本节要验证的是
 * 「服务发出的 SQL 守卫条件是否正确」与「affectedRows 的各种取值下行为是否正确」——
 * 前者通过捕获 Wrapper 断言 SQL 片段，后者通过桩返回 0/1 断言分支；
 * 真实并发（多副本抢同一租户）需要真库，属 M0b/P5 的端到端范围，已在方法注释里标注。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseServiceImplTest {

    private static final String NODE = "access-1";

    /**
     * 初始化 MyBatis-Plus 的实体元信息。
     *
     * <p>{@code LambdaUpdateWrapper}/{@code LambdaQueryWrapper} 用方法引用解析列名，列名来自实体的
     * TableInfo——它通常由 Mapper 扫描注册；纯单测（不起 Spring、不扫 Mapper）里没有这一步。
     * 不初始化会直接 NPE 或报「can not find lambda cache for this entity」。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            TenantNodeAssignment.class);
    }

    private TenantNodeAssignmentMapper mapper;
    private AccessNodeRegistry registry;
    private LeaseProperties properties;
    private LeaseServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(TenantNodeAssignmentMapper.class);
        registry = new AccessNodeRegistry();
        properties = new LeaseProperties();
        properties.setAssignableTenantIds(List.of(11L, 22L));
        lenient().when(mapper.selectList(any())).thenReturn(List.of());
        lenient().when(mapper.selectCount(any())).thenReturn(0L);
        service = new LeaseServiceImpl(mapper, registry, properties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("注册：节点与容量进注册表；claim 未注册节点时必须显式报错（不隐式注册）")
    void registerThenAcquireRejectsUnknownNode() {
        AccessNodeRegisterReq register = new AccessNodeRegisterReq();
        register.setAccessNode(NODE);
        register.setMaxTenants(5);
        service.register(register);

        assertThat(registry.isRegistered(NODE)).isTrue();
        assertThat(registry.capacityOf(NODE)).isEqualTo(5);

        LeaseAcquireReq acquire = new LeaseAcquireReq();
        acquire.setAccessNode("access-ghost");
        assertThatThrownBy(() -> service.acquire(acquire))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("节点未注册");
    }

    @Test
    @DisplayName("领取：先续期自己在采的（单条 UPDATE，守卫 access_node + state=active）")
    void acquireShouldRenewHeldTenantsWithSingleCasUpdate() {
        registerNode();
        when(mapper.update(isNull(), any())).thenReturn(1);
        TenantNodeAssignment held = assignment(11L, NODE, 1L, LeaseState.ACTIVE);
        when(mapper.selectList(any())).thenReturn(List.of(held));

        LeaseAcquireResp resp = service.acquire(acquireReq(NODE));

        assertThat(resp.getAccessNode()).isEqualTo(NODE);
        assertThat(resp.getAssignments()).hasSize(1);
        assertThat(resp.getAssignments().get(0).getTenantId()).isEqualTo(11L);
        assertThat(resp.getAssignments().get(0).getState()).isEqualTo(LeaseState.ACTIVE);
        assertThat(resp.getAssignments().get(0).getEpoch()).isEqualTo(1L);

        // acquire 会发多条 update（① 续期自己持有的 ② 接管候选）；这里只断言第①条
        List<LambdaUpdateWrapper<TenantNodeAssignment>> updates = allUpdates();
        assertThat(updates).isNotEmpty();
        LambdaUpdateWrapper<TenantNodeAssignment> renew = updates.get(0);
        assertThat(renew.getSqlSet()).contains("lease_expire_at");
        assertThat(renew.getSqlSegment()).contains("access_node").contains("state");
    }

    @Test
    @DisplayName("首次分配：可分配但无归属行的租户被 INSERT，epoch 取初始值 1")
    void acquireShouldInsertNewAssignmentsWithInitialEpoch() {
        registerNode();
        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insertIgnoringDuplicates(any())).thenReturn(2);

        service.acquire(acquireReq(NODE));

        // 一次批量写入（不是逐行 insert：循环内 DB 调用是 N+1，架构门禁会拦）
        ArgumentCaptor<List<TenantNodeAssignment>> captor = listCaptor();
        verify(mapper, times(1)).insertIgnoringDuplicates(captor.capture());
        assertThat(captor.getValue()).extracting(TenantNodeAssignment::getTenantId)
            .containsExactly(11L, 22L);
        assertThat(captor.getValue()).allSatisfy(a -> {
            assertThat(a.getId()).as("批量语句绕过字段填充，id 必须预生成").isNotNull();
            assertThat(a.getEpoch()).isEqualTo(LeaseEpochRules.INITIAL_EPOCH);
            assertThat(a.getState()).isEqualTo(LeaseState.ACTIVE.getCode());
            assertThat(a.getAccessNode()).isEqualTo(NODE);
            assertThat(a.getLeaseExpireAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("首次分配撞唯一键 = 别人先到：批量语句跳过冲突行、其余照常写入（不抛给调用方）")
    void duplicateInsertMeansLostRace() {
        registerNode();
        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectList(any())).thenReturn(List.of());
        // 批量语句里 2 行有 1 行被唯一键跳过：返回 1，调用方不该感知异常
        when(mapper.insertIgnoringDuplicates(any())).thenReturn(1);

        service.acquire(acquireReq(NODE));

        verify(mapper, times(1)).insertIgnoringDuplicates(any());
    }

    @Test
    @DisplayName("接管：CAS 命中（rows=1）才算接管成功；未命中（rows=0）不计入")
    void takeoverShouldCountOnlyWinningCas() {
        registerNode();
        when(mapper.update(isNull(), any())).thenReturn(0);
        TenantNodeAssignment orphan = assignment(11L, "access-dead", 7L, LeaseState.PENDING_TAKEOVER);
        when(mapper.selectList(any())).thenReturn(List.of(orphan), List.of());
        // 第一次 update（续期）返回 0；接管那条 CAS 也返回 0 ⇒ 未抢到
        service.acquire(acquireReq(NODE));

        List<LambdaUpdateWrapper<TenantNodeAssignment>> updates = allUpdates();
        assertThat(updates).hasSizeGreaterThanOrEqualTo(2);
        LambdaUpdateWrapper<TenantNodeAssignment> takeover = updates.get(updates.size() - 1);
        // 接管必须带「非本节点 + (待接管|已释放|已过期的 ACTIVE)」守卫。
        // 注意 MyBatis-Plus 的 getSqlSegment() 只给占位符，字面值要到 getParamNameValuePairs() 里找。
        assertThat(takeover.getSqlSegment()).contains("access_node").contains("lease_expire_at").contains("<>");
        assertThat(takeover.getParamNameValuePairs().values())
            .contains(LeaseState.PENDING_TAKEOVER.getCode())
            .contains(LeaseState.RELEASED.getCode())
            .contains(LeaseState.ACTIVE.getCode());
        // epoch 在 SQL 里自增（避免读-改-写丢更新）
        assertThat(takeover.getSqlSet()).contains("epoch");
    }

    @Test
    @DisplayName("续约：一次批量续期 + 一次批量查询；不在本节点名下的进回收清单")
    void renewShouldAckOrRevoke() {
        registerNode();
        when(mapper.update(isNull(), any())).thenReturn(2);
        // 批量查询只返回 11：22 已经不属于本节点（被接管/释放）
        when(mapper.selectList(any())).thenReturn(List.of(assignment(11L, NODE, 3L, LeaseState.ACTIVE)));

        LeaseRenewReq req = new LeaseRenewReq();
        req.setAccessNode(NODE);
        req.setLeases(List.of(renewItem(11L, 1L), renewItem(22L, 1L)));
        LeaseRenewResp resp = service.renew(req);

        assertThat(resp.isNodeFenced()).isFalse();
        assertThat(resp.getRenewedLeases()).extracting(a -> a.getTenantId()).containsExactly(11L);
        assertThat(resp.getRenewedLeases().get(0).getLeaseExpireAt()).isNotNull();
        assertThat(resp.getRevokedTenantIds()).containsExactly(22L);

        // 只有一次 UPDATE（批量），且守卫是「本节点 + active + tenant_id IN」
        LambdaUpdateWrapper<TenantNodeAssignment> wrapper = capturedUpdate();
        assertThat(wrapper.getSqlSegment()).contains("access_node").contains("state").contains("tenant_id");
        assertThat(wrapper.getSqlSet()).contains("lease_expire_at");
    }

    @Test
    @DisplayName("续约：本地 epoch 落后不再导致吊销，而是用回执里的服务端 epoch 自更新（少抖动）")
    void renewShouldReportServerEpochInsteadOfRevokingOnStaleLocalEpoch() {
        registerNode();
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectList(any())).thenReturn(List.of(assignment(11L, NODE, 9L, LeaseState.ACTIVE)));

        LeaseRenewReq req = new LeaseRenewReq();
        req.setAccessNode(NODE);
        req.setLeases(List.of(renewItem(11L, 1L)));
        LeaseRenewResp resp = service.renew(req);

        assertThat(resp.getRevokedTenantIds()).isEmpty();
        assertThat(resp.getRenewedLeases()).singleElement()
            .satisfies(ack -> assertThat(ack.getEpoch()).as("回执必须是服务端 epoch").isEqualTo(9L));
    }

    @Test
    @DisplayName("续约：节点未注册 ⇒ nodeFenced=true（access 据此整体停采并重新注册）")
    void renewFromUnknownNodeMeansNodeFenced() {
        LeaseRenewReq req = new LeaseRenewReq();
        req.setAccessNode("access-ghost");
        req.setLeases(List.of(renewItem(11L, 1L)));

        LeaseRenewResp resp = service.renew(req);

        assertThat(resp.isNodeFenced()).isTrue();
        assertThat(resp.getRenewedLeases()).isEmpty();
        assertThat(resp.getRevokedTenantIds()).isEmpty();
        verify(mapper, times(0)).update(isNull(), any());
    }

    @Test
    @DisplayName("释放：守卫「本节点 + active」，置为 released（不动他人租户）")
    void releaseShouldGuardOwnerAndState() {
        LeaseReleaseReq req = new LeaseReleaseReq();
        req.setAccessNode(NODE);
        req.setTenantIds(List.of(11L, 22L));
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.release(req);

        LambdaUpdateWrapper<TenantNodeAssignment> wrapper = capturedUpdate();
        assertThat(wrapper.getSqlSegment()).contains("access_node").contains("state");
        assertThat(wrapper.getSqlSet()).contains("state").contains("lease_expire_at");
    }

    @Test
    @DisplayName("失效扫描：单条原子 UPDATE（state=active 且到期时间 <= now），返回受影响行数")
    void markExpiredShouldUseSingleAtomicUpdate() {
        when(mapper.update(isNull(), any())).thenReturn(3);

        int affected = service.markExpired(LocalDateTime.of(2026, 9, 19, 12, 0));

        assertThat(affected).isEqualTo(3);
        LambdaUpdateWrapper<TenantNodeAssignment> wrapper = capturedUpdate();
        assertThat(wrapper.getSqlSegment()).contains("state").contains("lease_expire_at");
        // 置为目标状态（待接管），而不是删除或释放
        assertThat(wrapper.getSqlSet()).contains("state");
    }

    @Test
    @DisplayName("失效扫描：没有到期租约时不产生任何副作用（返回 0，不误改其它行）")
    void markExpiredShouldBeNoopWhenNothingExpired() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThat(service.markExpired(LocalDateTime.now())).isZero();
    }

    @Test
    @DisplayName("查询归属：从未分配返回 null；已分配返回契约模型（状态码解析回枚举）")
    void queryAssignmentShouldMapOrReturnNull() {
        AssignmentQueryReq req = new AssignmentQueryReq();
        req.setTenantId(11L);
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.queryAssignment(req)).isNull();

        when(mapper.selectOne(any())).thenReturn(assignment(11L, NODE, 5L, LeaseState.PENDING_TAKEOVER));
        assertThat(service.queryAssignment(req).getState()).isEqualTo(LeaseState.PENDING_TAKEOVER);
        assertThat(service.queryAssignment(req).getEpoch()).isEqualTo(5L);
    }

    @Test
    @DisplayName("批量对账：返回所有租户的 epoch 与读取时刻（判据只用 epoch）")
    void batchEpochShouldReturnAllTenants() {
        when(mapper.selectList(any())).thenReturn(List.of(
            assignment(11L, NODE, 1L, LeaseState.ACTIVE),
            assignment(22L, "access-2", 9L, LeaseState.PENDING_TAKEOVER)));

        TenantEpochBatchResp resp = service.batchEpoch();

        assertThat(resp.getItems()).extracting("tenantId").containsExactly(11L, 22L);
        assertThat(resp.getItems()).extracting("epoch").containsExactly(1L, 9L);
        assertThat(resp.getReadAt()).isNotNull();
    }

    /** 注册一个「不限容量」的节点（与 access 默认配置一致：maxTenants 不填）。 */
    private void registerNode() {
        registry.register(NODE, null);
    }

    @Test
    @DisplayName("回归：容量为 null（= 不限，默认配置）必须可用——曾经因 ConcurrentHashMap 不接受 null 而 NPE")
    void nullCapacityMeansUnlimited() {
        AccessNodeRegisterReq req = new AccessNodeRegisterReq();
        req.setAccessNode(NODE);
        // 不设置 maxTenants（= null），这正是 access 默认发上来的形态
        service.register(req);

        assertThat(registry.isRegistered(NODE)).isTrue();
        assertThat(registry.capacityOf(NODE)).isEqualTo(AccessNodeRegistry.UNLIMITED_CAPACITY);
    }

    @Test
    @DisplayName("容量为负数属于配置错误：显式报错，不静默当成不限")
    void negativeCapacityShouldFailLoudly() {
        assertThatThrownBy(() -> registry.register(NODE, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("容量不能为负数");
    }

    private LeaseAcquireReq acquireReq(String node) {
        LeaseAcquireReq req = new LeaseAcquireReq();
        req.setAccessNode(node);
        return req;
    }

    private LeaseRenewItem renewItem(Long tenantId, Long epoch) {
        LeaseRenewItem item = new LeaseRenewItem();
        item.setTenantId(tenantId);
        item.setEpoch(epoch);
        return item;
    }

    private TenantNodeAssignment assignment(Long tenantId, String node, Long epoch, LeaseState state) {
        TenantNodeAssignment assignment = new TenantNodeAssignment();
        assignment.setTenantId(tenantId);
        assignment.setAccessNode(node);
        assignment.setEpoch(epoch);
        assignment.setState(state.getCode());
        assignment.setLeaseExpireAt(LocalDateTime.now().plusSeconds(30));
        return assignment;
    }

    /** 捕获全部 update（acquire 会发多条：续期 + 接管 CAS）。 */
    private List<LambdaUpdateWrapper<TenantNodeAssignment>> allUpdates() {
        ArgumentCaptor<LambdaUpdateWrapper<TenantNodeAssignment>> captor = wrapperCaptor();
        verify(mapper, Mockito.atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues();
    }

    /** 只发一条 update 的场景（release / markExpired / renew 用得到）。 */
    private LambdaUpdateWrapper<TenantNodeAssignment> capturedUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<TenantNodeAssignment>> captor = wrapperCaptor();
        verify(mapper, times(1)).update(isNull(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<TenantNodeAssignment>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<TenantNodeAssignment>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }
}
