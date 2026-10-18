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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.realtime.IotRealtimeService;
import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.starter.tools.csv.CsvUtils;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final IotDeviceServiceImpl service = new IotDeviceServiceImpl(
        productMapper, ledgerService, mock(IotRealtimeService.class));

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
    @DisplayName("产品过滤：productId 非空按等值过滤，为空不加任何过滤")
    void buildWrapperShouldFilterByProductIdOnlyWhenPresent() {
        IotDeviceQuery query = new IotDeviceQuery();
        query.setProductId(2001L);

        LambdaQueryWrapper<IotDevice> wrapper = service.buildWrapper(query);

        assertThat(wrapper.getSqlSegment()).contains("product_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(2001L);

        IotDeviceQuery withoutProduct = new IotDeviceQuery();
        assertThat(service.buildWrapper(withoutProduct).getSqlSegment()).doesNotContain("product_id");
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

    @Test
    @DisplayName("★ 停用设备必须落库 status=0 并推进版本号（G7′：停用要真的停止采集，不是只写个标记）")
    void disableMustPersistStatusAndBumpEpoch() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(existingDevice());

        service.updateStatus(1L, EntityStatus.DISABLED.getCode());

        ArgumentCaptor<IotDevice> captured = ArgumentCaptor.forClass(IotDevice.class);
        verify(mapper).updateById(captured.capture());
        assertThat(captured.getValue().getId()).isEqualTo(1L);
        assertThat(captured.getValue().getStatus())
            .as("停用位必须是 EntityStatus.DISABLED 的码值").isEqualTo(EntityStatus.DISABLED.getCode());
        verify(ledgerService).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("★ 启用设备恢复 status=1（停用→启用必须能回到采集，不许单向）")
    void enableMustPersistEnabledStatus() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        IotDevice disabled = existingDevice();
        disabled.setStatus(EntityStatus.DISABLED.getCode());
        when(mapper.selectById(1L)).thenReturn(disabled);

        service.updateStatus(1L, EntityStatus.ENABLED.getCode());

        ArgumentCaptor<IotDevice> captured = ArgumentCaptor.forClass(IotDevice.class);
        verify(mapper).updateById(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo(EntityStatus.ENABLED.getCode());
        verify(ledgerService).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("非法 status（第三态）必须被拒且不写库：规格下发只认 1，第三态等于静默停采")
    void illegalStatusMustBeRejectedWithoutWrite() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(existingDevice());

        for (Integer bad : new Integer[] {2, -1, null}) {
            assertThatThrownBy(() -> service.updateStatus(1L, bad))
                .as("status=%s 必须抛业务异常", bad)
                .isInstanceOf(BusinessException.class);
        }
        verify(mapper, never()).updateById(any(IotDevice.class));
        verify(ledgerService, never()).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("设备不存在时启停必须报错（不得静默成功）")
    void statusUpdateOnMissingDeviceMustFail() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateStatus(404L, EntityStatus.DISABLED.getCode()))
            .isInstanceOf(BusinessException.class);
        verify(mapper, never()).updateById(any(IotDevice.class));
    }

    @Test
    @DisplayName("★ status 为 null 的编辑请求不得改动启停位（旧版表单只改备注时不能把设备静默停用）")
    void updateWithoutStatusMustKeepExistingStatus() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        IotDevice enabled = existingDevice();
        enabled.setStatus(EntityStatus.ENABLED.getCode());
        when(mapper.selectById(1L)).thenReturn(enabled);

        IotDeviceReq req = deviceReq();
        req.setStatus(null);
        service.updateDevice(1L, req);

        ArgumentCaptor<IotDevice> captured = ArgumentCaptor.forClass(IotDevice.class);
        verify(mapper).updateById(captured.capture());
        assertThat(captured.getValue().getStatus())
            .as("请求未带 status ⇒ 必须保留读到的原值").isEqualTo(EntityStatus.ENABLED.getCode());
    }

    @Test
    @DisplayName("带 status 的编辑请求生效（编辑表单里也能直接改启停位）")
    void updateWithStatusMustApplyIt() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(1L)).thenReturn(existingDevice());

        IotDeviceReq req = deviceReq();
        req.setStatus(EntityStatus.DISABLED.getCode());
        service.updateDevice(1L, req);

        ArgumentCaptor<IotDevice> captured = ArgumentCaptor.forClass(IotDevice.class);
        verify(mapper).updateById(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo(EntityStatus.DISABLED.getCode());
    }

    @Test
    @DisplayName("批量启停：一次批量 UPDATE + 台账版本号只推进一次")
    void batchUpdateStatusShouldUpdateOnceAndBumpEpochOnce() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.update(any(LambdaUpdateWrapper.class))).thenReturn(3);

        int rows = service.batchUpdateStatus(List.of(1L, 2L, 3L), EntityStatus.DISABLED.getCode());

        assertThat(rows).isEqualTo(3);
        ArgumentCaptor<LambdaUpdateWrapper<IotDevice>> captor =
            ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(1)).update(captor.capture());
        assertThat(captor.getValue().getSqlSet())
            .as("批量启停必须显式 set status").contains("status");
        assertThat(captor.getValue().getSqlSegment())
            .as("WHERE 必须是 id IN (一批主键)").contains("id");
        assertThat(captor.getValue().getParamNameValuePairs().values())
            .contains(1L, 2L, 3L, EntityStatus.DISABLED.getCode());
        verify(ledgerService, times(1)).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("批量删除：一次批量 DELETE + 台账版本号只推进一次")
    void batchRemoveShouldDeleteOnceAndBumpEpochOnce() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);

        int rows = service.batchRemove(List.of(7L, 8L));

        assertThat(rows).isEqualTo(2);
        ArgumentCaptor<LambdaQueryWrapper<IotDevice>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(1)).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .as("WHERE 必须是 id IN (一批主键)").contains("id");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(7L, 8L);
        verify(ledgerService, times(1)).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("批量校验：null/空/超 500/含 null ID/非法状态一律拒绝，不写库、不推进版本号")
    void batchOperationsMustValidateBeforeWrite() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        assertThatThrownBy(() -> service.batchUpdateStatus(null, EntityStatus.ENABLED.getCode()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchUpdateStatus(List.of(), EntityStatus.ENABLED.getCode()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchUpdateStatus(Arrays.asList(1L, null),
            EntityStatus.ENABLED.getCode()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchUpdateStatus(manyIds(501), EntityStatus.ENABLED.getCode()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 500");
        for (Integer bad : new Integer[] {null, 2, -1}) {
            assertThatThrownBy(() -> service.batchUpdateStatus(List.of(1L), bad))
                .as("status=%s 必须被拒（第三态会静默停采）", bad)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态取值非法");
        }
        assertThatThrownBy(() -> service.batchRemove(null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchRemove(List.of()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchRemove(manyIds(501)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 500");

        verify(mapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(mapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(ledgerService, never()).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("500 台上限：500 放行且仍为一次 SQL，501 在服务层兜底拒绝")
    void batchSizeLimitShouldAllowBoundaryAndRejectBeyond() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.delete(any(LambdaQueryWrapper.class))).thenReturn(500);

        int rows = service.batchRemove(manyIds(500));

        assertThat(rows).isEqualTo(500);
        verify(mapper, times(1)).delete(any(LambdaQueryWrapper.class));
        verify(ledgerService, times(1)).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("★ 设备导出：复用 keyword/protocol/productId 条件；中文可读列 + 产品名批量解析 + BOM")
    void exportCsvShouldApplyQueryAndRenderReadableColumns() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        IotDeviceQuery query = new IotDeviceQuery();
        query.setKeyword("  网关  ");
        query.setProtocol("tcp");
        query.setProductId(2001L);
        when(mapper.selectPage(any(), any())).thenReturn(pageOf(List.of(exportedDevice())));
        IotProduct product = new IotProduct();
        product.setId(2001L);
        product.setProductName("温湿度产品");
        when(productMapper.selectByIds(any())).thenReturn(List.of(product));

        String csv = service.exportCsv(query);

        assertThat(csv).startsWith(CsvUtils.UTF8_BOM);
        assertThat(csv).contains("设备编码,设备名称,接入协议").contains("TCP").contains("在线")
            .contains("温湿度产品").contains("启用").contains("2026-09-20 09:30:00");
        ArgumentCaptor<LambdaQueryWrapper<IotDevice>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .as("导出必须复用列表筛选条件，不能另写一套").contains("device_name")
            .contains("protocol").contains("product_id");
        assertThat(captor.getValue().getParamNameValuePairs().values())
            .contains("%网关%", "tcp", 2001L);
        verify(productMapper).selectByIds(any());
    }

    @Test
    @DisplayName("★ 设备导出：超过 10000 条必须拒绝（提示先筛选，不静默截断）")
    void exportCsvShouldRejectOverLimit() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectPage(any(), any())).thenReturn(pageOf(manyDevices(10001)));

        assertThatThrownBy(() -> service.exportCsv(new IotDeviceQuery()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("10000")
            .hasMessageContaining("筛选");
        verify(productMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("设备导出：10000 条边界放行，且空结果只输出 BOM + 表头")
    void exportCsvShouldAllowBoundaryAndHandleEmptyResult() {
        IotDeviceMapper mapper = mock(IotDeviceMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        when(mapper.selectPage(any(), any())).thenReturn(pageOf(manyDevices(10000)));
        assertThat(service.exportCsv(new IotDeviceQuery()))
            .as("恰好 10000 条必须放行").startsWith(CsvUtils.UTF8_BOM);

        when(mapper.selectPage(any(), any())).thenReturn(pageOf(List.of()));
        String empty = service.exportCsv(new IotDeviceQuery());
        assertThat(empty).startsWith(CsvUtils.UTF8_BOM).endsWith("\r\n");
        assertThat(empty.split("\r\n", -1)).as("空数据只有表头行 + 结尾换行").hasSize(2);
        assertThat(empty).contains("设备编码");
    }

    /** 构造 n 个连续设备（用于导出上限边界用例）。 */
    private static List<IotDevice> manyDevices(int size) {
        return IntStream.rangeClosed(1, size).mapToObj(index -> {
            IotDevice device = new IotDevice();
            device.setId((long) index);
            device.setDeviceCode("DEV-" + index);
            device.setDeviceName("设备-" + index);
            return device;
        }).toList();
    }

    /** 导出用例的 MyBatis-Plus 分页返回桩。 */
    private static Page<IotDevice> pageOf(List<IotDevice> devices) {
        Page<IotDevice> page = new Page<>(1, devices.size() + 1L, false);
        page.setRecords(devices);
        return page;
    }

    /** 含全部导出列的设备。 */
    private static IotDevice exportedDevice() {
        IotDevice device = new IotDevice();
        device.setId(1L);
        device.setDeviceCode("DEV-1");
        device.setDeviceName("水表-1");
        device.setProtocol("tcp");
        device.setEndpoint("tcp://127.0.0.1:15002");
        device.setProductId(2001L);
        device.setOnlineStatus("online");
        device.setStatus(EntityStatus.ENABLED.getCode());
        device.setLastSeenAt(LocalDateTime.of(2026, 9, 20, 9, 30));
        device.setCreateTime(LocalDateTime.of(2026, 9, 19, 10, 0));
        device.setRemark("测试");
        return device;
    }

    /** 构造 n 个连续主键（用于批量边界用例）。 */
    private static List<Long> manyIds(int size) {
        return IntStream.rangeClosed(1, size).mapToObj(Long::valueOf).toList();
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
