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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProductVersion;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotProductVersionMapper;
import cn.ypbin.admin.iot.model.query.IotProductQuery;
import cn.ypbin.admin.iot.model.resp.IotProductResp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 产品服务的纯逻辑单测（不起 Spring 上下文、不连库）。
 *
 * <p>版本号递增（§3.8 语义化 v{major}.{minor}）与发布状态机是发布流程的核心规则，必须钉死；
 * 查询条件构造与实体→响应映射与设备台账同构。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotProductServiceImplTest {

    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final IotProductVersionMapper versionMapper = mock(IotProductVersionMapper.class);
    private final IotProductServiceImpl service = new IotProductServiceImpl(versionMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotProduct.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotProductVersion.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        // getById 走 ServiceImpl 的 baseMapper 字段（Spring 注入点），纯单测里手动装桩
        ReflectionTestUtils.setField(service, "baseMapper", productMapper);
    }

    @Test
    @DisplayName("版本号递增：无历史 → v1.0；有 v1.0 → 新草稿 v1.1；最新 minor 取自版本列表倒序首条")
    void nextDraftVersionShouldIncrement() {
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        assertThat(service.nextDraftVersionNo(100L)).isEqualTo("v1.0");

        IotProductVersion v10 = new IotProductVersion();
        v10.setVersionNo("v1.0");
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(v10));
        assertThat(service.nextDraftVersionNo(100L)).isEqualTo("v1.1");
    }

    @Test
    @DisplayName("最新版本判定必须按主键序查询（ORDER BY id DESC），不能按版本号字符串序")
    void latestVersionQueryMustOrderByIdDesc() {
        // 断言查询条件本身：若退回 orderByDesc(versionNo)，v1.10 会被排在 v1.9 之前而算错版本
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        service.nextDraftVersionNo(100L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<IotProductVersion>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(versionMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        // 必须断言 ORDER BY 子句本身：只断言「含 id」会被 WHERE 的 product_id 满足而恒真
        assertThat(sql).containsIgnoringCase("ORDER BY id DESC");
        assertThat(sql).doesNotContainIgnoringCase("ORDER BY version_no");
    }

    @Test
    @DisplayName("号段解析：v1.10 之后应是 v1.11（字典序会误判为 v1.2）")
    void versionNumberShouldParseNumerically() {
        IotProductVersion v110 = new IotProductVersion();
        v110.setVersionNo("v1.10");
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(v110));

        assertThat(service.nextDraftVersionNo(100L)).isEqualTo("v1.11");
    }

    @Test
    @DisplayName("major 不写死：v2.3 之后应是 v2.4（而非 v1.4）")
    void majorShouldBePreserved() {
        IotProductVersion v23 = new IotProductVersion();
        v23.setVersionNo("v2.3");
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(v23));

        assertThat(service.nextDraftVersionNo(100L)).isEqualTo("v2.4");
    }

    @Test
    @DisplayName("发布复用当前草稿版本记录（置 published），不另分配版本号、不留孤儿草稿")
    void publishShouldReuseDraftVersionRecord() {
        IotProduct product = new IotProduct();
        product.setId(300L);
        product.setModelStatus("draft");
        when(productMapper.selectById(300L)).thenReturn(product);
        IotProductVersion draft = new IotProductVersion();
        draft.setId(900L);
        draft.setProductId(300L);
        draft.setVersionNo("v1.1");
        draft.setModelStatus("draft");
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(draft));

        String versionNo = service.publish(300L);

        assertThat(versionNo).isEqualTo("v1.1");
        // 草稿记录被更新为已发布（而不是再插一条 v1.2）
        verify(versionMapper).updateById(draft);
        verify(versionMapper, never()).insert(any(IotProductVersion.class));
        assertThat(draft.getModelStatus()).isEqualTo("published");
        assertThat(draft.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("首次发布（无草稿记录）：分配 v1.0 并插入 published 记录")
    void firstPublishShouldAllocateV1_0() {
        IotProduct product = new IotProduct();
        product.setId(301L);
        product.setModelStatus("draft");
        when(productMapper.selectById(301L)).thenReturn(product);
        when(versionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        String versionNo = service.publish(301L);

        assertThat(versionNo).isEqualTo("v1.0");
        verify(versionMapper).insert(any(IotProductVersion.class));
    }

    @Test
    @DisplayName("关键字同时匹配产品名与编码（模糊），协议/状态按等值过滤；空条件不加过滤")
    void buildWrapperShouldApplyFiltersOnlyWhenPresent() {
        IotProductQuery query = new IotProductQuery();
        query.setKeyword("水表");
        query.setProtocol("mqtt");
        query.setModelStatus("draft");

        LambdaQueryWrapper<IotProduct> wrapper = service.buildWrapper(query);
        assertThat(wrapper.getSqlSegment()).contains("product_name").contains("product_code")
            .contains("protocol").contains("model_status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("%水表%");

        IotProductQuery empty = new IotProductQuery();
        assertThat(service.buildWrapper(empty).getSqlSegment())
            .doesNotContain("product_name").doesNotContain("protocol");
    }

    @Test
    @DisplayName("实体→响应：字段逐个搬运（含 M-1 新字段），不做改名")
    void toRespShouldMapEveryField() {
        IotProduct entity = new IotProduct();
        entity.setId(2001L);
        entity.setProductCode("water-meter");
        entity.setProductName("智能水表");
        entity.setProtocol("mqtt");
        entity.setDataFormat("json");
        entity.setDeviceType("WaterMeter");
        entity.setManufacturerId("acme");
        entity.setManufacturerName("ACME");
        entity.setModelStatus("draft");
        entity.setRemark("测试产品");
        entity.setCreateTime(LocalDateTime.of(2026, 9, 20, 10, 0));

        IotProductResp resp = service.toResp(entity);

        assertThat(resp.getId()).isEqualTo(2001L);
        assertThat(resp.getProductCode()).isEqualTo("water-meter");
        assertThat(resp.getProductName()).isEqualTo("智能水表");
        assertThat(resp.getProtocol()).isEqualTo("mqtt");
        assertThat(resp.getDataFormat()).isEqualTo("json");
        assertThat(resp.getDeviceType()).isEqualTo("WaterMeter");
        assertThat(resp.getManufacturerId()).isEqualTo("acme");
        assertThat(resp.getManufacturerName()).isEqualTo("ACME");
        assertThat(resp.getModelStatus()).isEqualTo("draft");
        assertThat(resp.getRemark()).isEqualTo("测试产品");
        assertThat(resp.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
    }
}
