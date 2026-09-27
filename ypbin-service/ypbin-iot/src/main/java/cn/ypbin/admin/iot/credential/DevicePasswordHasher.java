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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 设备口令哈希：{@code sha256(password + salt)}，**盐放后缀**，与 EMQX 内置库同口径。
 *
 * <p><b>为什么不是 bcrypt/PBKDF2</b>：本口令是 {@link DevicePasswordGenerator} 生成的
 * ≥128 位随机串（不是人选口令），慢哈希解决的是「低熵口令可被离线爆破」，对本场景没有收益，
 * 却会让每一次设备 CONNECT 都要算一遍。EMQX 内置库的默认建议也是 {@code sha256} + salt
 * （设计 §3.3 F9/F10）。</p>
 *
 * <p><b>盐以「存储形态（hex 字符串）」参与拼接</b>，不是把 hex 解码成字节——这是**互操作契约**：
 * 将来把这两列直接喂给 EMQX 的 {@code /authentication/{id}/users}（{@code password_hash} + {@code salt}）
 * 时，EMQX 会算 {@code sha256(password + <salt 字符串>)}；若本侧按解码字节算，同一个口令在两侧
 * 会得出不同哈希 ⇒ 设备在平台侧校验通过、在 broker 侧却连不上。所以这里必须按字符串拼接，
 * 并有单测钉住这个口径。</p>
 *
 * <p><b>比较必须恒定时间</b>：用 {@link MessageDigest#isEqual}，不用 {@code String.equals}
 * （后者短路比较会泄露前缀匹配长度）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public final class DevicePasswordHasher {

    /** 算法标识（含盐位置）——与 {@code iot_device_credential.password_algo} 的取值一一对应。 */
    public static final String ALGO_SHA256_SUFFIX = "sha256-suffix";

    /** 盐字节数（hex 后 32 字符，列宽 64 够用）。 */
    public static final int SALT_BYTES = 16;

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final HexFormat HEX = HexFormat.of();

    private DevicePasswordHasher() {
    }

    /**
     * 生成新盐（hex，{@value #SALT_BYTES} 字节）。
     *
     * @return 盐（hex 小写）
     */
    public static String newSaltHex() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return HEX.formatHex(salt);
    }

    /**
     * 计算口令哈希（hex 小写）。
     *
     * @param password 明文口令
     * @param saltHex  盐（hex 字符串；以**字符串形态**参与拼接，见类注释）
     * @return 哈希（hex）
     */
    public static String hashHex(String password, String saltHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            digest.update(saltHex.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法；真缺了说明运行环境坏了，必须炸出去（不静默降级）
            throw new IllegalStateException(DIGEST_ALGORITHM + " 不可用：运行环境不完整", ex);
        }
    }

    /**
     * 凭据秘密是否可用（盐与哈希都非空）。
     *
     * <p>为什么单独判一次而不是只靠 {@link #matches}：{@code matches} 返回的只是一个布尔，
     * 而「秘密被清空」（吊销过、或库里被人工清过）与「口令打错」是**两种完全不同的运维结论**
     * ——前者要去重签，后者要去看设备配置。判空必须在调用点可见地分成两个原因码。</p>
     *
     * @param saltHex 盐（hex）
     * @param hashHex 哈希（hex）
     * @return 是否可用于校验
     */
    public static boolean isUsable(String saltHex, String hashHex) {
        return saltHex != null && !saltHex.isEmpty() && hashHex != null && !hashHex.isEmpty();
    }

    /**
     * 恒定时间比较口令与存储哈希。
     *
     * @param password       待校验明文口令（{@code null}/空白一律不通过）
     * @param saltHex        盐（hex）
     * @param expectedHashHex 存储的哈希（hex）
     * @return 是否匹配
     */
    public static boolean matches(String password, String saltHex, String expectedHashHex) {
        if (password == null || password.isEmpty() || !isUsable(saltHex, expectedHashHex)) {
            // 任一为空即不可能匹配：空哈希是**吊销后的显式状态**（见 revoke），不是兜底
            return false;
        }
        String actual = hashHex(password, saltHex);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
            expectedHashHex.getBytes(StandardCharsets.UTF_8));
    }
}
