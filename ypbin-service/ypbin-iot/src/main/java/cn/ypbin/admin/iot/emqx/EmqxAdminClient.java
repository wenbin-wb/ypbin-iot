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
 * EMQX 管理面客户端（凭据同步 + 下行发布）。
 *
 * <p><b>为什么是端口（接口）而不是直接一个类</b>：{@code enabled=false}（本环境没有 broker，
 * 决策 D3 的降级形态）时装配的是 {@link DisabledEmqxAdminClient}——调用点会**显式报错**，
 * 而不是拿到一个「静默返回成功」的空实现（后者会让「平台说签发成功、设备却永远连不上」）。
 * 选择逻辑集中在 {@code IotEmqxConfiguration} 的 {@code @Bean} 方法里，
 * 实现类**不标 {@code @Component}**（否则宿主再定义自己的实现会 {@code NoUniqueBeanDefinitionException}，
 * 见架构门禁 SRC-07 与教训三十一）。</p>
 *
 * <p><b>凭据纪律</b>：接口的所有方法都**不接受**明文口令，只接受口令哈希与盐（EMQX 内置库只存哈希，
 * 与平台自持哈希同口径）；实现里 API Key/Secret 只进 HTTP 头，不进日志、不进异常消息。</p>
 *
 * @author wenbin
 * @since 2026-10-01
 */
public interface EmqxAdminClient {

    /**
     * 幂等地上报一个设备账号（先删后建，见实现的语义说明）。
     *
     * <p><b>为什么不赌 POST 的 upsert 语义</b>：官方 user_management 页只说明 HTTP API 支持
     * create/update/delete/list/import，**未给出** POST 对已存在 {@code user_id} 是否 upsert；
     * 轮换必须让新哈希立即生效（否则旧口令仍然能连），故用「DELETE（404 视为成功）+ POST」。</p>
     *
     * @param username     MQTT 用户名（{@code {tenantId}.{deviceId}}）
     * @param passwordHash 口令哈希（hex；sha256(password + salt)，与平台自持口径一致）
     * @param salt         盐（hex 字符串，作为字符串参与拼接）
     */
    void upsertPasswordUser(String username, String passwordHash, String salt);

    /**
     * 删除设备账号（幂等：404 视为成功）。
     *
     * @param username MQTT 用户名
     */
    void deleteUser(String username);

    /**
     * 下行发布一条消息。
     *
     * @param topic   主题（定向靠主题，官方没有按 clientid 定向的端点）
     * @param payload 载荷
     * @param qos     QoS（0/1/2）
     * @param retain  是否保留
     * @return 投递结果 + EMQX 消息 ID（{@link EmqxPublishOutcome#result()} 为
     *         {@link EmqxPublishResult#NO_SUBSCRIBER} = 设备未连接）
     */
    EmqxPublishOutcome publish(String topic, String payload, int qos, boolean retain);
}
