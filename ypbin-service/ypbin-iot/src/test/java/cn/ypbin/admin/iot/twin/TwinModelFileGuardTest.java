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

import cn.ypbin.admin.iot.service.impl.TwinModelFileGuard;
import cn.ypbin.starter.core.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型文件内容校验（**按内容校验而不是按扩展名**）。
 *
 * <p>核心用例：把非模型内容改成 {@code .glb} 扩展名后必须被拒——这是「上传通道只能传模型」
 * 的唯一保证。只校验扩展名等于没有校验。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinModelFileGuardTest {

    private final TwinModelFileGuard guard = new TwinModelFileGuard(new ObjectMapper());

    @Test
    @DisplayName("扩展名：.glb/.gltf 通过，其它拒绝")
    void requireFormat() {
        assertThat(guard.requireFormat("a.glb")).isEqualTo("glb");
        assertThat(guard.requireFormat("a.GLB")).isEqualTo("glb");
        assertThat(guard.requireFormat("a.gltf")).isEqualTo("gltf");
        assertThatThrownBy(() -> guard.requireFormat("a.obj"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(".glb")
            .hasMessageContaining(".gltf");
        assertThatThrownBy(() -> guard.requireFormat("noext"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.requireFormat(null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("合法 GLB 头（魔数 glTF + version 2）通过，并解析出声明长度")
    void validGlb() throws IOException {
        byte[] glb = glbHeader(2, 1024);
        TwinModelFileGuard.ProbeResult result = guard.probe("glb", new ByteArrayInputStream(glb));
        assertThat(result.declaredLength()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("★ 伪装成 .glb 的非模型内容被拒（魔数不匹配）")
    void fakeGlbRejected() {
        byte[] fake = "<html><body>not a model</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(fake)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("魔数不匹配")
            .hasMessageContaining(".gltf");
    }

    @Test
    @DisplayName("GLB 版本不是 2 被拒并提示重新导出")
    void wrongGlbVersionRejected() {
        byte[] glb = glbHeader(1, 512);
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(glb)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的 glTF 版本")
            .hasMessageContaining("1");
    }

    @Test
    @DisplayName("GLB 内容不足 12 字节被拒")
    void truncatedGlbRejected() {
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(new byte[] {1, 2, 3})))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不足");
    }

    @Test
    @DisplayName("GLB 声明总长为 0 被拒（文件损坏）")
    void zeroLengthGlbRejected() {
        byte[] glb = glbHeader(2, 0);
        assertThatThrownBy(() -> guard.probe("glb", new ByteArrayInputStream(glb)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("损坏");
    }

    @Test
    @DisplayName("合法 .gltf（顶层 asset.version=2.0）通过")
    void validGltf() throws IOException {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scenes\":[]}";
        assertThat(guard.probe("gltf",
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).declaredLength())
            .isEqualTo(-1L);
    }

    @Test
    @DisplayName("缺 asset 对象或版本不是 2.x 的 .gltf 被拒")
    void invalidGltfRejected() {
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"scenes\":[]}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("asset");
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"asset\":{\"version\":\"1.0\"}}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的 glTF 版本");
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"asset\":{}}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未声明");
        assertThatThrownBy(() -> guard.probe("gltf", stream("{\"asset\":{\"version\":null}}")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未声明");
    }

    @Test
    @DisplayName("空 .gltf 被拒")
    void emptyGltfRejected() {
        assertThatThrownBy(() -> guard.probe("gltf", new ByteArrayInputStream(new byte[0])))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("为空");
    }

    @Test
    @DisplayName("非 JSON 的 .gltf 被拒，并提示「若是 GLB 请改扩展名」")
    void nonJsonGltfRejected() {
        assertThatThrownBy(() -> guard.probe("gltf",
            new ByteArrayInputStream(new byte[] {(byte) 0x67, (byte) 0x6C, (byte) 0x54, (byte) 0x46})))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(".glb");
    }

    @Test
    @DisplayName("节点名快照：去空、去重、超上限截断")
    void normalizeNodeNames() {
        assertThat(guard.normalizeNodeNames(null)).isEmpty();
        assertThat(guard.normalizeNodeNames(List.of())).isEmpty();
        assertThat(guard.normalizeNodeNames(List.of("A", " ", "A", "B")))
            .containsExactly("A", "B");
        assertThat(guard.normalizeNodeNames(List.of("  X  "))).containsExactly("X");
    }

    @Test
    @DisplayName("节点名快照超上限时截断到上限")
    void normalizeNodeNamesTruncates() {
        List<String> many = new ArrayList<>();
        for (int index = 0; index < TwinLimits.MAX_NODE_NAME_SNAPSHOT + 10; index++) {
            many.add("node-" + index);
        }
        assertThat(guard.normalizeNodeNames(many)).hasSize(TwinLimits.MAX_NODE_NAME_SNAPSHOT);
    }

    @Test
    @DisplayName("扩展名提取：无点/尾点/大写")
    void extensionExtraction() {
        assertThat(TwinModelFileGuard.extension("a.glb")).isEqualTo("glb");
        assertThat(TwinModelFileGuard.extension("a.GLB")).isEqualTo("glb");
        assertThat(TwinModelFileGuard.extension("nodot")).isEmpty();
        assertThat(TwinModelFileGuard.extension("trailing.")).isEmpty();
        assertThat(TwinModelFileGuard.extension(null)).isEmpty();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造 GLB 头部（12 字节）。
     *
     * @param version 版本号
     * @param length  声明总长
     * @return 12 字节头
     */
    private static byte[] glbHeader(int version, int length) {
        byte[] buf = new byte[12];
        writeIntLe(buf, 0, TwinLimits.GLB_MAGIC);
        writeIntLe(buf, 4, version);
        writeIntLe(buf, 8, length);
        return buf;
    }

    private static void writeIntLe(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    @Test
    @DisplayName("★ 合法的大 .gltf（asset 在 64KB 之后）必须被接受")
    void acceptsLargeGltfWithAssetBeyondOldProbeWindow() throws IOException {
        // 这是真实缺陷的回归用例（独立复核实测发现）：
        // 旧实现只读前 64KB 就当完整 JSON 解析 ⇒ asset 位于 64KB 之后的合法文件被拒。
        // 构造一份合法 glTF：先用一个超长字符串字段把 asset 挤到 64KB 之后。
        StringBuilder json = new StringBuilder("{\"scenes\":[],\"assetPadding\":\"");
        // 70KB 填充（保证 asset 起始偏移 > 64KB）
        json.append("x".repeat(70 * 1024));
        json.append("\",\"asset\":{\"version\":\"2.0\"}}");
        byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);

        // 前置断言：确认这份文件的 asset 确实在 64KB 之后（否则用例失去意义）
        assertThat(content.length).isGreaterThan(64 * 1024);
        assertThat(new String(content, StandardCharsets.UTF_8).indexOf("\"asset\""))
            .isGreaterThan(64 * 1024);

        TwinModelFileGuard.ProbeResult result = guard.probe("gltf",
            new ByteArrayInputStream(content));

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("纯二进制内容被当作 .gltf 上传时给出可理解的拒绝")
    void rejectsBinaryDisguisedAsGltf() throws IOException {
        // 随机二进制：流式 JSON 解析会失败，但错误信息要指向「扩展名可能写错」
        byte[] binary = new byte[256];
        for (int index = 0; index < binary.length; index++) {
            binary[index] = (byte) (index % 256);
        }
        assertThatThrownBy(() -> guard.probe("gltf", new ByteArrayInputStream(binary)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不是有效的 glTF");
    }
}
