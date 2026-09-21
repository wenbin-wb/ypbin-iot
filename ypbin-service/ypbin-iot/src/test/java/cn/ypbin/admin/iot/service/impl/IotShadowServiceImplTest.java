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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotShadow;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import cn.ypbin.admin.iot.model.resp.IotShadowResp;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备影子的纯逻辑单测（§3.10 合并视图）。
 *
 * <p>合并规则是影子的核心语义：reported 优先、无则回退 desired；未初始化返回空 Map 而非 null。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotShadowServiceImplTest {

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotShadowMapper shadowMapper = mock(IotShadowMapper.class);
    private final IotShadowServiceImpl service =
        new IotShadowServiceImpl(deviceMapper, new ObjectMapper());

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotShadow.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        // getOne 走 ServiceImpl 的 baseMapper 字段（Spring 注入点），纯单测里手动装桩
        ReflectionTestUtils.setField(service, "baseMapper", shadowMapper);
    }

    @Test
    @DisplayName("合并视图：reported 覆盖 desired 同键；reported 缺失的键回退 desired")
    void mergedShouldPreferReportedOverDesired() {
        when(deviceMapper.selectById(1L)).thenReturn(new IotDevice());
        IotShadow shadow = new IotShadow();
        shadow.setReported("{\"temperature\":25,\"humidity\":60}");
        shadow.setDesired("{\"temperature\":30,\"fan\":1}");
        shadow.setReportTs(LocalDateTime.of(2026, 9, 20, 12, 0));
        shadow.setDesiredTs(LocalDateTime.of(2026, 9, 20, 11, 0));
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(shadow);

        IotShadowResp resp = service.get(1L);

        // temperature 两者都有 → reported 优先 25；humidity 仅 reported；fan 仅 desired 回退
        assertThat(resp.getMerged()).containsEntry("temperature", 25)
            .containsEntry("humidity", 60)
            .containsEntry("fan", 1);
        assertThat(resp.getReported()).containsEntry("temperature", 25);
        assertThat(resp.getDesired()).containsEntry("temperature", 30);
        assertThat(resp.getReportTs()).isEqualTo(LocalDateTime.of(2026, 9, 20, 12, 0));
    }

    @Test
    @DisplayName("未初始化影子：返回空 Map（不返回 null），device 不存在抛业务异常")
    void emptyShadowShouldReturnEmptyMaps() {
        when(deviceMapper.selectById(2L)).thenReturn(new IotDevice());
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        IotShadowResp resp = service.get(2L);

        assertThat(resp.getReported()).isEmpty();
        assertThat(resp.getDesired()).isEmpty();
        assertThat(resp.getMerged()).isEmpty();
        assertThat(resp.getDeviceId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("reported 为 null 时 merged 仅含 desired（无 NPE）")
    void mergedShouldHandleNullReported() {
        when(deviceMapper.selectById(3L)).thenReturn(new IotDevice());
        IotShadow shadow = new IotShadow();
        shadow.setReported(null);
        shadow.setDesired("{\"speed\":5}");
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(shadow);

        IotShadowResp resp = service.get(3L);

        assertThat(resp.getMerged()).containsExactlyEntriesOf(Map.of("speed", 5));
    }
}
