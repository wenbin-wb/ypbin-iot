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

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceGroup;
import cn.ypbin.admin.iot.entity.IotDeviceGroupMember;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMemberMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.resp.IotDeviceGroupMemberResp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备分组的纯逻辑单测（§3.11）。
 *
 * <p>重点钉死成员列表的批量取设备逻辑：只发一次 IN 查询、不逐条 selectById（禁循环内 DB 查询铁律），
 * 且空成员列表短路返回、不产生任何 DB 调用。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotDeviceGroupServiceImplTest {

    private final IotDeviceGroupMapper groupMapper = mock(IotDeviceGroupMapper.class);
    private final IotDeviceGroupMemberMapper memberMapper = mock(IotDeviceGroupMemberMapper.class);
    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotDeviceGroupServiceImpl service =
        new IotDeviceGroupServiceImpl(memberMapper, deviceMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDeviceGroup.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDeviceGroupMember.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", groupMapper);
    }

    @Test
    @DisplayName("成员列表：一次 IN 批量取设备（不逐条 selectById），设备快照字段正确")
    void listMembersShouldBatchLoadDevices() {
        when(groupMapper.selectById(1L)).thenReturn(new IotDeviceGroup());
        IotDeviceGroupMember m1 = new IotDeviceGroupMember();
        m1.setId(11L);
        m1.setGroupId(1L);
        m1.setDeviceId(100L);
        m1.setCreateTime(LocalDateTime.of(2026, 9, 20, 10, 0));
        IotDeviceGroupMember m2 = new IotDeviceGroupMember();
        m2.setId(12L);
        m2.setGroupId(1L);
        m2.setDeviceId(101L);
        when(memberMapper.selectList(any())).thenReturn(List.of(m1, m2));

        IotDevice dev100 = new IotDevice();
        dev100.setId(100L);
        dev100.setDeviceCode("DEV-100");
        dev100.setDeviceName("一号表计");
        IotDevice dev101 = new IotDevice();
        dev101.setId(101L);
        dev101.setDeviceCode("DEV-101");
        dev101.setDeviceName("二号表计");
        when(deviceMapper.selectBatchIds(List.of(100L, 101L))).thenReturn(List.of(dev100, dev101));

        List<IotDeviceGroupMemberResp> resp = service.listMembers(1L);

        assertThat(resp).hasSize(2);
        assertThat(resp.get(0).getDeviceCode()).isEqualTo("DEV-100");
        assertThat(resp.get(0).getDeviceName()).isEqualTo("一号表计");
        assertThat(resp.get(1).getDeviceCode()).isEqualTo("DEV-101");
        assertThat(resp.get(1).getDeviceName()).isEqualTo("二号表计");
        assertThat(resp.get(0).getCreateTime()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
        verify(deviceMapper).selectBatchIds(List.of(100L, 101L));
    }

    @Test
    @DisplayName("空成员列表：短路返回空集合，不触发任何设备查询")
    void emptyMembersShouldShortCircuit() {
        when(groupMapper.selectById(2L)).thenReturn(new IotDeviceGroup());
        when(memberMapper.selectList(any())).thenReturn(List.of());

        List<IotDeviceGroupMemberResp> resp = service.listMembers(2L);

        assertThat(resp).isEmpty();
        verify(deviceMapper, never()).selectBatchIds(any());
    }
}
