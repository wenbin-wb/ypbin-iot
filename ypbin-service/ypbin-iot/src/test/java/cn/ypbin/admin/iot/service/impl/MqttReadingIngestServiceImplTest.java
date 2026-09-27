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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotMqttIngestReceipt;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotMqttIngestReceiptMapper;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectReason;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectionException;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestReq;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestResult;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.admin.iot.values.RedisLatestValueWriter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MQTT 入站适配服务的行为门禁：**动库前整批拒绝**、租户按设备行解析、{@code requestId} 幂等。
 *
 * <p><b>为什么逐条钉「拒绝发生在动库之前」</b>：本仓铁律是「非法输入不得进入任何落库路径」，
 * 但这条约束在代码上很容易被后来的改动破坏（例如把校验挪到 ingest 之后）。因此校验用例都断言
 * 与 mapper / {@code AvailabilityService} **零交互**（{@code verifyNoInteractions}），而不只是
 * 「抛了异常」——异常类型对但已经查过库的实现在前者下依然全绿。</p>
 *
 * <p><b>变异验证（本测试真的会咬人）</b>：① 去掉 {@code apply()} 里的幂等预查 ⇒
 * {@link #duplicateRequestMustNotReingest()} 转红；② 把设备租户改成报文里的 tenantId 或常量 ⇒
 * {@link #tenantMustComeFromDeviceRowNotPayload()} 转红；③ 把体积/质量码校验挪到 ingest 之后 ⇒
 * 对应的零交互断言转红。三条都实测过（见 PR 回执的变异记录）。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
class MqttReadingIngestServiceImplTest {

    private static final Long DEVICE_ID = 9300012L;

    private static final Long TENANT_ID = 1L;

    private AvailabilityService availabilityService;

    private IotDeviceMapper deviceMapper;

    private IotMqttIngestReceiptMapper receiptMapper;

    private MqttReadingIngestServiceImpl service;

    @BeforeEach
    void setUp() {
        availabilityService = mock(AvailabilityService.class);
        deviceMapper = mock(IotDeviceMapper.class);
        receiptMapper = mock(IotMqttIngestReceiptMapper.class);
        // 刻意**打开** FAIL_ON_UNKNOWN_PROPERTIES 再交给被测服务：这样「伪造 tenantId 被忽略」的断言
        // 证明的是服务自身钉死的解析口径（而非 Jackson/Boot 的某个默认值），默认值被改动时用例仍会咬人
        ObjectMapper mapper = new ObjectMapper().rebuild()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        service = new MqttReadingIngestServiceImpl(availabilityService, deviceMapper, receiptMapper,
            mapper, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("合法报文选设备真实租户并复用既有 ingest，落一条幂等回执")
    void acceptedRequestMustReuseExistingIngest() {
        stubDevice();
        when(availabilityService.ingest(any(ReadingIngestReq.class))).thenReturn(1);

        MqttReadingIngestResult result = service.ingest(validBody("r-1"));

        assertThat(result.isDuplicated()).isFalse();
        assertThat(result.getAccepted()).isEqualTo(1);
        assertThat(result.getDropped()).isZero();
        ArgumentCaptor<ReadingIngestReq> captor = ArgumentCaptor.forClass(ReadingIngestReq.class);
        verify(availabilityService).ingest(captor.capture());
        assertThat(captor.getValue().getItems()).hasSize(1);
        assertThat(captor.getValue().getItems().get(0).getPropertyId()).isEqualTo("temperature");
        ArgumentCaptor<IotMqttIngestReceipt> receipt =
            ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(receipt.capture());
        assertThat(receipt.getValue().getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(receipt.getValue().getRequestId()).isEqualTo("r-1");
        // 租户来自设备行（TENANT_ID），不是报文里的任何字段
        assertThat(receipt.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("报文里伪造 tenantId 不生效：回执仍写设备真实租户（不信任报文里的租户）")
    void tenantMustComeFromDeviceRowNotPayload() {
        stubDevice();
        when(availabilityService.ingest(any(ReadingIngestReq.class))).thenReturn(1);
        String forged = validBody("r-tenant")
            .replace("\"items\"", "\"tenantId\":9,\"items\"");

        service.ingest(forged);

        ArgumentCaptor<IotMqttIngestReceipt> receipt =
            ArgumentCaptor.forClass(IotMqttIngestReceipt.class);
        verify(receiptMapper).insertReceipt(receipt.capture());
        assertThat(receipt.getValue().getTenantId())
            .as("伪造的 tenantId=9 必须被忽略：租户只按设备行解析")
            .isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("同一 requestId 重投：命中回执即整批跳过，**不再调用**落库链路")
    void duplicateRequestMustNotReingest() {
        stubDevice();
        IotMqttIngestReceipt existing = new IotMqttIngestReceipt();
        existing.setItemCount(1);
        when(receiptMapper.selectByRequestId(DEVICE_ID, "r-dup")).thenReturn(existing);

        MqttReadingIngestResult result = service.ingest(validBody("r-dup"));

        assertThat(result.isDuplicated()).isTrue();
        assertThat(result.getAccepted()).isEqualTo(1);
        verify(availabilityService, never()).ingest(any(ReadingIngestReq.class));
        verify(receiptMapper, never()).insertReceipt(any(IotMqttIngestReceipt.class));
    }

    @Test
    @DisplayName("设备不存在：整批 400，且不调用落库链路（重投不会变好 ⇒ 4xx 不重试）")
    void unknownDeviceMustBeRejectedBeforeIngest() {
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.ingest(validBody("r-unknown")))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.DEVICE_NOT_FOUND);
        verifyNoInteractions(availabilityService);
        verifyNoInteractions(receiptMapper);
    }

    @Test
    @DisplayName("整批都被点位映射丢弃：400 + 不写回执（修好映射后同 requestId 重投仍应受理）")
    void fullyDroppedBatchMustBeRejectedWithoutReceipt() {
        stubDevice();
        when(availabilityService.ingest(any(ReadingIngestReq.class))).thenReturn(0);

        assertThatThrownBy(() -> service.ingest(validBody("r-dropped")))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.NO_ACCEPTED_ITEM);
        verify(receiptMapper, never()).insertReceipt(any(IotMqttIngestReceipt.class));
    }

    @Test
    @DisplayName("质量码不在白名单：动库之前整批 400（大小写不符也拒）")
    void invalidQualityMustBeRejectedWithoutDbAccess() {
        String body = validBody("r-quality").replace("\"GOOD\"", "\"good\"");

        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.QUALITY_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("坏 requestId（含斜杠等非法字符）整批 400，且不动库")
    void invalidRequestIdMustBeRejectedWithoutDbAccess() {
        assertThatThrownBy(() -> service.ingest(validBody("bad/request/id")))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.REQUEST_ID_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("点位标识形态非法（空格/超长字符集外）整批 400，且不动库")
    void invalidPropertyIdMustBeRejectedWithoutDbAccess() {
        String body = validBody("r-property").replace("\"temperature\"", "\"temp erature!\"");
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.PROPERTY_ID_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("超前的 ts（设备时钟错）整批 400：与最新值写入器用同一超前上限")
    void futureTsMustBeRejected() {
        long future = System.currentTimeMillis() + RedisLatestValueWriter.MAX_FUTURE_SKEW_MS + 60_000L;
        String body = validBody("r-future").replace("1758768000000", Long.toString(future));
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.TS_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("一条消息混了两台设备整批 400（幂等键与「整批」语义都依赖单设备）")
    void mixedDevicesMustBeRejected() {
        String body = "{\"requestId\":\"r-mixed\",\"items\":["
            + item(DEVICE_ID, "temperature") + "," + item(DEVICE_ID + 1, "temperature") + "]}";
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.DEVICE_ID_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("超过单消息条数上限整批 400，且不动库")
    void tooManyItemsMustBeRejected() {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"r-many\",\"items\":[");
        for (int i = 0; i <= MqttReadingIngestReq.MAX_ITEMS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(item(DEVICE_ID, "temperature"));
        }
        sb.append("]}");
        assertThatThrownBy(() -> service.ingest(sb.toString()))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.ITEMS_TOO_MANY);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("非 JSON 报文整批 400（不静默当成功，也不动库）")
    void malformedJsonMustBeRejectedWithoutDbAccess() {
        assertThatThrownBy(() -> service.ingest("not-json"))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.INVALID_JSON);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    @Test
    @DisplayName("缺 quality 整批 400（设计 §6.5 的经典缺口：绝不能返回 200 让 EMQX 判成功）")
    void missingQualityMustBeRejected() {
        String body = validBody("r-no-quality").replace(",\"quality\":\"GOOD\"", "");
        assertThatThrownBy(() -> service.ingest(body))
            .isInstanceOf(MqttIngestRejectionException.class)
            .extracting(ex -> ((MqttIngestRejectionException) ex).getReason())
            .isEqualTo(MqttIngestRejectReason.QUALITY_INVALID);
        verifyNoInteractions(availabilityService, deviceMapper, receiptMapper);
    }

    /**
     * 桩：设备行存在并属于 {@link #TENANT_ID}。
     */
    private void stubDevice() {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_ID);
        when(deviceMapper.selectBatchIds(anyList())).thenReturn(List.of(device));
    }

    /**
     * 构造一条合法的入站报文。
     *
     * @param requestId 请求 ID
     * @return JSON 文本
     */
    private static String validBody(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"items\":["
            + item(DEVICE_ID, "temperature") + "]}";
    }

    /**
     * 构造一条合法读数条目。
     *
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     * @return JSON 片段
     */
    private static String item(Long deviceId, String propertyId) {
        return "{\"deviceId\":" + deviceId + ",\"propertyId\":\"" + propertyId
            + "\",\"value\":\"23.4\",\"quality\":\"GOOD\",\"ts\":1758768000000,"
            + "\"pollIntervalMs\":30000}";
    }
}
