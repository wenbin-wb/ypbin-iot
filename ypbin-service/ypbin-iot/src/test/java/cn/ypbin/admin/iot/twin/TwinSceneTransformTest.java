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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.admin.iot.enums.TwinAssetType;
import cn.ypbin.admin.iot.enums.TwinDisplayRole;
import cn.ypbin.admin.iot.enums.TwinDisplayType;
import cn.ypbin.admin.iot.enums.TwinSceneType;
import cn.ypbin.starter.core.exception.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数字孪生枚举与锚点变换口径。
 *
 * <p>锚点变换的断言是前端 {@code scene-manager} 的对照基准：前端用 three 的
 * {@code Matrix4.compose + Euler('XYZ')} 合成，本类钉死「校验口径」那一半
 * （缩放 > 0、空值归一），数值合成由前端单测与手工验收保证。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinSceneTransformTest {

    @Test
    @DisplayName("缩放为 null ⇒ 归一为 1（不拒绝）")
    void nullScaleBecomesOne() {
        assertThat(TwinSceneTransform.requireScale(null)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("★ 缩放 ≤ 0 ⇒ 拒绝，且错误信息说明后果")
    void nonPositiveScaleRejected() {
        assertThatThrownBy(() -> TwinSceneTransform.requireScale(BigDecimal.ZERO))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须大于 0")
            .hasMessageContaining("消失");
        assertThatThrownBy(() -> TwinSceneTransform.requireScale(new BigDecimal("-1")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须大于 0");
    }

    @Test
    @DisplayName("缩放 > 0 原样返回（含极小正值）")
    void positiveScaleKept() {
        BigDecimal tiny = new BigDecimal("0.000001");
        assertThat(TwinSceneTransform.requireScale(tiny)).isEqualByComparingTo(tiny);
        assertThat(TwinSceneTransform.requireScale(new BigDecimal("2.5")))
            .isEqualByComparingTo(new BigDecimal("2.5"));
    }

    @Test
    @DisplayName("平移/旋转 null ⇒ 归零")
    void nullBecomesZero() {
        assertThat(TwinSceneTransform.zeroIfNull(null)).isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal value = new BigDecimal("3.2");
        assertThat(TwinSceneTransform.zeroIfNull(value)).isEqualByComparingTo(value);
    }

    @Test
    @DisplayName("场景类型：四个合法码 + 未知码返回 null")
    void sceneTypeLookup() {
        assertThat(TwinSceneType.of("park")).isEqualTo(TwinSceneType.PARK);
        assertThat(TwinSceneType.of("building")).isEqualTo(TwinSceneType.BUILDING);
        assertThat(TwinSceneType.of("floor")).isEqualTo(TwinSceneType.FLOOR);
        assertThat(TwinSceneType.of("room")).isEqualTo(TwinSceneType.ROOM);
        assertThat(TwinSceneType.of("warehouse")).isNull();
        assertThat(TwinSceneType.of(null)).isNull();
        for (TwinSceneType type : TwinSceneType.values()) {
            assertThat(type.getCode()).isNotBlank();
            assertThat(type.getDesc()).isNotBlank();
        }
    }

    @Test
    @DisplayName("模型来源类型：合法码 + 未知码")
    void assetTypeLookup() {
        assertThat(TwinAssetType.of("upload")).isEqualTo(TwinAssetType.UPLOAD);
        assertThat(TwinAssetType.of("external")).isEqualTo(TwinAssetType.EXTERNAL);
        assertThat(TwinAssetType.of("ftp")).isNull();
    }

    @Test
    @DisplayName("展示形态：五个合法码 + 未知码")
    void displayTypeLookup() {
        assertThat(TwinDisplayType.of("value")).isEqualTo(TwinDisplayType.VALUE);
        assertThat(TwinDisplayType.of("gauge")).isEqualTo(TwinDisplayType.GAUGE);
        assertThat(TwinDisplayType.of("switch")).isEqualTo(TwinDisplayType.SWITCH);
        assertThat(TwinDisplayType.of("color")).isEqualTo(TwinDisplayType.COLOR);
        assertThat(TwinDisplayType.of("sparkline")).isEqualTo(TwinDisplayType.SPARKLINE);
        assertThat(TwinDisplayType.of("pie")).isNull();
    }

    @Test
    @DisplayName("展示角色：两个合法码 + 未知码")
    void displayRoleLookup() {
        assertThat(TwinDisplayRole.of("primary")).isEqualTo(TwinDisplayRole.PRIMARY);
        assertThat(TwinDisplayRole.of("secondary")).isEqualTo(TwinDisplayRole.SECONDARY);
        assertThat(TwinDisplayRole.of("tertiary")).isNull();
    }

    @Test
    @DisplayName("上限常量口径（与前端 TWIN_LIMITS 必须一致）")
    void limitsAreSane() {
        assertThat(TwinLimits.MAX_SCENE_DEPTH).isEqualTo(4);
        assertThat(TwinLimits.MAX_BINDINGS_PER_POINT).isEqualTo(12);
        assertThat(TwinLimits.MAX_POINTS_PER_SCENE).isEqualTo(500);
        assertThat(TwinLimits.MAX_MODEL_BYTES).isEqualTo(64L * 1024 * 1024);
        assertThat(TwinLimits.MAX_VIEW_JSON_BYTES).isEqualTo(16 * 1024);
        assertThat(TwinLimits.MAX_DECIMALS).isEqualTo(6);
        assertThat(TwinLimits.GLTF_VERSION).isEqualTo(2);
        assertThat(TwinLimits.GLB_MAGIC).isEqualTo(0x46546C67);
        assertThat(TwinLimits.SCENE_CODE_PATTERN).isEqualTo("^[A-Za-z0-9_-]{1,64}$");
    }
}
