/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.emqx;

import cn.ypbin.starter.core.util.LogSanitizer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EMQX 5.8 REST 管理面客户端（官方 {@code /api/v5}）。
 *
 * <p><b>认证</b>：API Key/Secret 作 HTTP Basic（官方口径：5.0.0 起 REST **不接受** Dashboard 用户凭据）。
 * ⚠️ OSS 版的 API Key **没有角色约束**（官方：role-based API credentials 仅企业版）⇒ 它是本平台
 * 权限最大的凭据之一，只允许存在于 {@code deploy/.env}(600) 与容器 env，**不入库**（Nacos 里是
 * {@code ${EMQX_API_KEY}} 占位符，由 Spring 从容器 env 解析）、**不进日志/异常消息**。</p>
 *
 * <p><b>超时</b>：连接与读超时都来自配置（{@code ypbin.emqx.connect-timeout-ms/read-timeout-ms}），
 * 绝不使用无超时默认客户端（仓内铁律 + 全局规范「远程调用防失控」）。</p>
 *
 * <p><b>失败语义</b>：一切失败都抛 {@link EmqxClientException}（带可区分原因码），
 * 由调用方决定回滚（签发）还是告警（吊销）。**不做自动重试**：HTTP 调用本身不是幂等的判断依据，
 * 重试策略交给上层与 EMQX 侧（凭据同步是「先删后建」，重试安全；但显式重试会掩盖故障时长）。</p>
 *
 * <p><b>为什么异常里不带响应体（用户类接口）</b>：EMQX 的校验失败响应常回显请求字段，
 * 而请求里带口令哈希与盐。因此这两类接口的异常只带 HTTP 状态码与用户名；发布接口不带秘密，
 * 才允许带上截断后的响应体。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public class EmqxRestAdminClient implements EmqxAdminClient {

    private static final Logger log = LoggerFactory.getLogger(EmqxRestAdminClient.class);

    /** 用户管理路径前缀（{@code {id}} 形如 {@code password_based:built_in_database}，需 URL 编码）。 */
    private static final String AUTHENTICATION_PATH = "/api/v5/authentication/";

    /**
     * 新增用户的路径段（**明文** {@code password} 字段）。
     *
     * <p>🔴 <b>实测结论（2026-09-27，EMQX 5.8.9 OSS）：本端点只接受明文 {@code password}，
     * 不接受 {@code password_hash}/{@code salt}</b>（实测传 hash 得 HTTP 400
     * {@code unknown_fields: password_hash,salt}）。设计 §3.3 F11 把 {@code password_hash}+{@code salt}
     * 记在「User Management API」上，与容器实测不符 ⇒ <b>平台侧凭据同步改用
     * {@code POST .../import_users}（multipart CSV，接受 {@code password_hash}+{@code salt}）</b>，
     * 这样明文口令**永不离开平台**（这是本设计「平台自持哈希、同口径」的前提）。</p>
     */
    private static final String USERS_PATH_SUFFIX = "/users";

    /** 以「预计算哈希」导入用户的路径段（multipart/form-data，字段名实测为 {@code filename}）。 */
    private static final String IMPORT_USERS_PATH_SUFFIX = "/import_users";

    /** multipart 表单的字段名（实测必须叫 {@code filename}；叫 {@code file} 会 400 {@code Missing required parameter: filename}）。 */
    private static final String IMPORT_FIELD_NAME = "filename";

    /** 导入文件的文件名（EMQX 只用于识别，内容才是数据）。 */
    private static final String IMPORT_FILE_NAME = "ypbin-device-users.csv";

    /** 导入 CSV 的表头（列序固定；EMQX 按列名解析）。 */
    private static final String IMPORT_CSV_HEADER = "user_id,password_hash,salt,is_superuser";

    /** 发布路径（官方 summary: Publish a message）。 */
    private static final String PUBLISH_PATH = "/api/v5/publish";

    /** 日志里响应体的截断长度（只用于发布接口的排障，用户接口不回显响应体）。 */
    private static final int MAX_LOG_BODY = 200;

    private final EmqxProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private final String baseUrl;

    private final String authorization;

    /**
     * 构造（**fail-fast**：enabled=true 却缺 base-url 或凭据时直接拒绝启动，不等到第一次调用才发现）。
     *
     * @param properties   EMQX 配置
     * @param objectMapper JSON 序列化器（Jackson 3，由 Spring 装配）
     */
    public EmqxRestAdminClient(EmqxProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.baseUrl = require(properties.getBaseUrl(), "ypbin.emqx.base-url");
        // 构造 Basic 头：值只进内存与请求头；异常/日志里**永不**出现
        String key = require(properties.getApiKey(), "ypbin.emqx.api-key");
        String secret = require(properties.getApiSecret(), "ypbin.emqx.api-secret");
        this.authorization = "Basic " + Base64.getEncoder()
            .encodeToString((key + ':' + secret).getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            // 🔴 **必须显式 HTTP/1.1**，不要用 JDK 默认的 HTTP/2（2026-10-03 生产实测，勿删勿改）。
            //
            // 症状：`POST /api/v5/publish` 在「带体请求是某条新连接上的第一个请求」时抛
            //   java.io.IOException: EOF reached while reading（Http2Connection$Http2TubeSubscriber）
            //   ⇒ 本类把它映射成 UNREACHABLE ⇒ 平台显示「EMQX 管理面不可达」、命令实例 failed/EMQX_ERROR。
            //   极具误导性：**管理面其实是健康的**、同容器 curl 正常、隧道正常、import_users 同步也正常，
            //   只有走 h2c 的 JDK 客户端会失败 ⇒ 极易被误判成网络/隧道/暴露面问题。
            //
            // 判据（可复跑，见 deploy/emqx/diagnose-emqx-admin-h2c/）：JDK 客户端 HTTP/2 直接 POST
            //   `/api/v5/publish` = **3/3 EOF**；强制 HTTP/1.1 = **3/3 HTTP 202**；先发一个**无体** GET 把
            //   h2c 连接建起来再 POST = **3/3 HTTP 202**。即失败点是 **h2c upgrade 握手与"带体请求"的交互**。
            //
            // 为什么选"固定 1.1"而不是别的做法：
            //   · 加重试 ⇒ 只是掩盖（每次新连接的第一次带体请求仍会失败），且把一次用户可见的失败变成
            //     不可解释的延迟；
            //   · 先发无体请求"预热"连接 ⇒ 只是碰巧能过（依赖连接存活时间，空闲后照样失败），
            //     而且在每次发布前多一次往返；
            //   · 固定 HTTP/1.1 ⇒ 与 **EMQX 自身的 REST 也是 HTTP/1.1 语义**一致，去掉一整类未知，
            //     且已由上述判据证明 3/3 成功。
            // EMQX 5.8.9 的 Dashboard/REST 由 Cowboy 提供；同版本下 curl（HTTP/1.1）实测完全正常。
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    @Override
    public void upsertPasswordUser(String username, String passwordHash, String salt) {
        // 先删后建：import_users 对已存在用户是 skipped（实测 success=0/skipped=1），**不会覆盖哈希**
        // ⇒ 轮换时必须先删，否则「旧口令仍能连、平台以为换了」。DELETE 对不存在用户返回 404（幂等）。
        deleteUser(username);
        // CSV：只有 ASCII（用户名是两段数字、hash/salt 是 hex、布尔字面量），不涉及编码歧义
        String csv = IMPORT_CSV_HEADER + '\n' + username + ',' + passwordHash + ',' + salt
            + ",false\n";
        String boundary = "----ypbinEmqxImport" + System.nanoTime();
        String body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + IMPORT_FIELD_NAME + "\"; filename=\""
            + IMPORT_FILE_NAME + "\"\r\n"
            + "Content-Type: text/csv\r\n\r\n"
            + csv
            + "\r\n--" + boundary + "--\r\n";
        SendResult imported = sendWithBody("POST", authPath() + IMPORT_USERS_PATH_SUFFIX, body,
            "multipart/form-data; boundary=" + boundary, false);
        if (imported.status() < 200 || imported.status() >= 300) {
            throw new EmqxClientException(EmqxErrorCode.fromHttpStatus(imported.status()),
                "EMQX 导入设备账号失败：HTTP " + imported.status());
        }
        assertImportSucceeded(username, imported.body());
        log.info("[iot] 设备账号已同步到 EMQX（import_users，仅上报哈希与盐）：user={}",
            LogSanitizer.sanitize(username));
    }

    /**
     * 断言导入结果（**只看 HTTP 200 会假绿**：导入失败时 EMQX 仍返回 200，把失败计在 {@code failed}/{@code skipped}）。
     *
     * @param username 用户名（仅日志）
     * @param response 响应体
     */
    private void assertImportSucceeded(String username, String response) {
        int success;
        int failed;
        int skipped;
        try {
            JsonNode root = objectMapper.readTree(response);
            success = root.path("success").asInt(0);
            failed = root.path("failed").asInt(0);
            skipped = root.path("skipped").asInt(0);
        } catch (RuntimeException ex) {
            throw new EmqxClientException(EmqxErrorCode.REJECTED,
                "EMQX 导入设备账号的响应无法解析（user=" + LogSanitizer.sanitize(username) + "）", ex);
        }
        if (success != 1 || failed != 0) {
            // 只记计数，不记响应原文（防将来 EMQX 回显输入）
            throw new EmqxClientException(EmqxErrorCode.REJECTED,
                "EMQX 导入设备账号未生效：user=" + LogSanitizer.sanitize(username)
                    + " success=" + success + " failed=" + failed + " skipped=" + skipped);
        }
    }

    @Override
    public void deleteUser(String username) {
        int status = send("DELETE", usersPath() + '/' + urlEncode(username), null, false);
        // 404 = 本来就不存在 ⇒ 幂等成功（设计 §5.3：DELETE 不存在视为成功）
        if (status != 200 && status != 204 && status != 404) {
            throw new EmqxClientException(EmqxErrorCode.fromHttpStatus(status),
                "EMQX 删除设备账号失败：user=" + LogSanitizer.sanitize(username) + " HTTP " + status);
        }
    }

    @Override
    public EmqxPublishOutcome publish(String topic, String payload, int qos, boolean retain) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        body.put("payload", payload);
        body.put("qos", qos);
        body.put("retain", retain);
        SendResult sent = sendWithBody("POST", PUBLISH_PATH, toJson(body), "application/json", true);
        if (sent.status() == 200) {
            return new EmqxPublishOutcome(EmqxPublishResult.DELIVERED, extractMessageId(sent.body()));
        }
        if (sent.status() == 202) {
            // 官方语义：No matched subscribers ⇒ 立即判「设备未连接」，不必等超时（设计 §7.2）
            return new EmqxPublishOutcome(EmqxPublishResult.NO_SUBSCRIBER, null);
        }
        throw new EmqxClientException(EmqxErrorCode.fromHttpStatus(sent.status()),
            "EMQX 下行发布失败：topic=" + LogSanitizer.sanitize(topic) + " HTTP " + sent.status());
    }

    /**
     * 从 publish 响应体里取消息 ID（形如 {@code {"id":"..."}}；取不到返回 {@code null}）。
     *
     * @param body 响应体
     * @return 消息 ID；解析不出返回 {@code null}
     */
    private String extractMessageId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body).path("id");
            return node.isMissingNode() || node.isNull() ? null : node.asString();
        } catch (RuntimeException ex) {
            // 消息 ID 只是溯源字段：解析不出来不该把一次成功投递判成失败（但必须可见，故记 debug 全栈）
            log.debug("[iot] EMQX publish 响应体无法解析出消息 ID（不影响投递结果判定）", ex);
            return null;
        }
    }

    /**
     * 发一次请求并返回「状态码 + 响应体」。
     *
     * @param method         HTTP 方法
     * @param path           路径
     * @param body           请求体（可为 null）
     * @param contentType    Content-Type（multipart 需含 boundary）
     * @param logBodyOnError 非 2xx 时是否允许把响应体写进日志（仅发布接口允许——用户接口的响应体可能回显哈希）
     * @return 状态码 + 响应体
     */
    private SendResult sendWithBody(String method, String path, String body, String contentType,
                                    boolean logBodyOnError) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
            .header("Authorization", authorization)
            .header("Content-Type", contentType);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new EmqxClientException(EmqxErrorCode.UNREACHABLE, "EMQX 管理面不可达：" + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmqxClientException(EmqxErrorCode.UNREACHABLE, "EMQX 管理面调用被中断：" + path, ex);
        }
        if (response.statusCode() >= 300 && logBodyOnError) {
            log.warn("[iot] EMQX 调用失败：path={} HTTP {} 响应={}", LogSanitizer.sanitize(path),
                response.statusCode(), LogSanitizer.sanitize(truncate(response.body())));
        }
        return new SendResult(response.statusCode(), response.body());
    }

    /**
     * HTTP 调用结果（状态码 + 响应体）。
     *
     * @param status 状态码
     * @param body   响应体
     */
    private record SendResult(int status, String body) {
    }

    /**
     * 发一次请求。
     *
     * @param method         HTTP 方法
     * @param path           路径（含前导 /）
     * @param body           请求体（可为 null）
     * @param logBodyOnError 失败时是否允许把响应体写进异常（仅发布接口允许）
     * @return HTTP 状态码
     */
    private int send(String method, String path, String body, boolean logBodyOnError) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            // 不可达：可重试（隧道断/EMQX 重启），要告警；日志带完整堆栈（禁静默吞异常）
            throw new EmqxClientException(EmqxErrorCode.UNREACHABLE,
                "EMQX 管理面不可达：" + path, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmqxClientException(EmqxErrorCode.UNREACHABLE, "EMQX 管理面调用被中断：" + path, ex);
        }
        int status = response.statusCode();
        if (status >= 300 && logBodyOnError) {
            log.warn("[iot] EMQX 调用失败：path={} HTTP {} 响应={}", LogSanitizer.sanitize(path), status,
                LogSanitizer.sanitize(truncate(response.body())));
        }
        return status;
    }

    /**
     * 序列化 JSON（失败即抛，不静默）。
     *
     * @param body 请求体映射
     * @return JSON 文本
     */
    private String toJson(Map<String, Object> body) {
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 认证链路径（id 需 URL 编码：冒号在路径段里必须写成 %3A）。
     *
     * @return 路径（如 {@code /api/v5/authentication/password_based%3Abuilt_in_database}）
     */
    private String authPath() {
        return AUTHENTICATION_PATH + urlEncode(properties.getAuthenticationId());
    }

    /**
     * 用户集合路径。
     *
     * @return 路径
     */
    private String usersPath() {
        // 逐字对应线上实测的两条路径：`<auth>/users`（明文新增）与 `<auth>/import_users`（哈希导入）；
        // 它们**不是**父子关系（写成 `<auth>/users/import_users` 会 404）
        return authPath() + USERS_PATH_SUFFIX;
    }

    /**
     * URL 编码（路径段）。
     *
     * @param value 原值
     * @return 编码后的路径段
     */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 截断过长的日志正文。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOG_BODY ? text : text.substring(0, MAX_LOG_BODY) + "…";
    }

    /**
     * 必填配置项校验（缺失即显式失败）。
     *
     * @param value 值
     * @param key   配置键（用于报错定位）
     * @return 值
     */
    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("EMQX 管理面已启用但 " + key + " 未配置（检查 deploy/.env 与容器 env）");
        }
        return value;
    }
}
