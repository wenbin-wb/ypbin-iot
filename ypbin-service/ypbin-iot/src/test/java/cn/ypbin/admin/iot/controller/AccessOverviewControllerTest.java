/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.ypbin.admin.iot.entity.AccessNode;
import cn.ypbin.admin.iot.mapper.AccessNodeMapper;
import cn.ypbin.admin.iot.mapper.TenantNodeAssignmentMapper;
import cn.ypbin.admin.iot.platform.AccessMetricsReportStore;
import cn.ypbin.starter.core.model.R;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link AccessOverviewController} 的平台运维概览单测。
 *
 * <p>验三件事：① 节点列表来自两表聚合（持有数/最早到期）；
 * ② access 未上报 ⇒ {@code reported=false} 且**不报错**（access 不可达不能把运维页打挂）；
 * ③ 平台级端点必须挂权限码（`iot:ledger:list`），否则「跨租户可见」会被裸奔。</p>
 *
 * @author wenbin
 * @since 2026-10-11
 */
class AccessOverviewControllerTest {

    private AccessNodeMapper accessNodeMapper;
    private TenantNodeAssignmentMapper assignmentMapper;
    private AccessMetricsReportStore reportStore;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accessNodeMapper = mock(AccessNodeMapper.class);
        assignmentMapper = mock(TenantNodeAssignmentMapper.class);
        reportStore = new AccessMetricsReportStore(new SimpleMeterRegistry());
        // MockMvc 下没有 MyBatis-Spring 上下文：LambdaQueryWrapper 的列缓存需手动初始化，否则运行期抛 MPE
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
            new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
            cn.ypbin.admin.iot.entity.TenantNodeAssignment.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AccessOverviewController(accessNodeMapper, assignmentMapper, reportStore))
            .build();
    }

    @Test
    @DisplayName("★ access 未上报：概览仍返回 200 且 reported=false（access 不可达不得打挂运维页）")
    void noReportYetMustStillReturnOk() throws Exception {
        when(accessNodeMapper.selectList(any())).thenReturn(List.of(node("access-1", 10)));
        when(assignmentMapper.selectActiveAggregateByNode())
            .thenReturn(List.of(aggregate("access-1", 2L, null)));

        mockMvc.perform(get("/access-overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.metrics.reported").value(false))
            .andExpect(jsonPath("$.data.nodes[0].accessNode").value("access-1"))
            .andExpect(jsonPath("$.data.nodes[0].holdingCount").value(2));
    }

    @Test
    @DisplayName("★ 平台级端点必须挂 @SaCheckPermission（否则租户侧可裸看跨租户归属）")
    void mustDeclarePermission() throws NoSuchMethodException {
        var annotation = AccessOverviewController.class.getDeclaredMethod("overview")
            .getAnnotation(cn.dev33.satoken.annotation.SaCheckPermission.class);
        org.assertj.core.api.Assertions.assertThat(annotation)
            .as("平台级只读端点必须显式声明权限码").isNotNull();
        org.assertj.core.api.Assertions.assertThat(annotation.value())
            .containsExactly("iot:ledger:list");
    }

    private AccessNode node(String node, int max) {
        AccessNode accessNode = new AccessNode();
        accessNode.setAccessNode(node);
        accessNode.setMaxTenants(max);
        return accessNode;
    }

    private cn.ypbin.admin.iot.lease.AccessNodeLeaseAggregate aggregate(String node, Long count,
            java.time.LocalDateTime earliest) {
        cn.ypbin.admin.iot.lease.AccessNodeLeaseAggregate aggregate =
            new cn.ypbin.admin.iot.lease.AccessNodeLeaseAggregate();
        aggregate.setAccessNode(node);
        aggregate.setHoldingCount(count);
        aggregate.setEarliestExpireAt(earliest);
        return aggregate;
    }

    @Test
    @DisplayName("★ 租约存活由「最早到期 vs 当前时刻」推导：过期 ⇒ leaseAlive=false；未到期 ⇒ true")
    void leaseAliveMustBeDerivedFromEarliestExpiry() throws Exception {
        when(accessNodeMapper.selectList(any())).thenReturn(List.of(node("access-1", 10)));
        // 最早到期：一次给"1 小时后"（活的），一次给"1 小时前"（已过期）
        when(assignmentMapper.selectActiveAggregateByNode()).thenReturn(
            List.of(aggregate("access-1", 1L, java.time.LocalDateTime.now().plusHours(1))));

        mockMvc.perform(get("/access-overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nodes[0].leaseAlive").value(true))
            .andExpect(jsonPath("$.data.nodes[0].lastRegisteredAt").doesNotExist());

        when(assignmentMapper.selectActiveAggregateByNode()).thenReturn(
            List.of(aggregate("access-1", 1L, java.time.LocalDateTime.now().minusHours(1))));

        mockMvc.perform(get("/access-overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nodes[0].leaseAlive").value(false));
    }
}
