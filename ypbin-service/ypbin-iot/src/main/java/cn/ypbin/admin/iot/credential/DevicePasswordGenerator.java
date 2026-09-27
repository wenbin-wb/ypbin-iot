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

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 设备口令生成（一次性明文：只在签发响应里出现）。
 *
 * <p><b>为什么是 CSPRNG + 定长字节</b>：设备口令是**机器凭据**，会被写进设备固件/配置，
 * 不可能要求它「定期改」；它的强度只能靠熵。因此用 {@link SecureRandom} 取
 * {@code n} 字节再 base64url（无填充）——无填充是为了让口令能安全地放进 URL/二维码/CSV
 * 而不需要转义。</p>
 *
 * <p><b>长度为什么是配置值而不是常量</b>：设计 §8.4 把 `credential-password-length` 定为可配置项
 * （默认 32 **字节** ⇒ base64url 43 字符，≥ 设计 §5.3 要求的 24 字符）。本类只做**范围校验**：
 * 太短（&lt; {@value #MIN_PASSWORD_BYTES} 字节）直接拒绝，避免有人把它调成 4 字节还以为「能配就行」。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public final class DevicePasswordGenerator {

    /** 允许的最小口令字节数（128 位熵下界；低于它拒绝生成）。 */
    public static final int MIN_PASSWORD_BYTES = 16;

    /** 允许的最大口令字节数（避免误配成超长值把列写爆）。 */
    public static final int MAX_PASSWORD_BYTES = 64;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private DevicePasswordGenerator() {
    }

    /**
     * 生成一次性明文口令（base64url 无填充）。
     *
     * @param bytes 随机字节数（必须在 {@value #MIN_PASSWORD_BYTES}~{@value #MAX_PASSWORD_BYTES} 之间）
     * @return 明文口令
     */
    public static String generate(int bytes) {
        if (bytes < MIN_PASSWORD_BYTES || bytes > MAX_PASSWORD_BYTES) {
            // 配置错误必须暴露（不静默取默认值）：静默兜底会让「配错了」表现成「口令变短了」而无人知道
            throw new IllegalArgumentException("设备口令长度必须在 " + MIN_PASSWORD_BYTES + "~"
                + MAX_PASSWORD_BYTES + " 字节之间，实际=" + bytes);
        }
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return ENCODER.encodeToString(raw);
    }
}
