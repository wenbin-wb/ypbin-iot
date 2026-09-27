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
 * 下行发布结果（投递语义 + EMQX 返回的消息 ID）。
 *
 * <p>为什么要 message id：{@code iot_command_instance.emqx_message_id} 是**溯源**用的——排障时要能把
 * 平台的一条命令实例与 broker 侧的投递对上（设计 §7.1 的列定义）。EMQX 的 publish 响应体形如
 * {@code {"id":"..."}}；解析失败时为 {@code null}（**不因此判失败**：投递结果已由状态码判定）。</p>
 *
 * @param result    投递结果（{@link EmqxPublishResult}）
 * @param messageId EMQX 消息 ID（可能为 {@code null}）
 * @author wenbin
 * @since 2026-10-02
 */
public record EmqxPublishOutcome(EmqxPublishResult result, String messageId) {
}
