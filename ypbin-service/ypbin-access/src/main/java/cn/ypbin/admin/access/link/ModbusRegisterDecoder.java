/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.link;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Modbus 寄存器**纯词法解码**：把 SDK 交付的 16 位无符号寄存器值按 {@code word_count}/{@code signed}/
 * 字序组合成平台规范值（无 IO、无状态，便于单测钉住 8 组矩阵）。
 *
 * <p>SDK（{@code ModbusSession}）对每个订阅地址交付一个 16 位**无符号大端**寄存器值（{@code Integer}
 * 0..65535）；本类只做算术：</p>
 * <ul>
 *   <li>{@code word_count=1, signed=false} ⇒ **原样返回**（与切换前逐字节一致，向后兼容的锚点）；</li>
 *   <li>{@code word_count=1, signed=true} ⇒ 16 位 two's complement 符号扩展（-32768..32767）；</li>
 *   <li>{@code word_count=2} ⇒ 按字序组合成 32 位（big=高字在前 ABCD / little=低字在前 CDAB）；
 *       无符号用 {@code long} 承载 0..2^32-1（不截断/不溢出），有符号做 32 位符号扩展。</li>
 * </ul>
 *
 * <p>不支持 IEEE754 浮点与 BADC/DCBA 字内字节交换（见 {@code docs/VALUE-DECODE-DESIGN.md} §4.3.6）；
 * v3 起新增多寄存器 UTF-8 字符串（{@link #decodeStringUtf8(int[])}，见 §4.5）。</p>
 *
 * @author wenbin
 * @since 2026-10-13
 */
public final class ModbusRegisterDecoder {

    private ModbusRegisterDecoder() {
    }

    /**
     * 单寄存器（16 位）解码。
     *
     * @param raw    16 位无符号寄存器值（0..65535）
     * @param signed 是否有符号
     * @return 无符号时原样（0..65535）；有符号时 two's complement 扩展（-32768..32767）
     */
    public static int decodeSingle(int raw, boolean signed) {
        return signed ? (short) raw : raw;
    }

    /**
     * 双寄存器（32 位）整型解码。
     *
     * @param first      基址寄存器值（0..65535）
     * @param second     base+1 寄存器值（0..65535）
     * @param byteOrder  字序：{@code little}=低字在前（CDAB）；其它（含 null）=高字在前（ABCD）
     * @param signed     是否有符号
     * @return 无符号时 0..4294967295（long）；有符号时 -2147483648..2147483647（long）
     */
    public static long decodePair(int first, int second, String byteOrder, boolean signed) {
        long raw32 = assembleRaw32(first, second, byteOrder);
        // 有符号：截到 32 位再按 int 符号扩展（(int) 截断后赋值给 long 会保留符号）
        return signed ? (int) raw32 : raw32;
    }

    /**
     * IEEE754 单精度浮点（32 位 ⟺ 2 寄存器）解码。
     *
     * <p>先按字序组装 32 位，再 {@link Float#intBitsToFloat(int)}。NaN/±Inf 的处置**不在这里**：
     * 调用方按 {@code docs/VALUE-DECODE-DESIGN.md} §4.4.6 判 BAD + 计数（本方法只如实返回位模式对应的 float）。</p>
     *
     * @param first     基址寄存器值（0..65535）
     * @param second    base+1 寄存器值（0..65535）
     * @param byteOrder 字序：{@code little}=低字在前；其它（含 null）=高字在前
     * @return 该 32 位位模式对应的单精度浮点
     */
    public static float decodeFloatBits(int first, int second, String byteOrder) {
        return Float.intBitsToFloat((int) assembleRaw32(first, second, byteOrder));
    }

    /**
     * 单寄存器位域（无符号）解码。
     *
     * @param raw       16 位无符号寄存器值（0..65535）
     * @param bitOffset 起始位（0..15）
     * @param bitLength 位宽（1..16-bitOffset）
     * @return {@code bitLength=1} 返回 {@link Boolean}（true/false）；{@code >1} 返回无符号 {@link Integer}（0..65535）
     * @throws IllegalArgumentException bit 区间越界
     */
    public static Object decodeBits(int raw, int bitOffset, int bitLength) {
        if (bitOffset < 0 || bitOffset > 15 || bitLength < 1 || bitOffset + bitLength > 16) {
            throw new IllegalArgumentException("bit 区间非法：offset=" + bitOffset + " length=" + bitLength);
        }
        long field = ((raw & 0xFFFFL) >>> bitOffset) & ((1L << bitLength) - 1);
        return bitLength == 1 ? Boolean.valueOf(field != 0) : Integer.valueOf((int) field);
    }

    /**
     * 多寄存器 UTF-8 字符串解码（v3，{@code register_format=string}）。
     *
     * <p><b>口径（冻结，见 docs/VALUE-DECODE-DESIGN.md §4.5.2）</b>：</p>
     * <ol>
     *   <li>按寄存器顺序、每寄存器**高字节在前**（Modbus 本体序）拼字节：
     *       {@code bytes[2i]=(word>>8)&0xFF}、{@code bytes[2i+1]=word&0xFF}；</li>
     *   <li>去掉**尾部连续 0x00**（设备常用 NUL 补齐定长字段）；不去空格、中间 0x00 保留；</li>
     *   <li>严格 UTF-8（{@link CodingErrorAction#REPORT}）：非法字节序列抛
     *       {@link CharacterCodingException}，调用方据此判点位 BAD（不替换字符、不静默）。</li>
     * </ol>
     *
     * <p>全 NUL ⇒ 去尾后为空字节串 ⇒ 返回空字符串（合法，调用方按 GOOD 上报；「未填」的显示歧义见文档）。
     * {@code byte_order} 对字符串**不参与**（本方法不接收该参数）。</p>
     *
     * @param words 16 位无符号寄存器值（按 word_index 升序；长度 = word_count）
     * @return 严格 UTF-8 解码结果（可为空串）
     * @throws CharacterCodingException 字节串不是合法 UTF-8
     */
    public static String decodeStringUtf8(int[] words) throws CharacterCodingException {
        byte[] bytes = new byte[words.length * 2];
        for (int i = 0; i < words.length; i++) {
            bytes[i * 2] = (byte) ((words[i] >> 8) & 0xFF);
            bytes[i * 2 + 1] = (byte) (words[i] & 0xFF);
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString();
    }

    /** 按字序组装 32 位原始字（big=高字在前 / little=低字在前）。 */
    private static long assembleRaw32(int first, int second, String byteOrder) {
        long high = first & 0xFFFFL;
        long low = second & 0xFFFFL;
        return "little".equalsIgnoreCase(byteOrder) ? ((low << 16) | high) : ((high << 16) | low);
    }
}
