/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.credential;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceCredential;
import cn.ypbin.admin.iot.model.resp.DeviceConnectionResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialIssuedResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialResp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 凭据外泄面门禁：**查看类响应结构上不得存在秘密字段，日志里不得出现秘密表达式**。
 *
 * <p>为什么必须是结构级而不是「实现里记得别 set」：口令哈希一旦成为某个响应模型的字段，
 * 泄露与否就取决于每个读路径有没有顺手序列化它；而这类回归**不会报错**，只会安静地出现在
 * 某个接口的 JSON 里。本门禁用两把尺子：</p>
 * <ol>
 *   <li><b>字段名 + 序列化键集合</b>：查看/接入信息响应的键集合必须**恰好**是预期集合
 *       （多一个字段就转红），且不得出现 password/hash/salt/secret 之类字段名；</li>
 *   <li><b>源码级</b>：服务实现的每一行 {@code log.*} 都不得提到口令/哈希/盐；
 *       签发端点的 {@code @Log} 必须显式排除请求体与响应体（把「不得进日志」写在调用点上）。</li>
 * </ol>
 *
 * <p>另有一条**正向对照**：{@link IotDeviceCredential} 必须真的持有哈希字段——否则
 * 「设备实体没有秘密字段」这条断言会因为「哪里都没有」而恒真（教训二十七：断言要能被变异咬到）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class DeviceCredentialSecretLeakTest {

    /** 秘密字段名特征（中文注释不算，只看字段名）。 */
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i).*(password|passwd|secret|salt|hash).*");

    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path SERVICE_IMPL = REPO_ROOT.resolve(
        "ypbin-service/ypbin-iot/src/main/java/cn/ypbin/admin/iot/service/impl"
            + "/DeviceCredentialServiceImpl.java");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("查看/接入信息响应：字段名与序列化键集合都不得含秘密（多一个字段即转红）")
    void viewResponsesMustNotCarrySecrets() throws Exception {
        assertNoSecretField(DeviceCredentialResp.class);
        assertNoSecretField(DeviceConnectionResp.class);

        assertThat(jsonKeys(new DeviceCredentialResp())).containsExactlyInAnyOrder(
            "deviceId", "username", "credentialVersion", "credentialIssuedAt",
            "credentialRevokedAt", "issued", "valid");
        assertThat(jsonKeys(new DeviceConnectionResp())).containsExactlyInAnyOrder(
            "deviceId", "username", "clientId", "topicUpPrefix", "topicDownPrefix",
            "credentialIssued", "credentialVersion", "credentialValid", "emqxEnabled",
            "brokerHost", "brokerPort", "brokerTlsEnabled");
    }

    @Test
    @DisplayName("设备实体不得持有秘密字段（秘密只在 iot_device_credential）")
    void deviceEntityMustNotCarrySecret() {
        assertNoSecretField(IotDevice.class);
        // 正向对照：凭据实体必须真的持有哈希，否则上面的断言没有意义
        assertThat(Arrays.stream(IotDeviceCredential.class.getDeclaredFields())
            .map(Field::getName)
            .toList())
            .as("凭据实体必须持有 passwordHash/passwordSalt（否则「秘密不在设备实体上」是句废话）")
            .contains("passwordHash", "passwordSalt");
    }

    @Test
    @DisplayName("签发响应：恰好一个 password 字段（一次性明文的位置是契约的一部分）")
    void issuedResponseMustCarryPlaintextExactlyOnce() throws Exception {
        DeviceCredentialIssuedResp resp = new DeviceCredentialIssuedResp();
        resp.setDeviceId(1L);
        resp.setUsername("1.1");
        resp.setCredentialVersion(1);
        resp.setPassword("one-time");

        assertThat(jsonKeys(resp)).containsExactlyInAnyOrder(
            "deviceId", "username", "credentialVersion", "credentialIssuedAt", "password");
        assertThat(MAPPER.writeValueAsString(resp))
            .as("除 password 外不得再出现哈希/盐字段")
            .doesNotContain("passwordHash", "passwordSalt");
    }

    @Test
    @DisplayName("服务实现：任何 log 行都不得引用口令/哈希/盐（日志不是凭据的第二个存储）")
    void serviceLogsMustNotMentionSecrets() throws IOException {
        String code = stripComments(Files.readString(SERVICE_IMPL, StandardCharsets.UTF_8));
        List<String> logLines = Stream.of(code.split("\n"))
            .filter(line -> line.contains("log."))
            .toList();
        assertThat(logLines)
            .as("没扫到任何 log 行 ⇒ 本门禁空跑（教训八）")
            .isNotEmpty();
        for (String line : logLines) {
            assertThat(line)
                .as("日志行出现秘密相关标识符：%s", line.trim())
                .doesNotContainIgnoringCase("password", "salt", "hash", "getPassword");
        }
    }

    /**
     * 断言某类没有秘密字段。
     *
     * @param type 被检查的类型
     */
    private static void assertNoSecretField(Class<?> type) {
        List<String> offenders = Stream.concat(
                Arrays.stream(type.getDeclaredFields()),
                Stream.of(type.getSuperclass() == null ? new Field[0] : type.getSuperclass().getDeclaredFields()))
            .map(Field::getName)
            .filter(name -> SECRET_FIELD.matcher(name).matches())
            .toList();
        assertThat(offenders)
            .as("%s 不得声明秘密字段（%s）", type.getSimpleName(), offenders)
            .isEmpty();
    }

    /**
     * 取序列化后的 JSON 顶层键集合。
     *
     * @param value 对象
     * @return 键集合
     * @throws Exception 序列化失败
     */
    private static Set<String> jsonKeys(Object value) throws Exception {
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(value));
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /**
     * 剥离注释（门禁文本匹配必须作用在剥离注释后的代码上，教训二十三）。
     *
     * @param source 源码
     * @return 剥离注释后的源码
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
