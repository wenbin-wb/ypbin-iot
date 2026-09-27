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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备口令生成/哈希的纯逻辑单测。
 *
 * <p>这里钉住两件**改动即事故**的事：</p>
 * <ol>
 *   <li><b>强度下界</b>：长度是配置项（设计 §8.4），配小了必须在**生成时**就拒绝，
 *       而不是静默生成一个弱口令——那会表现成「签发成功但设备被爆破」，没有任何现象会提示配错了。</li>
 *   <li><b>哈希口径与 EMQX 一致</b>：{@code sha256(password + salt)}、盐按**存储形态（hex 字符串）**
 *       拼接、盐放后缀。将来把这两列直接 import 进 EMQX 内置库时，口径差一点点就会变成
 *       「平台侧校验通过、broker 侧连不上」的双侧不一致，且只能靠抓包定位。故用已知答案钉死。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class DevicePasswordHasherTest {

    @Test
    @DisplayName("生成：长度按字节配置、base64url 无填充、每次不同（CSPRNG 而非可预测序列）")
    void generateMustBeStrongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            seen.add(DevicePasswordGenerator.generate(32));
        }
        assertThat(seen)
            .as("64 次生成出现重复 ⇒ 随机源不可用（重复即等于口令可预测）")
            .hasSize(64);
        assertThat(seen.iterator().next())
            .as("32 字节 base64url 无填充应为 43 字符（设计 §5.3 要求 ≥24 字符）")
            .hasSize(43)
            .matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("生成：长度越界必须当场拒绝（不静默取默认值）")
    void generateMustRejectOutOfRangeLength() {
        assertThatThrownBy(() -> DevicePasswordGenerator.generate(4))
            .as("弱长度必须炸出去：静默降级会变成「签发成功但设备可被爆破」")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("设备口令长度");
        assertThatThrownBy(() -> DevicePasswordGenerator.generate(1024))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(DevicePasswordGenerator.generate(DevicePasswordGenerator.MIN_PASSWORD_BYTES))
            .isNotBlank();
        assertThat(DevicePasswordGenerator.generate(DevicePasswordGenerator.MAX_PASSWORD_BYTES))
            .isNotBlank();
    }

    @Test
    @DisplayName("哈希：盐以**存储形态（hex 字符串）**参与拼接且放后缀（EMQX 内置库同口径，已知答案）")
    void hashMustMatchEmqxSuffixSaltContract() {
        // 期望值 = sha256("pw" + "abcd")（python3 -c "import hashlib;print(hashlib.sha256(b'pwabcd').hexdigest())"）
        assertThat(DevicePasswordHasher.hashHex("pw", "abcd"))
            .isEqualTo("2639eaff49058fe0639bc4e75210db3066217ce2501edc44f2b19cc74f95478b");
        assertThat(DevicePasswordHasher.ALGO_SHA256_SUFFIX).isEqualTo("sha256-suffix");
    }

    @Test
    @DisplayName("哈希：同一口令不同盐结果不同；盐不可从哈希反推（单测只能证明前者）")
    void hashMustDependOnSalt() {
        String saltA = DevicePasswordHasher.newSaltHex();
        String saltB = DevicePasswordHasher.newSaltHex();
        assertThat(saltA).hasSize(DevicePasswordHasher.SALT_BYTES * 2).isNotEqualTo(saltB);
        assertThat(DevicePasswordHasher.hashHex("pw", saltA))
            .isNotEqualTo(DevicePasswordHasher.hashHex("pw", saltB));
    }

    @Test
    @DisplayName("校验：正确口令通过；错口令/空口令/空盐/空哈希一律不通过（空哈希=吊销态）")
    void matchesMustRejectEverythingButExactPassword() {
        String salt = DevicePasswordHasher.newSaltHex();
        String hash = DevicePasswordHasher.hashHex("s3cret", salt);

        assertThat(DevicePasswordHasher.matches("s3cret", salt, hash)).isTrue();
        assertThat(DevicePasswordHasher.matches("s3creT", salt, hash)).isFalse();
        assertThat(DevicePasswordHasher.matches("s3cret", salt, DevicePasswordHasher.hashHex("s3cret", salt) + "0"))
            .as("哈希被篡改一个字符也必须拒绝")
            .isFalse();
        assertThat(DevicePasswordHasher.matches(null, salt, hash)).isFalse();
        assertThat(DevicePasswordHasher.matches("", salt, hash)).isFalse();
        assertThat(DevicePasswordHasher.matches("s3cret", "", hash)).isFalse();
        assertThat(DevicePasswordHasher.matches("s3cret", salt, ""))
            .as("空哈希是**吊销后的显式状态**（revoke 会把秘密列清空）⇒ 不得匹配任何口令")
            .isFalse();
    }
}
