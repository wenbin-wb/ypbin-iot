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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotEventLog;
import cn.ypbin.admin.iot.event.EventIngestItemDto;
import cn.ypbin.admin.iot.event.EventIngestReq;
import cn.ypbin.admin.iot.event.EventIngestResult;
import cn.ypbin.admin.iot.event.EventLevel;
import cn.ypbin.admin.iot.event.EventLogQuery;
import cn.ypbin.admin.iot.event.EventLogResp;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotEventLogMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 运行期事件服务单测（G6）：上报（含幂等重复投递）/ 查询（时间范围、级别过滤、分页）/ 空结果 /
 * 跨租户不可见。
 *
 * <p><b>重点不是「能跑通」，而是几处会静默出错的行为</b>：重复投递不得落第二行、设备不存在的上报
 * 必须被计入「丢弃」而不是当作成功、非法级别必须在动库之前整批拒绝、跨租户查不到设备时按「不存在」
 * 处理而不是给出别人的数据。</p>
 *
 * <p><b>本类咬不到什么（如实声明）</b>：Mapper 全是 mock，因此
 * ① {@code ON DUPLICATE KEY UPDATE} 的真实幂等语义、② 租户拦截器是否真的给自定义 SQL 追加了
 * {@code tenant_id}、③ 并发重投的唯一键竞态，都<b>不在</b>本类射程内——前两条由
 * {@code EventLogMapperContractTest}（源码级）与 {@code EventLogIngestIT}（真库）覆盖，第三条只有真库
 * 并发用例能证明。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
class IotEventServiceImplTest {

    private static final Long TENANT = 11L;
    private static final Long DEVICE = 100L;
    private static final Long OTHER_DEVICE = 200L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 28, 10, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 28, 11, 0, 0);

    private IotEventLogMapper eventLogMapper;

    private IotDeviceMapper deviceMapper;

    private IotEventServiceImpl service;

    /**
     * 初始化 MyBatis-Plus 的实体元信息。
     *
     * <p>为什么需要：{@code LambdaQueryWrapper} 用方法引用解析列名，列名来自实体 TableInfo，
     * 而它通常由 Mapper 扫描时注册——纯单测（不起 Spring、不扫 Mapper）里没有这一步，会抛
     * {@code MybatisPlus can not find lambda cache for this entity}。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotEventLog.class);
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
    }

    @BeforeEach
    void setUp() {
        eventLogMapper = mock(IotEventLogMapper.class);
        deviceMapper = mock(IotDeviceMapper.class);
        service = new IotEventServiceImpl(eventLogMapper, deviceMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("★ 上报：租户按设备解析、级别规范化、epoch 按平台时区落库、幂等键去空白")
    void ingestMustPersistRowsWithResolvedTenantAndNormalizedFields() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device(DEVICE, TENANT)));
        when(eventLogMapper.selectExistingKeys(eq(DEVICE), anyList())).thenReturn(List.of());
        when(eventLogMapper.insertBatch(anyList())).thenAnswer(call -> rowsOf(call.getArgument(0)).size());

        EventIngestResult result = service.ingest(req(item(DEVICE, "overTemp", " WARN ", "k-1", T0),
            item(DEVICE, "deviceOffline", "error", "k-2", T1)));

        assertThat(result.getAccepted()).isEqualTo(2);
        assertThat(result.getDuplicated()).isZero();
        assertThat(result.getDiscarded()).isZero();

        List<IotEventLog> rows = capturedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(IotEventLog::getTenantId).containsOnly(TENANT);
        assertThat(rows).extracting(IotEventLog::getDeviceId).containsOnly(DEVICE);
        assertThat(rows).extracting(IotEventLog::getEventCode).containsExactly("overTemp", "deviceOffline");
        assertThat(rows).extracting(IotEventLog::getLevel)
            .as("级别必须规范化成枚举 code（大写 WARN → warn）")
            .containsExactly(EventLevel.WARN.getCode(), EventLevel.ERROR.getCode());
        assertThat(rows).extracting(IotEventLog::getIdempotentKey)
            .as("幂等键去首尾空白：带空白的键不去重会导致同一条事件落两行")
            .containsExactly("k-1", "k-2");
        assertThat(rows).extracting(IotEventLog::getEventTs)
            .as("epoch 毫秒必须按平台时区（GMT+8）还原成墙上时间")
            .containsExactly(T0, T1);
        assertThat(rows).extracting(IotEventLog::getId).doesNotContainNull();
    }

    @Test
    @DisplayName("★ 幂等：重复投递（幂等键已存在）不得再插一行，且必须回 200 语义的结果")
    void duplicateDeliveryMustNotInsertAgain() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device(DEVICE, TENANT)));
        when(eventLogMapper.selectExistingKeys(eq(DEVICE), anyList())).thenReturn(List.of("k-1"));

        EventIngestResult result = service.ingest(req(item(DEVICE, "overTemp", "warn", "k-1", T0)));

        assertThat(result.getAccepted()).isZero();
        assertThat(result.getDuplicated()).as("重复投递必须被明确报告为「已收过」").isEqualTo(1);
        assertThat(result.getDiscarded()).isZero();
        verify(eventLogMapper, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("★ 幂等：同一批里同一个幂等键出现两次，只能落一行")
    void sameKeyTwiceInOneBatchMustInsertOnce() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device(DEVICE, TENANT)));
        when(eventLogMapper.selectExistingKeys(eq(DEVICE), anyList())).thenReturn(List.of());
        when(eventLogMapper.insertBatch(anyList())).thenAnswer(call -> rowsOf(call.getArgument(0)).size());

        EventIngestResult result = service.ingest(req(item(DEVICE, "overTemp", "warn", "k-1", T0),
            item(DEVICE, "overTemp", "warn", "k-1", T1)));

        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getDuplicated()).as("批内重复必须计入幂等命中").isEqualTo(1);
        assertThat(capturedRows()).hasSize(1);
    }

    @Test
    @DisplayName("空批次：不得打库（不在空集合上做 IN 查询）")
    void emptyRequestMustNotTouchDatabase() {
        EventIngestResult result = service.ingest(new EventIngestReq());

        assertThat(result.total()).isZero();
        verifyNoInteractions(eventLogMapper, deviceMapper);
    }

    @Test
    @DisplayName("★ 设备不存在：丢弃并计数，绝不落库、绝不静默当成成功")
    void unknownDeviceMustBeDiscardedAndNotStored() {
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of());

        EventIngestResult result = service.ingest(req(item(DEVICE, "overTemp", "warn", "k-1", T0)));

        assertThat(result.getAccepted()).isZero();
        assertThat(result.getDiscarded()).as("设备不存在的上报必须被显式计数（不静默丢弃）").isEqualTo(1);
        verify(eventLogMapper, never()).insertBatch(anyList());
        verify(eventLogMapper, never()).selectExistingKeys(any(), anyList());
    }

    @Test
    @DisplayName("★ 非法级别：在动库之前整批拒绝（不留半批已落库的状态）")
    void illegalLevelMustRejectWholeBatchBeforeAnyWrite() {
        assertThatThrownBy(() -> service.ingest(req(item(DEVICE, "overTemp", "critical", "k-1", T0))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("事件级别非法");

        verifyNoInteractions(eventLogMapper, deviceMapper);
    }

    @Test
    @DisplayName("★ 查询：设备不存在/跨租户不可见一律按「设备不存在」报错，不泄露存在性")
    void pageMustFailClosedForUnknownOrCrossTenantDevice() {
        when(deviceMapper.selectById(OTHER_DEVICE)).thenReturn(null);

        assertThatThrownBy(() -> service.pageEvents(OTHER_DEVICE, new EventLogQuery()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");

        verify(eventLogMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("★ 查询：时间范围左闭右开 + 级别过滤 + 固定按时间倒序，且分页参数原样下传")
    void pageMustApplyTimeRangeLevelFilterAndOrdering() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device(DEVICE, TENANT));
        when(eventLogMapper.selectPage(any(), any())).thenAnswer(call -> {
            Page<IotEventLog> requested = call.getArgument(0);
            requested.setRecords(List.of(row(7L, DEVICE, "overTemp", "warn", T0)));
            requested.setTotal(1L);
            return requested;
        });
        EventLogQuery query = new EventLogQuery();
        query.setFrom(T0);
        query.setTo(T1);
        query.setLevel("WARN");
        query.setPage(2L);
        query.setPageSize(20L);

        PageResult<EventLogResp> result = service.pageEvents(DEVICE, query);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getLevel()).isEqualTo("warn");
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getPage()).as("分页参数必须原样下传（不能被前端分页器覆盖）").isEqualTo(2L);
        assertThat(result.getPageSize()).isEqualTo(20L);

        AbstractWrapper<IotEventLog, ?, ?> wrapper = capturedWrapper();
        String segment = wrapper.getSqlSegment();
        assertThat(segment).contains("device_id").contains("event_ts").contains("level");
        assertThat(segment.toUpperCase(Locale.ROOT))
            .as("时间线必须按事件发生时刻倒序（最新优先）").contains("ORDER BY").contains("DESC");
        assertThat(wrapper.getParamNameValuePairs().values())
            .as("左闭右开 + 规范化后的级别码必须作为参数绑进 SQL（不是字符串拼接）")
            .contains(T0, T1, EventLevel.WARN.getCode());
    }

    @Test
    @DisplayName("★ 查询：未给级别时不得附加 level 条件（否则空结果会被误读成「没有告警」）")
    void pageWithoutLevelMustNotFilterByLevel() {
        when(deviceMapper.selectById(DEVICE)).thenReturn(device(DEVICE, TENANT));
        when(eventLogMapper.selectPage(any(), any())).thenAnswer(call -> {
            Page<IotEventLog> requested = call.getArgument(0);
            requested.setRecords(List.of());
            requested.setTotal(0L);
            return requested;
        });

        PageResult<EventLogResp> result = service.pageEvents(DEVICE, new EventLogQuery());

        assertThat(result.getItems()).as("空结果必须是空集合，不是 null").isNotNull().isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(capturedWrapper().getSqlSegment()).doesNotContain("level");
    }

    @Test
    @DisplayName("★ 查询：级别非法或时间区间给反必须报错，不静默忽略/不静默交换")
    void pageMustRejectIllegalLevelAndInvertedRange() {
        EventLogQuery badLevel = new EventLogQuery();
        badLevel.setLevel("fatal");
        assertThatThrownBy(() -> service.pageEvents(DEVICE, badLevel))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("事件级别非法");

        EventLogQuery inverted = new EventLogQuery();
        inverted.setFrom(T1);
        inverted.setTo(T0);
        assertThatThrownBy(() -> service.pageEvents(DEVICE, inverted))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("时间范围起点必须早于终点");

        verifyNoInteractions(eventLogMapper);
    }

    /** 构造一台设备。 */
    private static IotDevice device(Long id, Long tenantId) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(tenantId);
        return device;
    }

    /** 构造一行事件实例。 */
    private static IotEventLog row(Long id, Long deviceId, String code, String level, LocalDateTime ts) {
        IotEventLog row = new IotEventLog();
        row.setId(id);
        row.setDeviceId(deviceId);
        row.setEventCode(code);
        row.setLevel(level);
        row.setEventTs(ts);
        return row;
    }

    /** 构造一条上报项（epoch 毫秒按平台时区换算，与真实上报一致）。 */
    private static EventIngestItemDto item(Long deviceId, String code, String level, String key,
                                           LocalDateTime ts) {
        EventIngestItemDto item = new EventIngestItemDto();
        item.setDeviceId(deviceId);
        item.setEventCode(code);
        item.setLevel(level);
        item.setIdempotentKey(key);
        item.setTs(ts.atZone(AvailabilityRules.PLATFORM_ZONE).toInstant().toEpochMilli());
        return item;
    }

    /** 构造上报请求。 */
    private static EventIngestReq req(EventIngestItemDto... items) {
        EventIngestReq req = new EventIngestReq();
        req.setItems(new ArrayList<>(List.of(items)));
        return req;
    }

    /** 取出 {@code insertBatch} 收到的行。 */
    @SuppressWarnings("unchecked")
    private List<IotEventLog> capturedRows() {
        ArgumentCaptor<List<IotEventLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventLogMapper).insertBatch(captor.capture());
        return captor.getValue();
    }

    /** 取出 {@code selectPage} 收到的条件构造器（{@code getParamNameValuePairs} 在 AbstractWrapper 上）。 */
    @SuppressWarnings("unchecked")
    private AbstractWrapper<IotEventLog, ?, ?> capturedWrapper() {
        ArgumentCaptor<Wrapper<IotEventLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(eventLogMapper).selectPage(any(), captor.capture());
        return (AbstractWrapper<IotEventLog, ?, ?>) captor.getValue();
    }

    /** 把 mock 收到的入参安全地当成行清单（避免在断言里到处强转）。 */
    @SuppressWarnings("unchecked")
    private static List<IotEventLog> rowsOf(Object value) {
        return value instanceof List<?> list ? (List<IotEventLog>) list : new ArrayList<>();
    }
}
