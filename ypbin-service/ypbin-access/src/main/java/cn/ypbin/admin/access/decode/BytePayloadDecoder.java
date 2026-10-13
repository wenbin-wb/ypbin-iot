/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.decode;

/**
 * 宿主**可插字节载荷解码 SPI**：把协议模块交付的原始 {@code byte[]} 按点位/设备上下文解成
 * 「规范值」或「显式失败原因」。
 *
 * <p><b>为什么需要它（缺口登记）</b>：starter 0.3.0 的 {@code payload-format=key-value} 只承接了
 * TCP 帧内的字段选择；MQTT {@code payload-format=binary} 与 OPC UA {@code ByteString} 节点仍把
 * 原始字节交付给宿主（MQTT 类注释原文：<i>「原样交付字节：宿主自行按业务协议解析」</i>）。
 * 没有本 SPI 时这些字节只能被出口闸门丢弃（{@code iot.access.egress.invalid} 增长），
 * 数据链路永远画不出曲线。见 {@code docs/VALUE-DECODE-DESIGN.md} §3/§5。</p>
 *
 * <p><b>契约（实现者必读）</b>：</p>
 * <ol>
 *   <li>{@link #supports(DecodeContext)} 只回答「本解码器是否认领这个点位」；<b>不得</b>在这里做实际解码，
 *       也不得抛异常（它是每次回调都会走的判定入口）；</li>
 *   <li>{@link #decode(byte[], DecodeContext)} 只做「字节 → 规范值」的纯解码：产出物是平台规范形态
 *       （数值/文本/布尔），<b>不负责落列</b>（列由 iot 侧按物模型 {@code data_type} 唯一决定）；
 *       解码失败必须返回 {@link DecodeOutcome#failed(DecodeFailure)}，<b>不得</b>返回 {@code null} 值充数；</li>
 *   <li>实现必须线程安全（协议回调可能在多线程上并发进入）；</li>
 *   <li><b>未注册任何解码器时行为与今天逐字一致</b>：{@code byte[]} 原样交给出口闸门丢弃并计
 *       {@code iot.access.egress.invalid}，本层不猜、不静默降级；</li>
 *   <li>解码器认领点位（{@code supports=true}）后若解码失败，宿主以 <b>BAD 质量</b> + 原因码指标
 *       {@code iot.access.payload.decode.failure} 显式表达，绝不吞异常、绝不把垃圾写进库。</li>
 * </ol>
 *
 * <p><b>注册机制（一处 SSOT）</b>：解码器是 Spring bean，由 {@link BytePayloadDecoderRegistry} 统一
 * 收集与选择（自动配置 {@code AccessDecodeConfiguration}）。业务仓只需再声明一个
 * {@code @Bean BytePayloadDecoder} 即可扩展，无需改动接入代码。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public interface BytePayloadDecoder {

    /**
     * 稳定标识：用于日志与排障（不得随文案变化，取值集合有界）。
     *
     * @return 解码器标识
     */
    String id();

    /**
     * 本解码器是否认领该点位。
     *
     * @param context 点位/设备上下文（非空）
     * @return 认领返回 {@code true}
     */
    boolean supports(DecodeContext context);

    /**
     * 解码一个原始字节载荷。
     *
     * @param payload 原始字节（协议模块交付；非空）
     * @param context 点位/设备上下文（非空）
     * @return 解码结果（成功带规范值，失败带显式原因；绝不返回 {@code null}）
     */
    DecodeOutcome decode(byte[] payload, DecodeContext context);
}
