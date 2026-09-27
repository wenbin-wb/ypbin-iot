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

/**
 * {@code ypbin.emqx.enabled=false} 时装配的管理面客户端：**每次调用都显式报错**。
 *
 * <p><b>为什么不是「静默成功的空实现」</b>：本环境没有 broker 时，平台侧凭据的签发/校验
 * （哈希自持）**照常可用**——调用点会先看 {@code enabled} 再决定是否同步 EMQX（见
 * {@code DeviceCredentialServiceImpl}）。若这里返回一个「假装成功」的实现，一旦有人漏判
 * {@code enabled}，就会得到「签发成功但设备连不上」这种最难查的静默故障。
 * 因此本实现的作用是**把误用变成显式异常**。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public class DisabledEmqxAdminClient implements EmqxAdminClient {

    /** 统一的失败文案（明确指向配置键，便于一眼定位）。 */
    private static final String MESSAGE =
        "本环境未启用 EMQX 管理面（ypbin.emqx.enabled=false）：不能同步设备账号或下发消息";

    @Override
    public void upsertPasswordUser(String username, String passwordHash, String salt) {
        throw new EmqxClientException(EmqxErrorCode.REJECTED, MESSAGE);
    }

    @Override
    public void deleteUser(String username) {
        throw new EmqxClientException(EmqxErrorCode.REJECTED, MESSAGE);
    }

    @Override
    public EmqxPublishOutcome publish(String topic, String payload, int qos, boolean retain) {
        throw new EmqxClientException(EmqxErrorCode.REJECTED, MESSAGE);
    }
}
