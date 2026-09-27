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
 * 协议原始值 → 平台规范值的解码扩展点（**过渡实现**，见 {@code docs/VALUE-DECODE-DESIGN.md}）。
 *
 * <p><b>为什么需要这一层（一手核实结论）</b>：协议栈的 TCP 模块把每一帧**原始字节**直接作为
 * {@code PointValue.value} 交给宿主（{@code ypbin-iot-protocol-tcp} 的 {@code TcpSession#dispatch}，
 * v0.1.0 源码第 295 行 {@code PointValue.good(address, payload, ...)}），字节没有任何类型语义；
 * 而姊妹模块 MQTT 有 {@code MqttPayloadFormat}（TEXT/NUMBER/BINARY）在协议层就解码。
 * 缺口是**框架侧**的（缺 TCP 的 payload-format / 缺宿主可插的解码 SPI，见
 * {@code docs/STARTER-FEEDBACK.md} SF-6），故本层按本仓铁律**先反馈、同时给过渡实现**：
 * 解码器只吃「原始字节」，按物模型数据类型与点位映射参数产出规范值；框架补上能力后本层整体删除。</p>
 *
 * <p><b>契约（调用方按此保证「解码失败不静默」）</b>：实现必须返回
 * {@link DecodeOutcome#failed(DecodeFailure)} 而不是 {@code null} 或抛出——调用方据此丢弃该点、
 * 计数并告警；**绝不允许**把 {@code byte[]} 的 {@code toString()}（{@code [B@…}）之类无意义形态交给落库。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public interface ValueDecoder {

    /**
     * 是否处理该接入协议码。
     *
     * <p>同一协议码只应有一个解码器命中（{@code List} 中**先命中者生效**），避免同一份字节被两条规则
     * 各解一次而产生「谁生效取决于 bean 顺序」的隐性不确定。</p>
     *
     * @param protocol 接入协议码（{@code IotProtocol} 的 code）
     * @return 处理该协议返回 {@code true}
     */
    boolean supports(String protocol);

    /**
     * 解码一条读数。
     *
     * @param rawValue   协议栈给出的原始值（TCP 透传为 {@code byte[]}；其它协议可能是已解码的数值/文本）
     * @param addressKey 该点位映射声明的协议地址（TCP 文本帧里即 {@code KEY=VALUE} 的键）
     * @param dataType   物模型属性数据类型（{@code ThingModelDataType} 的 code，可空）
     * @return 解码结果；解不出来必须返回具名失败原因
     */
    DecodeOutcome decode(Object rawValue, String addressKey, String dataType);
}
