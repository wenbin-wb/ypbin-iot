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

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备台账的纯逻辑单测（不起 Spring 上下文、不连库）。
 *
 * <p>为什么只测这两处：查询条件构造与实体→响应映射是<b>本类自己的逻辑</b>，也是回归风险最高的两处；
 * 分页与落库本身由 {@code BaseServiceImpl} 与 MyBatis-Plus 承担，起上下文测它们属于重复验证。
 * 租户隔离不在这里测——它由租户插件保证，端到端越权用例属于 M0b 的验收面。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class IotDeviceServiceImplTest {

    private final IotDeviceServiceImpl service = new IotDeviceServiceImpl();

    /**
     * 初始化 MyBatis-Plus 的实体元信息。
     *
     * <p>为什么需要：{@code LambdaQueryWrapper} 用方法引用解析列名，而列名来自实体的 TableInfo，
     * 后者通常由 Mapper 扫描时注册——纯单测（不起 Spring/不扫 Mapper）里没有这一步，
     * 会抛 {@code MybatisPlus can not find lambda cache for this entity}。
     * 这里显式初始化，保持「不起上下文也能验证查询条件」这一收益。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDevice.class);
    }

    @Test
    @DisplayName("关键字同时匹配设备名与编码（模糊），协议按等值过滤；空条件不加任何过滤")
    void buildWrapperShouldApplyKeywordAndProtocolOnlyWhenPresent() {
        IotDeviceQuery query = new IotDeviceQuery();
        query.setKeyword("  网关  ");
        query.setProtocol("tcp");

        LambdaQueryWrapper<IotDevice> wrapper = service.buildWrapper(query);
        String sql = wrapper.getSqlSegment();

        assertThat(sql).contains("device_name").contains("device_code").contains("protocol");
        // 关键字应被 trim 且由 like 包上 %（MyBatis-Plus 的参数形态是 %keyword%；带空格会说明没 trim）
        assertThat(wrapper.getParamNameValuePairs().values()).contains("%网关%");

        IotDeviceQuery empty = new IotDeviceQuery();
        String emptySql = service.buildWrapper(empty).getSqlSegment();
        assertThat(emptySql).doesNotContain("device_name").doesNotContain("protocol");
    }

    @Test
    @DisplayName("实体→响应：字段逐个搬运（含创建时间），不做改名")
    void toRespShouldMapEveryField() {
        IotDevice entity = new IotDevice();
        entity.setId(1001L);
        entity.setDeviceCode("DEV-001");
        entity.setDeviceName("一号网关");
        entity.setProtocol("tcp");
        entity.setEndpoint("tcp://127.0.0.1:15002");
        entity.setRemark("测试");
        entity.setCreateTime(LocalDateTime.of(2026, 9, 19, 10, 0));

        IotDeviceResp resp = service.toResp(entity);

        assertThat(resp.getId()).isEqualTo(1001L);
        assertThat(resp.getDeviceCode()).isEqualTo("DEV-001");
        assertThat(resp.getDeviceName()).isEqualTo("一号网关");
        assertThat(resp.getProtocol()).isEqualTo("tcp");
        assertThat(resp.getEndpoint()).isEqualTo("tcp://127.0.0.1:15002");
        assertThat(resp.getRemark()).isEqualTo("测试");
        assertThat(resp.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 9, 19, 10, 0));
    }
}
