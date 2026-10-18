/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import cn.ypbin.admin.access.decode.AccessDecodeProperties;
import cn.ypbin.admin.access.decode.MqttBinaryNumberDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.runtime.registry.DefaultBytePayloadDecoderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 宿主可插字节载荷解码 SPI 装配：把全部 {@link BytePayloadDecoder} bean 收进
 * {@link BytePayloadDecoderRegistry}（一处 SSOT），并注册参考实现 MQTT binary 定宽数值解码。
 *
 * <p><b>顺序</b>：本配置声明在 {@link AccessIotProtocolConfiguration} <b>之前</b>——后者构造
 * {@code AccessSubscriptionPlanner} 时需要注册表；若顺序颠倒，协议栈装配可能拿到尚未就绪的 bean。</p>
 *
 * <p><b>可扩展性</b>：业务侧只需再声明一个 {@code @Bean BytePayloadDecoder}，注册表自动收集；
 * 参考解码器带 {@code @ConditionalOnMissingBean}，业务可整体替换。</p>
 *
 * <p><b>未配置 <code>ypbin.access.decode.mqtt-binary.enabled</code>（默认 false）时</b>，参考解码器
 * 不认领任何点位 ⇒ 与「未注册解码器」逐字一致：{@code byte[]} 被出口闸门丢弃并计
 * {@code iot.access.egress.invalid}。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
@AutoConfiguration(before = AccessIotProtocolConfiguration.class)
@EnableConfigurationProperties(AccessDecodeProperties.class)
public class AccessDecodeConfiguration {

    /**
     * 解码器注册表：收集所有 {@link BytePayloadDecoder} bean（Spring 的 {@code @Order} 参与选择顺序）。
     *
     * @param decoders 解码器提供者（惰性，避免与其他 bean 形成构造期循环）
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public BytePayloadDecoderRegistry bytePayloadDecoderRegistry(
            ObjectProvider<BytePayloadDecoder> decoders) {
        // starter 0.4.0 的注册表种子：收集宿主 @Bean 解码器，保持 Spring 的 @Order 顺序
        return new DefaultBytePayloadDecoderRegistry(decoders.orderedStream().toList());
    }

    /**
     * 参考实现：MQTT {@code payload-format=binary} 定宽数值解码（构造函数内做配置自检，非法即拒绝启动）。
     *
     * @param properties 解码参数
     * @return MQTT binary 解码器
     */
    @Bean
    @ConditionalOnMissingBean
    public MqttBinaryNumberDecoder mqttBinaryNumberDecoder(AccessDecodeProperties properties) {
        return new MqttBinaryNumberDecoder(properties.getMqttBinary());
    }
}
