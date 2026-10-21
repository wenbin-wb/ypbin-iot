/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import cn.ypbin.admin.iot.service.IotTwinModelAssetService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.storage.core.FileStorageService;
import cn.ypbin.starter.storage.model.FileInfo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型资产服务：上传校验链、外链 scheme、删除保护、内容下载。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class IotTwinModelAssetServiceImplTest {

    private static final long ASSET_ID = 5_200_001L;

    private final IotTwinModelAssetMapper assetMapper = mock(IotTwinModelAssetMapper.class);

    private final IotTwinSceneMapper sceneMapper = mock(IotTwinSceneMapper.class);

    /**
     * 存储门面用深层 stub：{@code upload} 返回链式 builder
     * （{@code .platform().path().contentType().size().execute()}），普通 mock 无法表达整条链。
     */
    private final FileStorageService fileStorageService =
        mock(FileStorageService.class, RETURNS_DEEP_STUBS);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IotTwinModelAssetServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinModelAsset.class);
        TableInfoHelper.initTableInfo(assistant, IotTwinScene.class);
    }

    @BeforeEach
    void setUp() {
        service = new IotTwinModelAssetServiceImpl(sceneMapper, fileStorageService,
            new TwinModelFileGuard(objectMapper), objectMapper);
        ReflectionTestUtils.setField(service, "baseMapper", assetMapper);
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(sceneMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("上传：空文件 ⇒ 拒绝")
    void emptyFileRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.glb", "model/gltf-binary",
            new byte[0]);
        assertThatThrownBy(() -> service.upload(file, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("上传文件为空");
    }

    @Test
    @DisplayName("上传：null 文件 ⇒ 拒绝")
    void nullFileRejected() {
        assertThatThrownBy(() -> service.upload(null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("上传文件为空");
    }

    @Test
    @DisplayName("★ 上传：扩展名非法 ⇒ 拒绝（最便宜的校验先做）")
    void invalidExtensionRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.obj", "text/plain",
            new byte[] {1, 2, 3});
        assertThatThrownBy(() -> service.upload(file, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(".glb");
        verify(fileStorageService, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("★ 上传：伪装成 .glb 的内容 ⇒ 拒绝（魔数校验）")
    void fakeGlbRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.glb", "model/gltf-binary",
            "<html>evil</html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.upload(file, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("魔数不匹配");
        verify(fileStorageService, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("★ 上传：合法 GLB ⇒ 落存储 + 落库，返回 contentUrl 与摘要")
    void validGlbUploaded() throws IOException {
        byte[] glb = glbBytes();
        MockMultipartFile file = new MockMultipartFile("file", "plant.glb", "model/gltf-binary", glb);
        FileInfo info = new FileInfo();
        info.setPlatform("twin");
        info.setBucket("");
        info.setPath("models/2026/10/21/abc.glb");
        when(fileStorageService.upload(any(), anyString()).platform(anyString())
            .path(anyString()).contentType(any()).size(anyLong()).execute()).thenReturn(info);
        when(assetMapper.insert(any(IotTwinModelAsset.class))).thenReturn(1);
        when(assetMapper.countSceneReferences(any())).thenReturn(List.of());

        IotTwinModelAssetResp resp = service.upload(file, "厂房模型", List.of("A", "B"));

        assertThat(resp.getAssetName()).isEqualTo("厂房模型");
        assertThat(resp.getAssetType()).isEqualTo("upload");
        assertThat(resp.getModelFormat()).isEqualTo("glb");
        assertThat(resp.getContentUrl()).isEqualTo("/iot/twin/model-assets/null/content");
        assertThat(resp.getContentHash()).isNotBlank();
        assertThat(resp.getNodeNames()).containsExactly("A", "B");
        verify(assetMapper).insert(any(IotTwinModelAsset.class));
    }

    @Test
    @DisplayName("★ 上传：落库失败 ⇒ 补偿删除物理文件")
    void uploadCompensatesStorageOnDbFailure() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "plant.glb", "model/gltf-binary",
            glbBytes());
        FileInfo info = new FileInfo();
        info.setPlatform("twin");
        info.setBucket("");
        info.setPath("models/abc.glb");
        when(fileStorageService.upload(any(), anyString()).platform(anyString())
            .path(anyString()).contentType(any()).size(anyLong()).execute()).thenReturn(info);
        when(assetMapper.insert(any(IotTwinModelAsset.class)))
            .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.upload(file, "厂房模型", null))
            .isInstanceOf(IllegalStateException.class);
        verify(fileStorageService).delete("twin", "", "models/abc.glb");
    }

    @Test
    @DisplayName("上传：模型名称重复 ⇒ 拒绝")
    void duplicateAssetNameRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.glb", "model/gltf-binary",
            glbBytes());
        when(assetMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.upload(file, "重名", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型名称已存在");
    }

    @Test
    @DisplayName("上传：文件名去扩展名作为默认模型名")
    void assetNameDefaultsToFileName() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "plant.glb", "model/gltf-binary",
            glbBytes());
        FileInfo info = new FileInfo();
        info.setPlatform("twin");
        info.setBucket("");
        info.setPath("models/abc.glb");
        when(fileStorageService.upload(any(), anyString()).platform(anyString())
            .path(anyString()).contentType(any()).size(anyLong()).execute()).thenReturn(info);
        when(assetMapper.countSceneReferences(any())).thenReturn(List.of());

        IotTwinModelAssetResp resp = service.upload(file, null, null);

        assertThat(resp.getAssetName()).isEqualTo("plant");
    }

    @Test
    @DisplayName("★ 上传：超过 64MB ⇒ 拒绝并提示压缩")
    void oversizedUploadRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "a.glb", "model/gltf-binary",
            glbBytes()) {
            @Override
            public long getSize() {
                return TwinLimits.MAX_MODEL_BYTES + 1;
            }
        };
        assertThatThrownBy(() -> service.upload(file, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型文件过大")
            .hasMessageContaining("压缩");
    }

    @Test
    @DisplayName("★ 外链登记：非 http/https scheme ⇒ 拒绝（javascript:/data:/file:）")
    void invalidExternalSchemeRejected() {
        for (String url : List.of("javascript:alert(1)", "data:text/html,xx", "file:///etc/passwd",
            "ftp://a/b.glb")) {
            IotTwinModelExternalReq req = new IotTwinModelExternalReq();
            req.setAssetName("x");
            req.setExternalUrl(url);
            assertThatThrownBy(() -> service.createExternal(req))
                .as("scheme 必须被拒：" + url)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许 http/https");
        }
    }

    @Test
    @DisplayName("外链登记：缺主机名 ⇒ 拒绝")
    void externalWithoutHostRejected() {
        IotTwinModelExternalReq req = new IotTwinModelExternalReq();
        req.setAssetName("x");
        req.setExternalUrl("http:///path/a.glb");
        assertThatThrownBy(() -> service.createExternal(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("缺少主机名");
    }

    @Test
    @DisplayName("外链登记：格式非法 ⇒ 拒绝")
    void malformedExternalRejected() {
        IotTwinModelExternalReq req = new IotTwinModelExternalReq();
        req.setAssetName("x");
        req.setExternalUrl("http://a b/c.glb");
        assertThatThrownBy(() -> service.createExternal(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("格式不合法");
    }

    @Test
    @DisplayName("外链登记：空地址 ⇒ 拒绝")
    void emptyExternalRejected() {
        IotTwinModelExternalReq req = new IotTwinModelExternalReq();
        req.setAssetName("x");
        req.setExternalUrl("   ");
        assertThatThrownBy(() -> service.createExternal(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("★ 外链登记：合法 https ⇒ 成功，contentUrl 用外链原文")
    void validExternalCreated() {
        IotTwinModelExternalReq req = new IotTwinModelExternalReq();
        req.setAssetName("外链模型");
        req.setExternalUrl("https://cdn.example.com/models/plant.glb");
        when(assetMapper.countSceneReferences(any())).thenReturn(List.of());

        IotTwinModelAssetResp resp = service.createExternal(req);

        assertThat(resp.getAssetType()).isEqualTo("external");
        assertThat(resp.getModelFormat()).isEqualTo("glb");
        assertThat(resp.getContentUrl()).isEqualTo("https://cdn.example.com/models/plant.glb");
    }

    @Test
    @DisplayName("外链登记：显式 modelFormat 优先于 URL 推断")
    void explicitModelFormatWins() {
        IotTwinModelExternalReq req = new IotTwinModelExternalReq();
        req.setAssetName("x");
        req.setModelFormat("gltf");
        req.setExternalUrl("https://cdn.example.com/model?token=abc");
        when(assetMapper.countSceneReferences(any())).thenReturn(List.of());

        IotTwinModelAssetResp resp = service.createExternal(req);

        assertThat(resp.getModelFormat()).isEqualTo("gltf");
    }

    @Test
    @DisplayName("★ 删除：被场景引用 ⇒ 拒绝并说明引用数")
    void deleteReferencedAssetRejected() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset("upload"));
        when(sceneMapper.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.remove(ASSET_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已被 2 个场景引用");
        verify(assetMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("★ 删除：物理文件删不掉 ⇒ 保留记录并如实报错（不留孤儿且不静默）")
    void deleteStorageFailureKeepsRecord() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset("upload"));
        when(sceneMapper.selectCount(any())).thenReturn(0L);
        doThrow(new IllegalStateException("storage down"))
            .when(fileStorageService).delete(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.remove(ASSET_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型文件删除失败")
            .hasMessageContaining("记录已保留");
        verify(assetMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除：上传型资产 ⇒ 删文件 + 删记录")
    void deleteUploadAsset() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset("upload"));
        when(sceneMapper.selectCount(any())).thenReturn(0L);

        service.remove(ASSET_ID);

        verify(fileStorageService).delete("twin", "", "models/a.glb");
        verify(assetMapper).deleteById(ASSET_ID);
    }

    @Test
    @DisplayName("删除：外链型资产 ⇒ 只删记录（无物理文件）")
    void deleteExternalAsset() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset("external"));
        when(sceneMapper.selectCount(any())).thenReturn(0L);

        service.remove(ASSET_ID);

        verify(fileStorageService, never()).delete(anyString(), any(), anyString());
        verify(assetMapper).deleteById(ASSET_ID);
    }

    @Test
    @DisplayName("删除：资产不存在 ⇒ 拒绝")
    void deleteMissingAssetRejected() {
        when(assetMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.remove(ASSET_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型资产不存在");
    }

    @Test
    @DisplayName("★ 下载：外链型资产 ⇒ 拒绝并提示直接用外链")
    void contentOfExternalRejected() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset("external"));
        assertThatThrownBy(() -> service.openContent(ASSET_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("外链登记")
            .hasMessageContaining("没有平台内文件");
    }

    @Test
    @DisplayName("下载：上传型资产 ⇒ 返回流与类型")
    void contentOfUpload() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset("upload"));
        when(fileStorageService.download(anyString(), any(), anyString()))
            .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        IotTwinModelAssetService.ModelContent content = service.openContent(ASSET_ID);

        assertThat(content.contentType()).isEqualTo("model/gltf-binary");
        assertThat(content.size()).isEqualTo(100L);
        assertThat(content.contentHash()).isEqualTo("hash");
        assertThat(content.fileName()).isEqualTo("a.glb");
    }

    @Test
    @DisplayName("下载：缺少存储定位信息 ⇒ 拒绝并提示重新上传")
    void contentWithoutLocatorRejected() {
        IotTwinModelAsset upload = asset("upload");
        upload.setStoragePath(null);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(upload);
        assertThatThrownBy(() -> service.openContent(ASSET_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("缺少存储定位信息");
    }

    @Test
    @DisplayName("列表：空 ⇒ 空列表，不查引用统计")
    void emptyListReturnsEmpty() {
        when(assetMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listAssets()).isEmpty();
        verify(assetMapper, never()).countSceneReferences(any());
    }

    /**
     * 构造一个合法 GLB 字节（12 字节头 + 少量负载）。
     *
     * @return GLB 字节
     */
    private static byte[] glbBytes() {
        byte[] buf = new byte[28];
        writeIntLe(buf, 0, TwinLimits.GLB_MAGIC);
        writeIntLe(buf, 4, TwinLimits.GLTF_VERSION);
        writeIntLe(buf, 8, buf.length);
        return buf;
    }

    private static void writeIntLe(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static IotTwinModelAsset asset(String type) {
        IotTwinModelAsset asset = new IotTwinModelAsset();
        asset.setId(ASSET_ID);
        asset.setAssetName("a");
        asset.setAssetType(type);
        asset.setModelFormat("glb");
        asset.setStoragePlatform("twin");
        asset.setStorageBucket("");
        asset.setStoragePath("models/a.glb");
        asset.setFileSize(100L);
        asset.setContentHash("hash");
        asset.setOriginalName("a.glb");
        return asset;
    }
}
