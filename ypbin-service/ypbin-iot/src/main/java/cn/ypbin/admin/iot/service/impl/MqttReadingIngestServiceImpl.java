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

import cn.ypbin.admin.iot.availability.ReadingIngestReport;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import cn.ypbin.admin.iot.enums.MqttReceiptStatus;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectReason;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectionException;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestReq;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestResult;
import cn.ypbin.admin.iot.mqtt.ReadingQuality;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.admin.iot.service.MqttReadingIngestService;
import cn.ypbin.starter.iot.validate.PropertyIdRules;
import cn.ypbin.admin.iot.values.RedisLatestValueWriter;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * MQTT 入站薄适配服务实现（设计 §6.1 主推路径 P0-B-2）。
 *
 * <p><b>为什么返回真状态码的对端只认「整批」语义</b>：一条 MQTT 消息就是一台设备的一小批读数
 * （EMQX 的 HTTP 动作 body 不支持数组展开，P0 契约即「一消息一批」）⇒ 一批里只要有一条不合法，
 * 说明这条报文整体不可信，**在动库之前整批拒绝**（4xx：EMQX 明确不重试）；而不是像 HTTP 通道那样
 * 逐条丢弃——那条通道一批可能跨设备跨点位，整批拒绝会放大一次脏输入的影响面（见
 * {@code AvailabilityServiceImpl#dropInvalidPropertyIds} 的说明）。</p>
 *
 * <p><b>三段校验的分工（不重叠）</b>：</p>
 * <ol>
 *   <li><b>本类</b>：报文与字段的**形态**（JSON、requestId 形态、单设备、点位标识字符集、质量码白名单、
 *       时刻/周期的取值域）——纯内存，不查库；</li>
 *   <li><b>本类</b>：租户解析（按设备行反查，**不信任报文**）+ {@code requestId} 幂等（数据库唯一键
 *       兜底）；</li>
 *   <li><b>既有 {@code AvailabilityService.ingest}</b>：点位**成员/映射**校验（物模型属性 × 该设备
 *       点位映射）与全部落库——一行不改地复用，本服务不复制那份校验（复制必然漂移）。</li>
 * </ol>
 *
 * <p><b>为什么不自己给点位映射下判断</b>：映射校验已有唯一实现（{@code PointMappingIndex} +
 * {@code PropertyIdRules}），在入站层再写一份「设备有没有这个点位」的判断会形成第二份真源，
 * 而两处口径一旦漂移，表现是「入站放行、查询拒绝」这种看起来像丢数据的故障。</p>
 *
 * <p><b>幂等的作用域（如实说明）</b>：幂等只保证「同一设备同一 {@code requestId} 不重复落库」，
 * **不保证**「不同 requestId 携带同一份数据只落一次」——后者是设备侧的责任（每次上报用新 requestId，
 * 重发沿用原 requestId）。</p>
 *
 * <p><b>并发同 requestId 重投的边界（2026-10-12 补记）</b>：预查 + {@code INSERT ... ON DUPLICATE KEY
 * UPDATE} 只保证**回执行唯一**，**不保证业务副作用唯一**——两个并发请求都可能在各自事务快照里看不到
 * 回执、都通过预查并各执行一次 {@code availabilityService.ingest}（最新值/断档/影子/时序），随后回执被
 * 去重。因此 {@code iot.mqtt.ingest.duplicated} 在并发窗口内会**漏计**（只有命中预查的那条才计数）。
 * 影响：多数副作用是同值覆盖（幂等形态），但不构成 at-most-once；断档闭合/影子合并存在被执行两次的
 * 窗口。详见 {@code docs/MESSAGE-TRACE-DESIGN.md} §1.3.1b。低风险加固方案（**默认不实施**）：同事务内
 * 先 {@code INSERT ... ON DUPLICATE KEY UPDATE} 抢槽、再决定是否执行 ingest——它把回执写入提前到业务
 * 落库之前，失败路径需重做，改动面大于收益。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
@Service
public class MqttReadingIngestServiceImpl implements MqttReadingIngestService {

    private static final Logger log = LoggerFactory.getLogger(MqttReadingIngestServiceImpl.class);

    /** 受理成功的读数条数（累计；与 {@code iot.timeseries.points.collected} 配对定位断点）。 */
    public static final String METRIC_ACCEPTED = "iot.mqtt.ingest.accepted";

    /** 命中幂等回执的**报文**数（累计；验收口径 ④「重投只落一行」的观测出口）。 */
    public static final String METRIC_DUPLICATED = "iot.mqtt.ingest.duplicated";

    /** 被整批拒绝的**报文**数（累计；按原因码打 tag，tag 取值来自枚举而非输入）。 */
    public static final String METRIC_REJECTED = "iot.mqtt.ingest.rejected";

    /** 采集周期上限（毫秒；一天。超出基本是设备把秒当毫秒、或单位填错）。 */
    private static final int MAX_POLL_INTERVAL_MS = 24 * 60 * 60 * 1000;

    /** 原因码 tag 的键名（避免两处手写字符串）。 */
    private static final String TAG_REASON = "reason";

    /** 丢弃明细列上限（与 DDL discard_detail VARCHAR(512) 一致）。 */
    static final int MAX_DISCARD_DETAIL_LENGTH = 512;

    /** 丢弃明细最多列出的 pointId 数（超出记 ,...(+N) 后缀）。 */
    static final int MAX_DISCARD_DETAIL_IDS = 8;

    private final AvailabilityService availabilityService;

    private final IotDeviceMapper deviceMapper;

    private final IotMqttIngestReceiptMapper receiptMapper;

    /**
     * 解析用 reader：**显式**关闭「未知字段报错」。
     *
     * <p>为什么必须显式关：① 报文里可能被塞进 {@code tenantId} 之类的字段——契约里没有它，
     * 平台既不能信也不能因此把整批拒掉（拒绝会变成「随便加个字段就能让设备数据进不来」的可用性问题），
     * 正确语义是**忽略**；② Spring Boot 的默认 ObjectMapper 恰好关了该特性，但那是**自动配置的默认值**，
     * 谁把它打开（或本服务改用自建 mapper）就会出现「多一个字段 ⇒ 整批 400」的行为漂移。
     * 因此在这里把它钉死，并有单测（{@code tenantMustComeFromDeviceRowNotPayload}）用**裸 ObjectMapper**
     * 证明本服务自身配置生效，而不是靠 Boot 的默认值。</p>
     */
    private final ObjectReader reqReader;

    private final Counter acceptedCounter;

    private final Counter duplicatedCounter;

    /** 按原因码预建的拒绝计数（构造期一次性建好，避免在请求路径上反复构造 meter）。 */
    private final Map<MqttIngestRejectReason, Counter> rejectedCounters;

    public MqttReadingIngestServiceImpl(AvailabilityService availabilityService,
                                        IotDeviceMapper deviceMapper,
                                        IotMqttIngestReceiptMapper receiptMapper,
                                        ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.availabilityService = availabilityService;
        this.deviceMapper = deviceMapper;
        this.receiptMapper = receiptMapper;
        // 用重建出的独立 mapper 而不是 readerFor(...).without(...)：后者对「解析器构造期解析」的特性
        // （FAIL_ON_UNKNOWN_PROPERTIES 正属此类）受反序列化器缓存影响，实际是否生效依赖首次使用时机，
        // 属于会静默失效的写法。重建 mapper 把配置钉在实例上，行为与调用顺序无关。
        this.reqReader = objectMapper.rebuild()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
            .readerFor(MqttReadingIngestReq.class);
        this.acceptedCounter = Counter.builder(METRIC_ACCEPTED)
            .description("MQTT 入站受理成功的读数条数").register(meterRegistry);
        this.duplicatedCounter = Counter.builder(METRIC_DUPLICATED)
            .description("MQTT 入站命中幂等回执的报文数（重投只落一行）").register(meterRegistry);
        Map<MqttIngestRejectReason, Counter> counters = new EnumMap<>(MqttIngestRejectReason.class);
        for (MqttIngestRejectReason reason : MqttIngestRejectReason.values()) {
            counters.put(reason, Counter.builder(METRIC_REJECTED)
                .description("MQTT 入站被整批拒绝的报文数")
                .tag(TAG_REASON, reason.getCode()).register(meterRegistry));
        }
        this.rejectedCounters = counters;
    }

    @Override
    // noRollbackFor：整批被拒时我们**先写 rejected 回执再抛异常**，若按默认回滚会把刚落的证据一并抹掉；
    // 被拒分支在任何业务副作用之前（accepted<=0 时落库链路尚未写任何东西），提交是安全的。
    @Transactional(rollbackFor = Exception.class, noRollbackFor = MqttIngestRejectionException.class)
    public MqttReadingIngestResult ingest(String rawBody) {
        // 体积护栏必须在**这里**（而不是控制器）：控制器里直接写 400 会绕过 reject()，
        // 于是 iot.mqtt.ingest.rejected{reason=BODY_TOO_LARGE} **永远为 0**——监控规则"看起来配好了、
        // 却永不触发"（独立复核以实测判出的真缺陷）。护栏仍早于 JSON 解析，防内存放大的目的不变。
        // 体积护栏按 **UTF-8 字节数**（不是 String.length() 的字符数）：多字节内容下字符数会低估
        // 实际内存/传输体积（中文 1 字符 = 3 字节）⇒ 按字符判定等于把上限放宽到 3~4 倍。
        // 逐字符累加而不 getBytes()：护栏的目的正是防内存放大，再分配等大字节数组会抵消它。
        int bodyBytes = utf8ByteLength(rawBody);
        if (bodyBytes > MqttReadingIngestReq.MAX_BODY_LENGTH) {
            reject(MqttIngestRejectReason.BODY_TOO_LARGE,
                "报文长度 " + bodyBytes + " 字节超过上限 " + MqttReadingIngestReq.MAX_BODY_LENGTH + " 字节");
        }
        MqttReadingIngestReq req = parse(rawBody);
        // 第①段：纯内存的报文/字段校验——任何一条不合法都在动库之前整批拒绝
        Long deviceId = validate(req);
        // 第②段：租户**按设备行反查**（报文体里即便带了 tenantId 也不参与任何判断）
        Long tenantId = resolveTenant(deviceId);
        if (tenantId == null) {
            // 设备不存在或不在任何租户：契约错误，重投不会变好 ⇒ 4xx（EMQX 不重试，但会计入失败）
            reject(MqttIngestRejectReason.DEVICE_NOT_FOUND, "deviceId=" + deviceId + " 在设备台账中不存在");
        }
        // 第③段：进入设备真实租户上下文——幂等回执与落库都在该租户内（租户插件 fail-closed）
        return TenantContext.executeWithTenant(tenantId, () -> apply(req, tenantId, deviceId));
    }

    /**
     * 解析请求体（**不使用** Bean Validation；见类注释）。
     *
     * @param rawBody 原始请求体（非空由控制器保证，但仍防御）
     * @return 解析结果
     */
    private MqttReadingIngestReq parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            reject(MqttIngestRejectReason.INVALID_JSON, "请求体为空");
        }
        try {
            MqttReadingIngestReq req = reqReader.readValue(rawBody);
            if (req == null) {
                reject(MqttIngestRejectReason.INVALID_JSON, "请求体解析为 null");
            }
            return req;
        } catch (JacksonException ex) {
            // 只记解析器给出的诊断，不回显整段原文（不可信输入可能带换行/控制字符，会伪造日志行）；
            // 日志侧另有 LogSanitizer 兜底，响应体侧只有原因码与固定文案
            reject(MqttIngestRejectReason.INVALID_JSON, "JSON 解析失败：" + ex.getMessage());
            return null;
        }
    }

    /**
     * 纯内存校验（不查库、不建连接）；返回本批的唯一设备 ID。
     *
     * @param req 已解析的请求
     * @return 设备 ID（全部读数必须同属一台设备）
     */
    private Long validate(MqttReadingIngestReq req) {
        if (!MqttReadingIngestReq.isValidRequestId(req.getRequestId())) {
            reject(MqttIngestRejectReason.REQUEST_ID_INVALID,
                "requestId 不满足 " + MqttReadingIngestReq.REQUEST_ID_PATTERN);
        }
        List<ReadingObservationDto> items = req.getItems();
        if (items.isEmpty()) {
            reject(MqttIngestRejectReason.ITEMS_EMPTY, "items 为空");
        }
        if (items.size() > MqttReadingIngestReq.MAX_ITEMS) {
            reject(MqttIngestRejectReason.ITEMS_TOO_MANY,
                "items.size=" + items.size() + " 超过上限 " + MqttReadingIngestReq.MAX_ITEMS);
        }
        long now = System.currentTimeMillis();
        long maxTs = now + RedisLatestValueWriter.MAX_FUTURE_SKEW_MS;
        Long uniqueDeviceId = null;
        for (int index = 0; index < items.size(); index++) {
            ReadingObservationDto item = items.get(index);
            String position = "items[" + index + "]";
            if (item == null) {
                reject(MqttIngestRejectReason.ITEM_INVALID, position + " 为 null");
            }
            Long deviceId = item.getDeviceId();
            if (deviceId == null || deviceId <= 0) {
                reject(MqttIngestRejectReason.DEVICE_ID_INVALID, position + " 的 deviceId 非法");
            }
            if (uniqueDeviceId == null) {
                uniqueDeviceId = deviceId;
            } else if (!uniqueDeviceId.equals(deviceId)) {
                // 一条 MQTT 消息只承载一台设备的读数：混投会让「整批 4xx」的语义与幂等键都失去意义
                reject(MqttIngestRejectReason.DEVICE_ID_INVALID,
                    position + " 的 deviceId 与本消息首条不一致（一条消息只能报一台设备）");
            }
            String propertyId = item.getPropertyId();
            if (!PropertyIdRules.isValid(propertyId)) {
                reject(MqttIngestRejectReason.PROPERTY_ID_INVALID,
                    position + " 的 propertyId " + PropertyIdRules.INVALID_MESSAGE);
            }
            if (!ReadingQuality.isValid(item.getQuality())) {
                reject(MqttIngestRejectReason.QUALITY_INVALID,
                    position + " 的 quality 不在白名单（" + ReadingQuality.ALLOWED + "）");
            }
            String value = item.getValue();
            if (value == null || value.length() > ReadingObservationDto.MAX_VALUE_LENGTH) {
                reject(MqttIngestRejectReason.VALUE_INVALID,
                    position + " 的 value 缺失或超过 "
                        + ReadingObservationDto.MAX_VALUE_LENGTH + " 字符");
            }
            Long ts = item.getTs();
            if (ts == null || ts <= 0L || ts > maxTs) {
                // 超前上限复用最新值写入器的同一口径（相对平台时钟的偏差），避免两处判据漂移
                reject(MqttIngestRejectReason.TS_INVALID, position + " 的 ts 缺失/非正/超前");
            }
            Integer pollIntervalMs = item.getPollIntervalMs();
            if (pollIntervalMs == null || pollIntervalMs <= 0
                || pollIntervalMs > MAX_POLL_INTERVAL_MS) {
                reject(MqttIngestRejectReason.POLL_INTERVAL_INVALID,
                    position + " 的 pollIntervalMs 缺失/非正/超过 " + MAX_POLL_INTERVAL_MS);
            }
        }
        return uniqueDeviceId;
    }

    /**
     * 解析设备所属租户（**忽略租户条件**地按设备反查，再回到该租户执行写入）。
     *
     * @param deviceId 设备 ID
     * @return 租户 ID；设备不存在返回 {@code null}
     */
    private Long resolveTenant(Long deviceId) {
        List<IotDevice> devices = TenantContext.executeIgnore(
            () -> deviceMapper.selectBatchIds(List.of(deviceId)));
        if (devices.isEmpty()) {
            return null;
        }
        Long tenantId = devices.get(0).getTenantId();
        return tenantId;
    }

    /**
     * 在设备真实租户上下文内执行：幂等预查 → 落库（复用既有 ingest）→ 写幂等回执。
     *
     * @param req      请求
     * @param tenantId 设备所属租户（已进入其上下文）
     * @param deviceId 设备 ID
     * @return 受理结果
     */
    private MqttReadingIngestResult apply(MqttReadingIngestReq req, Long tenantId, Long deviceId) {
        String requestId = req.getRequestId();
        IotMqttIngestReceipt existing = receiptMapper.selectByRequestId(deviceId, requestId);
        if (existing != null) {
            if (MqttReceiptStatus.REJECTED.getCode().equals(existing.getReceiptStatus())) {
                // 消息跟踪二批否决项①：命中 rejected 行 ⇒ **物理删除后按新批次继续**（不 UPDATE 复活）。
                // 必须删：唯一键 (tenant_id, device_id, request_id) 仍被这行占着，不删会让
                // ON DUPLICATE KEY UPDATE 静默吞掉本次受理回执，把「可恢复」变成「永久拒绝」。
                receiptMapper.deleteRejectedByRequestId(deviceId, requestId);
                log.info("[emqx→iot] 命中被拒回执，已删除旧行并按新批次重新受理：deviceId={} requestId长度={}",
                    LogSanitizer.sanitize(deviceId), requestId.length());
            } else {
                // 幂等预查只认 accepted：命中即整批跳过，**不调用** ingest，
                // 因此最新值/时序/活性/影子都不会被重复写
                duplicatedCounter.increment();
                log.info("[emqx→iot] 重复投递命中幂等回执，本次不重复落库：deviceId={} requestId长度={}",
                    LogSanitizer.sanitize(deviceId), requestId.length());
                return MqttReadingIngestResult.duplicated(deviceId, requestId, existing.getItemCount());
            }
        }
        List<ReadingObservationDto> items = req.getItems();
        ReadingIngestReq ingestReq = new ReadingIngestReq();
        ingestReq.setItems(new ArrayList<>(items));
        // 复用落库链路的**同一次**处理，并取回被点位映射丢弃的点位标识（不复制映射判断）
        ReadingIngestReport report = availabilityService.ingestWithReport(ingestReq);
        int accepted = report.processed();
        if (accepted <= 0) {
            // 整批都被落库链路丢弃（点位未映射/孤儿映射/设备解析不到）：重投不会变好 ⇒ 4xx。
            // 二批：先落一条 rejected 回执（让"断点"可查询），再抛异常——ingest 上的
            // noRollbackFor=MqttIngestRejectionException 保证这行证据随事务提交而不被回滚。
            writeRejectedReceipt(tenantId, deviceId, requestId, items.size(),
                MqttIngestRejectReason.NO_ACCEPTED_ITEM, report.droppedPropertyIds());
            reject(MqttIngestRejectReason.NO_ACCEPTED_ITEM,
                "deviceId=" + deviceId + " 本批 " + items.size() + " 条读数全部未通过点位映射校验");
        }
        IotMqttIngestReceipt receipt = new IotMqttIngestReceipt();
        receipt.setId(IdWorker.getId());
        receipt.setTenantId(tenantId);
        receipt.setDeviceId(deviceId);
        receipt.setRequestId(requestId);
        receipt.setItemCount(accepted);
        receipt.setReceiptStatus(MqttReceiptStatus.ACCEPTED.getCode());
        receipt.setReceivedCount(items.size());
        receipt.setPersistedRows(accepted);
        receipt.setPersistedAt(LocalDateTime.now());
        int dropped = items.size() - accepted;
        if (dropped > 0) {
            // 部分丢弃不改变 HTTP 语义（本批已被受理），但必须**可见**：落回执 + 日志 + 由既有
            // iot.ingest.propertyid.unmapped/orphan 指标计数。原因码复用映射类既有枚举（不新造分类）。
            receipt.setDiscardReason(MqttIngestRejectReason.NO_ACCEPTED_ITEM.getCode());
            receipt.setDiscardDetail(discardDetail(report.droppedPropertyIds()));
            log.warn("[emqx→iot] 部分读数未通过点位映射校验，已丢弃（整批其余正常落库）："
                    + "deviceId={} 受理={} 丢弃={}",
                LogSanitizer.sanitize(deviceId), accepted, dropped);
        }
        // 返回值不用于判断（Connector/J 默认带 CLIENT_FOUND_ROWS，命中已有行也返回 1）——
        // 并发重投的保证来自唯一键 + ON DUPLICATE KEY UPDATE，而不是这个返回值
        receiptMapper.insertReceipt(receipt);
        acceptedCounter.increment(accepted);
        log.info("[emqx→iot] MQTT 入站受理成功：deviceId={} 条数={} requestId长度={}",
            LogSanitizer.sanitize(deviceId), accepted, requestId.length());
        return MqttReadingIngestResult.accepted(deviceId, requestId, accepted, dropped);
    }

    /**
     * 写一条**整批被拒**回执（消息跟踪二批否决项③：rejected 罕见路径仅 +1 insert）。
     *
     * @param tenantId          设备所属租户
     * @param deviceId          设备 ID
     * @param requestId         设备侧请求 ID（幂等键）
     * @param receivedCount     本批收到的读数条数（含被丢弃的）
     * @param reason            整批拒绝原因码（复用 MqttIngestRejectReason，不新造分类）
     * @param droppedPropertyIds 被点位映射丢弃的 pointId（用于 discard_detail）
     */
    private void writeRejectedReceipt(Long tenantId, Long deviceId, String requestId, int receivedCount,
                                      MqttIngestRejectReason reason, List<String> droppedPropertyIds) {
        IotMqttIngestReceipt receipt = new IotMqttIngestReceipt();
        receipt.setId(IdWorker.getId());
        receipt.setTenantId(tenantId);
        receipt.setDeviceId(deviceId);
        receipt.setRequestId(requestId);
        receipt.setItemCount(0);
        receipt.setReceiptStatus(MqttReceiptStatus.REJECTED.getCode());
        receipt.setReceivedCount(receivedCount);
        receipt.setPersistedRows(0);
        // persisted_at 刻意留空：整批被拒根本没有「落库完成时刻」，填时间戳就是造假
        receipt.setDiscardReason(reason.getCode());
        receipt.setDiscardDetail(discardDetail(droppedPropertyIds));
        receiptMapper.insertReceipt(receipt);
    }

    /**
     * 生成丢弃明细：未映射 pointId **前 8 个**逗号连接，超出记 {@code ,...(+N)}（N=剩余个数）；
     * 总长不超过 {@value #MAX_DISCARD_DETAIL_LENGTH}（超长时减少列出的 pointId，保留计数后缀）。
     *
     * <p>为什么要截断：明细是排障线索不是审计证据（审计证据是应用日志），全量 pointId 随批大小线性膨胀，
     * 512 + 计数已足够定位到"映射问题"。</p>
     *
     * @param propertyIds 被丢弃的点位标识（可空）
     * @return 明细；无丢弃返回 {@code null}
     */
    static String discardDetail(List<String> propertyIds) {
        if (propertyIds == null || propertyIds.isEmpty()) {
            return null;
        }
        int shown = Math.min(MAX_DISCARD_DETAIL_IDS, propertyIds.size());
        String detail = buildDetail(propertyIds, shown);
        while (detail.length() > MAX_DISCARD_DETAIL_LENGTH && shown > 0) {
            shown--;
            detail = buildDetail(propertyIds, shown);
        }
        return detail;
    }

    /**
     * 按"列出前 shown 个 + 计数后缀"拼一条明细。
     *
     * @param propertyIds 被丢弃的点位标识
     * @param shown       列出的个数
     * @return 明细
     */
    private static String buildDetail(List<String> propertyIds, int shown) {
        String suffix = propertyIds.size() > shown
            ? ",...(+" + (propertyIds.size() - shown) + ")" : "";
        return String.join(",", propertyIds.subList(0, shown)) + suffix;
    }

    /**
     * 字符串的 UTF-8 编码字节数（**不分配 byte[]**）。
     *
     * <p>与 {@code value.getBytes(StandardCharsets.UTF_8).length} 逐字节等价：1/2/3 字节按码点范围，
     * 合法代理对按 4 字节；**落单代理**按 1 字节（Java 的 UTF-8 编码器把非法代理替换成 {@code '?'}），
     * 因此不会把「含落单代理的报文」算得比真实编码更大。</p>
     *
     * @param value 待测字符串（null 视为 0 字节）
     * @return UTF-8 字节数
     */
    static int utf8ByteLength(String value) {
        if (value == null) {
            return 0;
        }
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < 0x80) {
                bytes += 1;
            } else if (ch < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(ch) && index + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(ch)) {
                // 落单代理：UTF-8 编码器替换成 '?'（1 字节），与 getBytes 保持一致
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * 整批拒绝：计数 + 抛异常（控制器据此写原始 4xx）。
     *
     * @param reason  原因码
     * @param message 细节（不含输入原文与凭据）
     */
    private void reject(MqttIngestRejectReason reason, String message) {
        Counter counter = rejectedCounters.get(reason);
        if (counter != null) {
            counter.increment();
        }
        log.warn("[emqx→iot] MQTT 入站整批拒绝：原因={}({}) 细节={}",
            reason.getCode(), reason.getDesc(), LogSanitizer.sanitize(message));
        throw new MqttIngestRejectionException(reason, message);
    }
}
