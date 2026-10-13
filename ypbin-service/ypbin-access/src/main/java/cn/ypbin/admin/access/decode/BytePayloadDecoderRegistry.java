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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * 宿主字节载荷解码器的**统一注册与选择点（SSOT）**：把所有 {@link BytePayloadDecoder} bean 收在一处，
 * 按固定顺序挑出第一个认领点位的解码器。
 *
 * <p><b>一处 SSOT 的含义</b>：选择逻辑（顺序 + {@code supports} 判定）只在这里，调用方（数据回调）
 * 不自行维护解码器清单；新增解码器只需注册 bean，不改接入代码。顺序由 Spring 的
 * {@code @Order}/{@code Ordered} 决定，同序再按 {@link BytePayloadDecoder#id()} 字典序兜底
 * ⇒ <b>结果确定</b>（多个解码器同时认领同一上下文时不会随注册次序飘动）。</p>
 *
 * <p><b>空注册表</b>：{@link #empty()} 或未收集到任何 bean 时 {@link #find(DecodeContext)} 恒返回空
 * ⇒ 调用方按「今天的行为」把 {@code byte[]} 交给出口闸门丢弃并计数，<b>不静默降级</b>。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class BytePayloadDecoderRegistry {

    private final List<BytePayloadDecoder> decoders;

    /**
     * 构造注册表：拷贝并对解码器排序（不修改调用方集合）。
     *
     * @param decoders 解码器（可空/可含 null，null 元素会被忽略）
     */
    public BytePayloadDecoderRegistry(List<BytePayloadDecoder> decoders) {
        List<BytePayloadDecoder> sorted = new ArrayList<>();
        if (decoders != null) {
            for (BytePayloadDecoder decoder : decoders) {
                if (decoder != null) {
                    sorted.add(decoder);
                }
            }
        }
        // @Order/Ordered 优先，同序按 id 字典序：选择结果确定，不随 bean 注册次序漂动
        sorted.sort((left, right) -> {
            int byOrder = AnnotationAwareOrderComparator.INSTANCE.compare(left, right);
            return byOrder != 0 ? byOrder : left.id().compareTo(right.id());
        });
        this.decoders = List.copyOf(sorted);
    }

    /**
     * 空注册表（未注册任何解码器 ⇒ 与今天行为一致）。
     *
     * @return 空注册表
     */
    public static BytePayloadDecoderRegistry empty() {
        return new BytePayloadDecoderRegistry(List.of());
    }

    /**
     * 选择第一个认领该点位的解码器。
     *
     * @param context 点位/设备上下文
     * @return 命中的解码器；没有解码器认领时返回空
     */
    public Optional<BytePayloadDecoder> find(DecodeContext context) {
        if (context == null) {
            return Optional.empty();
        }
        for (BytePayloadDecoder decoder : decoders) {
            if (decoder.supports(context)) {
                return Optional.of(decoder);
            }
        }
        return Optional.empty();
    }

    /**
     * 已注册的解码器（只读，按选择顺序）。
     *
     * @return 解码器列表
     */
    public List<BytePayloadDecoder> decoders() {
        return decoders;
    }
}
