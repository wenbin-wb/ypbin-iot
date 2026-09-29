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
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotCommandInstance;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotEventLog;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.IotCommandInstanceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotEventLogMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.model.query.DeviceTraceQuery;
import cn.ypbin.admin.iot.model.resp.DeviceTraceItemResp;
import cn.ypbin.admin.iot.model.resp.DeviceTraceResp;
import cn.ypbin.admin.iot.trace.DeviceTraceLimits;
import cn.ypbin.admin.iot.trace.TraceDirection;
import cn.ypbin.admin.iot.trace.TraceOutcome;
import cn.ypbin.admin.iot.trace.TraceStage;
import cn.ypbin.starter.core.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备消息跟踪实现用例（看板 #8，设计 `docs/MESSAGE-TRACE-DESIGN.md` §7）。
 *
 * <p><b>本用例的重点不是"能不能查出来"，而是锁死设计里那几条"容易做错且做错了看不出来"
 * 的硬约束</b>：</p>
 * <ol>
 *   <li>**一行 = 一条目**（§3.3.3）——一条命令实例**不得**展开成三个 DOWN 条目；</li>
 *   <li>**不造不可得的阶段**（§3.3）——时间线里**不得**出现 `UP_PERSISTED`/`UP_DISCARDED`；</li>
 *   <li>**上行受理不标 OK**（§3.3.2）——一期不能区分"已受理"与"已落库"，标 OK 等于对用户断言；</li>
 *   <li>**超窗显式报错、超量显式标记**（§7.2 判据 6）——都不静默；</li>
 *   <li>**空结果返回空集合**（仓内红线）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class DeviceTraceServiceImplTest {

    private IotDeviceMapper deviceMapper;
    private IotCommandInstanceMapper commandMapper;
    private IotMqttIngestReceiptMapper receiptMapper;
    private IotEventLogMapper eventLogMapper;
    private OutageEventMapper outageMapper;
    private DeviceTraceServiceImpl service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(IotDeviceMapper.class);
        commandMapper = mock(IotCommandInstanceMapper.class);
        receiptMapper = mock(IotMqttIngestReceiptMapper.class);
        eventLogMapper = mock(IotEventLogMapper.class);
        outageMapper = mock(OutageEventMapper.class);
        service = new DeviceTraceServiceImpl(deviceMapper, commandMapper, receiptMapper,
            eventLogMapper, outageMapper);

        IotDevice device = new IotDevice();
        device.setId(1L);
        when(deviceMapper.selectById(1L)).thenReturn(device);
        // 默认：各来源空
        when(commandMapper.selectList(any())).thenReturn(List.of());
        when(receiptMapper.selectList(any())).thenReturn(List.of());
        when(eventLogMapper.selectList(any())).thenReturn(List.of());
        when(outageMapper.selectList(any())).thenReturn(List.of());
    }

    private static IotCommandInstance command(String statusCode, String errorCode,
                                              LocalDateTime createTime, LocalDateTime sentAt,
                                              LocalDateTime finishedAt, String replyPayload,
                                              Integer retryCount) {
        IotCommandInstance row = new IotCommandInstance();
        row.setId(100L);
        row.setDeviceId(1L);
        row.setIdentifier("temperature");
        row.setKind("property_set");
        row.setRequestId("req-1");
        row.setStatusCode(statusCode);
        row.setErrorCode(errorCode);
        row.setErrorMsg(errorCode);
        row.setCreateTime(createTime);
        row.setSentAt(sentAt);
        row.setFinishedAt(finishedAt);
        row.setReplyPayload(replyPayload);
        row.setRetryCount(retryCount);
        return row;
    }

    @Test
    @DisplayName("🔴 状态机还在跑（pending）是一条『已受理』，不是三条 DOWN 条目")
    void pendingCommandMustProduceExactlyOneItem() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(5);
        when(commandMapper.selectList(any())).thenReturn(List.of(
            command("pending", null, t, null, null, null, 0)));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getStage()).isEqualTo(TraceStage.DOWN_ENQUEUED.getCode());
        assertThat(resp.getItems().get(0).getOccurredAt()).isEqualTo(t);
    }

    @Test
    @DisplayName("🔴 已投递+已回执的一条命令，仍然只产生 1 个条目（三个时刻挂在同一条上）")
    void fullyProgressedCommandMustStillProduceOneItem() {
        LocalDateTime created = LocalDateTime.now().minusMinutes(5);
        LocalDateTime sent = LocalDateTime.now().minusMinutes(4);
        LocalDateTime finished = LocalDateTime.now().minusMinutes(3);
        when(commandMapper.selectList(any())).thenReturn(List.of(
            command("succeeded", null, created, sent, finished, "{\"code\":0}", 0)));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems())
            .as("一条命令实例展开成多个条目会让时间线失真（同一件事重复出现）")
            .hasSize(1);
        DeviceTraceItemResp item = resp.getItems().get(0);
        assertThat(item.getStage()).isEqualTo(TraceStage.DOWN_ACK.getCode());
        // 三个时刻都要在，用户才能看到"受理 → 投递 → 回执"的推进
        assertThat(item.getEnqueuedAt()).isEqualTo(created);
        assertThat(item.getPublishedAt()).isEqualTo(sent);
        assertThat(item.getAckedAt()).isEqualTo(finished);
        assertThat(item.getOccurredAt()).isEqualTo(finished);
        assertThat(item.getOutcome()).isEqualTo(TraceOutcome.OK.getCode());
        assertThat(item.getAdvice()).as("成功的条目不需要建议").isNull();
    }

    @Test
    @DisplayName("🔴 publish 失败（无回执体）不得被当成『设备回执失败』（设计 §3.3.2b）")
    void publishFailureMustNotLookLikeDeviceRejection() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(2);
        // status=failed 且 reply_payload 为空 ⇒ 是"根本没送到"，不是"设备拒绝了"
        when(commandMapper.selectList(any())).thenReturn(List.of(
            command("failed", "NO_SUBSCRIBER", t, null, t, null, 0)));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        DeviceTraceItemResp item = resp.getItems().get(0);
        assertThat(item.getOutcome()).isEqualTo(TraceOutcome.FAILED.getCode());
        assertThat(item.getAdvice()).isNotNull();
        assertThat(item.getAdvice().ruleId())
            .as("必须走『设备未连接』规则，而不是『设备拒绝』")
            .isEqualTo("CMD_NO_SUBSCRIBER");
    }

    @Test
    @DisplayName("设备回执失败（有回执体）走『设备拒绝』规则")
    void deviceRejectionMustUseRejectionRule() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(2);
        when(commandMapper.selectList(any())).thenReturn(List.of(
            command("failed", "DEVICE_REJECTED", t, t, t, "{\"code\":500}", 0)));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems().get(0).getAdvice().ruleId()).isEqualTo("CMD_DEVICE_REJECTED");
    }

    @Test
    @DisplayName("🔴 时间线里绝不出现一期不可得的阶段（UP_PERSISTED / UP_DISCARDED）")
    void unimplementableStagesMustNeverAppear() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        IotMqttIngestReceipt receipt = new IotMqttIngestReceipt();
        receipt.setId(7L);
        receipt.setDeviceId(1L);
        receipt.setItemCount(3);
        receipt.setCreateTime(t);
        when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getStage()).isEqualTo(TraceStage.UP_RECEIVED.getCode());
        assertThat(TraceStage.values())
            .as("枚举里就不该有这两个阶段——没有取值就没有地方塞假数据")
            .noneMatch(stage -> stage.getCode().contains("persisted")
                || stage.getCode().contains("discarded"));
        assertThat(resp.isUpstreamTraceUnavailable())
            .as("必须如实告知上行链路有看不到的断点")
            .isTrue();
        assertThat(resp.getUpstreamTraceNote()).isNotBlank();
    }

    @Test
    @DisplayName("🔴 上行受理不得标 OK（一期无法区分『已受理』与『已落库』）")
    void upReceivedMustNotClaimSuccess() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        IotMqttIngestReceipt receipt = new IotMqttIngestReceipt();
        receipt.setId(7L);
        receipt.setDeviceId(1L);
        receipt.setItemCount(3);
        receipt.setCreateTime(t);
        when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems().get(0).getOutcome())
            .as("标 OK 等于对用户断言『数据已落库』——那是平台不知道的事")
            .isEqualTo(TraceOutcome.UNKNOWN.getCode());
    }

    @Test
    @DisplayName("事件按级别映射结果，warn/error 需要关注")
    void eventLevelMustMapToOutcome() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        when(eventLogMapper.selectList(any())).thenReturn(List.of(event("error", t), event("info", t)));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems()).hasSize(2);
        List<String> outcomes = resp.getItems().stream().map(DeviceTraceItemResp::getOutcome).toList();
        assertThat(outcomes).containsExactlyInAnyOrder(
            TraceOutcome.FAILED.getCode(), TraceOutcome.UNKNOWN.getCode());
    }

    @Test
    @DisplayName("时间线按时间倒序")
    void itemsMustBeOrderedByTimeDesc() {
        LocalDateTime now = LocalDateTime.now();
        when(eventLogMapper.selectList(any())).thenReturn(List.of(
            event("info", now.minusMinutes(30)), event("info", now.minusMinutes(10)),
            event("info", now.minusMinutes(20))));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        List<LocalDateTime> times = resp.getItems().stream()
            .map(DeviceTraceItemResp::getOccurredAt).toList();
        assertThat(times).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("🔴 超过单次上限时显式标记 truncated（不静默截断）")
    void oversizedResultMustBeFlagged() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        List<IotEventLog> many = new ArrayList<>();
        for (int i = 0; i < DeviceTraceLimits.MAX_ITEMS + 20; i++) {
            many.add(event("info", t.minusSeconds(i)));
        }
        when(eventLogMapper.selectList(any())).thenReturn(many);

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.isTruncated()).isTrue();
        assertThat(resp.getItems()).hasSize(DeviceTraceLimits.MAX_ITEMS);
        assertThat(resp.getMatched()).isEqualTo(many.size());
    }

    @Test
    @DisplayName("🔴 超过时间窗上限时显式报错（不静默截断成一个小窗口）")
    void oversizedWindowMustBeRejected() {
        DeviceTraceQuery query = new DeviceTraceQuery();
        query.setFrom(LocalDateTime.now().minusDays(30));
        query.setTo(LocalDateTime.now());

        assertThatThrownBy(() -> service.timeline(1L, query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("时间窗过大");
    }

    @Test
    @DisplayName("起点晚于终点时报错（不自作主张交换）")
    void invertedWindowMustBeRejected() {
        DeviceTraceQuery query = new DeviceTraceQuery();
        query.setFrom(LocalDateTime.now());
        query.setTo(LocalDateTime.now().minusHours(1));

        assertThatThrownBy(() -> service.timeline(1L, query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("起点晚于终点");
    }

    @Test
    @DisplayName("未知的筛选码显式报错（不静默当『全部』——那会让用户以为筛过了）")
    void unknownFilterCodeMustBeRejected() {
        DeviceTraceQuery stageQuery = new DeviceTraceQuery();
        stageQuery.setStage("no-such-stage");
        assertThatThrownBy(() -> service.timeline(1L, stageQuery))
            .isInstanceOf(BusinessException.class).hasMessageContaining("未知的阶段码");

        DeviceTraceQuery dirQuery = new DeviceTraceQuery();
        dirQuery.setDirection("sideways");
        assertThatThrownBy(() -> service.timeline(1L, dirQuery))
            .isInstanceOf(BusinessException.class).hasMessageContaining("未知的方向码");

        DeviceTraceQuery outQuery = new DeviceTraceQuery();
        outQuery.setOutcome("maybe");
        assertThatThrownBy(() -> service.timeline(1L, outQuery))
            .isInstanceOf(BusinessException.class).hasMessageContaining("未知的结果码");
    }

    @Test
    @DisplayName("筛选生效：阶段 / 方向 / 结果三组各自能筛")
    void filtersMustWork() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        when(eventLogMapper.selectList(any())).thenReturn(List.of(event("error", t)));
        when(commandMapper.selectList(any())).thenReturn(List.of(
            command("succeeded", null, t, t, t, "{}", 0)));

        DeviceTraceQuery byStage = new DeviceTraceQuery();
        byStage.setStage(TraceStage.EVENT_REPORTED.getCode());
        assertThat(service.timeline(1L, byStage).getItems()).hasSize(1);

        DeviceTraceQuery byDirection = new DeviceTraceQuery();
        byDirection.setDirection(TraceDirection.DOWN.getCode());
        assertThat(service.timeline(1L, byDirection).getItems()).hasSize(1);

        DeviceTraceQuery byOutcome = new DeviceTraceQuery();
        byOutcome.setOutcome(TraceOutcome.OK.getCode());
        assertThat(service.timeline(1L, byOutcome).getItems()).hasSize(1);
    }

    @Test
    @DisplayName("🔴 空结果是空集合而不是 null（仓内红线）")
    void emptyResultMustBeEmptyListNotNull() {
        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems()).isNotNull().isEmpty();
        assertThat(resp.getMatched()).isZero();
        assertThat(resp.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("设备不存在（含『存在但属别租户』，拦截器会让它查不到）时报错")
    void missingDeviceMustBeRejected() {
        when(deviceMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.timeline(999L, new DeviceTraceQuery()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("设备不存在");
    }

    @Test
    @DisplayName("重发过的条目要标记 historyOverwritten（前端据此提示时间被覆盖）")
    void retriedCommandMustBeFlagged() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        when(commandMapper.selectList(any())).thenReturn(List.of(
            command("timeout", "TIMEOUT", t, t, t, null, 2)));

        DeviceTraceResp resp = service.timeline(1L, new DeviceTraceQuery());

        assertThat(resp.getItems().get(0).isHistoryOverwritten()).isTrue();
        assertThat(resp.getItems().get(0).getRetryCount()).isEqualTo(2);
    }

    private static IotEventLog event(String level, LocalDateTime ts) {
        IotEventLog row = new IotEventLog();
        row.setId((long) Math.abs(ts.hashCode()));
        row.setDeviceId(1L);
        row.setEventCode("over_temp");
        row.setEventName("温度超限");
        row.setLevel(level);
        row.setEventTs(ts);
        return row;
    }
}
