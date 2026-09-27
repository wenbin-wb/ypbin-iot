/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.mqtt.MqttIngestRejectReason;
import cn.ypbin.admin.iot.mqtt.MqttIngestRejectionException;
import cn.ypbin.admin.iot.mqtt.MqttReadingIngestResult;
import cn.ypbin.admin.iot.service.MqttReadingIngestService;
import cn.ypbin.starter.core.model.R;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MQTT 入站薄适配端点（EMQX Rule Engine 的 HTTP 动作 → 平台）。
 *
 * <p>🔴 <b>本端点是全仓「HTTP 200 + {@code R.code}」惯例的**唯一**例外</b>（设计 §1.1 决策 D2，
 * 用户 2026-09-26 拍板），破例范围**仅** {@code /internal/mqtt/**}：</p>
 * <ul>
 *   <li><b>为什么破例</b>：对端是 EMQX 的 HTTP 动作，它**只看 HTTP 状态码**（官方源码
 *       {@code emqx/emqx} v5.8.9 {@code emqx_bridge_http_connector.erl}:963-986：
 *       {@code StatusCode >= 200 andalso StatusCode < 300 -> ok}；429/503 → {@code recoverable_error}；
 *       其余 → {@code unrecoverable_error}）。若沿用 HTTP 200 信封，非法报文会被判「投递成功」——
 *       不重试、不计入 {@code dropped.*}，于是设备以为上报了、broker 以为送达了、库里没有数据，
 *       且没有任何一处告警（设计 C9/H10 的静默丢数据缺口）。</li>
 *   <li><b>范围不得扩散</b>：浏览器/网关面向的 API 与 {@code /internal/**} 的其余端点
 *       （{@code /internal/readings}、{@code /internal/events}、{@code /internal/lease/**}、
 *       {@code /internal/device-credential/verify} …）**一律维持**原惯例。</li>
 *   <li><b>状态码语义</b>：契约非法 ⇒ {@code 400}（EMQX 不重试、计入失败）；可重试故障 ⇒ {@code 503}
 *       （官方源码里 503/429 是唯一被归为可恢复的两类）；成功（含幂等重投）⇒ {@code 200}。</li>
 *   <li><b>非法报文必须在动库之前整批拒绝</b>：校验全在服务层的纯内存段完成（见
 *       {@code MqttReadingIngestServiceImpl}）。</li>
 * </ul>
 *
 * <p><b>为什么不写 {@code @Valid @RequestBody MqttReadingIngestReq}</b>：绑定/校验失败会抛
 * {@code MethodArgumentNotValidException}，被全局异常处理器转成 HTTP 200 + {@code R.code}
 * ——等于本端点没做破例。因此这里接原始字符串，由服务层自己解析与校验（设计 §6.5 的
 * 「400 到底怎么出」要求写死做法）。</p>
 *
 * <p><b>控制器保持极薄</b>：只做「体积护栏 → 调服务 → 把结果/拒绝映射成原始状态码」，
 * 没有任何业务计算与落库逻辑。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
@RestController
@RequestMapping("/internal/mqtt/readings")
@RequiredArgsConstructor
public class InternalMqttReadingController {

    private static final Logger log = LoggerFactory.getLogger(InternalMqttReadingController.class);

    /** 可重试故障的错误码（响应体用；EMQX 不读，给排障用）。 */
    private static final String CODE_TRANSIENT = "TRANSIENT";

    /** 可重试故障的固定文案。 */
    private static final String MESSAGE_TRANSIENT = "服务暂时不可用，请稍后重试";

    private final MqttReadingIngestService mqttReadingIngestService;

    private final ObjectMapper objectMapper;

    /**
     * 受理一条 MQTT 上行报文。
     *
     * @param body     原始 JSON 体（EMQX HTTP 动作产出）
     * @param response 响应（**原始**状态码在这里写：200 / 400 / 503）
     * @throws IOException 写响应失败
     */
    @PostMapping
    public void ingest(@RequestBody(required = false) String body, HttpServletResponse response)
        throws IOException {
        MqttReadingIngestResult result;
        try {
            result = mqttReadingIngestService.ingest(body);
        } catch (MqttIngestRejectionException ex) {
            // 契约非法：4xx（EMQX 判 unrecoverable ⇒ 不重试）；原因码进响应体，细节已在服务层记日志
            writeRejected(response, ex.getReason());
            return;
        } catch (RuntimeException ex) {
            // 其它异常（DB/Redis/IoTDB 故障、未预期错误）：503 让 EMQX 重试——落库失败绝不能静默，
            // 这是「平台宕机不丢数据」在入站侧的最后一道（重试与缓冲在 broker）
            log.error("[emqx→iot] MQTT 入站处理失败（返回 503，交由 EMQX 重试）", ex);
            writeJson(response, HttpStatus.SERVICE_UNAVAILABLE.value(),
                errorBody(CODE_TRANSIENT, MESSAGE_TRANSIENT));
            return;
        }
        writeJson(response, HttpStatus.OK.value(), objectMapper.writeValueAsString(R.ok(result)));
    }

    /**
     * 写整批拒绝（原始 4xx）。
     *
     * @param response 响应
     * @param reason   原因码
     * @throws IOException 写响应失败
     */
    private void writeRejected(HttpServletResponse response, MqttIngestRejectReason reason)
        throws IOException {
        writeJson(response, HttpStatus.BAD_REQUEST.value(),
            errorBody(reason.getCode(), reason.getDesc()));
    }

    /**
     * 组装最小错误体（只有码与文案，**不回显任何输入原文**）。
     *
     * @param code    错误码
     * @param message 文案
     * @return JSON 文本
     */
    private String errorBody(String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 写 JSON 响应（显式状态码 + UTF-8）。
     *
     * @param response 响应
     * @param status   原始 HTTP 状态码
     * @param json     响应体
     * @throws IOException 写响应失败
     */
    private void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }
}
