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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotShadow;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import cn.ypbin.admin.iot.model.req.IotShadowReq;
import cn.ypbin.admin.iot.model.resp.IotShadowResp;
import cn.ypbin.admin.iot.shadow.ShadowDesiredRow;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import tools.jackson.databind.ObjectMapper;
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

    @Test
    @DisplayName("delta：只含未达成/缺上报的期望键；数值字符串与数值等价（\"25\" == 25.0）")
    void deltaShouldComputeTypeLenientDifference() {
        when(deviceMapper.selectById(7L)).thenReturn(new IotDevice());
        IotShadow shadow = new IotShadow();
        shadow.setReported("{\"temperature\":\"25\",\"humidity\":\"60\"}");
        shadow.setDesired("{\"temperature\":25.0,\"fan\":1}");
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(shadow);

        IotShadowResp resp = service.get(7L);

        assertThat(resp.getDelta()).as("temperature 数值等价 ⇒ 不落后；fan 缺上报 ⇒ 落后")
            .containsOnlyKeys("fan");
        assertThat(resp.getDelta()).containsEntry("fan", 1);
    }

    @Test
    @DisplayName("delta：设备补齐上报后该键自动清空；未初始化影子/无期望时 delta 为空 Map（非 null）")
    void deltaShouldClearWhenReportedMatches() {
        when(deviceMapper.selectById(8L)).thenReturn(new IotDevice());
        IotShadow shadow = new IotShadow();
        shadow.setReported("{\"temperature\":\"25\"}");
        shadow.setDesired("{\"temperature\":25}");
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(shadow);
        assertThat(service.get(8L).getDelta()).isEmpty();

        when(deviceMapper.selectById(9L)).thenReturn(new IotDevice());
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        assertThat(service.get(9L).getDelta()).as("未初始化影子也要给空 Map，不能是 null").isEmpty();
    }

    @Test
    @DisplayName("mergeDesired：显式带租户 + 雪花主键落库（JSON 合并）；空增量短路不写库")
    void mergeDesiredShouldUpsertByTenant() {
        IotDevice device = new IotDevice();
        device.setId(5L);
        device.setTenantId(11L);
        when(deviceMapper.selectById(5L)).thenReturn(device);

        service.mergeDesired(5L, Map.of("temperature", 25.0));

        org.mockito.ArgumentCaptor<ShadowDesiredRow> captor =
            org.mockito.ArgumentCaptor.forClass(ShadowDesiredRow.class);
        verify(shadowMapper).mergeDesired(captor.capture());
        assertThat(captor.getValue().id()).as("插入路径需要雪花主键").isNotNull();
        assertThat(captor.getValue().tenantId()).as("租户由服务端解析，绝不来自请求").isEqualTo(11L);
        assertThat(captor.getValue().deviceId()).isEqualTo(5L);
        assertThat(captor.getValue().desiredJson()).contains("temperature").contains("25.0");
        assertThat(captor.getValue().desiredTs()).isNotNull();

        service.mergeDesired(5L, Map.of());
        verify(shadowMapper, times(1)).mergeDesired(any());
    }

    @Test
    @DisplayName("越权/租户隔离：跨租户（查不到）设备拒绝，desired 不落库")
    void mergeDesiredMustRejectCrossTenantDevice() {
        when(deviceMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.mergeDesired(999L, Map.of("temperature", 1)))
            .isInstanceOf(BusinessException.class);

        verify(shadowMapper, never()).mergeDesired(any());
        verify(shadowMapper, never()).insert(any(IotShadow.class));
    }

    @Test
    @DisplayName("updateDesired：整份置换（不在本次 desired 里的旧键会被清掉），写回本次 JSON")
    void updateDesiredShouldReplaceWholeDocument() {
        IotDevice device = new IotDevice();
        device.setId(6L);
        device.setTenantId(11L);
        when(deviceMapper.selectById(6L)).thenReturn(device);
        IotShadow existing = new IotShadow();
        existing.setId(66L);
        existing.setDeviceId(6L);
        existing.setDesired("{\"old\":1}");
        when(shadowMapper.selectOne(any(), anyBoolean())).thenReturn(existing);

        IotShadowReq req = new IotShadowReq();
        req.setDeviceId(6L);
        req.setDesired(Map.of("temperature", 30));
        service.updateDesired(req);

        org.mockito.ArgumentCaptor<IotShadow> captor =
            org.mockito.ArgumentCaptor.forClass(IotShadow.class);
        verify(shadowMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDesired()).as("整份置换 ⇒ 旧的 old 键不保留").isEqualTo(
            "{\"temperature\":30}");
        assertThat(captor.getValue().getDesiredTs()).isNotNull();
    }
}
