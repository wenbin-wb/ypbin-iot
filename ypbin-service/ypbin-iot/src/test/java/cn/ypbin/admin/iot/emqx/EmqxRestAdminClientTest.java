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
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** 每个请求在**线上**声明的 HTTP 版本（例如 {@code HTTP/1.1}）。 */
    private final List<String> requestProtocols = new ArrayList<>();

    /** 每个请求的**请求头名**（小写）；用于检出 h2c upgrade 相关头部。 */
    private final List<List<String>> requestHeaderNames = new ArrayList<>();

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
        assertThat(client.publish("ypbin/v1/1/9300012/down/property/set", "{}", 1, false).result())
            .isEqualTo(EmqxPublishResult.DELIVERED);
        responseStatus = 202;
        assertThat(client.publish("ypbin/v1/1/9300012/down/property/set", "{}", 1, false).result())
            .isEqualTo(EmqxPublishResult.NO_SUBSCRIBER);
    }

    @Test
    @DisplayName("🔴 客户端必须显式配置为 HTTP/1.1：JDK 默认 HTTP/2 会在 h2c upgrade 上被 EMQX 断开")
    void httpClientMustBeConfiguredForHttp11() throws Exception {
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());

        Field field = EmqxRestAdminClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        HttpClient httpClient = (HttpClient) field.get(client);

        // 为什么这条断言值得存在：这是**配置级**的防回归。JDK 的 HttpClient 默认 HTTP/2（h2c），
        // 而 EMQX/Cowboy 在「带体请求是新连接上第一个请求」时会断开（见类内注释与
        // deploy/emqx/diagnose-emqx-admin-h2c/）。有人把 .version(...) 删掉时，这条会立刻转红。
        // 注：这里刻意先取成局部变量再比，不用 `assertThat(httpClient.version()).contains(...)`——
        //     本仓的 AssertJ 在 `HttpClient.Version`（枚举 ⇒ Comparable）上会解析到 Comparable 重载，
        //     `.contains(...)` **编译不过**（实测踩过），而 `isEqualTo(...)` 在两个重载上都可用。
        //     另：`HttpClient#version()` 返回的就是 `Version`（不是 Optional），别多写 orElse。
        HttpClient.Version configured = httpClient.version();
        assertThat(configured)
            .as("必须显式 HTTP_1_1；HTTP_2 会让带体 POST 在新连接上抛 EOF（平台侧显示「管理面不可达」）")
            .isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    @DisplayName("🔴 带体 POST 作为**新连接上第一个请求**时不得尝试 h2c upgrade（否则被断开 ⇒ 假「不可达」）")
    void bodyCarryingPostOnFreshConnectionMustNotAttemptH2cUpgrade() {
        // 新构造客户端 = 空连接池 ⇒ 这次 publish 就是某条新连接上的**第一个**请求，
        // 正是生产上失败的那个形态（EMQX_ERROR / 管理面不可达）。
        EmqxAdminClient client = new EmqxRestAdminClient(properties, new ObjectMapper());
        responseStatus = 202;

        client.publish("ypbin/v1/1/9300012/down/property/set", "{\"requestId\":\"t\"}", 1, false);

        assertThat(requests).hasSize(1);
        assertThat(requestProtocols).isNotEmpty();
        assertThat(requestHeaderNames).isNotEmpty();
        // 承重判据：只有**请求 HTTP/2（h2c upgrade）**时才会带这两个头。
        // 注意不能只断言 exchange.getProtocol()：JDK 内建 HttpServer 只会用 1.1 应答，
        // 即使客户端发起了 upgrade，服务端看到的协议也仍是 HTTP/1.1 ⇒ 那样断言会**恒真**（假绿）。
        assertThat(requestHeaderNames.get(0))
            .as("不得出现 h2c upgrade 相关请求头（upgrade / http2-settings）——它们正是被 EMQX 断开的触发点")
            .doesNotContain("upgrade", "http2-settings");
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
        requestProtocols.add(exchange.getProtocol());
        List<String> headerNames = new ArrayList<>();
        exchange.getRequestHeaders().forEach((name, values) -> headerNames.add(name.toLowerCase(Locale.ROOT)));
        requestHeaderNames.add(headerNames);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
