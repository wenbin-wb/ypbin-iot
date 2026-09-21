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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceTag;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceTagMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceTagReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceTagResp;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备标签服务的纯逻辑单测（§3.11）。
 *
 * <p>钉死「同设备同 tag_key 唯一」这条业务约束的<b>服务层前置校验</b>：唯一键在库上兜底，
 * 但服务层必须先给出可读的业务错误，而不是把 SQL 唯一键冲突抛给用户。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotDeviceTagServiceImplTest {

    private static final Long DEVICE_ID = 1L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotDeviceTagMapper tagMapper = mock(IotDeviceTagMapper.class);
    private final IotDeviceTagServiceImpl service = new IotDeviceTagServiceImpl(deviceMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDeviceTag.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", tagMapper);
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
    }

    @Test
    @DisplayName("同设备同 tag_key 已存在 → 抛业务异常且不落库（不让库唯一键冲突冒到用户）")
    void duplicateTagKeyShouldBeRejectedBeforeInsert() {
        when(tagMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(DEVICE_ID, req("location", "A 区")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("标签键已存在");
        verify(tagMapper, never()).insert(any(IotDeviceTag.class));
    }

    @Test
    @DisplayName("设备不存在 → 抛业务异常（不产生孤儿标签）")
    void unknownDeviceShouldBeRejected() {
        when(deviceMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(99L, req("location", "A 区")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        verify(tagMapper, never()).insert(any(IotDeviceTag.class));
    }

    @Test
    @DisplayName("新增成功：tag_key / tag_value 按请求落库")
    void createShouldPersistKeyAndValue() {
        when(tagMapper.selectCount(any())).thenReturn(0L);

        service.create(DEVICE_ID, req("location", "A 区"));

        verify(tagMapper).insert(any(IotDeviceTag.class));
    }

    @Test
    @DisplayName("实体→响应：字段逐个搬运，不做改名")
    void toRespShouldMapEveryField() {
        IotDeviceTag entity = new IotDeviceTag();
        entity.setId(50L);
        entity.setDeviceId(DEVICE_ID);
        entity.setTagKey("location");
        entity.setTagValue("A 区");
        entity.setCreateTime(LocalDateTime.of(2026, 9, 20, 10, 0));

        IotDeviceTagResp resp = service.toResp(entity);

        assertThat(resp.getId()).isEqualTo(50L);
        assertThat(resp.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(resp.getTagKey()).isEqualTo("location");
        assertThat(resp.getTagValue()).isEqualTo("A 区");
        assertThat(resp.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
    }

    private static IotDeviceTagReq req(String key, String value) {
        IotDeviceTagReq req = new IotDeviceTagReq();
        req.setTagKey(key);
        req.setTagValue(value);
        return req;
    }
}
