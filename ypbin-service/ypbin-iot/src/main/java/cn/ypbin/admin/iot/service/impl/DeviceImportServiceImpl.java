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

import cn.ypbin.admin.iot.deviceimport.DeviceImportColumn;
import cn.ypbin.admin.iot.deviceimport.DeviceImportCsvParser;
import cn.ypbin.admin.iot.deviceimport.DeviceImportLimits;
import cn.ypbin.admin.iot.deviceimport.DeviceImportParseResult;
import cn.ypbin.admin.iot.deviceimport.DeviceImportParseResult.ParsedRow;
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
import cn.ypbin.admin.iot.model.resp.DeviceImportRowResp;
import cn.ypbin.admin.iot.service.DeviceImportService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 设备批量导入（CSV）+ 批次管理服务实现。
 *
 * <p><b>贯穿本类的三条设计决定</b>：</p>
 * <ol>
 *   <li><b>单行独立</b>：每一行独立校验、独立写入，单行失败不影响其它行。因此**不能**把整个
 *       导入放在一个「要么全成功要么全回滚」的事务语义里——但落库又必须是原子的（不能出现
 *       「批次头写了 3 行、明细只写了 1 行」）。做法是：<b>先在内存里算出每一行的结果，
 *       再在一个事务里一次性写批次头 + 全部明细 + 全部设备</b>。这样既满足「单行独立」，
 *       也满足「批次与计数自洽」。</li>
 *   <li><b>重复 deviceCode 一律失败，绝不覆盖</b>：这是本能力与「导入即 upsert」型工具最重要的
 *       区别。覆盖式导入在生产上意味着「一次误传把 200 台设备的名称/端点全改了」，
 *       而且用户无从知道改了哪些。判重有**两个来源**：库中已有（{@code DEVICE_CODE_DUPLICATED}）
 *       与本文件内前面已成功的行（{@code DEVICE_CODE_DUPLICATED_IN_FILE}）——分开两个码是因为
 *       用户的处置方式不同：前者要改编码，后者要删重复行。</li>
 *   <li><b>校验前置成批量查询</b>：已有的 deviceCode、产品、分组都**一次查全量**再在内存里判，
 *       而不是逐行查库（既违反「禁循环内 DB」，也会让 1 万行导入变成 1 万次往返）。</li>
 * </ol>
 *
 * <p><b>为什么没有「异步 + 轮询进度」</b>：批次状态里的 {@code running} 是给未来的异步执行留的位
 * （也是对标产品里真实存在的态），但当前实现在一次请求内完成——1 万行解析 + 批量写入在本仓
 * 的量级下是秒级。为了一个还没出现的问题引入消息队列/线程池，会让「批次怎么就卡在 running 了」
 * 成为新的运维问题。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Service
public class DeviceImportServiceImpl implements DeviceImportService {

    private static final Logger log = LoggerFactory.getLogger(DeviceImportServiceImpl.class);

    /** 设备编码格式（与 {@code IotDeviceReq} 的口径一致：非空、≤64）。合法字符不做额外收窄，只挡空白。 */
    private static final Pattern DEVICE_CODE_PATTERN = Pattern.compile("\\S{1,64}");

    /** 协议码格式（与 {@code IotDeviceReq} 的 {@code @Pattern} 逐字一致）。 */
    private static final Pattern PROTOCOL_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

    /** 端点格式（与 {@code IotDeviceReq} 的 {@code @Pattern} 逐字一致：必须带 scheme 且不含空白）。 */
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");

    /** 明细批量插入的批大小（与仓内 {@code insertBatch} 用法一致，避免单条 SQL 过大）。 */
    static final int ROW_INSERT_BATCH_SIZE = 500;

    /** 失败行下载的单次上限（一个批次最多 1 万行，这里是防御性上限而非业务上限）。 */
    static final int FAILED_CSV_MAX_ROWS = DeviceImportLimits.MAX_ROWS;

    /** 明细分页的默认/最大每页条数。 */
    static final long DEFAULT_PAGE_SIZE = 20L;

    static final long MAX_PAGE_SIZE = 200L;

    private final IotDeviceImportBatchMapper batchMapper;
    private final IotDeviceImportRowMapper rowMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotProductMapper productMapper;
    private final IotDeviceGroupMapper groupMapper;
    private final IotDeviceGroupMemberMapper groupMemberMapper;
    private final TenantLedgerService tenantLedgerService;
    private final TenantProvider tenantProvider;
    private final DeviceImportCsvParser csvParser = new DeviceImportCsvParser();

    public DeviceImportServiceImpl(IotDeviceImportBatchMapper batchMapper,
                                   IotDeviceImportRowMapper rowMapper,
                                   IotDeviceMapper deviceMapper,
                                   IotProductMapper productMapper,
                                   IotDeviceGroupMapper groupMapper,
                                   IotDeviceGroupMemberMapper groupMemberMapper,
                                   TenantLedgerService tenantLedgerService,
                                   TenantProvider tenantProvider) {
        this.batchMapper = batchMapper;
        this.rowMapper = rowMapper;
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.groupMapper = groupMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.tenantLedgerService = tenantLedgerService;
        this.tenantProvider = tenantProvider;
    }

    @Override
    public String buildTemplateCsv() {
        return csvParser.buildTemplate();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceImportBatchResp importCsv(String fileName, byte[] content) {
        Long tenantId = currentTenantId();
        if (tenantId == null) {
            // 批次是租户数据：没有租户上下文绝不猜、不写「无租户」的行
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "缺少租户上下文，无法执行批量导入");
        }
        LocalDateTime now = LocalDateTime.now();
        String safeFileName = normalizeFileName(fileName);

        DeviceImportParseResult parsed = csvParser.parse(content);
        if (parsed.isFatal()) {
            // 文件级失败（表头错/超限/非 UTF-8）：整批拒绝，但仍**留下一个批次记录**——
            // 用户在批次列表里能看到「我传过这个文件、它因为什么被拒了」，而不是「点了上传什么都没发生」。
            return persistFatalBatch(safeFileName, parsed, tenantId, now);
        }

        List<ParsedRow> rows = parsed.rows();
        ImportContext context = loadImportContext(rows);
        List<RowOutcome> outcomes = evaluateRows(rows, context);
        int totalRows = outcomes.size();

        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setTenantId(tenantId);
        batch.setFileName(safeFileName);
        batch.setTotalRows(totalRows);
        batch.setStartTime(now);
        batch.setOperatorUserId(currentUserId());

        // 设备先写（明细要带 deviceId）。写库阶段还可能把某几行**降级为失败**
        // （分块批量写失败后逐行重试、那一行确实写不进去），所以计数必须在写库**之后**才算：
        // 先算再写会让「头说成功 2 行、实际只进去 1 行」这种自相矛盾的批次落库
        // （对账断言 assertCountsConsistent 会拦住它 —— 本能力第一版就是在这里被自己抓住的）。
        persistDevices(outcomes, tenantId);

        int successRows = countSuccess(outcomes);
        batch.setSuccessRows(successRows);
        batch.setFailedRows(totalRows - successRows);
        batch.setBatchStatus(DeviceImportStatus.ofCounts(totalRows, successRows).getCode());
        batch.setEndTime(LocalDateTime.now());
        batch.setErrorSummary(summarize(outcomes));
        batchMapper.insert(batch);

        persistRows(batch, outcomes, tenantId);

        // 对账断言：成功数 + 失败数必须等于总行数，且与明细落库条数一致。
        // 不一致说明上面的逻辑有 bug —— 宁可这里炸掉（事务回滚、用户看到明确错误），
        // 也不要让页面显示一个自相矛盾的批次（「成功 3 / 失败 2 / 共 6 行」）。
        assertCountsConsistent(batch, outcomes);

        // 设备被批量创建 ⇒ 采集配置变了，必须推进台账版本号（与单条创建同一约定）
        if (successRows > 0) {
            tenantLedgerService.bumpConfigEpochOfCurrentTenant();
        }
        log.info("[iot] 批量导入完成：batchId={}, file={}, total={}, success={}, failed={}",
            batch.getId(), LogSanitizer.sanitize(safeFileName), totalRows, successRows,
            totalRows - successRows);
        return toBatchResp(batch);
    }

    @Override
    public DeviceImportBatchDetailResp getBatchDetail(Long batchId, DeviceImportRowQuery query) {
        IotDeviceImportBatch batch = requireBatch(batchId);
        DeviceImportRowResult filter = resolveRowFilter(query);
        long page = query == null || query.getPage() <= 0 ? 1L : query.getPage();
        long pageSize = query == null || query.getPageSize() <= 0 ? DEFAULT_PAGE_SIZE : query.getPageSize();
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        LambdaQueryWrapper<IotDeviceImportRow> wrapper = new LambdaQueryWrapper<IotDeviceImportRow>()
            .eq(IotDeviceImportRow::getBatchId, batchId)
            .orderByAsc(IotDeviceImportRow::getRowNo);
        if (filter != null) {
            wrapper.eq(IotDeviceImportRow::getRowResult, filter.getCode());
        }
        IPage<IotDeviceImportRow> source = rowMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<DeviceImportRowResp> items = source.getRecords().stream().map(this::toRowResp).toList();

        DeviceImportBatchDetailResp resp = new DeviceImportBatchDetailResp();
        resp.setBatch(toBatchResp(batch));
        resp.setRows(PageResult.of(items, source.getTotal(), source.getCurrent(), source.getSize()));
        return resp;
    }

    @Override
    public String buildFailedCsv(Long batchId) {
        IotDeviceImportBatch batch = requireBatch(batchId);
        List<IotDeviceImportRow> failed = rowMapper.selectFailedRows(batchId, FAILED_CSV_MAX_ROWS);
        List<DeviceImportCsvParser.FailedRow> rows = new ArrayList<>(failed.size());
        for (IotDeviceImportRow row : failed) {
            rows.add(new DeviceImportCsvParser.FailedRow(row.getRawLine(), row.getErrorCode(),
                row.getErrorMessage()));
        }
        // 计数对账：库里失败行数必须等于批次头的 failedRows（不等说明有并发写入或数据被手工改过；
        // 静默返回一份少了几行的文件会让用户以为「改完这些就好了」，而实际还有没下发的失败行）
        if (batch.getFailedRows() != null && batch.getFailedRows() > failed.size()
            && batch.getFailedRows() <= FAILED_CSV_MAX_ROWS) {
            log.error("[iot] 批次 {} 的失败行数与明细不一致：头={}，明细={}（下载内容可能不完整）",
                batchId, batch.getFailedRows(), failed.size());
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "批次失败行明细与计数不一致（头 " + batch.getFailedRows() + " / 明细 " + failed.size()
                    + "），请重新查询批次详情");
        }
        return csvParser.buildFailedCsv(rows);
    }

    @Override
    public PageResult<DeviceImportBatchResp> pageBatches(long page, long pageSize) {
        long current = page <= 0 ? 1L : page;
        long size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        IPage<IotDeviceImportBatch> source = batchMapper.selectPage(new Page<>(current, size),
            new LambdaQueryWrapper<IotDeviceImportBatch>()
                .orderByDesc(IotDeviceImportBatch::getId));
        List<DeviceImportBatchResp> items = source.getRecords().stream().map(this::toBatchResp).toList();
        return PageResult.of(items, source.getTotal(), source.getCurrent(), source.getSize());
    }

    /**
     * 文件级失败也要留痕：写一条「总行数 0 / 失败 0 / 状态 failed + 错误摘要」的批次。
     *
     * <p>为什么不干脆报错返回：用户在页面上看到的应该是一个**批次记录**（点得开、看得到原因），
     * 而不是一个转瞬即逝的 toast。这也是对标产品「批次管理」页签的意义所在。</p>
     *
     * @param fileName 文件名
     * @param parsed   解析结果（必为 fatal）
     * @param tenantId 租户
     * @param now      开始时刻
     * @return 批次响应
     */
    private DeviceImportBatchResp persistFatalBatch(String fileName, DeviceImportParseResult parsed,
                                                    Long tenantId, LocalDateTime now) {
        String summary = parsed.fatalError().format(parsed.fatalDetail());
        IotDeviceImportBatch batch = new IotDeviceImportBatch();
        batch.setTenantId(tenantId);
        batch.setFileName(fileName);
        batch.setTotalRows(0);
        batch.setSuccessRows(0);
        batch.setFailedRows(0);
        batch.setBatchStatus(DeviceImportStatus.FAILED.getCode());
        batch.setErrorSummary(summary);
        batch.setStartTime(now);
        batch.setEndTime(LocalDateTime.now());
        batch.setOperatorUserId(currentUserId());
        batchMapper.insert(batch);
        log.warn("[iot] 批量导入被整批拒绝：file={}, error={}",
            LogSanitizer.sanitize(fileName), summary);
        return toBatchResp(batch);
    }

    /**
     * 装载本次导入所需的**全部**外部数据（一次查完，不在循环里查库）。
     *
     * @param rows 解析出的行
     * @return 导入上下文
     */
    private ImportContext loadImportContext(List<ParsedRow> rows) {
        Set<String> codesInFile = new LinkedHashSet<>();
        Set<Long> productIds = new LinkedHashSet<>();
        Set<Long> groupIds = new LinkedHashSet<>();
        for (ParsedRow row : rows) {
            if (row.errorCode() != null) {
                continue;
            }
            String code = row.value(DeviceImportColumn.DEVICE_CODE);
            if (!code.isEmpty()) {
                codesInFile.add(code);
            }
            Long productId = parseLongOrNull(row.value(DeviceImportColumn.PRODUCT_ID));
            if (productId != null) {
                productIds.add(productId);
            }
            groupIds.addAll(parseGroupIds(row.value(DeviceImportColumn.GROUP_IDS)));
        }

        // ① 库中已有的 deviceCode（一次 IN 查询）
        Set<String> existingCodes = new LinkedHashSet<>();
        if (!codesInFile.isEmpty()) {
            for (IotDevice device : deviceMapper.selectList(
                new LambdaQueryWrapper<IotDevice>().in(IotDevice::getDeviceCode, codesInFile))) {
                existingCodes.add(device.getDeviceCode());
            }
        }
        // ② 产品（一次批量查询 + 内存判定「存在且已发布」）
        Map<Long, IotProduct> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (IotProduct product : productMapper.selectBatchIds(productIds)) {
                products.put(product.getId(), product);
            }
        }
        // ③ 分组（一次批量查询；分组不存在 ⇒ 该行失败，不静默忽略）
        Set<Long> existingGroupIds = new LinkedHashSet<>();
        if (!groupIds.isEmpty()) {
            for (IotDeviceGroup group : groupMapper.selectBatchIds(groupIds)) {
                existingGroupIds.add(group.getId());
            }
        }
        return new ImportContext(existingCodes, products, existingGroupIds);
    }

    /**
     * 逐行独立校验（**纯内存**：所需数据已在 {@link ImportContext} 里）。
     *
     * @param rows    解析出的行
     * @param context 导入上下文
     * @return 逐行结果（顺序与入参一致）
     */
    private List<RowOutcome> evaluateRows(List<ParsedRow> rows, ImportContext context) {
        List<RowOutcome> outcomes = new ArrayList<>(rows.size());
        // 本文件内已成功占用的 deviceCode：重复出现 ⇒ 该行失败（不覆盖、不静默跳过）
        Set<String> claimedInFile = new LinkedHashSet<>();
        for (ParsedRow row : rows) {
            if (row.errorCode() != null) {
                outcomes.add(RowOutcome.failed(row, row.errorCode(), row.errorDetail()));
                continue;
            }
            RowOutcome outcome = evaluateRow(row, context, claimedInFile);
            if (outcome.success()) {
                claimedInFile.add(outcome.device().getDeviceCode());
            }
            outcomes.add(outcome);
        }
        return outcomes;
    }

    /**
     * 单行业务校验。
     *
     * @param row           行
     * @param context       导入上下文
     * @param claimedInFile 本文件内已成功的 deviceCode
     * @return 行结果
     */
    private RowOutcome evaluateRow(ParsedRow row, ImportContext context, Set<String> claimedInFile) {
        String deviceCode = row.value(DeviceImportColumn.DEVICE_CODE);
        String deviceName = row.value(DeviceImportColumn.DEVICE_NAME);
        String protocol = row.value(DeviceImportColumn.PROTOCOL);
        String endpoint = row.value(DeviceImportColumn.ENDPOINT);

        if (deviceCode.isEmpty()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.REQUIRED_VALUE_MISSING,
                DeviceImportColumn.DEVICE_CODE.getHeader());
        }
        if (deviceName.isEmpty()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.REQUIRED_VALUE_MISSING,
                DeviceImportColumn.DEVICE_NAME.getHeader());
        }
        if (protocol.isEmpty()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.REQUIRED_VALUE_MISSING,
                DeviceImportColumn.PROTOCOL.getHeader());
        }
        if (endpoint.isEmpty()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.REQUIRED_VALUE_MISSING,
                DeviceImportColumn.ENDPOINT.getHeader());
        }
        // 长度上限：先于格式判定，让用户看到的是「太长」而不是「格式非法」
        RowOutcome lengthCheck = checkLength(row, deviceCode, deviceName, endpoint);
        if (lengthCheck != null) {
            return lengthCheck;
        }
        if (!DEVICE_CODE_PATTERN.matcher(deviceCode).matches()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.DEVICE_CODE_INVALID, deviceCode);
        }
        if (!PROTOCOL_PATTERN.matcher(protocol).matches()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.PROTOCOL_INVALID, protocol);
        }
        if (!ENDPOINT_PATTERN.matcher(endpoint).matches()) {
            return RowOutcome.failed(row, DeviceImportErrorCode.ENDPOINT_INVALID, endpoint);
        }
        // 判重：文件内重复与库中已存在**分开两个码**（用户的处置方式不同）
        if (claimedInFile.contains(deviceCode)) {
            return RowOutcome.failed(row, DeviceImportErrorCode.DEVICE_CODE_DUPLICATED_IN_FILE, deviceCode);
        }
        if (context.existingCodes().contains(deviceCode)) {
            return RowOutcome.failed(row, DeviceImportErrorCode.DEVICE_CODE_DUPLICATED, deviceCode);
        }
        Long productId = parseLongOrNull(row.value(DeviceImportColumn.PRODUCT_ID));
        if (!row.isBlank(DeviceImportColumn.PRODUCT_ID) && productId == null) {
            return RowOutcome.failed(row, DeviceImportErrorCode.PRODUCT_NOT_FOUND,
                row.value(DeviceImportColumn.PRODUCT_ID));
        }
        if (productId != null) {
            IotProduct product = context.products().get(productId);
            if (product == null) {
                return RowOutcome.failed(row, DeviceImportErrorCode.PRODUCT_NOT_FOUND,
                    String.valueOf(productId));
            }
            if (!ModelStatus.PUBLISHED.getCode().equals(product.getModelStatus())) {
                return RowOutcome.failed(row, DeviceImportErrorCode.PRODUCT_NOT_PUBLISHED,
                    String.valueOf(productId));
            }
        }
        List<Long> groupIds = parseGroupIds(row.value(DeviceImportColumn.GROUP_IDS));
        for (Long groupId : groupIds) {
            if (!context.existingGroupIds().contains(groupId)) {
                return RowOutcome.failed(row, DeviceImportErrorCode.GROUP_NOT_FOUND,
                    String.valueOf(groupId));
            }
        }
        Integer status = parseStatus(row.value(DeviceImportColumn.STATUS));
        if (status == null && !row.isBlank(DeviceImportColumn.STATUS)) {
            return RowOutcome.failed(row, DeviceImportErrorCode.STATUS_INVALID,
                row.value(DeviceImportColumn.STATUS));
        }

        IotDevice device = new IotDevice();
        device.setDeviceCode(deviceCode);
        device.setDeviceName(deviceName);
        device.setProtocol(protocol);
        device.setEndpoint(endpoint);
        device.setProductId(productId);
        device.setProductVersion(emptyToNull(row.value(DeviceImportColumn.PRODUCT_VERSION)));
        device.setRemark(emptyToNull(row.value(DeviceImportColumn.REMARK)));
        // 空表示启用：与单条创建一致（请求不带 status 时由 DB 默认值 1 兜底）。
        // 这里显式赋值而不是留 null，因为批量导入的「留空」语义是「我要它启用」而不是「不改」。
        device.setStatus(status == null ? EntityStatus.ENABLED.getCode() : status);
        return RowOutcome.success(row, device, groupIds);
    }

    /**
     * 必填字段的长度上限校验（与 {@code IotDeviceReq} 的 {@code @Size} 一致）。
     *
     * @param row        行
     * @param deviceCode 设备编码
     * @param deviceName 设备名称
     * @param endpoint   端点
     * @return 超限时的失败结果；都不超限返回 {@code null}
     */
    private RowOutcome checkLength(ParsedRow row, String deviceCode, String deviceName, String endpoint) {
        if (deviceCode.length() > 64) {
            return RowOutcome.failed(row, DeviceImportErrorCode.VALUE_TOO_LONG,
                DeviceImportColumn.DEVICE_CODE.getHeader() + "（" + deviceCode.length() + " > 64）");
        }
        if (deviceName.length() > 100) {
            return RowOutcome.failed(row, DeviceImportErrorCode.VALUE_TOO_LONG,
                DeviceImportColumn.DEVICE_NAME.getHeader() + "（" + deviceName.length() + " > 100）");
        }
        if (endpoint.length() > 300) {
            return RowOutcome.failed(row, DeviceImportErrorCode.VALUE_TOO_LONG,
                DeviceImportColumn.ENDPOINT.getHeader() + "（" + endpoint.length() + " > 300）");
        }
        for (DeviceImportColumn column : List.of(DeviceImportColumn.PRODUCT_VERSION,
            DeviceImportColumn.REMARK)) {
            if (row.value(column).length() > DeviceImportLimits.MAX_FIELD_CHARS) {
                return RowOutcome.failed(row, DeviceImportErrorCode.VALUE_TOO_LONG,
                    column.getHeader());
            }
        }
        return null;
    }

    /**
     * 写入成功行的设备（含分组归属）——**分块批量插入**，不是逐行往返。
     *
     * <p><b>为什么分块批量</b>：一次导入最多 1 万行，逐行 {@code insert} 就是 1 万次数据库往返；
     * 按 {@value #ROW_INSERT_BATCH_SIZE} 切块后每块一条语句，往返降到「块数」量级
     * （与本仓 {@code IotEventLogMapper.insertBatch} / {@code MaintenanceWindowMapper.insertBatch}
     * 的既有形态一致，也是架构门禁 {@code loopsMustNotCallDbOrRpc} 期望的写法）。</p>
     *
     * <p><b>块失败怎么还能「单行独立」</b>：整块写失败时**回退到逐行重试**
     * （{@link #persistDevicesRowByRow}），把那块里的坏行挑出来单独判失败、其余行照常入库。
     * 这样既拿到了批量写入的性能，又保住了「单行失败不影响其它行」这条本能力的核心语义：
     * 块级失败只是「这一块里至少有一行有问题」的信号，不是「这一块全废」的结论。</p>
     *
     * @param outcomes 行结果（就地改写失败行）
     * @param tenantId 租户
     */
    private void persistDevices(List<RowOutcome> outcomes, Long tenantId) {
        // 索引级操作而不是「拿对象去找它在哪」：RowOutcome 是 record，没有标识可比；
        // 用下标就地改写是唯一不会错改到别的行的做法
        List<Integer> successIndexes = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i).success()) {
                outcomes.get(i).device().setTenantId(tenantId);
                successIndexes.add(i);
            }
        }
        for (int start = 0; start < successIndexes.size(); start += ROW_INSERT_BATCH_SIZE) {
            int end = Math.min(start + ROW_INSERT_BATCH_SIZE, successIndexes.size());
            List<Integer> chunk = successIndexes.subList(start, end);
            List<IotDevice> devices = new ArrayList<>(chunk.size());
            for (Integer index : chunk) {
                // 主键由应用层预生成（批量插入必须自带 id：BaseMapper.insert 的雪花回填不适用于批次语句）
                outcomes.get(index).device().setId(IdWorker.getId());
                devices.add(outcomes.get(index).device());
            }
            try {
                deviceMapper.insertBatch(devices);
            } catch (RuntimeException ex) {
                // 记录完整堆栈（不吞异常）：块失败的具体原因对排障有价值
                log.error("[iot] 批量导入第 {}-{} 行设备分块写入失败，回退逐行重试",
                    outcomes.get(chunk.get(0)).row().rowNo(),
                    outcomes.get(chunk.get(chunk.size() - 1)).row().rowNo(), ex);
                persistDevicesRowByRow(outcomes, chunk, tenantId);
            }
        }
        // 归组在设备全部落库之后一次批量写：不必在每台设备之后各发一条语句
        persistGroupMembers(outcomes, successIndexes, tenantId);
    }

    /**
     * 逐行重试（分块失败时的回退路径）：把坏行单独判失败，好行照常入库。
     *
     * @param outcomes 全部行结果（就地改写失败行）
     * @param chunk    待重试的行下标
     * @param tenantId 租户
     */
    private void persistDevicesRowByRow(List<RowOutcome> outcomes, List<Integer> chunk,
                                        Long tenantId) {
        for (Integer index : chunk) {
            IotDevice device = outcomes.get(index).device();
            try {
                device.setTenantId(tenantId);
                deviceMapper.insert(device);
            } catch (RuntimeException ex) {
                log.error("[iot] 批量导入第 {} 行写入失败（其它行继续）：deviceCode={}",
                    outcomes.get(index).row().rowNo(),
                    LogSanitizer.sanitize(device.getDeviceCode()), ex);
                outcomes.set(index, RowOutcome.failed(outcomes.get(index).row(),
                    DeviceImportErrorCode.PERSIST_FAILED, ex.getMessage()));
            }
        }
    }

    /**
     * 批量写入分组归属（分块 INSERT，不在每台设备之后各发一条语句）。
     *
     * @param outcomes       全部行结果（重试后仍失败的行的 device.id 为 null）
     * @param successIndexes 设备写入阶段成功的行下标
     * @param tenantId       租户
     */
    private void persistGroupMembers(List<RowOutcome> outcomes, List<Integer> successIndexes,
                                     Long tenantId) {
        List<IotDeviceGroupMember> pending = new ArrayList<>(ROW_INSERT_BATCH_SIZE);
        for (Integer index : successIndexes) {
            RowOutcome outcome = outcomes.get(index);
            if (!outcome.success() || outcome.device().getId() == null) {
                // 该行的设备没写成功（重试也失败）⇒ 不能给它建归属，否则会出现指向不存在设备的成员
                continue;
            }
            for (Long groupId : outcome.groupIds()) {
                IotDeviceGroupMember member = new IotDeviceGroupMember();
                member.setId(IdWorker.getId());
                member.setTenantId(tenantId);
                member.setGroupId(groupId);
                member.setDeviceId(outcome.device().getId());
                pending.add(member);
                if (pending.size() >= ROW_INSERT_BATCH_SIZE) {
                    groupMemberMapper.insertBatch(pending);
                    pending = new ArrayList<>(ROW_INSERT_BATCH_SIZE);
                }
            }
        }
        if (!pending.isEmpty()) {
            groupMemberMapper.insertBatch(pending);
        }
    }

    /**
     * 分批写入明细（先写设备，明细才带得上 deviceId）。
     *
     * @param batch    批次
     * @param outcomes 行结果
     * @param tenantId 租户
     */
    private void persistRows(IotDeviceImportBatch batch, List<RowOutcome> outcomes, Long tenantId) {
        List<IotDeviceImportRow> pending = new ArrayList<>(ROW_INSERT_BATCH_SIZE);
        for (RowOutcome outcome : outcomes) {
            IotDeviceImportRow row = new IotDeviceImportRow();
            row.setId(IdWorker.getId());
            row.setTenantId(tenantId);
            row.setBatchId(batch.getId());
            row.setRowNo(outcome.row().rowNo());
            row.setRawLine(outcome.row().rawLine());
            row.setRowResult(outcome.success()
                ? DeviceImportRowResult.SUCCESS.getCode()
                : DeviceImportRowResult.FAILED.getCode());
            row.setErrorCode(outcome.errorCode() == null ? null : outcome.errorCode().getCode());
            row.setErrorMessage(outcome.errorMessage());
            row.setDeviceId(outcome.success() ? outcome.device().getId() : null);
            row.setDeviceCode(outcome.success()
                ? outcome.device().getDeviceCode()
                : outcome.row().value(DeviceImportColumn.DEVICE_CODE));
            pending.add(row);
            if (pending.size() >= ROW_INSERT_BATCH_SIZE) {
                rowMapper.insertBatch(pending);
                pending = new ArrayList<>(ROW_INSERT_BATCH_SIZE);
            }
        }
        if (!pending.isEmpty()) {
            rowMapper.insertBatch(pending);
        }
    }

    /**
     * 数成功行（单一实现，供批次头与对账断言共用）。
     *
     * <p>刻意只写一份：两处各数一次必然漂移，而对账断言的全部意义就是「两边必须一致」——
     * 用同一份实现去对账等于没对账。</p>
     *
     * @param outcomes 行结果
     * @return 成功行数
     */
    private int countSuccess(List<RowOutcome> outcomes) {
        int success = 0;
        for (RowOutcome outcome : outcomes) {
            if (outcome.success()) {
                success++;
            }
        }
        return success;
    }

    /**
     * 对账断言：批次头的计数、状态与逐行结果必须自洽。
     *
     * <p>这条断言是**故意**放在写库路径上的：它是本能力最容易静默漂移的地方
     * （「成功 3 / 失败 2 / 共 6 行」这种自相矛盾的批次一旦落库，页面就会一直显示它）。
     * 断言失败 ⇒ 事务回滚 ⇒ 用户得到一个明确的错误，而不是一个看起来正常的坏数据。</p>
     *
     * @param batch    批次
     * @param outcomes 行结果
     */
    private void assertCountsConsistent(IotDeviceImportBatch batch, List<RowOutcome> outcomes) {
        int success = countSuccess(outcomes);
        int total = outcomes.size();
        DeviceImportStatus expected = DeviceImportStatus.ofCounts(total, success);
        if (!batch.getSuccessRows().equals(success) || !batch.getTotalRows().equals(total)
            || !batch.getFailedRows().equals(total - success)
            || !batch.getBatchStatus().equals(expected.getCode())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "批量导入计数不一致（内部错误，已回滚）：头 "
                    + batch.getSuccessRows() + "/" + batch.getTotalRows() + "，实际 "
                    + success + "/" + total);
        }
    }

    /**
     * 生成错误摘要（「失败 N 行，最常见原因：xxx」）。
     *
     * <p>摘要算自**内存里的行结果**而不是再查一次库：同一份数据算两次会漂移；
     * 而这里要的是「刚刚这一批」的结论，内存里的就是最准的。</p>
     *
     * @param outcomes 行结果
     * @return 摘要；无失败行返回 {@code null}
     */
    private String summarize(List<RowOutcome> outcomes) {
        Map<DeviceImportErrorCode, Integer> counts = new HashMap<>();
        int failed = 0;
        for (RowOutcome outcome : outcomes) {
            if (!outcome.success()) {
                failed++;
                counts.merge(outcome.errorCode(), 1, Integer::sum);
            }
        }
        if (failed == 0) {
            return null;
        }
        List<Map.Entry<DeviceImportErrorCode, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        StringBuilder builder = new StringBuilder("失败 ").append(failed).append(" 行");
        for (Map.Entry<DeviceImportErrorCode, Integer> entry : sorted) {
            builder.append("；").append(entry.getKey().getMessage())
                .append(" ").append(entry.getValue()).append(" 行");
        }
        return builder.toString();
    }

    /**
     * 取批次（不存在或不属于本租户一律报「批次不存在」，不区分以免泄露存在性）。
     *
     * @param batchId 批次 id
     * @return 批次实体
     */
    private IotDeviceImportBatch requireBatch(Long batchId) {
        IotDeviceImportBatch batch = batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "导入批次不存在：" + batchId);
        }
        return batch;
    }

    /**
     * 解析明细结果筛选参数（非法取值**报错**而不是静默忽略）。
     *
     * @param query 查询条件
     * @return 结果枚举；未筛选返回 {@code null}
     */
    private DeviceImportRowResult resolveRowFilter(DeviceImportRowQuery query) {
        if (query == null || !StringUtils.hasText(query.getRowResult())) {
            return null;
        }
        DeviceImportRowResult parsed = DeviceImportRowResult.parse(query.getRowResult());
        if (parsed == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "结果筛选取值非法（仅支持 success / failed）：" + query.getRowResult());
        }
        return parsed;
    }

    /**
     * 当前请求的租户（与 MP 租户插件**完全一致**：ThreadLocal 优先，其次 TenantProvider）。
     *
     * @return 租户 id；无上下文返回 {@code null}
     */
    private Long currentTenantId() {
        return TenantContext.getTenantId().or(tenantProvider::getCurrentTenantId).orElse(null);
    }

    /**
     * 当前操作人用户 id（拿不到时为 {@code null}，不阻断导入）。
     *
     * @return 用户 id；无登录态返回 {@code null}
     */
    private Long currentUserId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                return null;
            }
            return Long.valueOf(loginId.toString().trim());
        } catch (RuntimeException ex) {
            // 非数字/无上下文：审计字段留空即可，不该阻断业务（记 warn 留痕，不静默）
            log.warn("[iot] 批量导入无法解析操作人用户 ID，审计字段留空", ex);
            return null;
        }
    }

    private String normalizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "未命名.csv";
        }
        String trimmed = fileName.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 解析长整型（失败返回 {@code null}）。
     *
     * @param value 文本
     * @return 长整型；解析失败返回 {@code null}
     */
    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析分组 ID 列表（竖线分隔；非法项**整体视为非法**，由调用方决定报错）。
     *
     * @param value 文本
     * @return 分组 id 列表（去重、保持顺序；永不返回 null）
     */
    private List<Long> parseGroupIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String item : value.split("\\" + DeviceImportColumn.GROUP_ID_SEPARATOR)) {
            Long id = parseLongOrNull(item);
            if (id == null) {
                // 非法项直接进列表用 0 占位：调用方查不到该 id ⇒ 报 GROUP_NOT_FOUND，
                // 用户能看到具体是哪个取值有问题（比「格式非法」更好定位）
                ids.add(-1L);
            } else {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 解析启停位（空=未填；非法返回 {@code null} 但调用方用 {@code isBlank} 区分）。
     *
     * @param value 文本
     * @return 启停位；空或非法返回 {@code null}
     */
    private Integer parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed == EntityStatus.ENABLED.getCode() || parsed == EntityStatus.DISABLED.getCode()
                ? parsed
                : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private DeviceImportBatchResp toBatchResp(IotDeviceImportBatch entity) {
        DeviceImportBatchResp resp = new DeviceImportBatchResp();
        resp.setId(entity.getId());
        resp.setFileName(entity.getFileName());
        resp.setTotalRows(entity.getTotalRows());
        resp.setSuccessRows(entity.getSuccessRows());
        resp.setFailedRows(entity.getFailedRows());
        resp.setBatchStatus(entity.getBatchStatus());
        resp.setErrorSummary(entity.getErrorSummary());
        resp.setStartTime(entity.getStartTime());
        resp.setEndTime(entity.getEndTime());
        resp.setOperatorUserId(entity.getOperatorUserId());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }

    private DeviceImportRowResp toRowResp(IotDeviceImportRow entity) {
        DeviceImportRowResp resp = new DeviceImportRowResp();
        resp.setId(entity.getId());
        resp.setRowNo(entity.getRowNo());
        resp.setRawLine(entity.getRawLine());
        resp.setRowResult(entity.getRowResult());
        resp.setErrorCode(entity.getErrorCode());
        resp.setErrorMessage(entity.getErrorMessage());
        resp.setDeviceId(entity.getDeviceId());
        resp.setDeviceCode(entity.getDeviceCode());
        return resp;
    }

    /**
     * 导入所需的外部数据快照（一次查全量，避免循环内查库）。
     *
     * @param existingCodes   库中已存在的 deviceCode
     * @param products        涉及到的产品（id → 产品）
     * @param existingGroupIds 存在的分组 id
     * @author wenbin
     * @since 2026-09-30
     */
    private record ImportContext(Set<String> existingCodes,
                                 Map<Long, IotProduct> products,
                                 Set<Long> existingGroupIds) {
    }

    /**
     * 单行结果（成功携带待写入的设备实体；失败携带错误码与信息）。
     *
     * @param row          原始行
     * @param device       设备实体（失败为 null）
     * @param groupIds     分组 id（失败为空）
     * @param errorCode    错误码（成功为 null）
     * @param errorMessage 错误信息（成功为 null）
     * @author wenbin
     * @since 2026-09-30
     */
    private record RowOutcome(ParsedRow row,
                              IotDevice device,
                              List<Long> groupIds,
                              DeviceImportErrorCode errorCode,
                              String errorMessage) {

        /**
         * 成功行。
         *
         * @param row      原始行
         * @param device   设备实体
         * @param groupIds 分组 id
         * @return 结果
         */
        static RowOutcome success(ParsedRow row, IotDevice device, List<Long> groupIds) {
            return new RowOutcome(row, device, groupIds, null, null);
        }

        /**
         * 失败行。
         *
         * @param row    原始行
         * @param code   错误码
         * @param detail 具体说明（可为 null）
         * @return 结果
         */
        static RowOutcome failed(ParsedRow row, DeviceImportErrorCode code, String detail) {
            return new RowOutcome(row, null, List.of(), code, code.format(detail));
        }

        /**
         * 是否成功。
         *
         * @return 成功返回 {@code true}
         */
        boolean success() {
            return errorCode == null;
        }
    }
}
