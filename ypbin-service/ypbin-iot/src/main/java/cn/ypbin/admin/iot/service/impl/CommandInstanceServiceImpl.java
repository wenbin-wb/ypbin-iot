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
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.enums.AccessMode;
import cn.ypbin.admin.iot.enums.CommandErrorCode;
import cn.ypbin.admin.iot.enums.CommandInstanceStatus;
import cn.ypbin.admin.iot.enums.CommandKind;
import cn.ypbin.admin.iot.enums.CommandSource;
import cn.ypbin.admin.iot.enums.PointRefType;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.model.req.CommandBatchSendReq;
import cn.ypbin.admin.iot.model.req.CommandQuery;
import cn.ypbin.admin.iot.model.req.CommandSendReq;
import cn.ypbin.admin.iot.model.resp.CommandBatchItemResult;
import cn.ypbin.admin.iot.model.resp.CommandBatchResp;
import cn.ypbin.admin.iot.model.resp.CommandInstanceResp;
import cn.ypbin.admin.iot.service.CommandBatchProperties;
import cn.ypbin.admin.iot.service.CommandInstanceService;
import cn.ypbin.admin.iot.service.IotShadowService;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.iot.validate.PropertyIdRules;
import cn.ypbin.starter.iot.validate.RequestIdRules;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
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
 * <p><b>下发流水线（校验都在 publish 之前）</b>：设备存在性 → 端点字段校验（{@code writeDesired} 范围、
 * params 体积）→ 物模型校验（标识在不在、属性可不可写）→ topic/payload 构造（标识过白名单）
 * → 落 pending 实例 → 投递 → 推进状态。**任何校验失败都不发布**（否则会出现"发出去了但没人认"）。</p>
 *
 * <p><b>{@code writeDesired}</b>（设计 §7.3 ①/§7.5）：仅 {@code property_set} 支持；命令投递成功
 * （{@code SENT}）后把该属性写进影子 {@code desired}，供设备上报 {@code reported} 后做 delta 比对。
 * 设备离线（202 无订阅者）时**不写** desired——不制造"设备从未收到却永远待确认"的假期望。</p>
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
 * {@code requestId} 过 {@link cn.ypbin.starter.iot.validate.RequestIdRules}；回执里的 {@code deviceId} 必须与实例上的设备一致
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

    /** 纳秒/毫秒（"是否继续下发"阈值用单调时钟计算，避免系统时钟回拨影响判定）。 */
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    /** 可读属性的访问模式（读属性请求的合法目标）。 */
    private static final List<String> READABLE_MODES = List.of(AccessMode.READ.getCode(),
        AccessMode.READ_WRITE.getCode());

    /** 可写属性的访问模式（属性设置请求的合法目标）。 */
    private static final List<String> WRITABLE_MODES = List.of(AccessMode.WRITE.getCode(),
        AccessMode.READ_WRITE.getCode());

    /** 未受理原因码：设备不存在（跨租户与不存在同语义，不区分）。 */
    private static final String REASON_DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND";

    /** 未受理原因码：设备已停用（批量运维只对在网设备下发）。 */
    private static final String REASON_DEVICE_DISABLED = "DEVICE_DISABLED";

    /** 未受理原因码：设备未绑定产品。 */
    private static final String REASON_DEVICE_WITHOUT_PRODUCT = "DEVICE_WITHOUT_PRODUCT";

    /** 未受理原因码：物模型为空/无服务定义。 */
    private static final String REASON_THING_MODEL_EMPTY = "THING_MODEL_EMPTY";

    /** 未受理原因码：物模型加载失败（查询异常；与"物模型为空"是两回事）。 */
    private static final String REASON_THING_MODEL_LOAD_FAILED = "THING_MODEL_LOAD_FAILED";

    /** 未受理原因码：模板校验未通过（标识不在物模型/属性不可写/params 形态非法等）。 */
    private static final String REASON_COMMAND_REJECTED = "COMMAND_REJECTED";

    /** 未受理原因码：循环入口的"继续下发"阈值已用尽（该台未下发）。 */
    private static final String REASON_BATCH_BUDGET_EXCEEDED = "BATCH_BUDGET_EXCEEDED";

    /** 未受理原因码：单台处理时抛出非业务异常（内部错误；已落完整堆栈日志）。 */
    private static final String REASON_INTERNAL_ERROR = "INTERNAL_ERROR";

    private final IotDeviceMapper deviceMapper;

    private final IotServiceMapper serviceMapper;

    private final IotPropertyMapper propertyMapper;

    private final IotCommandMapper commandMapper;

    private final IotCommandInstanceMapper instanceMapper;

    private final IotPointMappingMapper pointMappingMapper;

    private final EmqxAdminClient emqxAdminClient;

    private final EmqxProperties emqxProperties;

    private final ObjectMapper objectMapper;

    private final IotShadowService shadowService;

    private final CommandBatchProperties batchProperties;

    private final Counter sentCounter;

    private final Counter failedCounter;

    private final Counter timeoutCounter;

    private final Counter replyAcceptedCounter;

    private final Counter replyDuplicatedCounter;

    private final Counter replyDiscardedCounter;

    public CommandInstanceServiceImpl(IotDeviceMapper deviceMapper, IotServiceMapper serviceMapper,
                                      IotPropertyMapper propertyMapper, IotCommandMapper commandMapper,
                                      IotCommandInstanceMapper instanceMapper,
                                      IotPointMappingMapper pointMappingMapper,
                                      EmqxAdminClient emqxAdminClient, EmqxProperties emqxProperties,
                                      ObjectMapper objectMapper, IotShadowService shadowService,
                                      CommandBatchProperties batchProperties,
                                      MeterRegistry meterRegistry) {
        this.deviceMapper = deviceMapper;
        this.serviceMapper = serviceMapper;
        this.propertyMapper = propertyMapper;
        this.commandMapper = commandMapper;
        this.instanceMapper = instanceMapper;
        this.pointMappingMapper = pointMappingMapper;
        this.emqxAdminClient = emqxAdminClient;
        this.emqxProperties = emqxProperties;
        this.objectMapper = objectMapper;
        this.shadowService = shadowService;
        this.batchProperties = batchProperties;
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
    // ⚠️ 刻意无 @Transactional（开放 API O-7 C1）：insert（PENDING，即时提交）→ publish（事务外）
    // → update 各自单语句自动提交；一次慢 EMQX（最坏 5s）不再占住数据库事务。
    // 崩溃窗口（insert 后 publish 前进程挂掉）由 CommandTimeoutScanner（15s 一轮）收敛为超时。
    // 代价（用户已接受）：publish 失败会留 FAILED 行（以前是回滚无痕）—— residual 更可审计。
    public CommandInstanceResp send(Long deviceId, CommandSendReq req, CommandSource source,
                                    Long operatorUserId) {
        IotDevice device = requireDevice(deviceId);
        CommandKind kind = requireKind(req.getKind());
        requireWriteDesiredScope(kind, req.getWriteDesired());
        // 单台路径与批量路径**共用同一个** {@link #sendWithModel}：校验、payload 构造、落库、投递、
        // 影子写入的规则只有一份（不复制粘贴出第二份，避免两条路径日后漂移）。
        // 物模型**惰性加载**（{@code prepareCommandForDevice} 在幂等检查**之后**才被调用）：
        // 幂等命中时不查库，与改造前逐字一致。
        return sendWithModel(device, req, source, operatorUserId, req.getClientRequestId(), kind,
            () -> prepareCommandForDevice(device, req, kind));
    }

    /**
     * 单台下发的公共实现（**唯一**的校验/构造/落库/投递出口）。
     *
     * <p>单台与批量唯一的差别是「设备与物模型从哪来」：单台从 {@link #requireDevice} 与
     * {@link #loadThingModel} 惰性取；批量从循环外一次预取好的上下文取（见 {@link #sendBatch}）。
     * 其余步骤逐字一致。</p>
     *
     * @param device            设备（已解析，租户范围内）
     * @param req               下发请求（批量时为模板；{@code clientRequestId} 字段**不在此处读取**）
     * @param source            来源
     * @param operatorUserId    下发人
     * @param rawClientKey      已派生好的幂等键原文（批量入口传入派生键；单台传入请求原值）
     * @param kind              已解析的命令类型
     * @param preparedSupplier  物模型/目标/点位的供应器（单台惰性加载；批量返回预取结果）
     * @return 实例视图
     */
    private CommandInstanceResp sendWithModel(IotDevice device, CommandSendReq req, CommandSource source,
                                              Long operatorUserId, String rawClientKey, CommandKind kind,
                                              Supplier<PreparedCommand> preparedSupplier) {
        Long deviceId = device.getId();
        String clientKey = normalizeClientKey(rawClientKey);
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
        // 解析结果为「必须是对象 + ≤64KB」；payload 仍走文本重建，但 writeDesired 要用这棵树的 value。
        JsonNode paramsNode = CommandPayloads.readObjectOrNull(paramsJson, objectMapper);
        PreparedCommand prepared = preparedSupplier.get();
        Target target = prepared.target();
        // M-3：service_call 命中命令点位映射时，把目标协议物理点随下行 payload 携带（未配置则为 null）
        CommandPayloads.ResolvedPoint point = prepared.point();
        int timeoutMs = req.getTimeoutMs() == null
            ? emqxProperties.getDefaultCommandTimeoutMs() : req.getTimeoutMs();
        String requestId = nextRequestId();
        String topic = CommandPayloads.topic(kind, device.getTenantId(), deviceId, target.identifier());
        // ⚠️ payload 用 target.identifier()（**不是 label**）：property_get 不带标识时它是 null ⇒
        //    payload 里是空数组（"全部可读属性"），而实例列里记的是审计标签 all-properties
        String payload = CommandPayloads.build(kind, requestId, target.payloadId(), paramsJson,
            point, objectMapper);

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
        if (Boolean.TRUE.equals(req.getWriteDesired())
            && CommandInstanceStatus.SENT.getCode().equals(row.getStatusCode())) {
            // 只在真正投递到至少一个订阅者后写 desired（离线不写假期望；超时/被拒时 desired 已在此时写入）
            writeDesiredAfterDelivery(row, paramsNode);
        }
        log.info("[iot] 命令已下发：deviceId={} kind={} identifier={} requestId={} status={}",
            LogSanitizer.sanitize(deviceId), kind.getCode(), LogSanitizer.sanitize(target.identifier()),
            LogSanitizer.sanitize(requestId), row.getStatusCode());
        return toResp(row);
    }

    /**
     * 单台路径的物模型/目标/点位准备（**惰性**调用：幂等命中时不会触发查询）。
     *
     * @param device 设备
     * @param req    下发请求
     * @param kind   已解析类型
     * @return 预解析结果
     */
    private PreparedCommand prepareCommandForDevice(IotDevice device, CommandSendReq req, CommandKind kind) {
        ThingModel model = loadThingModel(device.getProductId());
        Target target = resolveTarget(kind, model, req.getIdentifier());
        CommandPayloads.ResolvedPoint point = resolveCommandPoint(kind, device.getId(), target.commandId());
        return new PreparedCommand(target, point);
    }

    @Override
    public CommandBatchResp sendBatch(CommandBatchSendReq req, CommandSource source, Long operatorUserId) {
        List<Long> deviceIds = normalizeBatchDeviceIds(req);
        // @Valid 在 HTTP 入口挡住了空模板；但服务层可能被**直接调用**（自测/其它服务），
        // 这里再挡一次，避免 NPE 变成 500（与单台端点"服务层是第二道闸"的口径一致）。
        CommandSendReq template = req.getCommand();
        if (template == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "命令模板不能为空");
        }
        // ── 批量级前置校验（与设备无关的规则只判一次；失败整批拒绝）──────────────────────────
        // 模板的 kind/writeDesired/params 形态若非法，是**调用方把请求写错了**：整批拒绝比返回 N 条
        // 一模一样的错误更有用（也与单台端点"校验失败绝不发布"的口径一致）。
        CommandKind kind = requireKind(template.getKind());
        requireWriteDesiredScope(kind, template.getWriteDesired());
        String paramsJson = toJsonText(template.getParams());
        CommandPayloads.readObjectOrNull(paramsJson, objectMapper);
        String batchKey = normalizeClientKey(req.getClientRequestId());
        requireDerivedClientKeyFits(batchKey, deviceIds);

        // ── 批量预取（**所有查询都在循环之外**；这是本方法满足「循环内禁 DB/RPC」门禁的关键）──────
        // ① 一次 IN 查全部设备：租户插件自动注入 tenant_id ⇒ 别的租户/已删除的设备**根本查不出来**，
        //    与"设备不存在"同语义（不泄露"该 ID 在别的租户存在"）。
        Map<Long, IotDevice> devicesById = loadBatchDevices(deviceIds);
        // ② 按 productId 去重后，一次批量装载物模型：**共 3 条 IN**（服务 / 属性 / 命令各一次；
        //    见 loadThingModels），与设备数、产品数都无关——不是"每个 productId 各一条 IN"。
        //    ⚠️ 查询本身失败（库/网络）时**不能**把结果当成"物模型为空"这种确定结论——两者要修的东西
        //    完全不同（修连接 vs 修产品配置）。批量查询无法逐产品归因，故该产品集合下的设备**逐条**
        //    报"物模型加载失败：<原因>"，且原因进日志时带完整堆栈。
        Map<Long, ThingModel> modelsByProduct;
        Map<Long, PreRejection> preRejections = new LinkedHashMap<>();
        try {
            modelsByProduct = loadThingModels(productIdsOf(devicesById.values()));
        } catch (RuntimeException ex) {
            log.error("[iot] 批量下发物模型装载失败（受影响设备逐条归因，不再尝试下发）", ex);
            modelsByProduct = Map.of();
            for (IotDevice device : devicesById.values()) {
                if (device.getProductId() != null) {
                    preRejections.put(device.getId(), new PreRejection(REASON_THING_MODEL_LOAD_FAILED,
                        "物模型加载失败：" + ex.getMessage()));
                }
            }
        }
        // ③ 逐台的目标在**内存里**预解析（resolveTarget 是纯函数，不查库）：这样既能一次性拿到
        //    service_call 需要的 commandId（供第 ④ 步批量取点位映射），也能把"标识不在物模型/
        //    属性不可写"这类**确定结论**在循环前算好。
        Map<Long, Target> targets = new LinkedHashMap<>();
        resolveTargetsForBatch(deviceIds, devicesById, modelsByProduct, kind, template.getIdentifier(),
            targets, preRejections);
        // ④ service_call 才需要命令点位映射：用「设备 + 命令定义」批量一次查完（循环内不再查库）。
        Map<PointKey, List<IotPointMapping>> mappingsByDeviceCommand =
            resolveCommandPointMappings(kind, targets);

        // ── 逐台下发 ───────────────────────────────────────────────────────────────────────
        // ⚠️ **这里的循环为什么是必要的**：每次下发都要向 EMQX **发布一条独立的 MQTT 报文**
        // （topic 按设备不同、payload 的 requestId 也不同），且每台各自落一条命令实例——这是 N 个
        // 不可合并的外部动作。被批量化的是它**之外**的全部查询：设备（1 次 IN）、物模型（3 次 IN）、
        // 命令点位映射（1 次 IN）。循环体内只调用自研服务的 sendWithModel，不再出现任何查询。
        // 逐台 try/catch：一台失败只影响它自己，其余照常下发（部分失败如实逐条返回）。
        long deadlineNanos = System.nanoTime()
            + batchProperties.getTotalBudgetMs() * NANOS_PER_MILLISECOND;
        List<CommandBatchItemResult> results = new ArrayList<>(deviceIds.size());
        int accepted = 0;
        int delivered = 0;
        for (Long deviceId : deviceIds) {
            CommandBatchItemResult result = new CommandBatchItemResult();
            result.setDeviceId(deviceId);
            if (System.nanoTime() > deadlineNanos) {
                // ⚠️ 这是**循环入口检查点**，不是硬中断：只保证"已超阈值的设备不再开跑"，
                //    故实际总耗时上界 ≈ 阈值 + 最后一台被放行设备的耗时（单台耗时上界 ≈ EMQX 写超时）。
                // 阈值用尽：**如实**告诉调用方"这些没发"，而不是让请求挂到被网关掐断（那时"发了哪些"
                // 就成了未知数）。已发出的命令不受影响（无回滚）。
                reject(result, REASON_BATCH_BUDGET_EXCEEDED,
                    "整批下发耗时超过预算 " + batchProperties.getTotalBudgetMs()
                        + "ms，本设备未下发；请缩小批量或调大 "
                        + CommandBatchProperties.PREFIX + ".total-budget-ms");
                results.add(result);
                continue;
            }
            PreRejection preRejection = preRejections.get(deviceId);
            if (preRejection != null) {
                // 预取/预解析阶段已能确定的结论（设备不存在/停用/物模型为空/标识不在该产品物模型）：
                // 逐条如实给出，**不**在循环里再查一次库。
                reject(result, preRejection.code(), preRejection.message());
                results.add(result);
                continue;
            }
            IotDevice device = devicesById.get(deviceId);
            Target target = targets.get(deviceId);
            try {
                CommandInstanceResp resp = sendWithModel(device, template, source, operatorUserId,
                    deriveClientKey(batchKey, deviceId), kind,
                    () -> new PreparedCommand(target, toResolvedPoint(deviceId, target,
                        mappingsByDeviceCommand.get(new PointKey(deviceId, target.commandId())))));
                result.setAccepted(Boolean.TRUE);
                result.setRequestId(resp.getRequestId());
                result.setStatusCode(resp.getStatusCode());
                result.setErrorCode(resp.getErrorCode());
                boolean ok = CommandInstanceStatus.SENT.getCode().equals(resp.getStatusCode());
                result.setOk(ok);
                result.setMessage(ok
                    ? "已下发，等待设备回执"
                    : "平台已受理但未送达（" + describeDeliveryFailure(resp) + "），可按 requestId 重发");
                accepted++;
                if (ok) {
                    delivered++;
                }
            } catch (BusinessException ex) {
                // 业务性拒绝（物模型不可写/点位映射歧义/超长 payload/…）：**逐台归因**，不整体失败
                reject(result, REASON_COMMAND_REJECTED, ex.getMessage());
            } catch (RuntimeException ex) {
                // 非预期异常：单台隔离 + **完整堆栈**进日志（禁吞异常、禁静默降级）；
                // 其余设备继续下发，绝不让一台的意外拖垮整批。
                // ⚠️ 措辞必须诚实：异常可能发生在"实例已落库、投递尚未返回"之间（共享流水线是先 insert
                //    再 publish），因此该设备**可能已有一条命令记录**（PENDING，会被超时扫描收敛）。
                //    不能简单说"没下发"——那会让运维漏掉一条真实存在的命令。
                log.error("[iot] 批量下发单台失败（其余设备继续）：deviceId={} identifier={}",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(template.getIdentifier()), ex);
                reject(result, REASON_INTERNAL_ERROR,
                    "单台下发异常（已记录完整堆栈）：" + ex.getMessage()
                        + "；该设备可能已生成命令记录，请到该设备的命令历史核对");
            }
            results.add(result);
        }
        CommandBatchResp resp = new CommandBatchResp();
        resp.setTotal(deviceIds.size());
        resp.setAccepted(accepted);
        resp.setRejected(deviceIds.size() - accepted);
        resp.setDelivered(delivered);
        resp.setResults(results);
        log.info("[iot] 批量下发完成：total={} accepted={} delivered={} rejected={} kind={} identifier={}",
            deviceIds.size(), accepted, delivered, resp.getRejected(), kind.getCode(),
            LogSanitizer.sanitize(template.getIdentifier()));
        return resp;
    }

    /**
     * 批量设备清单归一化（非空、元素非空、去重保序、按配置上限判定）。
     *
     * <p>这些是**整批级**错误（请求本身不成立），因此抛业务错误拒绝整批——与"逐台设备的问题"
     * （设备不存在/停用/物模型不可用）必须逐条返回的口径区分开。</p>
     *
     * @param req 批量请求
     * @return 去重后保序的设备清单
     */
    private List<Long> normalizeBatchDeviceIds(CommandBatchSendReq req) {
        List<Long> raw = req.getDeviceIds();
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 列表不能为空");
        }
        if (raw.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        List<Long> deduped = List.copyOf(new LinkedHashSet<>(raw));
        int maxDevices = batchProperties.getMaxDevices();
        if (deduped.size() > maxDevices) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "单次批量下发设备数不能超过 " + maxDevices + "（去重后 " + deduped.size()
                    + "；可调整 " + CommandBatchProperties.PREFIX + ".max-devices）");
        }
        return deduped;
    }

    /**
     * 校验「请求级幂等键 + 设备 ID」派生后的键不超 {@code request_id} 列宽。
     *
     * <p>派生格式 {@code <key>:<deviceId>} 最长 = 请求级键长 + 1 + 设备 ID 十进制位数。
     * 超限时**整批拒绝并说清原因**，绝不截断——截断会把不同设备/不同批次压成同一个键，
     * 造成"以为去重、实际串单"（与单台端点拒绝非法幂等键同一理由）。</p>
     *
     * @param batchKey  归一化后的请求级键（可空）
     * @param deviceIds 去重后的设备清单
     */
    private static void requireDerivedClientKeyFits(String batchKey, List<Long> deviceIds) {
        if (batchKey == null) {
            return;
        }
        long maxDigits = 1;
        for (Long deviceId : deviceIds) {
            maxDigits = Math.max(maxDigits, String.valueOf(deviceId).length());
        }
        long maxLength = batchKey.length() + 1L + maxDigits;
        if (maxLength > RequestIdRules.MAX_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "请求级幂等键过长：逐设备派生后最长 " + maxLength + " 字符，超过上限 "
                    + RequestIdRules.MAX_LENGTH + "（派生规则为 \"<clientRequestId>:<deviceId>\"）");
        }
    }

    /**
     * 逐设备派生幂等键（{@code <请求级键>:<设备 ID>}）。
     *
     * <p><b>为什么必须逐设备派生</b>：命令实例的幂等唯一口径是
     * {@code uk_command_client_key(tenant_id, device_id, client_request_id)}——「同租户 + 同设备」。
     * 若整批共用一个键，则 A 设备命中已有实例时会连带把 B 设备也短路掉（B 明明没下发过却不发），
     * 而数据库唯一键也会在第二台插入时冲突。派生后每台各自去重：A 命中只影响 A。</p>
     *
     * @param batchKey 请求级键（可空 = 不做幂等，每台都是新命令）
     * @param deviceId 设备 ID
     * @return 派生键；请求级键为空时返回 {@code null}
     */
    private static String deriveClientKey(String batchKey, Long deviceId) {
        return batchKey == null ? null : batchKey + ":" + deviceId;
    }

    /**
     * 一次批量查设备（租户条件由插件注入；不手写 tenant_id）。
     *
     * @param deviceIds 去重后的设备清单（非空）
     * @return deviceId → 设备（查不到的不在结果里）
     */
    private Map<Long, IotDevice> loadBatchDevices(List<Long> deviceIds) {
        Map<Long, IotDevice> byId = new LinkedHashMap<>();
        for (IotDevice device : deviceMapper.selectBatchIds(deviceIds)) {
            byId.put(device.getId(), device);
        }
        return byId;
    }

    /**
     * 收集设备清单里出现过的 productId（去重、剔除空值）。
     *
     * @param devices 设备集合
     * @return productId 清单
     */
    private static List<Long> productIdsOf(Collection<IotDevice> devices) {
        Set<Long> productIds = new LinkedHashSet<>();
        for (IotDevice device : devices) {
            if (device.getProductId() != null) {
                productIds.add(device.getProductId());
            }
        }
        return List.copyOf(productIds);
    }

    /**
     * 一次批量装载多个产品的物模型（**3 条 IN 查询**，与设备数、产品数无关）。
     *
     * <p>这是批量下发相对"逐台调 send"的全部优化所在：物模型装载原是每台设备 2 条 SQL
     * （服务 + 属性/命令），50 台同产品会放大成 100 次往返；这里按 productId 去重后一次取回，
     * 无论多少台/多少个产品都固定为 **3 条 IN**（{@code iot_service} → {@code iot_property}
     * → {@code iot_command}，后两者按前者的 serviceId 集合查询）。</p>
     *
     * <p><b>生命周期</b>：返回的物模型索引只在**本次请求内**有效并复用（不放进任何缓存、不跨请求），
     * 因此不会让"改了物模型但缓存没刷新"变成新的静默故障。</p>
     *
     * @param productIds 产品 ID 清单（可空/空 = 返回空 Map，且**不发起查询**）
     * @return productId → 物模型索引（含"无服务定义"的空索引）
     */
    private Map<Long, ThingModel> loadThingModels(List<Long> productIds) {
        if (productIds.isEmpty()) {
            // 批量 IN 前先判空短路（仓内铁律：空集合会生成 IN () 语法错误）
            return Map.of();
        }
        // 每个产品先放一个**空索引**：这样"该产品没有服务定义"（物模型为空）与"产品根本没预取到"
        // 在循环里可区分——前者逐条报 THING_MODEL_EMPTY，后者是内部错误。
        Map<Long, ThingModel> byProduct = new LinkedHashMap<>();
        for (Long productId : productIds) {
            byProduct.put(productId, new ThingModel(new LinkedHashMap<>(), new LinkedHashMap<>()));
        }
        List<IotService> services = serviceMapper.selectList(
            Wrappers.<IotService>lambdaQuery().in(IotService::getProductId, productIds));
        if (services.isEmpty()) {
            return byProduct;
        }
        Map<Long, Long> productIdByServiceId = new LinkedHashMap<>();
        List<Long> serviceIds = new ArrayList<>(services.size());
        for (IotService service : services) {
            serviceIds.add(service.getId());
            productIdByServiceId.put(service.getId(), service.getProductId());
        }
        for (IotProperty property : propertyMapper.selectList(
            Wrappers.<IotProperty>lambdaQuery().in(IotProperty::getServiceId, serviceIds))) {
            ThingModel model = byProduct.get(productIdByServiceId.get(property.getServiceId()));
            if (model != null) {
                model.properties().putIfAbsent(property.getIdentifier(), property);
            }
        }
        for (IotCommand command : commandMapper.selectList(
            Wrappers.<IotCommand>lambdaQuery().in(IotCommand::getServiceId, serviceIds))) {
            ThingModel model = byProduct.get(productIdByServiceId.get(command.getServiceId()));
            if (model != null) {
                model.commands().putIfAbsent(command.getIdentifier(), command);
            }
        }
        return byProduct;
    }

    /**
     * 循环前逐台预解析目标（把"确定结论"提前算好，循环里不再查库/不再重复物模型判定）。
     *
     * <p>能在这里定论的：设备不存在、设备停用、未绑定产品、物模型为空、标识不在该产品物模型
     * （含属性不可写/不可读）。判不出而必须在循环里做的只剩"点位映射"与"实际投递"。</p>
     *
     * @param deviceIds       去重后的设备清单（顺序即回执顺序）
     * @param devicesById     预取的设备
     * @param modelsByProduct 预取的物模型
     * @param kind            已解析类型
     * @param identifier      模板标识
     * @param targets         出参：deviceId → 目标（仅预解析成功的设备）
     * @param preRejections   出/入参：deviceId → 预置拒绝（含原因码与人话）；已预置的（如物模型装载失败）
     *                        不再重复判定，保证"加载失败"的归因不被后面的"物模型为空"覆盖
     */
    private void resolveTargetsForBatch(List<Long> deviceIds, Map<Long, IotDevice> devicesById,
                                        Map<Long, ThingModel> modelsByProduct, CommandKind kind,
                                        String identifier, Map<Long, Target> targets,
                                        Map<Long, PreRejection> preRejections) {
        for (Long deviceId : deviceIds) {
            if (preRejections.containsKey(deviceId)) {
                continue;
            }
            IotDevice device = devicesById.get(deviceId);
            if (device == null) {
                // 跨租户与不存在同语义（租户插件已过滤）：不泄露"该 ID 在别的租户存在"
                preRejections.put(deviceId, new PreRejection(REASON_DEVICE_NOT_FOUND,
                    "设备不存在或不属于当前租户"));
                continue;
            }
            if (!EntityStatus.ENABLED.getCode().equals(device.getStatus())) {
                preRejections.put(deviceId, new PreRejection(REASON_DEVICE_DISABLED,
                    "设备已停用，不参与批量下发（如需下发请先在设备台账启用）"));
                continue;
            }
            if (device.getProductId() == null) {
                preRejections.put(deviceId, new PreRejection(REASON_DEVICE_WITHOUT_PRODUCT,
                    "设备未绑定产品，无法下发命令"));
                continue;
            }
            ThingModel model = modelsByProduct.get(device.getProductId());
            if (model == null) {
                // 预取结果里没有该产品（内部不一致）：**不能**谎报成"物模型为空"这种确定结论
                preRejections.put(deviceId, new PreRejection(REASON_INTERNAL_ERROR,
                    "物模型预取结果缺少该设备的产品（内部错误，请联系管理员）"));
                continue;
            }
            if (model.properties().isEmpty() && model.commands().isEmpty()) {
                preRejections.put(deviceId, new PreRejection(REASON_THING_MODEL_EMPTY,
                    "该设备所属产品没有物模型定义（无属性/无命令），无法下发命令"));
                continue;
            }
            try {
                targets.put(deviceId, resolveTarget(kind, model, identifier));
            } catch (BusinessException ex) {
                // 模板在该设备物模型上不成立（标识缺失/不可写）——**确定结论**，逐条归因
                preRejections.put(deviceId, new PreRejection(REASON_COMMAND_REJECTED, ex.getMessage()));
            }
        }
    }

    /**
     * 循环前一次批量取命令点位映射（仅 {@code service_call} 需要；其它类型不查库）。
     *
     * <p>语义与单台的 {@link #resolveCommandPoint} 完全一致：未配置 ⇒ 无点位；
     * 多条启用映射 ⇒ 该设备**逐条**报"映射不唯一"（配置歧义不能静默挑一条写错寄存器）。</p>
     *
     * @param kind    已解析类型
     * @param targets 预解析出的目标（含 commandId）
     * @return (deviceId, commandId) → 唯一映射（未配置的键不存在；歧义的键也不进结果）
     */
    private Map<PointKey, List<IotPointMapping>> resolveCommandPointMappings(CommandKind kind,
                                                                             Map<Long, Target> targets) {
        if (kind != CommandKind.SERVICE_CALL) {
            return Map.of();
        }
        Set<PointKey> wanted = new LinkedHashSet<>();
        List<Long> deviceIds = new ArrayList<>();
        List<Long> definitionIds = new ArrayList<>();
        for (Map.Entry<Long, Target> entry : targets.entrySet()) {
            if (entry.getValue().commandId() == null) {
                continue;
            }
            PointKey key = new PointKey(entry.getKey(), entry.getValue().commandId());
            if (wanted.add(key)) {
                deviceIds.add(key.deviceId());
                definitionIds.add(key.commandId());
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        List<IotPointMapping> mappings = pointMappingMapper.selectList(
            Wrappers.<IotPointMapping>lambdaQuery()
                .in(IotPointMapping::getDeviceId, deviceIds)
                .in(IotPointMapping::getPropertyId, definitionIds)
                .eq(IotPointMapping::getRefType, PointRefType.COMMAND.getCode())
                .eq(IotPointMapping::getEnabled, Boolean.TRUE)
                .orderByAsc(IotPointMapping::getId));
        Map<PointKey, List<IotPointMapping>> byKey = new LinkedHashMap<>();
        for (IotPointMapping mapping : mappings) {
            PointKey key = new PointKey(mapping.getDeviceId(), mapping.getPropertyId());
            if (!wanted.contains(key)) {
                // 两列各自 IN 是笛卡尔上界：这里收回到真正的 (device, command) 组合，
                // 避免"设备 A 的点位映射被算到设备 B 的同一个 commandId 上"这种串台
                continue;
            }
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mapping);
        }
        return byKey;
    }

    /**
     * 把预取的映射按单台口径转成点位（与 {@link #resolveCommandPoint} 的返回语义一致）。
     *
     * @param deviceId 设备
     * @param target   预解析目标（非服务调用时 commandId 为空）
     * @param mapping  预取的唯一映射（可空 = 未配置）
     * @return 点位；未配置返回 {@code null}
     */
    private static CommandPayloads.ResolvedPoint toResolvedPoint(Long deviceId, Target target,
                                                                 List<IotPointMapping> mappings) {
        if (target.commandId() == null || mappings == null || mappings.isEmpty()) {
            // 非服务调用、或该设备未配命令点位 ⇒ 无点位（payload 保持 §7.6 B2 契约不变）
            return null;
        }
        if (mappings.size() > 1) {
            // 歧义：不静默挑一条（与单台 resolveCommandPoint 同一口径）⇒ 逐台报错
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "设备 " + deviceId + " 的命令点位映射不唯一（" + mappings.size()
                    + " 条启用映射），无法确定下发地址；请清理重复映射");
        }
        IotPointMapping mapping = mappings.get(0);
        return new CommandPayloads.ResolvedPoint(mapping.getRawAddress(), mapping.getAddressType(),
            mapping.getRw());
    }

    /**
     * 描述"已受理但未送达"的原因（面向运维的人话）。
     *
     * @param resp 实例视图
     * @return 说明文本
     */
    private static String describeDeliveryFailure(CommandInstanceResp resp) {
        if (CommandErrorCode.NO_SUBSCRIBER.getCode().equals(resp.getErrorCode())) {
            return "设备未连接";
        }
        if (resp.getErrorMsg() != null && !resp.getErrorMsg().isBlank()) {
            return resp.getErrorMsg();
        }
        return "原因码 " + resp.getErrorCode();
    }

    /**
     * 标注一条"未受理"结果（不创建实例）。
     *
     * @param result  结果
     * @param code    原因码
     * @param message 人话说明
     */
    private static void reject(CommandBatchItemResult result, String code, String message) {
        result.setAccepted(Boolean.FALSE);
        result.setOk(Boolean.FALSE);
        result.setCode(code);
        result.setMessage(message);
    }

    /**
     * 客户端幂等键归一化（开放 API O-7 C2）。
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
     * 端点字段校验：{@code writeDesired} 只对属性设置有意义（其它类型显式拒绝，不静默忽略）。
     *
     * <p>影子 {@code desired} 的语义是「平台期望某属性变成什么值」；读属性没有期望值，服务调用对应的是
     * 动作而不是属性，两者都不该写 desired。</p>
     *
     * @param kind         命令类型（已解析）
     * @param writeDesired 是否同时写影子 desired（可空）
     */
    private static void requireWriteDesiredScope(CommandKind kind, Boolean writeDesired) {
        if (Boolean.TRUE.equals(writeDesired) && kind != CommandKind.PROPERTY_SET) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "只有属性设置（property_set）支持同时写入影子 desired（当前 kind=" + kind.getCode() + "）");
        }
    }

    /**
     * 命令投递成功后写入影子 {@code desired}（供设备上报 {@code reported} 后做 delta 比对）。
     *
     * <p><b>为什么只在 {@code SENT} 后写</b>：设备离线（EMQX 202 无订阅者）⇒ 命令
     * {@code failed/NO_SUBSCRIBER}，此时写 desired 会制造一个「设备从未收到、却永远待确认」的假期望；
     * 不写更诚实——离线结果由实例状态明确表达，不存在"静默成功"。超时/被拒时命令已投递，desired 已在
     * 投递成功那一刻写入，保持待设备上报确认。</p>
     *
     * <p><b>写失败不静默</b>：命令已经投递出去（设备侧可能已执行），desired 写失败不能当成功返回，
     * 故抛业务错误并在消息里点名 requestId，提示"不要重复下发、改为补写期望值"。</p>
     *
     * @param row        已投递的命令实例（状态 {@code SENT}）
     * @param paramsNode {@code property_set} 的入参对象（含 {@code value}）
     */
    private void writeDesiredAfterDelivery(IotCommandInstance row, JsonNode paramsNode) {
        if (paramsNode == null || !paramsNode.hasNonNull(CommandPayloads.FIELD_VALUE)) {
            // 理论不可达：property_set 的 payload 构造已要求 params 含 value；显式失败而不是静默跳过
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "属性设置缺少 value，无法写入影子 desired：" + LogSanitizer.sanitize(row.getIdentifier()));
        }
        Object value = objectMapper.convertValue(paramsNode.get(CommandPayloads.FIELD_VALUE), Object.class);
        try {
            shadowService.mergeDesired(row.getDeviceId(), Map.of(row.getIdentifier(), value));
        } catch (RuntimeException ex) {
            log.error("[iot] 命令已投递但影子 desired 写入失败：deviceId={} requestId={} identifier={}",
                LogSanitizer.sanitize(row.getDeviceId()), LogSanitizer.sanitize(row.getRequestId()),
                LogSanitizer.sanitize(row.getIdentifier()), ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令已投递（requestId=" + LogSanitizer.sanitize(row.getRequestId())
                    + "），但影子 desired 写入失败；请勿重复下发，改为重试写入期望值");
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
     * 解析命令点位（M-3）：{@code service_call} 命中 {@code ref_type=command} 的**启用**映射时，
     * 返回目标协议物理点；未配映射返回 {@code null}（payload 保持 §7.6 B2 契约不变）。
     *
     * <p>多条启用映射 = 配置有歧义 ⇒ **发布之前**显式业务错误（不静默挑一条，否则写错寄存器）。</p>
     *
     * @param kind      类型
     * @param deviceId  设备 ID
     * @param commandId 物模型命令定义 ID（{@code iot_command.id}；非服务调用为 {@code null}）
     * @return 命令点位；未配置时 {@code null}
     */
    private CommandPayloads.ResolvedPoint resolveCommandPoint(CommandKind kind, Long deviceId,
                                                              Long commandId) {
        if (kind != CommandKind.SERVICE_CALL || commandId == null) {
            return null;
        }
        List<IotPointMapping> mappings = pointMappingMapper.selectList(
            Wrappers.<IotPointMapping>lambdaQuery()
                .eq(IotPointMapping::getDeviceId, deviceId)
                .eq(IotPointMapping::getRefType, PointRefType.COMMAND.getCode())
                .eq(IotPointMapping::getPropertyId, commandId)
                .eq(IotPointMapping::getEnabled, Boolean.TRUE)
                .orderByAsc(IotPointMapping::getId));
        if (mappings.isEmpty()) {
            return null;
        }
        if (mappings.size() > 1) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该设备的命令点位映射不唯一（" + mappings.size()
                    + " 条启用映射），无法确定下发地址；请清理重复映射");
        }
        IotPointMapping mapping = mappings.get(0);
        return new CommandPayloads.ResolvedPoint(mapping.getRawAddress(), mapping.getAddressType(),
            mapping.getRw());
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
     * 物模型索引（标识 → 属性/命令；批量预取的结果）。
     *
     * <p>两个 Map **可变**：批量装载时按产品逐条归集（先建空索引、再填充），避免为每个产品
     * 复制一份不可变 Map。装载完成后本请求内只读。</p>
     *
     * @param properties 属性
     * @param commands   命令
     */
    private record ThingModel(Map<String, IotProperty> properties, Map<String, IotCommand> commands) {
    }

    /**
     * 单台下发的预解析结果（物模型目标 + 命令点位）。
     *
     * @param target 目标
     * @param point  命令点位（非服务调用/未配置时为 {@code null}）
     */
    private record PreparedCommand(Target target, CommandPayloads.ResolvedPoint point) {
    }

    /**
     * 循环前已能确定的"未受理"结论（原因码 + 人话）。
     *
     * @param code    原因码
     * @param message 人话说明
     */
    private record PreRejection(String code, String message) {
    }

    /**
     * 「设备 + 物模型命令定义」组合键（命令点位映射按这个组合批量取）。
     *
     * @param deviceId  设备
     * @param commandId 命令定义 ID
     */
    private record PointKey(Long deviceId, Long commandId) {
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
