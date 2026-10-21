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

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.enums.TwinAssetType;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.req.IotTwinModelRenameReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import cn.ypbin.admin.iot.service.IotTwinModelAssetService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import cn.ypbin.starter.storage.core.FileStorageService;
import cn.ypbin.starter.storage.model.FileInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 数字孪生模型资产服务实现。
 *
 * <p><b>为什么不复用 {@code /system/file/upload}</b>：该端点所在控制器标了
 * {@code @PlatformAccess}（非平台用户一律 403），租户用户根本传不上来；且它依赖
 * {@code ypbin.storage} 配置，而本仓此前没有任何该配置 ⇒ {@code StorageRouter#route(null)}
 * 会抛「未指定存储平台且未配置默认平台」。孪生的上传通道因此自建并自管
 * （存储配置见 {@code deploy/nacos/ypbin-iot.yaml}）。</p>
 *
 * <p><b>失败补偿</b>：存储写入成功但落库失败时，必须把已写入的物理文件删掉——
 * 否则每次失败都留下一份没人引用的几十 MB 文件，磁盘只增不减。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinModelAssetServiceImpl
    extends BaseServiceImpl<IotTwinModelAssetMapper, IotTwinModelAsset>
    implements IotTwinModelAssetService {

    private static final Logger log = LoggerFactory.getLogger(IotTwinModelAssetServiceImpl.class);

    /** 孪生模型使用的存储平台标识（与 {@code ypbin.storage.local[0].platform} 同值）。 */
    private static final String STORAGE_PLATFORM = "twin";

    /** 存储路径前缀（落在 platform 的 base-path 之下）。 */
    private static final String STORAGE_PATH_PREFIX = "models/";

    /** {@code .glb} 的 MIME 类型（glTF 规范的注册类型）。 */
    private static final String CONTENT_TYPE_GLB = "model/gltf-binary";

    /** {@code .gltf} 的 MIME 类型。 */
    private static final String CONTENT_TYPE_GLTF = "model/gltf+json";

    /** 允许的外链 scheme。 */
    private static final List<String> ALLOWED_URL_SCHEMES = List.of("http", "https");

    /** 模型内容下载端点前缀（页面直接用它拼 contentUrl）。 */
    private static final String CONTENT_URL_PREFIX = "/iot/twin/model-assets/";

    /** 模型内容下载端点后缀。 */
    private static final String CONTENT_URL_SUFFIX = "/content";

    private final IotTwinSceneMapper twinSceneMapper;

    private final FileStorageService fileStorageService;

    private final TwinModelFileGuard fileGuard;

    private final ObjectMapper objectMapper;

    public IotTwinModelAssetServiceImpl(IotTwinSceneMapper twinSceneMapper,
                                        FileStorageService fileStorageService,
                                        TwinModelFileGuard fileGuard,
                                        ObjectMapper objectMapper) {
        this.twinSceneMapper = twinSceneMapper;
        this.fileStorageService = fileStorageService;
        this.fileGuard = fileGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IotTwinModelAssetResp> listAssets() {
        List<IotTwinModelAsset> assets = baseMapper.selectList(
            new LambdaQueryWrapper<IotTwinModelAsset>()
                .orderByDesc(IotTwinModelAsset::getCreateTime)
                .orderByDesc(IotTwinModelAsset::getId));
        return toRespList(assets);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotTwinModelAssetResp upload(MultipartFile file, String assetName, List<String> nodeNames)
        throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        // ① 扩展名（最便宜的校验先做）
        String format = fileGuard.requireFormat(originalName);
        // ② 字节数
        if (file.getSize() > TwinLimits.MAX_MODEL_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型文件过大（" + file.getSize() + " 字节，上限 " + TwinLimits.MAX_MODEL_BYTES
                    + " 字节 = 64MB）。请先用 glTF 压缩工具（Draco/KTX2）压缩后重试");
        }
        String resolvedName = resolveAssetName(assetName, originalName);
        checkAssetNameUnique(resolvedName);
        // ③ 内容头部（魔数 / glTF 版本）
        try (InputStream probeStream = file.getInputStream()) {
            TwinModelFileGuard.ProbeResult probeResult =
                fileGuard.probe(format, probeStream);
            // **消费 probe 的结论**（工单 #310）：`.glb` 头部声明了总长度，
            // 但不与真实文件大小比对就等于「算了不用」。手工构造一个头部声明
            // length=0xFFFFFFFF 的小文件可绕过全部校验落存储，渲染端 three.js 会按声明
            // 长度分配缓冲（GB 量级）⇒ 用户浏览器崩溃。
            // `.gltf` 无法从头部预知长度（declaredLength = -1），跳过该比对。
            if (probeResult.declaredLength() > 0
                && probeResult.declaredLength() != file.getSize()) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "GLB 文件头声明的长度（" + probeResult.declaredLength()
                        + " 字节）与实际文件大小（" + file.getSize()
                        + " 字节）不一致，文件可能已损坏或被截断");
            }
        }
        List<String> normalizedNodeNames = fileGuard.normalizeNodeNames(nodeNames);
        String nodeNamesJson = serializeNodeNames(normalizedNodeNames);
        // ④ 摘要必须在落存储前算（上传流是一次性的）
        String contentHash = sha256(file);

        // ⑤ 落存储
        FileInfo info;
        try (InputStream uploadStream = file.getInputStream()) {
            info = fileStorageService.upload(uploadStream, originalName)
                .platform(STORAGE_PLATFORM)
                .path(STORAGE_PATH_PREFIX)
                .contentType(file.getContentType())
                .size(file.getSize())
                .execute();
        }

        // ⑥ 落库（失败则补偿删除物理文件）
        try {
            IotTwinModelAsset asset = new IotTwinModelAsset();
            asset.setAssetName(resolvedName);
            asset.setAssetType(TwinAssetType.UPLOAD.getCode());
            asset.setModelFormat(format);
            asset.setStoragePlatform(info.getPlatform());
            asset.setStorageBucket(info.getBucket());
            asset.setStoragePath(info.getPath());
            asset.setOriginalName(originalName);
            asset.setFileSize(file.getSize());
            asset.setContentHash(contentHash);
            asset.setNodeNamesJson(nodeNamesJson);
            save(asset);
            return toRespList(List.of(asset)).getFirst();
        } catch (RuntimeException ex) {
            compensateUpload(info, ex);
            throw ex;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotTwinModelAssetResp createExternal(IotTwinModelExternalReq req) {
        String url = req.getExternalUrl() == null ? null : req.getExternalUrl().trim();
        requireHttpUrl(url);
        String format = StringUtils.hasText(req.getModelFormat())
            ? fileGuard.requireFormat("x." + req.getModelFormat().trim().toLowerCase(Locale.ROOT))
            : fileGuard.requireFormat(url);
        String assetName = req.getAssetName().trim();
        checkAssetNameUnique(assetName);

        IotTwinModelAsset asset = new IotTwinModelAsset();
        asset.setAssetName(assetName);
        asset.setAssetType(TwinAssetType.EXTERNAL.getCode());
        asset.setModelFormat(format);
        asset.setExternalUrl(url);
        save(asset);
        return toRespList(List.of(asset)).getFirst();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotTwinModelAssetResp rename(Long id, IotTwinModelRenameReq req) {
        IotTwinModelAsset asset = requireAsset(id);
        String newName = req.getAssetName().trim();
        if (newName.length() > TwinLimits.MAX_ASSET_NAME_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型名称长度不能超过 " + TwinLimits.MAX_ASSET_NAME_LENGTH);
        }
        // **幂等**：改成和原来一样的名字直接返回成功。
        // 为什么不做成报错：前端可能因为重复点击/网络重试再发一次同名请求，
        // 报错会让用户以为「改名失败」，而实际上目标状态已经满足。
        if (newName.equals(asset.getAssetName())) {
            return toRespList(List.of(asset)).getFirst();
        }
        // 前置查重（排除自身）：给出可读文案，而不是把 DuplicateKeyException 抛给用户
        Long sameName = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinModelAsset>()
            .eq(IotTwinModelAsset::getAssetName, newName)
            .ne(IotTwinModelAsset::getId, id));
        if (sameName != null && sameName > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型名称已存在：" + newName + "。请换一个名称（同一租户内模型名必须唯一）");
        }

        // 只改名称：格式/存储路径/内容哈希/节点名快照都描述「文件本身」，改名不该动它们
        IotTwinModelAsset patch = new IotTwinModelAsset();
        patch.setId(asset.getId());
        patch.setAssetName(newName);
        updateById(patch);

        asset.setAssetName(newName);
        return toRespList(List.of(asset)).getFirst();
    }

    @Override
    public ModelContent openContent(Long id) {
        IotTwinModelAsset asset = requireAsset(id);
        if (!TwinAssetType.UPLOAD.getCode().equals(asset.getAssetType())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该模型是外链登记，没有平台内文件：请直接使用登记的外链地址加载");
        }
        if (!StringUtils.hasText(asset.getStoragePlatform())
            || !StringUtils.hasText(asset.getStoragePath())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该模型缺少存储定位信息，无法下载：请删除后重新上传（资产 ID " + asset.getId() + "）");
        }
        InputStream stream = fileStorageService.download(asset.getStoragePlatform(),
            asset.getStorageBucket(), asset.getStoragePath());
        String contentType = TwinLimits.EXT_GLB.equals(asset.getModelFormat())
            ? CONTENT_TYPE_GLB : CONTENT_TYPE_GLTF;
        String fileName = StringUtils.hasText(asset.getOriginalName())
            ? asset.getOriginalName()
            : asset.getAssetName() + "." + asset.getModelFormat();
        return new ModelContent(fileName, contentType,
            asset.getFileSize() == null ? -1L : asset.getFileSize(),
            asset.getContentHash(), stream);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        IotTwinModelAsset asset = requireAsset(id);
        Long refCount = twinSceneMapper.selectCount(new LambdaQueryWrapper<IotTwinScene>()
            .eq(IotTwinScene::getModelAssetId, asset.getId()));
        if (refCount != null && refCount > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该模型已被 " + refCount + " 个场景引用，无法删除：请先在这些场景里换绑模型"
                    + "（删除会留下打不开的场景）");
        }
        if (TwinAssetType.UPLOAD.getCode().equals(asset.getAssetType())
            && StringUtils.hasText(asset.getStoragePath())) {
            try {
                fileStorageService.delete(asset.getStoragePlatform(), asset.getStorageBucket(),
                    asset.getStoragePath());
            } catch (RuntimeException ex) {
                // 物理文件删不掉时**不能**静默删记录：那样文件成为永久孤儿且无人知道。
                // 记录完整堆栈并如实失败，让运维看到并手工清理。
                log.error("[iot] 删除孪生模型物理文件失败：assetId={} platform={} path={}",
                    asset.getId(), asset.getStoragePlatform(), asset.getStoragePath(), ex);
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "模型文件删除失败（存储不可用或路径不存在），资产记录已保留："
                        + "请稍后重试或联系运维清理存储路径 " + asset.getStoragePath());
            }
        }
        removeById(id);
    }

    // ---------- 校验与工具 ----------

    private IotTwinModelAsset requireAsset(Long id) {
        IotTwinModelAsset asset = getById(id);
        if (asset == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "模型资产不存在：" + id);
        }
        return asset;
    }

    private void checkAssetNameUnique(String assetName) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinModelAsset>()
            .eq(IotTwinModelAsset::getAssetName, assetName));
        if (exists != null && exists > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "模型名称已存在：" + assetName);
        }
    }

    private String resolveAssetName(String assetName, String originalName) {
        String candidate = StringUtils.hasText(assetName)
            ? assetName.trim() : stripExtension(originalName);
        if (!StringUtils.hasText(candidate)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "无法确定模型名称：请显式提供 assetName 或使用带文件名的上传");
        }
        if (candidate.length() > TwinLimits.MAX_ASSET_NAME_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型名称长度不能超过 " + TwinLimits.MAX_ASSET_NAME_LENGTH);
        }
        return candidate;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /**
     * 校验外链地址：只允许 http/https 绝对地址。
     *
     * <p>禁止 {@code javascript:}/{@code data:}/{@code file:}：前端会用 {@code fetch} 拉取该
     * 地址，{@code data:} 可以绕过文件大小限制、{@code javascript:} 是明确的注入面。</p>
     *
     * @param url 外链地址
     */
    private void requireHttpUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "外链地址不能为空");
        }
        if (url.length() > TwinLimits.MAX_EXTERNAL_URL_LENGTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址长度不能超过 " + TwinLimits.MAX_EXTERNAL_URL_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址格式不合法：" + ex.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_URL_SCHEMES.contains(scheme)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址只允许 http/https 绝对地址（当前 scheme：" + scheme + "）");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "外链地址缺少主机名：" + url);
        }
    }

    private String serializeNodeNames(List<String> nodeNames) {
        if (nodeNames.isEmpty()) {
            return null;
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(nodeNames);
            if (json.length > TwinLimits.MAX_NODE_NAMES_JSON_BYTES) {
                log.warn("[iot] 模型节点名快照 JSON 过大，已丢弃：bytes={} 上限={}",
                    json.length, TwinLimits.MAX_NODE_NAMES_JSON_BYTES);
                return null;
            }
            return new String(json, StandardCharsets.UTF_8);
        } catch (JacksonException ex) {
            log.warn("[iot] 模型节点名快照序列化失败，已丢弃（不影响模型上传）：count={}",
                nodeNames.size(), ex);
            return null;
        }
    }

    /**
     * 计算上传文件的 SHA-256（用于判重与 ETag）。
     *
     * @param file 上传文件
     * @return 十六进制小写摘要
     * @throws IOException 读取失败
     */
    private String sha256(MultipartFile file) throws IOException {
        try (InputStream stream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必然内置 SHA-256；真缺了说明运行环境异常，必须显式暴露而不是返回 null
            throw new IllegalStateException("运行环境缺少 SHA-256 摘要算法", ex);
        }
    }

    private void compensateUpload(FileInfo info, RuntimeException cause) {
        try {
            fileStorageService.delete(info.getPlatform(), info.getBucket(), info.getPath());
        } catch (RuntimeException cleanupFailure) {
            cleanupFailure.addSuppressed(cause);
            log.error("[iot] 模型上传落库失败且物理文件补偿删除失败（留下孤儿文件，需人工清理）："
                    + "platform={} bucket={} path={}",
                info.getPlatform(), info.getBucket(), info.getPath(), cleanupFailure);
        }
    }

    // ---------- 响应转换 ----------

    private List<IotTwinModelAssetResp> toRespList(List<IotTwinModelAsset> assets) {
        if (assets.isEmpty()) {
            return List.of();
        }
        List<Long> assetIds = assets.stream().map(IotTwinModelAsset::getId).toList();
        Map<Long, Integer> sceneCountById = new HashMap<>();
        for (Map<String, Object> row : baseMapper.countSceneReferences(assetIds)) {
            sceneCountById.put(asLong(row.get("assetId")), (int) asLong(row.get("sceneCount")));
        }
        List<IotTwinModelAssetResp> result = new ArrayList<>(assets.size());
        for (IotTwinModelAsset asset : assets) {
            result.add(toResp(asset, sceneCountById.getOrDefault(asset.getId(), 0)));
        }
        return result;
    }

    private IotTwinModelAssetResp toResp(IotTwinModelAsset asset, int sceneCount) {
        IotTwinModelAssetResp resp = new IotTwinModelAssetResp();
        resp.setId(asset.getId());
        resp.setAssetName(asset.getAssetName());
        resp.setAssetType(asset.getAssetType());
        resp.setModelFormat(asset.getModelFormat());
        resp.setExternalUrl(asset.getExternalUrl());
        resp.setContentUrl(contentUrl(asset));
        resp.setOriginalName(asset.getOriginalName());
        resp.setFileSize(asset.getFileSize());
        resp.setContentHash(asset.getContentHash());
        resp.setNodeNames(parseNodeNames(asset));
        resp.setSceneCount(sceneCount);
        resp.setCreateTime(asset.getCreateTime());
        return resp;
    }

    /**
     * 页面实际使用的模型地址：upload 走平台下载端点（同源 + 鉴权 + 浏览器缓存），
     * external 用外链原文。
     */
    private String contentUrl(IotTwinModelAsset asset) {
        if (TwinAssetType.EXTERNAL.getCode().equals(asset.getAssetType())) {
            return asset.getExternalUrl();
        }
        // 未落库的资产**没有**可下载地址（工单 #311）。
        // 旧实现直接拼字符串 ⇒ `asset.getId()` 为 null 时产出
        // `/iot/twin/model-assets/null/content` 这种坏地址，而单测把这个坏行为
        // 写成了期望值（等于把错误固化成契约）。
        // 这里显式失败：拼出一个必然 404 的 URL 比直接报错更难排查。
        if (asset.getId() == null) {
            throw new IllegalStateException(
                "模型资产尚未落库（id 为空），无法生成下载地址");
        }
        return CONTENT_URL_PREFIX + asset.getId() + CONTENT_URL_SUFFIX;
    }

    private List<String> parseNodeNames(IotTwinModelAsset asset) {
        if (!StringUtils.hasText(asset.getNodeNamesJson())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(asset.getNodeNamesJson(),
                new TypeReference<List<String>>() { });
        } catch (JacksonException ex) {
            // 辅助数据坏掉不影响任何功能：记 WARN 返回空数组，不让资产列表打不开
            log.warn("[iot] 模型节点名快照解析失败，按空列表返回：assetId={}", asset.getId(), ex);
            return List.of();
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            log.warn("[iot] 模型统计值不是数字，按 0 处理：value={}", value);
            return 0L;
        }
    }
}
