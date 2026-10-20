/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.twin;

import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import java.math.BigDecimal;

/**
 * 场景锚点变换的**校验口径**（纯函数，零 Spring 依赖，可单测）。
 *
 * <p>为什么单独成类：锚点变换是「子场景如何嵌进父场景」的唯一数学定义，前端
 * {@code scene-manager} 的合成顺序必须与这里**逐字一致**，否则页面显示的位置与用户配置的
 * 位置会系统性偏移（而且只在多级嵌套时才暴露，最难排查）。抽成纯函数后，后端单测可以钉死
 * 「合成顺序 = T × Rz × Ry × Rx × S」，前端用同一组数字对照。</p>
 *
 * <p><b>合成顺序为什么是 Z→Y→X（内旋）</b>：three.js 的 {@code Object3D.rotation}
 * 默认欧拉序是 {@code 'XYZ'}，语义即「先绕 X、再绕 Y、最后绕 Z 的**内旋**」。用
 * {@code object.rotation.set(rx, ry, rz)} + {@code object.position.set(...)} + {@code scale}
 * 时，three.js 内部合成结果等价于 {@code T × Rz × Ry × Rx × S}。这里显式写出该结论，
 * 是为了让前端**不要**手写矩阵（手写极易把顺序搞反，且无法与后端单测对照）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public final class TwinSceneTransform {

    private TwinSceneTransform() {
    }

    /**
     * 校验缩放系数（必须严格大于 0）。
     *
     * <p>不允许 0 的原因：缩放为 0 会让整棵子树退化成一个点，模型「消失」但配置看起来正常，
     * 用户会怀疑是渲染 bug。这里直接拒绝，并说明是缩放系数问题。</p>
     *
     * @param anchorScale 缩放系数（可空，空视为 1）
     * @return 归一化后的缩放系数（永不为 null）
     */
    public static BigDecimal requireScale(BigDecimal anchorScale) {
        if (anchorScale == null) {
            return BigDecimal.ONE;
        }
        if (anchorScale.compareTo(BigDecimal.valueOf(TwinLimits.MIN_ANCHOR_SCALE)) < 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "锚点缩放系数必须大于 0（当前值：" + anchorScale.toPlainString()
                    + "）。缩放为 0 或负数会让该场景及其全部子场景在画布上消失");
        }
        return anchorScale;
    }

    /**
     * 空值归零（平移与旋转列在库里是 NOT NULL DEFAULT 0，但请求里允许不传）。
     *
     * @param value 原值
     * @return 非空值
     */
    public static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
