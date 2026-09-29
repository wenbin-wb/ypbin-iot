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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.deviceimport.DeviceImportColumn;
import cn.ypbin.admin.iot.deviceimport.DeviceImportLimits;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceGroup;
import cn.ypbin.admin.iot.entity.IotDeviceGroupMember;
import cn.ypbin.admin.iot.entity.IotDeviceImportBatch;
import cn.ypbin.admin.iot.entity.IotDeviceImportRow;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.enums.DeviceImportErrorCode;
import cn.ypbin.admin.iot.enums.DeviceImportRowResult;
import cn.ypbin.admin.iot.enums.DeviceImportStatus;
import cn.ypbin.admin.iot.enums.ModelStatus;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMemberMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceImportBatchMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceImportRowMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.model.query.DeviceImportRowQuery;
import cn.ypbin.admin.iot.model.resp.DeviceImportBatchDetailResp;
import cn.ypbin.admin.iot.model.resp.DeviceImportBatchResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.tenant.core.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.mockito.ArgumentCaptor;

/**
 * 设备批量导入服务用例（纯逻辑：mock Mapper，不起 Spring、不连库）。
 *
 * <p>覆盖任务的用例清单：合法 / 缺列 / 表头错 / BOM / 引号转义 / 超行数 / 超大小 /
 * **重复 deviceCode** / **部分失败** / 租户隔离（无租户上下文时拒绝）。</p>
 *
 * <p>两条本能力**最容易被写错**的断言在这里被钉死：</p>
 * <ol>
 *   <li><b>单行失败不影响其它行</b>：中间一行脏数据不能让前后成功的行一起回滚；</li>
 *   <li><b>重复 deviceCode 不得静默覆盖</b>：必须让该行失败，且库中已有的设备**一个字段都不改**。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class DeviceImportServiceImplTest {

    private final IotDeviceImportBatchMapper batchMapper = mock(IotDeviceImportBatchMapper.class);
    private final IotDeviceImportRowMapper rowMapper = mock(IotDeviceImportRowMapper.class);
    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotProductMapper productMapper = mock(IotProductMapper.class);
    private final IotDeviceGroupMapper groupMapper = mock(IotDeviceGroupMapper.class);
    private final IotDeviceGroupMemberMapper groupMemberMapper =
        mock(IotDeviceGroupMemberMapper.class);
    private final TenantLedgerService ledgerService = mock(TenantLedgerService.class);
    private final cn.ypbin.starter.tenant.core.TenantProvider tenantProvider =
        mock(cn.ypbin.starter.tenant.core.TenantProvider.class);

    private final DeviceImportServiceImpl service = new DeviceImportServiceImpl(batchMapper, rowMapper,
        deviceMapper, productMapper, groupMapper, groupMemberMapper, ledgerService, tenantProvider);

    private static final String HEADER = DeviceImportColumn.headerLine();

    /**
     * 初始化 MyBatis-Plus 的实体元信息。
     *
     * <p>为什么需要：{@code LambdaQueryWrapper} 用方法引用解析列名，而列名来自实体的 TableInfo，
     * 后者通常由 Mapper 扫描时注册——纯单测（不起 Spring/不扫 Mapper）里没有这一步，
     * 会抛 {@code MybatisPlus can not find lambda cache for this entity}。
     * 与 {@code IotDeviceServiceImplTest} 同构。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotDevice.class);
        TableInfoHelper.initTableInfo(assistant, IotDeviceImportBatch.class);
        TableInfoHelper.initTableInfo(assistant, IotDeviceImportRow.class);
    }

    @BeforeEach
    void stubInsert() {
        mockInsertAssignsId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * 在租户 7 的作用域内执行导入。
     *
     * <p>{@code TenantContext} 只有作用域式 API（无公开 setter）⇒ 这里用
     * {@code runWithTenant} 包一层，与生产上租户插件的取值路径完全一致。</p>
     *
     * @param fileName 文件名
     * @param content  文件内容
     * @return 批次响应
     */
    private DeviceImportBatchResp importAsTenant(String fileName, byte[] content) {
        return TenantContext.executeWithTenant(7L, () -> service.importCsv(fileName, content));
    }

    /**
     * 组装一份 CSV 文本。
     *
     * @param dataLines 数据行
     * @return 文本
     */
    private static String csv(String... dataLines) {
        return HEADER + "\n" + String.join("\n", dataLines) + "\n";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 让 mock 的 {@code insert} 像真 MyBatis-Plus 一样回填主键。
     *
     * <p>真库上 {@code insert} 会把雪花 id 写回实体；不模拟这一步，「成功行必须带 deviceId」
     * 这条断言在单测里必然为空 —— 那是测试假象，不是被测代码的缺陷。
     * 批量路径（{@code insertBatch}）由服务层**预生成 id**，不需要 mock 回填。</p>
     */
    private void mockInsertAssignsId() {
        org.mockito.Mockito.doAnswer(invocation -> {
            IotDevice device = invocation.getArgument(0);
            device.setId(IdWorker.getId());
            return 1;
        }).when(deviceMapper).insert(any(IotDevice.class));
    }

    /**
     * 取出本次通过**批量**路径写入的设备（服务层的常态路径）。
     *
     * @return 设备列表
     */
    @SuppressWarnings("unchecked")
    private List<IotDevice> batchInsertedDevices() {
        ArgumentCaptor<List<IotDevice>> captured = ArgumentCaptor.forClass(List.class);
        verify(deviceMapper).insertBatch(captured.capture());
        return captured.getValue();
    }

    /** 一行合法数据（9 列）。 */
    private static String row(String code, String name) {
        return code + "," + name + ",tcp,tcp://10.0.0.1:502,,,,,";
    }

    // ------------------------------------------------------------------
    // 合法路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 全部合法：批次落库为 success，成功数=总数，设备逐行写入，台账版本号被推进")
    void allValidRowsShouldSucceed() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv(row("GW-1", "一号"), row("GW-2", "二号"))));

        assertThat(resp.getTotalRows()).isEqualTo(2);
        assertThat(resp.getSuccessRows()).isEqualTo(2);
        assertThat(resp.getFailedRows()).isZero();
        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.SUCCESS.getCode());
        assertThat(resp.getErrorSummary()).as("全部成功时不该有错误摘要").isNull();
        assertThat(batchInsertedDevices()).as("两台设备必须落库").hasSize(2);
        verify(ledgerService).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("★ 设备默认启用（CSV 的 status 留空 = 我要它启用，不是「不改」）")
    void blankStatusMeansEnabled() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        importAsTenant("devices.csv", bytes(csv(row("GW-1", "一号"))));

        assertThat(batchInsertedDevices().get(0).getStatus()).isEqualTo(EntityStatus.ENABLED.getCode());
    }

    @Test
    @DisplayName("status=0 的设备确实以停用落库（CSV 里能直接建停用设备）")
    void explicitDisabledStatusIsHonored() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        String disabled = "GW-1,一号,tcp,tcp://10.0.0.1:502,,,,0,";

        importAsTenant("devices.csv", bytes(csv(disabled)));

        assertThat(batchInsertedDevices().get(0).getStatus()).isEqualTo(EntityStatus.DISABLED.getCode());
    }

    @Test
    @DisplayName("★ status 非法值（如 2）⇒ 该行失败且**不写库**（第三态等于静默停采）")
    void illegalStatusMustFailTheRow() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        String bad = "GW-1,一号,tcp,tcp://10.0.0.1:502,,,,2,";

        DeviceImportBatchResp resp = importAsTenant("devices.csv", bytes(csv(bad)));

        assertThat(resp.getSuccessRows()).isZero();
        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.FAILED.getCode());
        verify(deviceMapper, never()).insertBatch(any());
        assertThat(errorCodes()).containsExactly(DeviceImportErrorCode.STATUS_INVALID.getCode());
    }

    // ------------------------------------------------------------------
    // 部分失败 / 单行独立
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 部分失败：中间一行脏数据不影响前后成功的行（单行独立，绝不整批回滚）")
    void partialFailureMustNotRollBackOtherRows() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        // 第 2 行端点缺 scheme ⇒ 行级失败；第 1/3 行必须照常建出来
        DeviceImportBatchResp resp = importAsTenant("devices.csv", bytes(csv(
            row("GW-1", "一号"),
            "GW-2,二号,tcp,10.0.0.2:502,,,,,",
            row("GW-3", "三号"))));

        assertThat(resp.getTotalRows()).isEqualTo(3);
        assertThat(resp.getSuccessRows()).as("第 1、3 行必须成功落库").isEqualTo(2);
        assertThat(resp.getFailedRows()).isEqualTo(1);
        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.PARTIAL_FAILED.getCode());
        assertThat(resp.getErrorSummary())
            .as("摘要必须说清失败几行、最常见原因是什么")
            .contains("失败 1 行")
            .contains(DeviceImportErrorCode.ENDPOINT_INVALID.getMessage());
        assertThat(batchInsertedDevices()).as("两台设备必须落库").hasSize(2);
    }

    @Test
    @DisplayName("★ 全部失败：状态是 failed，一台设备都不写")
    void allRowsFailingMeansFailedStatus() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv(",,,,,,,", ",二号,tcp,tcp://h:1,,,,,")));

        assertThat(resp.getSuccessRows()).isZero();
        assertThat(resp.getFailedRows()).isEqualTo(2);
        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.FAILED.getCode());
        verify(deviceMapper, never()).insertBatch(any());
        verify(ledgerService, never()).bumpConfigEpochOfCurrentTenant();
    }

    @Test
    @DisplayName("★ 必填列留空 ⇒ 错误信息指出**是哪一列**（不是笼统一句「参数错误」）")
    void blankRequiredValueMustNameTheColumn() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        importAsTenant("devices.csv", bytes(csv("GW-1,,tcp,tcp://h:1,,,,,")));

        assertThat(errorMessages().get(0))
            .contains(DeviceImportColumn.DEVICE_NAME.getHeader());
    }

    // ------------------------------------------------------------------
    // 幂等 / 去重（本能力最核心的一条）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 库中已有同 deviceCode ⇒ 该行失败，且**绝不覆盖**已有设备（不静默 upsert）")
    void duplicatedDeviceCodeMustFailAndNeverOverwrite() {
        IotDevice existing = new IotDevice();
        existing.setId(99L);
        existing.setDeviceCode("GW-1");
        when(deviceMapper.selectList(any())).thenReturn(List.of(existing));

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv(row("GW-1", "重复的编码"), row("GW-2", "新设备"))));

        assertThat(resp.getSuccessRows()).as("重复行失败、新行成功").isEqualTo(1);
        assertThat(resp.getFailedRows()).isEqualTo(1);
        assertThat(errorCodes()).contains(DeviceImportErrorCode.DEVICE_CODE_DUPLICATED.getCode());
        // 关键断言：只写了「新设备」那一台；已有设备一个字段都没被改
        assertThat(batchInsertedDevices()).hasSize(1);
        assertThat(batchInsertedDevices().get(0).getDeviceCode()).isEqualTo("GW-2");
        verify(deviceMapper, never()).updateById(any(IotDevice.class));
    }

    @Test
    @DisplayName("★ 同一文件内 deviceCode 重复 ⇒ 第二行失败，错误码与「库中已存在」区分开（处置方式不同）")
    void duplicatedCodeInsideFileMustBeDistinguished() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv(row("GW-1", "第一台"), row("GW-1", "又是它"))));

        assertThat(resp.getSuccessRows()).isEqualTo(1);
        assertThat(errorCodes())
            .as("文件内重复用独立错误码：用户要删重复行，而不是改编码")
            .containsExactly(DeviceImportErrorCode.DEVICE_CODE_DUPLICATED_IN_FILE.getCode());
        assertThat(batchInsertedDevices()).hasSize(1);
    }

    @Test
    @DisplayName("★ 库中判重必须**一次批量查询**，不得逐行查库（1 万行导入不能变成 1 万次往返）")
    void duplicateCheckMustBeOneBatchQuery() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        importAsTenant("devices.csv", bytes(csv(
            row("GW-1", "一"), row("GW-2", "二"), row("GW-3", "三"))));

        verify(deviceMapper, times(1)).selectList(any());
    }

    // ------------------------------------------------------------------
    // 产品 / 分组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 产品未发布 ⇒ 该行失败（设备只能绑已发布物模型）")
    void unpublishedProductMustFailTheRow() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        IotProduct draft = new IotProduct();
        draft.setId(5L);
        draft.setModelStatus(ModelStatus.DRAFT.getCode());
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(draft));

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv("GW-1,一号,tcp,tcp://h:1,5,,,,")));

        assertThat(resp.getSuccessRows()).isZero();
        assertThat(errorCodes()).containsExactly(DeviceImportErrorCode.PRODUCT_NOT_PUBLISHED.getCode());
    }

    @Test
    @DisplayName("产品已发布 ⇒ 该行成功，且 productId 落到设备上")
    void publishedProductIsAccepted() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        IotProduct published = new IotProduct();
        published.setId(5L);
        published.setModelStatus(ModelStatus.PUBLISHED.getCode());
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(published));

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv("GW-1,一号,tcp,tcp://h:1,5,,,,")));

        assertThat(resp.getSuccessRows()).isEqualTo(1);
        assertThat(batchInsertedDevices().get(0).getProductId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("产品不存在 ⇒ 该行失败（不是静默把 productId 丢掉）")
    void missingProductMustFailTheRow() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectBatchIds(any())).thenReturn(List.of());

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv("GW-1,一号,tcp,tcp://h:1,404,,,,")));

        assertThat(resp.getSuccessRows()).isZero();
        assertThat(errorCodes()).containsExactly(DeviceImportErrorCode.PRODUCT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("★ groupIds 有效 ⇒ 设备建出来后自动归组（「建完顺手归组」一步到位）")
    void validGroupIdsMustCreateMembership() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        IotDeviceGroup group = new IotDeviceGroup();
        group.setId(12L);
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of(group));

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv("GW-1,一号,tcp,tcp://h:1,,,12,,")));

        assertThat(resp.getSuccessRows()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IotDeviceGroupMember>> captured = ArgumentCaptor.forClass(List.class);
        verify(groupMemberMapper).insertBatch(captured.capture());
        assertThat(captured.getValue()).hasSize(1);
        assertThat(captured.getValue().get(0).getGroupId()).isEqualTo(12L);
        assertThat(captured.getValue().get(0).getTenantId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("★ groupIds 含不存在的分组 ⇒ 该行失败（不静默丢掉归组意图）")
    void unknownGroupIdMustFailTheRow() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of());

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv("GW-1,一号,tcp,tcp://h:1,,,999,,")));

        assertThat(resp.getSuccessRows()).isZero();
        assertThat(errorCodes()).containsExactly(DeviceImportErrorCode.GROUP_NOT_FOUND.getCode());
        verify(groupMemberMapper, never()).insertBatch(any());
    }

    // ------------------------------------------------------------------
    // 明细落库与对账
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 明细逐行落库：成功行带 deviceId，失败行带错误码与原始行（供失败行 CSV 回吐）")
    void rowsMustBePersistedWithOutcome() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        importAsTenant("devices.csv", bytes(csv(row("GW-1", "一号"),
            "GW-2,二号,tcp,坏端点,,,,,")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IotDeviceImportRow>> captured = ArgumentCaptor.forClass(List.class);
        verify(rowMapper).insertBatch(captured.capture());
        List<IotDeviceImportRow> rows = captured.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getRowResult()).isEqualTo(DeviceImportRowResult.SUCCESS.getCode());
        assertThat(rows.get(0).getDeviceId()).as("成功行必须带上刚建出来的设备 ID").isNotNull();
        assertThat(rows.get(0).getErrorCode()).isNull();
        assertThat(rows.get(1).getRowResult()).isEqualTo(DeviceImportRowResult.FAILED.getCode());
        assertThat(rows.get(1).getErrorCode())
            .isEqualTo(DeviceImportErrorCode.ENDPOINT_INVALID.getCode());
        assertThat(rows.get(1).getRawLine()).as("失败行必须留原始内容").contains("GW-2");
        assertThat(rows.get(1).getDeviceCode()).isEqualTo("GW-2");
        // 行号从 1 开始且连续：用户在失败行 CSV 里靠它定位
        assertThat(rows.get(0).getRowNo()).isEqualTo(1);
        assertThat(rows.get(1).getRowNo()).isEqualTo(2);
        // 明细行必须显式带租户（批量插入不经插件注入）
        assertThat(rows.get(0).getTenantId()).isEqualTo(7L);
        assertThat(rows.get(0).getBatchId()).isEqualTo(rows.get(1).getBatchId());
    }

    @Test
    @DisplayName("★ 批次头计数自洽：总数 = 成功 + 失败，状态与计数一致（对账断言）")
    void batchHeaderMustBeSelfConsistent() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        importAsTenant("devices.csv", bytes(csv(row("GW-1", "一"),
            "GW-2,二,tcp,坏,,,,,",
            row("GW-3", "三"))));

        ArgumentCaptor<IotDeviceImportBatch> captured =
            ArgumentCaptor.forClass(IotDeviceImportBatch.class);
        verify(batchMapper).insert(captured.capture());
        IotDeviceImportBatch batch = captured.getValue();
        assertThat(batch.getTotalRows())
            .isEqualTo(batch.getSuccessRows() + batch.getFailedRows());
        assertThat(batch.getBatchStatus())
            .isEqualTo(DeviceImportStatus.ofCounts(batch.getTotalRows(), batch.getSuccessRows())
                .getCode());
        assertThat(batch.getStartTime()).isNotNull();
        assertThat(batch.getEndTime()).as("同步执行 ⇒ 结束时刻必须已经写上（不留「进行中」假象）")
            .isNotNull();
    }

    @Test
    @DisplayName("★ 分块批量写入失败 ⇒ 回退逐行重试：坏行判失败、同块里的好行照常入库（单行独立不被批量写法吃掉）")
    void batchFailureMustFallBackToRowByRowRetry() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        // 批量路径整体抛异常（真库上对应「块里有一行违反约束」）
        org.mockito.Mockito.doThrow(new RuntimeException("Data too long for column 'device_name'"))
            .when(deviceMapper).insertBatch(any());
        // 逐行重试时：第一行失败、第二行成功 —— 用 doAnswer 按设备编码区分
        org.mockito.Mockito.doAnswer(invocation -> {
            IotDevice device = invocation.getArgument(0);
            if ("GW-BAD".equals(device.getDeviceCode())) {
                throw new RuntimeException("Data too long for column 'device_name'");
            }
            device.setId(IdWorker.getId());
            return 1;
        }).when(deviceMapper).insert(any(IotDevice.class));

        DeviceImportBatchResp resp = importAsTenant("devices.csv",
            bytes(csv(row("GW-BAD", "坏行"), row("GW-OK", "好行"))));

        assertThat(resp.getSuccessRows())
            .as("批量块失败后必须逐行重试：同块里的好行不能跟着一起废掉")
            .isEqualTo(1);
        assertThat(resp.getFailedRows()).isEqualTo(1);
        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.PARTIAL_FAILED.getCode());
        assertThat(errorCodes()).containsExactly(DeviceImportErrorCode.PERSIST_FAILED.getCode());
        // 明细里：失败行（下标 0）没有 deviceId，成功行（下标 1）必须带上重试后回填的 deviceId
        List<Long> deviceIds = deviceIdsInRows();
        assertThat(deviceIds.get(0)).as("写不进去的那一行不得带上 deviceId").isNull();
        assertThat(deviceIds.get(1)).as("重试成功的那一行必须带上 deviceId").isNotNull();
    }

    // ------------------------------------------------------------------
    // 文件级拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 表头错 ⇒ 整批拒绝，但**仍留下一条批次记录**（用户在批次列表里看得到原因）")
    void headerErrorMustStillLeaveABatchRecord() {
        DeviceImportBatchResp resp = importAsTenant("bad.csv",
            bytes("deviceCode,protocol\nGW-1,tcp\n"));

        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.FAILED.getCode());
        assertThat(resp.getErrorSummary())
            .contains(DeviceImportErrorCode.HEADER_MISSING_COLUMN.getMessage())
            .contains(DeviceImportColumn.DEVICE_NAME.getHeader());
        verify(batchMapper).insert(any(IotDeviceImportBatch.class));
        verify(deviceMapper, never()).insertBatch(any());
        verify(rowMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("★ 超行数 ⇒ 整批拒绝（不产生半截明细）")
    void tooManyRowsMustRejectWholeBatch() {
        StringBuilder builder = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i <= DeviceImportLimits.MAX_ROWS; i++) {
            builder.append(row("GW-" + i, "设备")).append('\n');
        }

        DeviceImportBatchResp resp = importAsTenant("huge.csv", bytes(builder.toString()));

        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.FAILED.getCode());
        assertThat(resp.getErrorSummary())
            .contains(DeviceImportErrorCode.TOO_MANY_ROWS.getMessage());
        verify(deviceMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("★ 超文件大小 ⇒ 整批拒绝，且不在库里留下任何设备")
    void tooLargeFileMustRejectWholeBatch() {
        byte[] huge = new byte[(int) DeviceImportLimits.MAX_FILE_BYTES + 1];

        DeviceImportBatchResp resp = importAsTenant("huge.csv", huge);

        assertThat(resp.getErrorSummary())
            .contains(DeviceImportErrorCode.FILE_TOO_LARGE.getMessage());
        verify(deviceMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("★ 非 UTF-8 ⇒ 错误摘要明确说「不是 UTF-8」（不是满屏「编码非法」）")
    void nonUtf8MustBeNamedClearly() {
        byte[] gbk = "deviceCode,deviceName,protocol,endpoint\nGW-1,网关,tcp,tcp://h:1\n"
            .getBytes(java.nio.charset.Charset.forName("GBK"));

        DeviceImportBatchResp resp = importAsTenant("gbk.csv", gbk);

        assertThat(resp.getErrorSummary())
            .contains(DeviceImportErrorCode.FILE_NOT_UTF8.getMessage());
    }

    // ------------------------------------------------------------------
    // 租户隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 无租户上下文 ⇒ 拒绝导入（绝不写「无租户」的批次；fail-closed）")
    void missingTenantContextMustBeRejected() {
        // 不进入任何租户作用域 ⇒ 走 fail-closed 分支
        assertThatThrownBy(() -> service.importCsv("devices.csv", bytes(csv(row("GW-1", "一")))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("租户");
        verify(batchMapper, never()).insert(any(IotDeviceImportBatch.class));
        verify(deviceMapper, never()).insertBatch(any());
    }

    // ------------------------------------------------------------------
    // 批次详情 / 失败行下载
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 批次不存在（或不属于本租户）⇒ 报「批次不存在」，不区分以免泄露存在性")
    void missingBatchMustReportNotFound() {
        when(batchMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.getBatchDetail(404L, new DeviceImportRowQuery()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("★ 明细结果筛选非法取值 ⇒ 报错而不是静默忽略（静默会让用户以为筛过了）")
    void illegalRowFilterMustBeRejected() {
        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setId(1L);
        when(batchMapper.selectById(1L)).thenReturn(batch);
        DeviceImportRowQuery query = new DeviceImportRowQuery();
        query.setRowResult("whatever");

        assertThatThrownBy(() -> service.getBatchDetail(1L, query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("success");
    }

    @Test
    @DisplayName("明细筛选按结果收敛（按 failed 查时 wrapper 必须带上结果条件）")
    void rowFilterMustNarrowTheQuery() {
        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setId(1L);
        when(batchMapper.selectById(1L)).thenReturn(batch);
        when(rowMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension
            .plugins.pagination.Page<>(1, 20));
        DeviceImportRowQuery query = new DeviceImportRowQuery();
        query.setRowResult("failed");

        DeviceImportBatchDetailResp resp = service.getBatchDetail(1L, query);

        assertThat(resp.getBatch().getId()).isEqualTo(1L);
        assertThat(resp.getRows().getItems()).isEmpty();
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceImportRow>>
            captured = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(rowMapper).selectPage(any(), captured.capture());
        assertThat(captured.getValue().getSqlSegment())
            .as("必须按 row_result 过滤，否则「只看失败」会显示全部行")
            .contains("row_result");
    }

    @Test
    @DisplayName("★ 失败行 CSV：从库里取失败行并回吐原始内容 + 错误码")
    void failedCsvMustComeFromDatabase() {
        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setId(3L);
        batch.setFailedRows(1);
        when(batchMapper.selectById(3L)).thenReturn(batch);
        IotDeviceImportRow failed = new IotDeviceImportRow();
        failed.setRowNo(2);
        failed.setRawLine("GW-2,二号,tcp,坏端点,,,,,");
        failed.setErrorCode(DeviceImportErrorCode.ENDPOINT_INVALID.getCode());
        failed.setErrorMessage(DeviceImportErrorCode.ENDPOINT_INVALID.getCode());
        when(rowMapper.selectFailedRows(3L, DeviceImportServiceImpl.FAILED_CSV_MAX_ROWS))
            .thenReturn(List.of(failed));

        String csv = service.buildFailedCsv(3L);

        assertThat(csv).contains(DeviceImportColumn.headerLine());
        assertThat(csv).contains("GW-2,二号,tcp,坏端点,,,,,");
        assertThat(csv).contains(DeviceImportErrorCode.ENDPOINT_INVALID.getCode());
    }

    @Test
    @DisplayName("★ 失败行明细与批次头计数不一致 ⇒ 报错（不静默返回一份少几行的文件）")
    void inconsistentFailedCountMustBeReported() {
        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setId(3L);
        batch.setFailedRows(5);
        when(batchMapper.selectById(3L)).thenReturn(batch);
        when(rowMapper.selectFailedRows(anyLong(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.buildFailedCsv(3L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("没有失败行时下载失败 CSV 不报错（这是一份只有表头的合法文件）")
    void noFailedRowsIsNotAnError() {
        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setId(3L);
        batch.setFailedRows(0);
        when(batchMapper.selectById(3L)).thenReturn(batch);
        when(rowMapper.selectFailedRows(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.buildFailedCsv(3L)).contains(DeviceImportColumn.headerLine());
    }

    @Test
    @DisplayName("批次列表：按 id 倒序取本租户批次并转响应（字段逐个搬运，不做改名）")
    void pageBatchesMustMapEveryField() {
        IotDeviceImportBatch entity = new IotDeviceImportBatch();
        entity.setId(9L);
        entity.setFileName("a.csv");
        entity.setTotalRows(3);
        entity.setSuccessRows(2);
        entity.setFailedRows(1);
        entity.setBatchStatus(DeviceImportStatus.PARTIAL_FAILED.getCode());
        entity.setErrorSummary("失败 1 行");
        entity.setOperatorUserId(42L);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<IotDeviceImportBatch> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1L);
        when(batchMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<DeviceImportBatchResp> result = service.pageBatches(1, 10);

        assertThat(result.getItems()).hasSize(1);
        DeviceImportBatchResp resp = result.getItems().get(0);
        assertThat(resp.getId()).isEqualTo(9L);
        assertThat(resp.getFileName()).isEqualTo("a.csv");
        assertThat(resp.getSuccessRows()).isEqualTo(2);
        assertThat(resp.getFailedRows()).isEqualTo(1);
        assertThat(resp.getBatchStatus()).isEqualTo(DeviceImportStatus.PARTIAL_FAILED.getCode());
        assertThat(resp.getErrorSummary()).isEqualTo("失败 1 行");
        assertThat(resp.getOperatorUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("批次列表每页条数被上限截断（防止 pageSize=100000 把库拖住）")
    void pageBatchesMustCapPageSize() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<IotDeviceImportBatch> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 200);
        page.setRecords(List.of());
        when(batchMapper.selectPage(any(), any())).thenReturn(page);

        service.pageBatches(1, 100_000L);

        ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page<IotDeviceImportBatch>>
            captured = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
        verify(batchMapper).selectPage(captured.capture(), any());
        assertThat(captured.getValue().getSize())
            .isLessThanOrEqualTo(DeviceImportServiceImpl.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模板下载：含表头、说明行、示例行，且与解析器同源（不会漂移）")
    void templateMustBeGeneratedFromColumnContract() {
        String template = service.buildTemplateCsv();

        assertThat(template).contains(DeviceImportColumn.headerLine());
        assertThat(template).contains(DeviceImportLimits.TEMPLATE_NOTE_PREFIX);
        assertThat(template).contains("GW-DEMO-001");
    }

    /**
     * 取本次落库的全部明细，抽出错误码（按行号顺序）。
     *
     * @return 错误码列表
     */
    @SuppressWarnings("unchecked")
    private List<String> errorCodes() {
        ArgumentCaptor<List<IotDeviceImportRow>> captured = ArgumentCaptor.forClass(List.class);
        verify(rowMapper).insertBatch(captured.capture());
        List<String> codes = new ArrayList<>();
        for (IotDeviceImportRow row : captured.getValue()) {
            if (row.getErrorCode() != null) {
                codes.add(row.getErrorCode());
            }
        }
        return codes;
    }

    /**
     * 取本次落库明细里的 deviceId（按行序）。
     *
     * @return deviceId 列表（失败行为 null）
     */
    @SuppressWarnings("unchecked")
    private List<Long> deviceIdsInRows() {
        ArgumentCaptor<List<IotDeviceImportRow>> captured = ArgumentCaptor.forClass(List.class);
        verify(rowMapper).insertBatch(captured.capture());
        List<Long> ids = new ArrayList<>();
        for (IotDeviceImportRow row : captured.getValue()) {
            ids.add(row.getDeviceId());
        }
        return ids;
    }

    /**
     * 取本次落库的失败行错误信息（按行号顺序）。
     *
     * @return 错误信息列表
     */
    @SuppressWarnings("unchecked")
    private List<String> errorMessages() {
        ArgumentCaptor<List<IotDeviceImportRow>> captured = ArgumentCaptor.forClass(List.class);
        verify(rowMapper).insertBatch(captured.capture());
        List<String> messages = new ArrayList<>();
        for (IotDeviceImportRow row : captured.getValue()) {
            if (row.getErrorMessage() != null) {
                messages.add(row.getErrorMessage());
            }
        }
        return messages;
    }
}
