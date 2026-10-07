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

import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
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
    @Transactional(rollbackFor = Exception.class)
    public MqttReadingIngestResult ingest(String rawBody) {
        // 体积护栏必须在**这里**（而不是控制器）：控制器里直接写 400 会绕过 reject()，
        // 于是 iot.mqtt.ingest.rejected{reason=BODY_TOO_LARGE} **永远为 0**——监控规则"看起来配好了、
        // 却永不触发"（独立复核以实测判出的真缺陷）。护栏仍早于 JSON 解析，防内存放大的目的不变。
        if (rawBody != null && rawBody.length() > MqttReadingIngestReq.MAX_BODY_LENGTH) {
            reject(MqttIngestRejectReason.BODY_TOO_LARGE,
                "报文长度 " + rawBody.length() + " 超过上限 " + MqttReadingIngestReq.MAX_BODY_LENGTH);
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
            // 命中即整批跳过：**不调用** ingest，因此最新值/时序/活性/影子都不会被重复写
            duplicatedCounter.increment();
            log.info("[emqx→iot] 重复投递命中幂等回执，本次不重复落库：deviceId={} requestId长度={}",
                LogSanitizer.sanitize(deviceId), requestId.length());
            return MqttReadingIngestResult.duplicated(deviceId, requestId, existing.getItemCount());
        }
        List<ReadingObservationDto> items = req.getItems();
        ReadingIngestReq ingestReq = new ReadingIngestReq();
        ingestReq.setItems(new ArrayList<>(items));
        int accepted = availabilityService.ingest(ingestReq);
        if (accepted <= 0) {
            // 整批都被落库链路丢弃（点位未映射/孤儿映射/设备解析不到）：重投不会变好 ⇒ 4xx。
            // 刻意**不写回执**：修好映射后设备用同一个 requestId 重投仍应被受理。
            reject(MqttIngestRejectReason.NO_ACCEPTED_ITEM,
                "deviceId=" + deviceId + " 本批 " + items.size() + " 条读数全部未通过点位映射校验");
        }
        IotMqttIngestReceipt receipt = new IotMqttIngestReceipt();
        receipt.setId(IdWorker.getId());
        receipt.setTenantId(tenantId);
        receipt.setDeviceId(deviceId);
        receipt.setRequestId(requestId);
        receipt.setItemCount(accepted);
        // 返回值不用于判断（Connector/J 默认带 CLIENT_FOUND_ROWS，命中已有行也返回 1）——
        // 并发重投的保证来自唯一键 + ON DUPLICATE KEY UPDATE，而不是这个返回值
        receiptMapper.insertReceipt(receipt);
        acceptedCounter.increment(accepted);
        int dropped = items.size() - accepted;
        if (dropped > 0) {
            // 部分丢弃不改变 HTTP 语义（本批已被受理），但必须是**可见**的：落日志 + 由既有
            // iot.ingest.propertyid.unmapped/orphan 指标计数
            log.warn("[emqx→iot] 部分读数未通过点位映射校验，已丢弃（整批其余正常落库）："
                    + "deviceId={} 受理={} 丢弃={}",
                LogSanitizer.sanitize(deviceId), accepted, dropped);
        }
        log.info("[emqx→iot] MQTT 入站受理成功：deviceId={} 条数={} requestId长度={}",
            LogSanitizer.sanitize(deviceId), accepted, requestId.length());
        return MqttReadingIngestResult.accepted(deviceId, requestId, accepted, dropped);
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
