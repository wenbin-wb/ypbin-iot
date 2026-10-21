/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import cn.ypbin.admin.iot.service.IotTwinModelAssetService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 数字孪生模型资产接口。
 *
 * <p><b>模型下载为什么返回 {@code ResponseEntity} 而不是 {@code R}</b>：它是二进制文件流，
 * 响应体必须是模型字节而不是 JSON 信封（three.js 的 {@code GLTFLoader} 直接吃这个流）。
 * 这是「HTTP 200 + {@code R.code}」惯例的最小例外，与既有的 CSV 下载端点同款处置
 * （{@code DeviceImportController} 的 template/failed.csv 两处）。</p>
 *
 * <p><b>为什么上传通道自建而不复用 {@code /system/file/upload}</b>：见
 * {@code IotTwinModelAssetServiceImpl} 的类注释（平台访问限制 + 存储平台此前未配置）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@RestController
@RequestMapping("/twin/model-assets")
@RequiredArgsConstructor
public class IotTwinModelController {

    private static final Logger log = LoggerFactory.getLogger(IotTwinModelController.class);

    /** 下载时的缓存时长（秒）：同一模型反复进出场景不重复拉取。 */
    /**
     * 允许回给浏览器的模型 MIME 白名单（工单 #310）。
     *
     * <p>只放 glTF 规范的两种标准类型与通用二进制；其余（尤其 {@code text/html}）
     * 一律降级为 octet-stream，避免「客户端自报 Content-Type」被浏览器采信。</p>
     */
    private static final Set<String> MODEL_CONTENT_TYPES = Set.of(
        "model/gltf-binary", "model/gltf+json", "application/octet-stream");

    private static final long CACHE_MAX_AGE_SECONDS = 3600L;

    /** 下载文件名兜底（名称为空时用它，避免 Content-Disposition 出现空文件名）。 */
    private static final String FALLBACK_FILE_NAME = "model";

    private final IotTwinModelAssetService twinModelAssetService;

    /**
     * 查询全部模型资产。
     *
     * @return 资产列表（无数据为空数组）
     */
    @GetMapping
    @SaCheckPermission("iot:twin:list")
    public R<List<IotTwinModelAssetResp>> list() {
        return R.ok(twinModelAssetService.listAssets());
    }

    /**
     * 上传模型文件。
     *
     * @param file      模型文件（multipart 字段 {@code file}）
     * @param assetName 模型名称（可空，空则用文件名去扩展名）
     * @param nodeNames 模型节点名快照（可空，页面解析模型后上报）
     * @return 新资产
     * @throws IOException 读取上传流失败
     */
    @PostMapping("/upload")
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("上传数字孪生模型")
    public R<IotTwinModelAssetResp> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "assetName", required = false) String assetName,
        @RequestParam(value = "nodeNames", required = false) List<String> nodeNames)
        throws IOException {
        return R.ok(twinModelAssetService.upload(file, assetName, nodeNames));
    }

    /**
     * 登记外链模型。
     *
     * @param req 外链信息
     * @return 新资产
     */
    @PostMapping("/external")
    @SaCheckPermission("iot:twin:create")
    @Idempotent
    @Log("登记数字孪生外链模型")
    public R<IotTwinModelAssetResp> createExternal(@Valid @RequestBody IotTwinModelExternalReq req) {
        return R.ok(twinModelAssetService.createExternal(req));
    }

    /**
     * 下载（流式）平台内上传的模型文件。
     *
     * <p>支持 {@code If-None-Match}：与 {@code contentHash} 相同的 ETag 返回 304，
     * 切场景时不重复下载几十 MB 的模型。</p>
     *
     * @param id          资产主键
     * @param ifNoneMatch 客户端缓存校验头（可空）
     * @return 模型字节流（或 304）
     */
    @GetMapping("/{id}/content")
    @SaCheckPermission("iot:twin:list")
    public ResponseEntity<Resource> content(
        @PathVariable Long id,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        IotTwinModelAssetService.ModelContent content = twinModelAssetService.openContent(id);
        String etag = content.contentHash() == null
            ? null : "\"" + content.contentHash() + "\"";
        if (etag != null && etag.equals(ifNoneMatch)) {
            closeQuietly(content.stream(), id);
            ResponseEntity.BodyBuilder notModified = ResponseEntity.status(HttpStatus.NOT_MODIFIED);
            if (content.contentHash() != null) {
                notModified.eTag(content.contentHash());
            }
            return notModified.build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .contentType(safeContentType(content.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename*=UTF-8''" + urlEncode(content.fileName()))
            // 纵深防御（工单 #310）：Content-Disposition: inline + 缺 nosniff 时，
            // 老版浏览器/部分爬虫会按**内容嗅探** MIME。contentType 来自上传时客户端
            // 自报的值，若不 nosniff，一个「内容是 HTML」的文件可能被当 HTML 执行。
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + CACHE_MAX_AGE_SECONDS);
        if (content.size() > 0) {
            builder.contentLength(content.size());
        }
        if (content.contentHash() != null) {
            builder.eTag(content.contentHash());
        }
        return builder.body(new InputStreamResource(content.stream()));
    }

    /**
     * 删除模型资产（被场景引用时拒绝）。
     *
     * @param id 资产主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:twin:delete")
    @Idempotent
    @Log("删除数字孪生模型")
    public R<Void> remove(@PathVariable Long id) {
        twinModelAssetService.remove(id);
        return R.ok();
    }

    /**
     * 文件名按 RFC 5987 编码（中文文件名直接放 {@code filename=} 会因浏览器编码差异乱码）。
     *
     * @param fileName 原始文件名
     * @return 百分号编码后的文件名
     */
    private static String urlEncode(String fileName) {
        String name = fileName == null || fileName.isBlank() ? FALLBACK_FILE_NAME : fileName;
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 关闭流并记录失败（304 分支复用；关闭失败不改变响应语义，但必须留痕）。
     *
     * @param stream 内容流
     * @param id     资产主键（仅用于定位）
     */
    private static void closeQuietly(InputStream stream, Long id) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ex) {
            log.warn("[iot] 关闭模型内容流失败：assetId={}", id, ex);
        }
    }

    /**
     * 把上传时记录的 contentType **收敛到白名单**后再回给浏览器。
     *
     * <p>为什么不能原样回显（工单 #310）：`Content-Type` 是**客户端自报**的
     * （`MultipartFile#getContentType`），原样回显等于让上传者决定响应头。
     * 收敛到 3D 模型应有的类型后，即使有人上传「内容是 HTML」的文件，
     * 浏览器也只会把它当模型/二进制处理（配合 `nosniff` 双保险）。</p>
     *
     * @param stored 上传时记录的 contentType（可能为空或任意值）
     * @return 白名单内的 MediaType；不在白名单内一律降级为 octet-stream
     */
    private MediaType safeContentType(String stored) {
        if (stored == null || stored.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String normalized = stored.trim().toLowerCase(Locale.ROOT);
        // 只允许 glTF 的两种标准 MIME 与通用二进制
        if (MODEL_CONTENT_TYPES.contains(normalized)) {
            return MediaType.parseMediaType(normalized);
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
