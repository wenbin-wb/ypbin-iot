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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备台账的纯逻辑单测（不起 Spring 上下文、不连库）。
 *
 * <p>为什么只测这两处：查询条件构造与实体→响应映射是<b>本类自己的逻辑</b>，也是回归风险最高的两处；
 * 分页与落库本身由 {@code BaseServiceImpl} 与 MyBatis-Plus 承担，起上下文测它们属于重复验证。
 * 租户隔离的<b>机制面</b>由 {@code IotTenantIsolationGateTest} 覆盖（表不被 ignore、租户上下文
 * 缺失时 fail-closed、实体继承 TenantBaseEntity）；§10/§14 要求的<b>端到端</b>越权用例
 * （A 租户 token 访问 B 租户数据 → HTTP 200 + R.code=403）需要真实 DB 与登录态，归属 CI
 * 集成测试面，本机纯单测形态跑不了——它是 M-1 尚未关闭的验收项，不因本注释而移出 M-1。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class IotDeviceServiceImplTest {

    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final TenantLedgerService ledgerService = mock(TenantLedgerService.class);
    private final IotDeviceServiceImpl service = new IotDeviceServiceImpl(productMapper, ledgerService);

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
    @DisplayName("实体→响应：字段逐个搬运（含创建时间与 M-1 扩展字段），不做改名")
    void toRespShouldMapEveryField() {
        IotDevice entity = new IotDevice();
        entity.setId(1001L);
        entity.setDeviceCode("DEV-001");
        entity.setDeviceName("一号网关");
        entity.setProtocol("tcp");
        entity.setEndpoint("tcp://127.0.0.1:15002");
        entity.setProductId(2001L);
        entity.setProductVersion("v1.0");
        entity.setOnlineStatus("online");
        entity.setLastSeenAt(LocalDateTime.of(2026, 9, 20, 9, 30));
        entity.setRemark("测试");
        entity.setCreateTime(LocalDateTime.of(2026, 9, 19, 10, 0));

        IotDeviceResp resp = service.toResp(entity);

        assertThat(resp.getId()).isEqualTo(1001L);
        assertThat(resp.getDeviceCode()).isEqualTo("DEV-001");
        assertThat(resp.getDeviceName()).isEqualTo("一号网关");
        assertThat(resp.getProtocol()).isEqualTo("tcp");
        assertThat(resp.getEndpoint()).isEqualTo("tcp://127.0.0.1:15002");
        assertThat(resp.getProductId()).isEqualTo(2001L);
        assertThat(resp.getProductVersion()).isEqualTo("v1.0");
        assertThat(resp.getOnlineStatus()).isEqualTo("online");
        assertThat(resp.getLastSeenAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 9, 30));
        assertThat(resp.getRemark()).isEqualTo("测试");
        assertThat(resp.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 9, 19, 10, 0));
    }

    @Test
    @DisplayName("★ 设备增删改都必须推进台账配置版本号（否则接入侧永远不知道设备变了 ⇒ G7 静默零更新）")
    void deviceMutationsMustBumpConfigEpoch() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(existingDevice());

        service.createDevice(deviceReq());
        service.updateDevice(1L, deviceReq());
        service.removeDevice(1L);

        verify(ledgerService, times(3)).bumpConfigEpochOfCurrentTenant();
    }

    private static IotDevice existingDevice() {
        IotDevice device = new IotDevice();
        device.setId(1L);
        device.setDeviceCode("DEV-1");
        device.setDeviceName("水表-1");
        device.setProtocol("tcp");
        device.setEndpoint("tcp://127.0.0.1:15002");
        return device;
    }

    private static IotDeviceReq deviceReq() {
        IotDeviceReq req = new IotDeviceReq();
        req.setDeviceCode("DEV-1");
        req.setDeviceName("水表-1");
        req.setProtocol("tcp");
        req.setEndpoint("tcp://127.0.0.1:15002");
        return req;
    }
}
