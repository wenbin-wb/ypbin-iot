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
 * <p>不支持 IEEE754 浮点与 BADC/DCBA 字内字节交换（见 {@code docs/VALUE-DECODE-DESIGN.md} §4.3.6）。</p>
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

    /** 按字序组装 32 位原始字（big=高字在前 / little=低字在前）。 */
    private static long assembleRaw32(int first, int second, String byteOrder) {
        long high = first & 0xFFFFL;
        long low = second & 0xFFFFL;
        return "little".equalsIgnoreCase(byteOrder) ? ((low << 16) | high) : ((high << 16) | low);
    }
}
