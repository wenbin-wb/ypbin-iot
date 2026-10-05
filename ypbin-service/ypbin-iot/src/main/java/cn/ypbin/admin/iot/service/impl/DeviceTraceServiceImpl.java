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

import cn.ypbin.admin.iot.entity.IotCommandInstance;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotEventLog;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.enums.CommandErrorCode;
import cn.ypbin.admin.iot.enums.CommandInstanceStatus;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotEventLogMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.model.query.DeviceTraceQuery;
import cn.ypbin.admin.iot.model.resp.DeviceTraceItemResp;
import cn.ypbin.admin.iot.model.resp.DeviceTraceResp;
import cn.ypbin.admin.iot.service.DeviceTraceService;
import cn.ypbin.admin.iot.trace.DeviceTraceLimits;
import cn.ypbin.admin.iot.trace.TraceAdviceResolver;
import cn.ypbin.admin.iot.trace.TraceDirection;
import cn.ypbin.admin.iot.trace.TraceItem;
import cn.ypbin.admin.iot.trace.TraceOutcome;
import cn.ypbin.admin.iot.trace.TraceStage;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 设备消息跟踪实现（看板 #8，设计 `docs/MESSAGE-TRACE-DESIGN.md`）——**只读聚合**。
 *
 * <p><b>本类严格照设计的四条硬约束实现</b>：</p>
 * <ol>
 *   <li><b>一行 = 一条目</b>（设计 §3.3.3）：一条 `iot_command_instance` 记录只产生**一个**
 *       {@link TraceStage} 条目，其三个时刻（`create_time`/`sent_at`/`finished_at`）
 *       都挂在这一条上。**不展开成三条**——展开会让同一件事在时间线上重复出现三次。</li>
 *   <li><b>只呈现可推导的阶段</b>（设计 §3.3）：不产出 `UP_PERSISTED`（语义不可区分）
 *       与 `UP_DISCARDED`（库中零痕迹）。要呈现必须走二批补写入侧字段。</li>
 *   <li><b>`DOWN_ACK` 的判据用是否有回执体，而不是 `status_code = failed`</b>
 *       （设计 §3.3.2b）：该状态值有"根本没送到"与"设备拒绝"两个来源。</li>
 *   <li><b>时间窗与条数是正确性闸门</b>（设计 §6 R5）：跨源合并在内存做，
 *       超窗**显式报错**、超量**显式标记**，都不静默。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@Service
public class DeviceTraceServiceImpl implements DeviceTraceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTraceServiceImpl.class);

    /** 边界说明文案（前端直接展示；指明"看不到"的部分该怎么办）。 */
    static final String UPSTREAM_NOTE =
        "上行链路仅 MQTT 通道留有受理回执；经 HTTP 通道上报的设备不会出现在本时间线上。"
            + "另外，『被整批拒绝的上报』与『是否已落库』当前不产生可查询的记录——"
            + "若怀疑这两类问题，请查应用日志与 iot.ingest.propertyid.unmapped / orphan 指标。";

    private final IotDeviceMapper deviceMapper;
    private final IotCommandInstanceMapper commandInstanceMapper;
    private final IotMqttIngestReceiptMapper receiptMapper;
    private final IotEventLogMapper eventLogMapper;
    private final OutageEventMapper outageEventMapper;

    public DeviceTraceServiceImpl(IotDeviceMapper deviceMapper,
                                  IotCommandInstanceMapper commandInstanceMapper,
                                  IotMqttIngestReceiptMapper receiptMapper,
                                  IotEventLogMapper eventLogMapper,
                                  OutageEventMapper outageEventMapper) {
        this.deviceMapper = deviceMapper;
        this.commandInstanceMapper = commandInstanceMapper;
        this.receiptMapper = receiptMapper;
        this.eventLogMapper = eventLogMapper;
        this.outageEventMapper = outageEventMapper;
    }

    @Override
    public DeviceTraceResp timeline(Long deviceId, DeviceTraceQuery query) {
        requireDevice(deviceId);
        TimeWindow window = resolveWindow(query);
        Filters filters = resolveFilters(query);

        List<DeviceTraceItemResp> merged = new ArrayList<>();
        merged.addAll(loadCommands(deviceId, window));
        merged.addAll(loadReceipts(deviceId, window));
        merged.addAll(loadEvents(deviceId, window));
        merged.addAll(loadOutages(deviceId, window));

        // 先按筛选过滤再排序：筛选不改变"条数上限"的语义（上限约束的是**返回给用户的条数**）
        List<DeviceTraceItemResp> filtered = merged.stream().filter(filters::accept).toList();
        // 时间倒序；同一时刻用"阶段序号"稳定排序，避免同一批数据每次返回顺序不同（用户会以为在跳）
        List<DeviceTraceItemResp> sorted = new ArrayList<>(filtered);
        sorted.sort(Comparator.comparing(DeviceTraceItemResp::getOccurredAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.getStage() == null ? "" : item.getStage()));

        int matched = sorted.size();
        boolean truncated = matched > DeviceTraceLimits.MAX_ITEMS;
        List<DeviceTraceItemResp> items = truncated
            ? List.copyOf(sorted.subList(0, DeviceTraceLimits.MAX_ITEMS))
            : List.copyOf(sorted);

        if (truncated) {
            // 必须留痕：静默截断是"用户以为就这些"的根源
            log.info("[iot] 消息跟踪结果被截断：deviceId={} 命中={} 上限={}",
                deviceId, matched, DeviceTraceLimits.MAX_ITEMS);
        }

        DeviceTraceResp resp = new DeviceTraceResp();
        resp.setDeviceId(deviceId);
        resp.setFrom(window.from());
        resp.setTo(window.to());
        resp.setItems(items);
        resp.setMatched(matched);
        resp.setTruncated(truncated);
        resp.setUpstreamTraceNote(UPSTREAM_NOTE);
        return resp;
    }

    /**
     * 装载下行命令条目（**一行 = 一条目**，设计 §3.3.3）。
     *
     * @param deviceId 设备
     * @param window   时间窗
     * @return 条目
     */
    private List<DeviceTraceItemResp> loadCommands(Long deviceId, TimeWindow window) {
        List<IotCommandInstance> rows = commandInstanceMapper.selectList(
            new LambdaQueryWrapper<IotCommandInstance>()
                .eq(IotCommandInstance::getDeviceId, deviceId)
                .ge(IotCommandInstance::getCreateTime, window.from())
                .le(IotCommandInstance::getCreateTime, window.to())
                .orderByDesc(IotCommandInstance::getCreateTime)
                .last("LIMIT " + DeviceTraceLimits.PER_SOURCE_SCAN));

        List<DeviceTraceItemResp> items = new ArrayList<>(rows.size());
        for (IotCommandInstance row : rows) {
            items.add(toCommandItem(row));
        }
        return items;
    }

    /**
     * 把一条命令实例映射为**单个**时间线条目。
     *
     * <p>阶段取"已达的**最远**阶段"：有回执 ⟶ ACK，已投递 ⟶ PUBLISHED，否则 ⟶ ENQUEUED。
     * 排序时刻按同一顺序回落（`finished_at` → `sent_at` → `create_time`）。</p>
     *
     * @param row 命令实例
     * @return 条目
     */
    private DeviceTraceItemResp toCommandItem(IotCommandInstance row) {
        CommandInstanceStatus status = CommandInstanceStatus.ofCode(row.getStatusCode());
        boolean acked = row.getFinishedAt() != null && isAcked(row, status);
        TraceStage stage = acked ? TraceStage.DOWN_ACK
            : (row.getSentAt() != null ? TraceStage.DOWN_PUBLISHED : TraceStage.DOWN_ENQUEUED);
        LocalDateTime occurredAt = acked ? row.getFinishedAt()
            : (row.getSentAt() != null ? row.getSentAt() : row.getCreateTime());

        DeviceTraceItemResp item = new DeviceTraceItemResp();
        item.setOccurredAt(occurredAt);
        item.setStage(stage.getCode());
        item.setStageDesc(stage.getDesc());
        item.setDirection(stage.getDirection().getCode());
        item.setOutcome(resolveOutcome(status).getCode());
        item.setTitle(buildCommandTitle(row));
        item.setSource(SOURCE_COMMAND);
        item.setSourceId(row.getId());
        item.setErrorCode(row.getErrorCode());
        item.setErrorMsg(row.getErrorMsg());
        item.setRetryCount(row.getRetryCount());
        item.setHistoryOverwritten(row.getRetryCount() != null && row.getRetryCount() > 0);
        item.setEnqueuedAt(row.getCreateTime());
        item.setPublishedAt(row.getSentAt());
        item.setAckedAt(row.getFinishedAt());
        item.setAdvice(TraceAdviceResolver.resolve(toTraceItem(item, stage)));
        return item;
    }

    /**
     * 命令是否"已收到回执"。
     *
     * <p>🔴 <b>判据是「有回执体」而不是「`status_code = failed`」</b>（设计 §3.3.2b）：
     * `failed` 有两个来源——publish 阶段失败（`reply_payload` 为空，**根本没送到设备**）
     * 与设备回执失败（`reply_payload` 非空，**设备收到了并拒绝**）。只看状态码会把前者
     * 说成后者，把用户引向错误方向。</p>
     *
     * @param row    命令实例
     * @param status 状态
     * @return 已收到回执返回 {@code true}
     */
    private boolean isAcked(IotCommandInstance row, CommandInstanceStatus status) {
        if (status == null) {
            return false;
        }
        // 成功/取消：由状态机给出终态即可
        if (status == CommandInstanceStatus.SUCCEEDED || status == CommandInstanceStatus.CANCELLED) {
            return true;
        }
        // 失败/超时：只有在**确实收到了回执体**时才算"回执到了"
        return row.getReplyPayload() != null && !row.getReplyPayload().isBlank();
    }

    /**
     * 把命令行的状态映射为结果（仅看状态码；回执阶段由调用方另行判定，不在此重复）。
     *
     * @param status 状态
     * @return 结果
     */
    private TraceOutcome resolveOutcome(CommandInstanceStatus status) {
        if (status == null) {
            return TraceOutcome.UNKNOWN;
        }
        return switch (status) {
            case SUCCEEDED -> TraceOutcome.OK;
            case TIMEOUT -> TraceOutcome.TIMEOUT;
            case CANCELLED -> TraceOutcome.UNKNOWN;
            case PENDING -> TraceOutcome.UNKNOWN;
            case FAILED -> TraceOutcome.FAILED;
            case SENT -> TraceOutcome.UNKNOWN;
        };
    }

    /**
     * 命令条目的标题（人话；带标识与类型）。
     *
     * @param row 命令行
     * @return 标题
     */
    private String buildCommandTitle(IotCommandInstance row) {
        String identifier = row.getIdentifier() == null ? "（未命名）" : row.getIdentifier();
        String kind = row.getKind() == null ? "" : row.getKind();
        return "下发：" + identifier + (kind.isEmpty() ? "" : "（" + kind + "）");
    }

    /**
     * 装载上行受理条目（**仅 MQTT 通道**，设计 §1.1 澄清②）。
     *
     * @param deviceId 设备
     * @param window   时间窗
     * @return 条目
     */
    private List<DeviceTraceItemResp> loadReceipts(Long deviceId, TimeWindow window) {
        List<IotMqttIngestReceipt> rows = receiptMapper.selectList(
            new LambdaQueryWrapper<IotMqttIngestReceipt>()
                .eq(IotMqttIngestReceipt::getDeviceId, deviceId)
                .ge(IotMqttIngestReceipt::getCreateTime, window.from())
                .le(IotMqttIngestReceipt::getCreateTime, window.to())
                .orderByDesc(IotMqttIngestReceipt::getCreateTime)
                .last("LIMIT " + DeviceTraceLimits.PER_SOURCE_SCAN));

        List<DeviceTraceItemResp> items = new ArrayList<>(rows.size());
        for (IotMqttIngestReceipt row : rows) {
            DeviceTraceItemResp item = new DeviceTraceItemResp();
            item.setOccurredAt(row.getCreateTime());
            item.setStage(TraceStage.UP_RECEIVED.getCode());
            item.setStageDesc(TraceStage.UP_RECEIVED.getDesc());
            item.setDirection(TraceDirection.UP.getCode());
            // 🔴 不设 OK：一期无法区分"已受理"与"已落库"（设计 §3.3.2），
            // 标成 OK 等于对用户断言"数据已经存好了"——那是我们不知道的事。
            item.setOutcome(TraceOutcome.UNKNOWN.getCode());
            item.setTitle("上行受理：" + (row.getItemCount() == null ? 0 : row.getItemCount()) + " 条");
            item.setSource(SOURCE_RECEIPT);
            item.setSourceId(row.getId());
            item.setAdvice(TraceAdviceResolver.resolve(toTraceItem(item, TraceStage.UP_RECEIVED)));
            items.add(item);
        }
        return items;
    }

    /**
     * 装载设备事件条目。
     *
     * @param deviceId 设备
     * @param window   时间窗
     * @return 条目
     */
    private List<DeviceTraceItemResp> loadEvents(Long deviceId, TimeWindow window) {
        List<IotEventLog> rows = eventLogMapper.selectList(
            new LambdaQueryWrapper<IotEventLog>()
                .eq(IotEventLog::getDeviceId, deviceId)
                .ge(IotEventLog::getEventTs, window.from())
                .le(IotEventLog::getEventTs, window.to())
                .orderByDesc(IotEventLog::getEventTs)
                .last("LIMIT " + DeviceTraceLimits.PER_SOURCE_SCAN));

        List<DeviceTraceItemResp> items = new ArrayList<>(rows.size());
        for (IotEventLog row : rows) {
            DeviceTraceItemResp item = new DeviceTraceItemResp();
            item.setOccurredAt(row.getEventTs());
            item.setStage(TraceStage.EVENT_REPORTED.getCode());
            item.setStageDesc(TraceStage.EVENT_REPORTED.getDesc());
            item.setDirection(TraceDirection.UP.getCode());
            item.setOutcome(resolveEventOutcome(row.getLevel()).getCode());
            item.setTitle("设备事件：" + (row.getEventName() == null ? row.getEventCode() : row.getEventName()));
            item.setSource(SOURCE_EVENT);
            item.setSourceId(row.getId());
            item.setErrorCode(row.getLevel());
            item.setAdvice(TraceAdviceResolver.resolve(toTraceItem(item, TraceStage.EVENT_REPORTED)));
            items.add(item);
        }
        return items;
    }

    /**
     * 事件级别 → 结果（只有明确的告警级才算"需要关注"，其余不臆断）。
     *
     * @param level 事件级别（`info`/`warn`/`error` 或空）
     * @return 结果
     */
    private TraceOutcome resolveEventOutcome(String level) {
        if (level == null) {
            return TraceOutcome.UNKNOWN;
        }
        return switch (level.trim().toLowerCase()) {
            case "error" -> TraceOutcome.FAILED;
            case "warn", "warning" -> TraceOutcome.FAILED;
            default -> TraceOutcome.UNKNOWN;
        };
    }

    /**
     * 装载设备离线/断档条目。
     *
     * @param deviceId 设备
     * @param window   时间窗
     * @return 条目
     */
    private List<DeviceTraceItemResp> loadOutages(Long deviceId, TimeWindow window) {
        List<OutageEvent> rows = outageEventMapper.selectList(
            new LambdaQueryWrapper<OutageEvent>()
                .eq(OutageEvent::getDeviceId, deviceId)
                .ge(OutageEvent::getStartTs, window.from())
                .le(OutageEvent::getStartTs, window.to())
                .orderByDesc(OutageEvent::getStartTs)
                .last("LIMIT " + DeviceTraceLimits.PER_SOURCE_SCAN));

        List<DeviceTraceItemResp> items = new ArrayList<>(rows.size());
        for (OutageEvent row : rows) {
            DeviceTraceItemResp item = new DeviceTraceItemResp();
            item.setOccurredAt(row.getStartTs());
            item.setStage(TraceStage.DEVICE_OFFLINE.getCode());
            item.setStageDesc(TraceStage.DEVICE_OFFLINE.getDesc());
            item.setDirection(TraceDirection.INTERNAL.getCode());
            item.setOutcome(TraceOutcome.FAILED.getCode());
            item.setTitle(row.getEndTs() == null ? "设备离线（进行中）" : "设备离线");
            item.setSource(SOURCE_OUTAGE);
            item.setSourceId(row.getId());
            item.setErrorMsg(row.getReason());
            item.setAdvice(TraceAdviceResolver.resolve(toTraceItem(item, TraceStage.DEVICE_OFFLINE)));
            items.add(item);
        }
        return items;
    }

    /**
     * 把接口视图还原为领域条目（供**纯函数**规则引擎使用）。
     *
     * <p>规则引擎刻意只依赖领域对象，因此这里做一次显式映射；缺点是多一层对象，
     * 好处是"规则"能脱离 Spring/DB 被穷举单测（这是设计 §4.1 第 3 条的全部意义）。</p>
     *
     * @param item  接口视图
     * @param stage 阶段
     * @return 领域条目
     */
    private TraceItem toTraceItem(DeviceTraceItemResp item, TraceStage stage) {
        return new TraceItem(item.getOccurredAt(), stage,
            TraceDirection.ofCode(item.getDirection()),
            TraceOutcome.valueOf(item.getOutcome() == null ? "UNKNOWN"
                : item.getOutcome().toUpperCase().replace('-', '_')),
            item.getTitle(), item.getSource(), item.getSourceId(), item.getRetryCount(),
            item.getEnqueuedAt(), item.getPublishedAt(), item.getAckedAt(),
            item.getErrorCode(), item.getErrorMsg(), null);
    }

    /**
     * 解析并校验时间窗。
     *
     * @param query 查询条件
     * @return 生效时间窗
     */
    private TimeWindow resolveWindow(DeviceTraceQuery query) {
        LocalDateTime to = query == null || query.getTo() == null
            ? LocalDateTime.now() : query.getTo();
        LocalDateTime from = query == null || query.getFrom() == null
            ? to.minus(DeviceTraceLimits.DEFAULT_WINDOW) : query.getFrom();
        if (from.isAfter(to)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "时间窗起点晚于终点");
        }
        Duration span = Duration.between(from, to);
        if (span.compareTo(DeviceTraceLimits.MAX_WINDOW) > 0) {
            // 显式拒绝而不是静默截断：静默截断会让用户以为"这个窗口内就这些消息"
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "时间窗过大（最大 " + DeviceTraceLimits.MAX_WINDOW.toDays() + " 天），请缩小范围后重试");
        }
        return new TimeWindow(from, to);
    }

    /**
     * 解析筛选条件（未知取值**显式报错**，不静默当"全部"——那会让用户以为筛过了）。
     *
     * @param query 查询条件
     * @return 筛选器
     */
    private Filters resolveFilters(DeviceTraceQuery query) {
        if (query == null) {
            return Filters.none();
        }
        TraceStage stage = null;
        if (query.getStage() != null && !query.getStage().isBlank()) {
            stage = parseStage(query.getStage());
            if (stage == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "未知的阶段码：" + query.getStage());
            }
        }
        TraceDirection direction = null;
        if (query.getDirection() != null && !query.getDirection().isBlank()) {
            direction = TraceDirection.ofCode(query.getDirection());
            if (direction == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "未知的方向码：" + query.getDirection());
            }
        }
        TraceOutcome outcome = null;
        if (query.getOutcome() != null && !query.getOutcome().isBlank()) {
            outcome = parseOutcome(query.getOutcome());
            if (outcome == null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "未知的结果码：" + query.getOutcome());
            }
        }
        return new Filters(stage, direction, outcome);
    }

    /**
     * 按码解析阶段。
     *
     * @param code 码
     * @return 阶段；未知返回 `null`
     */
    private TraceStage parseStage(String code) {
        for (TraceStage stage : TraceStage.values()) {
            if (stage.getCode().equalsIgnoreCase(code.trim())) {
                return stage;
            }
        }
        return null;
    }

    /**
     * 按码解析结果。
     *
     * @param code 码
     * @return 结果；未知返回 `null`
     */
    private TraceOutcome parseOutcome(String code) {
        for (TraceOutcome outcome : TraceOutcome.values()) {
            if (outcome.getCode().equalsIgnoreCase(code.trim())) {
                return outcome;
            }
        }
        return null;
    }

    /**
     * 校验设备存在（租户条件由拦截器追加 ⇒ 别租户的设备此处即"不存在"）。
     *
     * @param deviceId 设备主键
     */
    private void requireDevice(Long deviceId) {
        if (deviceId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
    }

    /** 来源表名常量。 */
    static final String SOURCE_COMMAND = "iot_command_instance";

    static final String SOURCE_RECEIPT = "iot_mqtt_ingest_receipt";

    static final String SOURCE_EVENT = "iot_event_log";

    static final String SOURCE_OUTAGE = "outage_event";

    /**
     * 生效时间窗。
     *
     * @param from 起点
     * @param to   终点
     */
    private record TimeWindow(LocalDateTime from, LocalDateTime to) {
    }

    /**
     * 生效筛选器（三组条件同时满足才算命中）。
     *
     * @param stage     阶段（`null`=不限）
     * @param direction 方向（`null`=不限）
     * @param outcome   结果（`null`=不限）
     */
    private record Filters(TraceStage stage, TraceDirection direction, TraceOutcome outcome) {

        /**
         * 无筛选。
         *
         * @return 全通过
         */
        static Filters none() {
            return new Filters(null, null, null);
        }

        /**
         * 是否命中。
         *
         * @param item 条目
         * @return 命中返回 {@code true}
         */
        boolean accept(DeviceTraceItemResp item) {
            if (stage != null && !stage.getCode().equals(item.getStage())) {
                return false;
            }
            if (direction != null && !direction.getCode().equals(item.getDirection())) {
                return false;
            }
            return outcome == null || outcome.getCode().equals(item.getOutcome());
        }
    }
}
