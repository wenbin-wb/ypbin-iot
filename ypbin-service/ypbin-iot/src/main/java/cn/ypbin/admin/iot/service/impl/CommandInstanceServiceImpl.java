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

import cn.ypbin.admin.iot.command.CommandPayloads;
import cn.ypbin.admin.iot.command.CommandReplyReq;
import cn.ypbin.admin.iot.command.CommandReplyResult;
import cn.ypbin.admin.iot.emqx.EmqxAdminClient;
import cn.ypbin.admin.iot.emqx.EmqxClientException;
import cn.ypbin.admin.iot.emqx.EmqxProperties;
import cn.ypbin.admin.iot.emqx.EmqxPublishOutcome;
import cn.ypbin.admin.iot.emqx.EmqxPublishResult;
import cn.ypbin.admin.iot.entity.IotCommand;
import cn.ypbin.admin.iot.entity.IotCommandInstance;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.enums.AccessMode;
import cn.ypbin.admin.iot.enums.CommandErrorCode;
import cn.ypbin.admin.iot.enums.CommandInstanceStatus;
import cn.ypbin.admin.iot.enums.CommandKind;
import cn.ypbin.admin.iot.enums.CommandSource;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.CommandQuery;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandInstanceResp;
import cn.ypbin.admin.iot.service.CommandInstanceService;
import cn.ypbin.admin.iot.timeseries.PropertyIdRules;
import cn.ypbin.admin.iot.util.RequestIdRules;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 运行期命令实例服务实现（设计 §7.1–§7.4，评审确认版）。
 *
 * <p><b>下发流水线（每一步都在 publish 之前）</b>：设备存在性 → 端点字段校验（含 {@code writeDesired}
 * 显式拒绝、params 体积）→ 物模型校验（标识在不在、属性可不可写）→ topic/payload 构造（标识过白名单）
 * → 落 pending 实例 → 投递 → 推进状态。**任何校验失败都不发布**（否则会出现"发出去了但没人认"）。</p>
 *
 * <p><b>{@code 202 = 无订阅者}</b>：EMQX 的官方语义（F33）⇒ **立刻**判 {@code failed/NO_SUBSCRIBER}
 * （文案"设备未连接"），不等超时——这正是设计 §7.2 要消灭的"超时(未在线)"不可区分错误。</p>
 *
 * <p><b>为什么状态更新用显式 set 的 wrapper</b>：重发要把 {@code finished_at}/{@code error_code}
 * 置回 NULL，而 MyBatis-Plus 的 {@code updateById} 默认"非 NULL 才更新"会**静默跳过**这些列
 * （同 {@code DeviceCredentialServiceImpl} 的教训）⇒ 用 {@link LambdaUpdateWrapper} 显式 set。</p>
 *
 * <p><b>回执的租户与白名单</b>：{@code /internal/command-replies} 没有租户身份 ⇒ 先按 {@code deviceId}
 * **反查租户**（{@code executeIgnore}）再进该租户上下文（与 {@code AvailabilityServiceImpl} 同构）；
 * {@code requestId} 过 {@link RequestIdRules}；回执里的 {@code deviceId} 必须与实例上的设备一致
 * （防"同租户内 A 设备替 B 设备回执"）。</p>
 *
 * @author wenbin
 * @since 2026-10-02
 */
@Service
public class CommandInstanceServiceImpl implements CommandInstanceService {

    private static final Logger log = LoggerFactory.getLogger(CommandInstanceServiceImpl.class);

    /** 下行实例创建（含投递成功）计数。 */
    public static final String METRIC_SENT = "iot.command.sent";

    /** 投递即失败（无订阅者 / EMQX 错误）计数。 */
    public static final String METRIC_FAILED = "iot.command.failed";

    /** 超时扫描置超时计数。 */
    public static final String METRIC_TIMEOUT = "iot.command.timeout";

    /** 回执受理成功计数。 */
    public static final String METRIC_REPLY_ACCEPTED = "iot.command.reply.accepted";

    /** 重复回执计数。 */
    public static final String METRIC_REPLY_DUPLICATED = "iot.command.reply.duplicated";

    /** 回执被丢弃计数（设备不存在/不一致/形态非法）。 */
    public static final String METRIC_REPLY_DISCARDED = "iot.command.reply.discarded";

    /** 成功回执的结果码（评审确认：`code == 0` 判成功）。 */
    private static final int REPLY_CODE_SUCCESS = 0;

    /** {@code error_msg} 列宽（超长截断，避免一次脏回执把行写坏）。 */
    private static final int MAX_ERROR_MSG_LENGTH = 500;

    /** 可读属性的访问模式（读属性请求的合法目标）。 */
    private static final List<String> READABLE_MODES = List.of(AccessMode.READ.getCode(),
        AccessMode.READ_WRITE.getCode());

    /** 可写属性的访问模式（属性设置请求的合法目标）。 */
    private static final List<String> WRITABLE_MODES = List.of(AccessMode.WRITE.getCode(),
        AccessMode.READ_WRITE.getCode());

    private final IotDeviceMapper deviceMapper;

    private final IotServiceMapper serviceMapper;

    private final IotPropertyMapper propertyMapper;

    private final IotCommandMapper commandMapper;

    private final IotCommandInstanceMapper instanceMapper;

    private final EmqxAdminClient emqxAdminClient;

    private final EmqxProperties emqxProperties;

    private final ObjectMapper objectMapper;

    private final Counter sentCounter;

    private final Counter failedCounter;

    private final Counter timeoutCounter;

    private final Counter replyAcceptedCounter;

    private final Counter replyDuplicatedCounter;

    private final Counter replyDiscardedCounter;

    public CommandInstanceServiceImpl(IotDeviceMapper deviceMapper, IotServiceMapper serviceMapper,
                                      IotPropertyMapper propertyMapper, IotCommandMapper commandMapper,
                                      IotCommandInstanceMapper instanceMapper,
                                      EmqxAdminClient emqxAdminClient, EmqxProperties emqxProperties,
                                      ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.deviceMapper = deviceMapper;
        this.serviceMapper = serviceMapper;
        this.propertyMapper = propertyMapper;
        this.commandMapper = commandMapper;
        this.instanceMapper = instanceMapper;
        this.emqxAdminClient = emqxAdminClient;
        this.emqxProperties = emqxProperties;
        this.objectMapper = objectMapper;
        this.sentCounter = Counter.builder(METRIC_SENT).description("下行命令实例创建并投递成功")
            .register(meterRegistry);
        this.failedCounter = Counter.builder(METRIC_FAILED).description("下行命令投递即失败")
            .register(meterRegistry);
        this.timeoutCounter = Counter.builder(METRIC_TIMEOUT).description("命令实例被扫描判为超时")
            .register(meterRegistry);
        this.replyAcceptedCounter = Counter.builder(METRIC_REPLY_ACCEPTED)
            .description("设备回执被受理").register(meterRegistry);
        this.replyDuplicatedCounter = Counter.builder(METRIC_REPLY_DUPLICATED)
            .description("重复回执（不改终态）").register(meterRegistry);
        this.replyDiscardedCounter = Counter.builder(METRIC_REPLY_DISCARDED)
            .description("回执被丢弃（设备不存在/不一致/形态非法）").register(meterRegistry);
    }

    @Override
    // ⚠️ 刻意无 @Transactional（看板 #11 O-7 C1）：insert（PENDING，即时提交）→ publish（事务外）
    // → update 各自单语句自动提交；一次慢 EMQX（最坏 5s）不再占住数据库事务。
    // 崩溃窗口（insert 后 publish 前进程挂掉）由 CommandTimeoutScanner（15s 一轮）收敛为超时。
    // 代价（用户已接受）：publish 失败会留 FAILED 行（以前是回滚无痕）—— residual 更可审计。
    public CommandInstanceResp send(Long deviceId, CommandSendReq req, CommandSource source,
                                    Long operatorUserId) {
        IotDevice device = requireDevice(deviceId);
        CommandKind kind = requireKind(req.getKind());
        requireWriteDesiredUnsupported(req);
        String clientKey = normalizeClientKey(req.getClientRequestId());
        if (clientKey != null) {
            IotCommandInstance existing = instanceMapper.selectByClientKey(deviceId, clientKey);
            if (existing != null) {
                log.info("[iot] 命令幂等命中（不二次下发）：deviceId={} clientKey={} requestId={} status={}",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(clientKey),
                    LogSanitizer.sanitize(existing.getRequestId()), existing.getStatusCode());
                return toResp(existing);
            }
        }
        // 体积与形态：params 必须在**构造 payload 之前**校验（评审核心要求：校验失败一律不发布）
        // DTO 里是 Object（客户端发的是 JSON 对象）；这里统一序列化成文本再走"必须是对象 + ≤64KB"的校验
        String paramsJson = toJsonText(req.getParams());
        JsonNode params = CommandPayloads.readObjectOrNull(paramsJson, objectMapper);
        ThingModel model = loadThingModel(device.getProductId());
        Target target = resolveTarget(kind, model, req.getIdentifier());
        int timeoutMs = req.getTimeoutMs() == null
            ? emqxProperties.getDefaultCommandTimeoutMs() : req.getTimeoutMs();
        String requestId = nextRequestId();
        String topic = CommandPayloads.topic(kind, device.getTenantId(), deviceId, target.identifier());
        // ⚠️ payload 用 target.identifier()（**不是 label**）：property_get 不带标识时它是 null ⇒
        //    payload 里是空数组（"全部可读属性"），而实例列里记的是审计标签 all-properties
        String payload = CommandPayloads.build(kind, requestId, target.payloadId(), paramsJson,
            objectMapper);

        IotCommandInstance row = new IotCommandInstance();
        row.setId(IdWorker.getId());
        row.setTenantId(device.getTenantId());
        row.setDeviceId(deviceId);
        row.setCommandId(target.commandId());
        row.setIdentifier(target.identifier());
        row.setKind(kind.getCode());
        row.setRequestId(requestId);
        row.setClientRequestId(clientKey);
        row.setTopic(topic);
        row.setPayload(payload);
        row.setStatusCode(CommandInstanceStatus.PENDING.getCode());
        row.setTimeoutMs(timeoutMs);
        row.setRetryCount(0);
        row.setSource(source.getCode());
        row.setOperatorUserId(operatorUserId);
        try {
            instanceMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            // 并发同键：对方先落库 ⇒ 读回现有的返回（不二次下发）；读不到则原样抛出
            // （request_id 唯一冲突理论上不可能：服务端每次新生成；只处理 clientKey 分支）。
            IotCommandInstance raced = clientKey == null ? null
                : instanceMapper.selectByClientKey(deviceId, clientKey);
            if (raced != null) {
                log.info("[iot] 命令并发幂等命中（不二次下发）：deviceId={} clientKey={} requestId={}",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(clientKey),
                    LogSanitizer.sanitize(raced.getRequestId()));
                return toResp(raced);
            }
            throw ex;
        }
        publishAndPersist(row, topic, payload, false);
        log.info("[iot] 命令已下发：deviceId={} kind={} identifier={} requestId={} status={}",
            LogSanitizer.sanitize(deviceId), kind.getCode(), LogSanitizer.sanitize(target.identifier()),
            LogSanitizer.sanitize(requestId), row.getStatusCode());
        return toResp(row);
    }

    /**
     * 客户端幂等键归一化（看板 #11 O-7 C2）。
     *
     * @param raw 原始值（可空）
     * @return trim 后的键；空输入返回 {@code null}（= 每次调用都是独立命令）
     */
    private static String normalizeClientKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (!RequestIdRules.isValid(key)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "客户端幂等键形态非法（字母/数字/下划线/点/冒号/连字符，1~64 字符）："
                    + LogSanitizer.sanitize(key));
        }
        return key;
    }

    @Override
    public PageResult<CommandInstanceResp> page(Long deviceId, CommandQuery query) {
        if (deviceId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        requireDevice(deviceId);
        String statusCode = null;
        if (query.getStatusCode() != null && !query.getStatusCode().isBlank()) {
            CommandInstanceStatus parsed = CommandInstanceStatus.ofCode(query.getStatusCode());
            if (parsed == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "状态码非法：" + LogSanitizer.sanitize(query.getStatusCode()));
            }
            statusCode = parsed.getCode();
        }
        LambdaQueryWrapper<IotCommandInstance> wrapper = Wrappers.<IotCommandInstance>lambdaQuery()
            .eq(IotCommandInstance::getDeviceId, deviceId)
            .eq(statusCode != null, IotCommandInstance::getStatusCode, statusCode)
            // 排序固定为「最新优先」：不接前端排序字段（避免 ORDER BY 注入面）
            .orderByDesc(IotCommandInstance::getCreateTime)
            .orderByDesc(IotCommandInstance::getId);
        IPage<IotCommandInstance> source = instanceMapper.selectPage(
            new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<CommandInstanceResp> items = new ArrayList<>(source.getRecords().size());
        for (IotCommandInstance row : source.getRecords()) {
            items.add(toResp(row));
        }
        return PageResult.of(items, source.getTotal(), source.getCurrent(), source.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandInstanceResp resend(Long deviceId, String requestId) {
        requireDevice(deviceId);
        if (!RequestIdRules.isValid(requestId)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "请求 ID 形态非法");
        }
        IotCommandInstance row = requireInstance(deviceId, requestId);
        CommandInstanceStatus current = requireStatus(row);
        if (!current.isResendable()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "只有失败/超时的实例可以重发（当前状态=" + current.getCode() + "）");
        }
        requireTransition(current, CommandInstanceStatus.SENT);
        row.setRetryCount(row.getRetryCount() == null ? 1 : row.getRetryCount() + 1);
        publishAndPersist(row, row.getTopic(), row.getPayload(), true);
        log.info("[iot] 命令已人工重发（同一 requestId）：deviceId={} requestId={} retryCount={} status={}",
            LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(requestId), row.getRetryCount(),
            row.getStatusCode());
        return toResp(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        List<IotCommandInstance> candidates = TenantContext.executeIgnore(
            () -> instanceMapper.selectTimeoutCandidates(now, emqxProperties.getCommandScanBatchSize()));
        if (candidates.isEmpty()) {
            // 批量 IN 前先判空短路（仓内铁律：空集合会生成 IN () 语法错误）
            return 0;
        }
        List<Long> ids = new ArrayList<>(candidates.size());
        for (IotCommandInstance candidate : candidates) {
            ids.add(candidate.getId());
        }
        int updated = TenantContext.executeIgnore(() -> instanceMapper.markTimeout(ids, now));
        if (updated > 0) {
            timeoutCounter.increment(updated);
            log.info("[iot] 命令超时扫描：候选={} 置超时={}（**不自动重试**，等待人工重发）",
                candidates.size(), updated);
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommandReplyResult applyReply(CommandReplyReq req, Long authenticatedDevice) {
        if (authenticatedDevice != null && !Objects.equals(authenticatedDevice, req.getDeviceId())) {
            // 载荷声称的设备必须等于**主题派生的认证设备**：否则同租户可替别人回执、跨租户可伪造（载荷不可信）
            replyDiscardedCounter.increment();
            log.warn("[iot] 回执声称的设备与认证主题不一致，已丢弃：载荷={} 认证={}",
                LogSanitizer.sanitize(req.getDeviceId()), LogSanitizer.sanitize(authenticatedDevice));
            return CommandReplyResult.of(false, false, true, "回执设备与认证主题不一致");
        }
        if (authenticatedDevice == null) {
            // fail-open 路径必须可观测：EMQX 动作**总是**带这个头，没带说明是直接 HTTP 调用
            // （自测/旁路），此时信任边界退化为"载荷自称的设备"，必须留痕（禁静默降级）
            log.info("[iot] 回执缺少 X-Mqtt-Device 认证头，按载荷设备处理（仅直接 HTTP 调用应出现）：载荷={}",
                LogSanitizer.sanitize(req.getDeviceId()));
        }
        if (req.getDeviceId() == null || req.getDeviceId() <= 0) {
            replyDiscardedCounter.increment();
            return CommandReplyResult.of(false, false, true, "设备 ID 非法");
        }
        if (!RequestIdRules.isValid(req.getRequestId())) {
            replyDiscardedCounter.increment();
            return CommandReplyResult.of(false, false, true, "请求 ID 形态非法");
        }
        Long tenantId = resolveTenant(req.getDeviceId());
        if (tenantId == null) {
            replyDiscardedCounter.increment();
            log.warn("[iot] 回执的设备不存在，已丢弃：deviceId={}",
                LogSanitizer.sanitize(req.getDeviceId()));
            return CommandReplyResult.of(false, false, true, "设备不存在");
        }
        return TenantContext.executeWithTenant(tenantId, () -> applyReplyInTenant(req));
    }

    /**
     * 在设备真实租户上下文内应用回执（CAS 更新：只从 pending/sent 出发）。
     *
     * @param req 回执
     * @return 受理结果
     */
    private CommandReplyResult applyReplyInTenant(CommandReplyReq req) {
        IotCommandInstance row = instanceMapper.selectByRequestId(req.getRequestId());
        if (row == null) {
            replyDiscardedCounter.increment();
            log.warn("[iot] 回执找不到对应实例（未知 requestId），已丢弃：deviceId={}",
                LogSanitizer.sanitize(req.getDeviceId()));
            return CommandReplyResult.of(false, false, true, "请求 ID 未找到");
        }
        if (!Objects.equals(row.getDeviceId(), req.getDeviceId())) {
            // 同租户内 A 设备替 B 设备回执：拒绝（不更新、也不当重复）
            replyDiscardedCounter.increment();
            log.warn("[iot] 回执设备与实例不一致，已丢弃：回执 deviceId={} 实例 deviceId={}",
                LogSanitizer.sanitize(req.getDeviceId()), LogSanitizer.sanitize(row.getDeviceId()));
            return CommandReplyResult.of(false, false, true, "回执设备与实例不一致");
        }
        CommandInstanceStatus current = requireStatus(row);
        boolean success = req.getCode() == REPLY_CODE_SUCCESS;
        CommandInstanceStatus target = success
            ? CommandInstanceStatus.SUCCEEDED : CommandInstanceStatus.FAILED;
        if (!current.canTransitionTo(target)) {
            replyDuplicatedCounter.increment();
            return CommandReplyResult.of(false, true, false, "实例已处于终态 " + current.getCode());
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<IotCommandInstance> update = Wrappers.<IotCommandInstance>lambdaUpdate()
            .eq(IotCommandInstance::getId, row.getId())
            // CAS：只有仍处于待回执态才更新（并发重复回执只有一个能成功）
            .in(IotCommandInstance::getStatusCode, List.of(CommandInstanceStatus.PENDING.getCode(),
                CommandInstanceStatus.SENT.getCode()))
            .set(IotCommandInstance::getStatusCode, target.getCode())
            .set(IotCommandInstance::getFinishedAt, now)
            .set(IotCommandInstance::getReplyPayload, buildReplyJson(req))
            .set(IotCommandInstance::getErrorCode,
                success ? null : CommandErrorCode.DEVICE_REJECTED.getCode())
            .set(IotCommandInstance::getErrorMsg, success ? null : replyErrorMsg(req));
        int updated = instanceMapper.update(null, update);
        if (updated == 0) {
            // 并发下另一个回执先落地：本次不改状态（幂等）
            replyDuplicatedCounter.increment();
            return CommandReplyResult.of(false, true, false, "并发回执，本次未更新");
        }
        replyAcceptedCounter.increment();
        log.info("[iot] 设备回执已受理：deviceId={} requestId={} code={} → {}",
            LogSanitizer.sanitize(req.getDeviceId()), LogSanitizer.sanitize(req.getRequestId()),
            req.getCode(), target.getCode());
        return CommandReplyResult.of(true, false, false, success ? "成功" : "设备回执失败");
    }

    /**
     * 把回执**原样**组装成 JSON（含设备时间与 {@code data}；不含任何凭据）。
     *
     * @param req 回执
     * @return JSON 文本
     */
    private String buildReplyJson(CommandReplyReq req) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("deviceId", req.getDeviceId());
        node.put("requestId", req.getRequestId());
        node.put("code", req.getCode());
        if (req.getMessage() != null) {
            node.put("message", req.getMessage());
        }
        if (req.getData() != null) {
            // data 形态由物模型定义决定：**原样嵌入，不解析语义**（对象/数组/标量都能存）
            node.set("data", objectMapper.valueToTree(req.getData()));
        }
        if (req.getTs() != null) {
            node.put("ts", req.getTs());
        }
        node.put("receivedAt", LocalDateTime.now().toString());
        return objectMapper.writeValueAsString(node);
    }

    /**
     * 失败回执的 error_msg（**保留设备给的 message**，超长截断）。
     *
     * @param req 回执
     * @return 文案
     */
    private static String replyErrorMsg(CommandReplyReq req) {
        String message = req.getMessage();
        String text = message == null || message.isBlank()
            ? "设备回执失败（code=" + req.getCode() + "）"
            : "设备回执失败（code=" + req.getCode() + "）：" + message;
        return text.length() <= MAX_ERROR_MSG_LENGTH ? text : text.substring(0, MAX_ERROR_MSG_LENGTH);
    }

    /**
     * 投递并落库结果（**唯一的 publish 出口**）。
     *
     * @param row      实例（内存态，方法内更新）
     * @param topic    主题
     * @param payload  报文
     * @param isResend 是否人工重发（重发要把 {@code finished_at}/{@code error_*} 显式清空）
     */
    private void publishAndPersist(IotCommandInstance row, String topic, String payload,
                                   boolean isResend) {
        // ⚠️ from 必须是**本次尝试的起点**，不是"库里当前的状态"：
        //    重发把 failed/timeout 重新打开为 sent（resend() 已用 requireTransition 校验过这一步），
        //    本次投递的起点因此是 SENT。若这里仍取库里的 FAILED/TIMEOUT，投递再失败时
        //    SENT→FAILED 之外的 FAILED→FAILED / TIMEOUT→FAILED 不在转换表里 ⇒ 抛"非法状态转换"，
        //    连带 retry_count/error_* 全部回滚（独立复核实测到的真缺陷 M2）。
        CommandInstanceStatus from = isResend
            ? CommandInstanceStatus.SENT : CommandInstanceStatus.PENDING;
        LocalDateTime now = LocalDateTime.now();
        CommandInstanceStatus target;
        CommandErrorCode errorCode = null;
        String errorMsg = null;
        String messageId = null;
        try {
            EmqxPublishOutcome outcome = emqxAdminClient.publish(topic, payload,
                emqxProperties.getDownlinkQos(), emqxProperties.isDownlinkRetain());
            messageId = outcome.messageId();
            if (outcome.result() == EmqxPublishResult.NO_SUBSCRIBER) {
                target = CommandInstanceStatus.FAILED;
                errorCode = CommandErrorCode.NO_SUBSCRIBER;
                errorMsg = CommandErrorCode.NO_SUBSCRIBER.getDesc();
            } else {
                target = CommandInstanceStatus.SENT;
            }
        } catch (EmqxClientException ex) {
            target = CommandInstanceStatus.FAILED;
            errorCode = CommandErrorCode.EMQX_ERROR;
            errorMsg = ex.getErrorCode().getDesc();
            log.error("[iot] 命令投递失败（EMQX 调用异常）：deviceId={} requestId={} 原因码={}",
                LogSanitizer.sanitize(row.getDeviceId()), LogSanitizer.sanitize(row.getRequestId()),
                ex.getErrorCode().getCode(), ex);
        }
        if (target != from) {
            // 只对**真正的状态变化**做转换校验：target == from 表示本次投递没有改变状态
            // （重发成功时就是 SENT→SENT，它不在转换表里也不该在——转换表描述的是"状态迁移"）。
            // 起点合法性由调用方保证：send 从 PENDING、resend 已校验 current→SENT。
            requireTransition(from, target);
        }
        LocalDateTime sentAt = target == CommandInstanceStatus.SENT ? now : null;
        LocalDateTime finishedAt = target == CommandInstanceStatus.SENT ? null : now;
        row.setStatusCode(target.getCode());
        row.setErrorCode(errorCode == null ? null : errorCode.getCode());
        row.setErrorMsg(errorMsg);
        row.setEmqxMessageId(messageId);
        row.setSentAt(sentAt);
        row.setFinishedAt(finishedAt);
        // 显式 set（含把 error_code/error_msg/finished_at 置 NULL）：updateById 的"非 NULL 才更新"
        // 会让重发永远带着上一次的失败原因（与 DeviceCredentialServiceImpl 的教训同一类）
        LambdaUpdateWrapper<IotCommandInstance> update = Wrappers.<IotCommandInstance>lambdaUpdate()
            .eq(IotCommandInstance::getId, row.getId())
            .set(IotCommandInstance::getStatusCode, row.getStatusCode())
            .set(IotCommandInstance::getErrorCode, errorCode == null ? null : errorCode.getCode())
            .set(IotCommandInstance::getErrorMsg, errorMsg)
            .set(IotCommandInstance::getEmqxMessageId, messageId)
            .set(IotCommandInstance::getSentAt, sentAt)
            .set(IotCommandInstance::getFinishedAt, finishedAt)
            .set(IotCommandInstance::getRetryCount, row.getRetryCount());
        instanceMapper.update(null, update);
        if (target == CommandInstanceStatus.SENT) {
            sentCounter.increment();
        } else {
            failedCounter.increment();
        }
    }

    /**
     * 端点字段校验：{@code writeDesired} 本轮**显式拒绝**（不静默忽略）。
     *
     * @param req 下发请求
     */
    private static void requireWriteDesiredUnsupported(CommandSendReq req) {
        if (Boolean.TRUE.equals(req.getWriteDesired())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令下发时同时写影子 desired 本轮未实现（避免静默忽略）：请改用既有 PUT /devices/{id}/shadow");
        }
    }

    /**
     * 解析类型码（未知即报错，不默认成某个类型）。
     *
     * @param code 类型码
     * @return 类型
     */
    private static CommandKind requireKind(String code) {
        CommandKind kind = CommandKind.ofCode(code);
        if (kind == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令类型非法（只支持 property_set/property_get/service_call）："
                    + LogSanitizer.sanitize(code));
        }
        return kind;
    }

    /**
     * 取设备（**租户范围内**；跨租户与不存在同语义，不区分）。
     *
     * @param deviceId 设备 ID
     * @return 设备
     */
    private IotDevice requireDevice(Long deviceId) {
        if (deviceId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    /**
     * 取实例（租户范围内，并校验实例确实属于该设备）。
     *
     * @param deviceId  设备 ID
     * @param requestId 请求 ID
     * @return 实例
     */
    private IotCommandInstance requireInstance(Long deviceId, String requestId) {
        IotCommandInstance row = instanceMapper.selectByRequestId(requestId);
        if (row == null || !Objects.equals(row.getDeviceId(), deviceId)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令实例不存在：" + LogSanitizer.sanitize(requestId));
        }
        return row;
    }

    /**
     * 解析实例状态（未知状态码即报错——不猜）。
     *
     * @param row 实例
     * @return 状态
     */
    private static CommandInstanceStatus requireStatus(IotCommandInstance row) {
        CommandInstanceStatus status = CommandInstanceStatus.ofCode(row.getStatusCode());
        if (status == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "实例状态码非法：" + LogSanitizer.sanitize(row.getStatusCode()));
        }
        return status;
    }

    /**
     * 状态机硬校验（不在这张表里的转换一律拒绝）。
     *
     * @param from 来源态
     * @param to   目标态
     */
    private static void requireTransition(CommandInstanceStatus from, CommandInstanceStatus to) {
        if (from == null || !from.canTransitionTo(to)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "非法状态转换：" + (from == null ? "null" : from.getCode()) + " → " + to.getCode());
        }
    }

    /**
     * 解析设备所属租户（忽略租户条件反查；设备不存在返回 {@code null}）。
     *
     * @param deviceId 设备 ID
     * @return 租户 ID
     */
    private Long resolveTenant(Long deviceId) {
        List<IotDevice> devices = TenantContext.executeIgnore(
            () -> deviceMapper.selectBatchIds(List.of(deviceId)));
        if (devices.isEmpty()) {
            return null;
        }
        return devices.get(0).getTenantId();
    }

    /**
     * 载入该产品的物模型索引（**两条批量查询**，与设备数/点位数无关；绝不在循环里查库）。
     *
     * @param productId 产品 ID
     * @return 物模型索引
     */
    private ThingModel loadThingModel(Long productId) {
        if (productId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备未绑定产品，无法下发命令");
        }
        List<IotService> services = serviceMapper.selectList(
            Wrappers.<IotService>lambdaQuery().eq(IotService::getProductId, productId));
        if (services.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备所属产品没有物模型服务定义，无法下发命令");
        }
        List<Long> serviceIds = new ArrayList<>(services.size());
        for (IotService service : services) {
            serviceIds.add(service.getId());
        }
        Map<String, IotProperty> properties = new LinkedHashMap<>();
        for (IotProperty property : propertyMapper.selectList(
            Wrappers.<IotProperty>lambdaQuery().in(IotProperty::getServiceId, serviceIds))) {
            properties.putIfAbsent(property.getIdentifier(), property);
        }
        Map<String, IotCommand> commands = new LinkedHashMap<>();
        for (IotCommand command : commandMapper.selectList(
            Wrappers.<IotCommand>lambdaQuery().in(IotCommand::getServiceId, serviceIds))) {
            commands.putIfAbsent(command.getIdentifier(), command);
        }
        return new ThingModel(properties, commands);
    }

    /**
     * 按类型解析并校验目标（**发布之前**的物模型校验）。
     *
     * @param kind       类型
     * @param model      物模型索引
     * @param identifier 目标标识
     * @return 目标（含物模型命令 ID）
     */
    private static Target resolveTarget(CommandKind kind, ThingModel model, String identifier) {
        if (kind == CommandKind.PROPERTY_GET && (identifier == null || identifier.isBlank())) {
            // 空 = 全部可读属性（评审确认）：payload 用空数组（identifier 传 null），实例列记审计标签
            return new Target(CommandPayloads.ALL_PROPERTIES_LABEL, null, null);
        }
        if (!PropertyIdRules.isValid(identifier)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "目标标识" + PropertyIdRules.INVALID_MESSAGE);
        }
        if (kind == CommandKind.SERVICE_CALL) {
            IotCommand command = model.commands().get(identifier);
            if (command == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "该产品物模型中没有命令：" + LogSanitizer.sanitize(identifier));
            }
            return new Target(identifier, identifier, command.getId());
        }
        IotProperty property = model.properties().get(identifier);
        if (property == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该产品物模型中没有属性：" + LogSanitizer.sanitize(identifier));
        }
        List<String> allowed = kind == CommandKind.PROPERTY_SET ? WRITABLE_MODES : READABLE_MODES;
        if (!allowed.contains(property.getAccessMode())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                (kind == CommandKind.PROPERTY_SET ? "属性不可写" : "属性不可读")
                    + "（accessMode=" + property.getAccessMode() + "）："
                    + LogSanitizer.sanitize(identifier));
        }
        return new Target(identifier, identifier, null);
    }

    /**
     * 把 DTO 里的 {@code Object} 入参序列化成 JSON 文本（{@code null} 原样返回）。
     *
     * @param value 入参对象
     * @return JSON 文本；入参为 {@code null} 时返回 {@code null}
     */
    private String toJsonText(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    /**
     * 生成请求 ID（平台生成、全局唯一；形态与 {@code RequestIdRules} 一致）。
     *
     * @return 请求 ID
     */
    private static String nextRequestId() {
        return "cmd-" + IdWorker.getId();
    }

    /**
     * 实体 → 视图。
     *
     * @param row 实例
     * @return 视图
     */
    private static CommandInstanceResp toResp(IotCommandInstance row) {
        CommandInstanceResp resp = new CommandInstanceResp();
        resp.setId(row.getId());
        resp.setDeviceId(row.getDeviceId());
        resp.setKind(row.getKind());
        resp.setIdentifier(row.getIdentifier());
        resp.setRequestId(row.getRequestId());
        resp.setClientRequestId(row.getClientRequestId());
        resp.setTopic(row.getTopic());
        resp.setPayload(row.getPayload());
        resp.setReplyPayload(row.getReplyPayload());
        resp.setStatusCode(row.getStatusCode());
        resp.setErrorCode(row.getErrorCode());
        resp.setErrorMsg(row.getErrorMsg());
        resp.setTimeoutMs(row.getTimeoutMs());
        resp.setRetryCount(row.getRetryCount());
        resp.setEmqxMessageId(row.getEmqxMessageId());
        resp.setSource(row.getSource());
        resp.setOperatorUserId(row.getOperatorUserId());
        resp.setSentAt(row.getSentAt());
        resp.setFinishedAt(row.getFinishedAt());
        resp.setCreateTime(row.getCreateTime());
        return resp;
    }

    /**
     * 物模型索引（标识 → 属性/命令；两条批量查询的结果）。
     *
     * @param properties 属性
     * @param commands   命令
     */
    private record ThingModel(Map<String, IotProperty> properties, Map<String, IotCommand> commands) {
    }

    /**
     * 解析出的下发目标。
     *
     * @param identifier 目标标识（进主题；{@code property_get} 全部可读时为审计标签）
     * @param payloadId  进 payload 的标识（{@code property_get} 全部可读时为 {@code null} ⇒ 空数组）
     * @param commandId  物模型命令定义 ID（属性类为空）
     */
    private record Target(String identifier, String payloadId, Long commandId) {
    }
}
