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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * EMQX 管理面客户端的**真 HTTP 往返**门禁（用 JDK 内建 {@code HttpServer} 当假 EMQX）。
 *
 * <p><b>为什么不用 mock 框架桩掉 HTTP 层</b>：本类最要紧的两条约束都发生在**线上报文**里——
 * ① 同步设备凭据时只允许上报**哈希与盐**，绝不能出现明文口令；② 导入结果要看
 * {@code success/failed} 计数（HTTP 200 也可能 {@code failed=1}）。用 mock 掉客户端就看不到报文，
 * 这两条都成了「代码看着对」。这里让请求真的走一遍 HTTP，断言的是服务端**收到的东西**。</p>
 *
 * <p><b>已固化的实测口径（2026-09-27，EMQX 5.8.9 OSS）</b>：{@code POST .../users} 只收明文
 * {@code password}（传 hash 会 400）；带哈希的导入必须走 {@code POST .../import_users}
 * （multipart，字段名 {@code filename}），且对已存在用户是 {@code skipped}（不覆盖）⇒ 客户端必须先 DELETE。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
class EmqxRestAdminClientTest {

    private static final String HASH = "9f2c1e" + "0".repeat(58);

    private static final String SALT = "00112233445566778899aabbccddeeff";

    private HttpServer server;

    private final List<String> requests = new ArrayList<>();

    private final List<String> bodies = new ArrayList<>();

    private String responseBody = "{\"success\":1,\"failed\":0,\"skipped\":0,\"total\":1}";

    private int responseStatus = 200;

    private String importPath;

    private EmqxProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new EmqxProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("unit-test-key");
        properties.setApiSecret("unit-test-secret");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(2000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("同步设备账号：先 DELETE 再走 import_users，报文里**只有哈希与盐、绝无明文口令**")
    void upsertMustImportHashAndNeverSendPlaintext() {
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());

        client.upsertPasswordUser("1.9300012", HASH, SALT);

        assertThat(requests).as("必须先删后导（import_users 对已存在用户是 skipped，不覆盖哈希）")
            .hasSize(2);
        assertThat(requests.get(0)).startsWith("DELETE ").contains("/users/1.9300012");
        assertThat(requests.get(1)).startsWith("POST ")
            .contains("/authentication/password_based%3Abuilt_in_database/import_users");
        String csv = bodies.get(1);
        assertThat(csv).contains("user_id,password_hash,salt,is_superuser")
            .contains("1.9300012," + HASH + "," + SALT + ",false")
            .as("报文里不得出现任何明文口令字段（users 端点只收明文 password，故不能走它）")
            .doesNotContain("\"password\"");
        assertThat(importPath).contains("import_users");
    }

    @Test
    @DisplayName("导入结果按计数判定：HTTP 200 但 success=0/skipped=1 必须抛错（否则轮换假成功）")
    void importCountsMatterNotOnlyHttpStatus() {
        responseBody = "{\"total\":1,\"success\":0,\"failed\":0,\"override\":0,\"skipped\":1}";
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());

        assertThatThrownBy(() -> client.upsertPasswordUser("1.9300012", HASH, SALT))
            .isInstanceOf(EmqxClientException.class)
            .extracting(ex -> ((EmqxClientException) ex).getErrorCode())
            .isEqualTo(EmqxErrorCode.REJECTED);
    }

    @Test
    @DisplayName("删除不存在的用户（404）视为成功：吊销是幂等的")
    void deleteMissingUserIsIdempotent() {
        responseStatus = 404;
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());

        client.deleteUser("1.9300012");

        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("鉴权失败（401）映射为 AUTH_FAILED：不可重试，需人工修配置")
    void unauthorizedMapsToAuthFailed() {
        responseStatus = 401;
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());

        assertThatThrownBy(() -> client.deleteUser("1.9300012"))
            .isInstanceOf(EmqxClientException.class)
            .extracting(ex -> ((EmqxClientException) ex).getErrorCode())
            .isEqualTo(EmqxErrorCode.AUTH_FAILED);
    }

    @Test
    @DisplayName("管理面不可达映射为 UNREACHABLE：可重试、要告警")
    void unreachableMapsToUnreachable() {
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setConnectTimeoutMs(300);
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());

        assertThatThrownBy(() -> client.deleteUser("1.9300012"))
            .isInstanceOf(EmqxClientException.class)
            .extracting(ex -> ((EmqxClientException) ex).getErrorCode())
            .isEqualTo(EmqxErrorCode.UNREACHABLE);
    }

    @Test
    @DisplayName("下行发布：200=已投递、202=无订阅者（设备未连接，不必等超时）")
    void publishMapping() {
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());
        responseStatus = 200;
        assertThat(client.publish("ypbin/v1/1/9300012/down/property/set", "{}", 1, false))
            .isEqualTo(EmqxPublishResult.DELIVERED);
        responseStatus = 202;
        assertThat(client.publish("ypbin/v1/1/9300012/down/property/set", "{}", 1, false))
            .isEqualTo(EmqxPublishResult.NO_SUBSCRIBER);
    }

    @Test
    @DisplayName("启用管理面却缺凭据：构造即失败（启动期暴露，而不是第一次签发才炸）")
    void missingCredentialFailsFast() {
        properties.setApiKey(" ");
        assertThatThrownBy(() -> new EmqxRestAdminClient(properties, new ObjectMapper()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ypbin.emqx.api-key");
    }

    /**
     * 假 EMQX：记录方法与路径，按需返回预设状态码与响应体。
     *
     * @param exchange 请求
     * @throws IOException 写响应失败
     */
    private void handle(HttpExchange exchange) throws IOException {
        // 用 getRawPath()：认证链 id 里的冒号必须**在线上**是 %3A（路径段编码），getPath() 会把它解码掉
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
        importPath = exchange.getRequestURI().getRawPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
