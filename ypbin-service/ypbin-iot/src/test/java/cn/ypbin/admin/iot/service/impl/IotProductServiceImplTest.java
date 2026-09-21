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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProductVersion;
import cn.ypbin.admin.iot.mapper.IotProductVersionMapper;
import cn.ypbin.admin.iot.model.query.IotProductQuery;
import cn.ypbin.admin.iot.model.resp.IotProductResp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 产品服务的纯逻辑单测（不起 Spring 上下文、不连库）。
 *
 * <p>版本号递增（§3.8 语义化 v{major}.{minor}）是发布流程的核心规则，必须钉死；
 * 查询条件构造与实体→响应映射与设备台账同构。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotProductServiceImplTest {

    private final IotProductVersionMapper versionMapper = mock(IotProductVersionMapper.class);
    private final IotProductServiceImpl service = new IotProductServiceImpl(versionMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotProduct.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotProductVersion.class);
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
