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

import cn.ypbin.admin.iot.model.query.DeviceImportRowQuery;
import cn.ypbin.admin.iot.model.resp.DeviceImportBatchDetailResp;
import cn.ypbin.admin.iot.model.resp.DeviceImportBatchResp;
import cn.ypbin.admin.iot.service.DeviceImportService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.log.annotation.Log;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * IoT 设备批量导入（CSV）+ 批次管理接口。
 *
 * <p><b>四个端点 = 用户的四步闭环</b>：下载模板 → 上传 → 看结果 → 拿走失败行。
 * 每一步都有对应的端点，用户不需要读文档也不需要拼凑接口。</p>
 *
 * <p>权限码沿用设备域的既有命名风格：读（模板/批次/明细/失败行）用
 * {@code iot:device:list}，写（上传）用 {@code iot:device:import}（**新造**：批量导入是
 * 比单条创建更高危的动作——一次请求就能创建上万台设备，复用 {@code iot:device:create}
 * 会让「只想让某个角色逐台建」的租户被迫放开批量口子）。且该码在
 * {@code 007-iot-data.sql} 与 migration 中都已登记（{@code IotPermissionCodeGateTest} 守着）。</p>
 *
 * <p>路径是纯资源路径：网关按 {@code Path=/iot/**} + {@code StripPrefix=1} 转发，
 * 客户端调 {@code /iot/devices/import}，服务内看到 {@code /devices/import}。</p>
 *
 * <p><b>下载端点为什么返回 {@code ResponseEntity} 而不是 {@code R<String>}</b>：
 * 这两处是**文件下载**，响应体必须是 CSV 字节而不是 JSON 信封，否则浏览器拿到的是
 * 一段被引号包裹的 JSON（用户点「下载模板」会得到一个打不开的 .csv）。
 * 这是「全局 HTTP 200 + R.code」惯例的**必要例外**，且只在这两个下载端点上——
 * 其余端点一律沿用 {@code R}。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
@RestController
@RequestMapping("/devices/import")
@RequiredArgsConstructor
public class DeviceImportController {

    /** 模板下载的固定文件名（用户拿到就知道这是什么）。 */
    private static final String TEMPLATE_FILE_NAME = "device-import-template.csv";

    /** 失败行下载的文件名前缀（后面拼批次 id，同一个用户下载多次不会互相覆盖）。 */
    private static final String FAILED_FILE_NAME_PREFIX = "device-import-failed-";

    /** CSV 的 MIME 类型（带 charset：不带时浏览器/Excel 可能按本机编码解读）。 */
    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";

    private final DeviceImportService deviceImportService;

    /**
     * 下载 CSV 模板（表头 + 说明行 + 示例行）。
     *
     * @return CSV 文件
     */
    @GetMapping("/template")
    @SaCheckPermission("iot:device:list")
    public ResponseEntity<byte[]> template() {
        return csvResponse(TEMPLATE_FILE_NAME, deviceImportService.buildTemplateCsv());
    }

    /**
     * 上传 CSV 并执行批量导入。
     *
     * <p><b>返回 HTTP 200 + 批次信息</b>（不是 4xx）：上传成功与被整批拒绝都可能发生，
     * 而两者在用户那里都是「我传了一个文件」，页面都需要把结果作为**批次记录**展示出来。
     * 用 4xx 会让前端只能弹一个 toast，用户在批次列表里看不到「我传过但被拒了」的痕迹
     * （对标产品的批次管理页签也正是这么做的）。</p>
     *
     * @param file CSV 文件（multipart 字段名 {@code file}）
     * @return 批次信息
     * @throws IOException 读取上传流失败
     */
    @PostMapping
    @SaCheckPermission("iot:device:import")
    @Log("批量导入 IoT 设备（CSV）")
    public R<DeviceImportBatchResp> upload(@RequestParam("file") MultipartFile file)
        throws IOException {
        // 文件名原样带下去：多文件上传后用户要靠它认出「哪一批是哪个文件」
        return R.ok(deviceImportService.importCsv(file.getOriginalFilename(), file.getBytes()));
    }

    /**
     * 分页查询本租户的导入批次（最近在前）。
     *
     * @param page     页码
     * @param pageSize 每页条数
     * @return 批次分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:device:list")
    public R<PageResult<DeviceImportBatchResp>> pageBatches(
        @RequestParam(value = "page", defaultValue = "1") long page,
        @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return R.ok(deviceImportService.pageBatches(page, pageSize));
    }

    /**
     * 查询批次详情（批次头 + 分页明细，可按结果筛选）。
     *
     * @param batchId 批次 id
     * @param query   明细筛选/分页条件
     * @return 批次详情
     */
    @GetMapping("/{batchId}")
    @SaCheckPermission("iot:device:list")
    public R<DeviceImportBatchDetailResp> detail(@PathVariable Long batchId,
                                                 @Valid DeviceImportRowQuery query) {
        return R.ok(deviceImportService.getBatchDetail(batchId, query));
    }

    /**
     * 下载失败行 CSV（含错误码/错误信息列，改完可直接重传）。
     *
     * @param batchId 批次 id
     * @return CSV 文件
     */
    @GetMapping("/{batchId}/failed.csv")
    @SaCheckPermission("iot:device:list")
    public ResponseEntity<byte[]> failedCsv(@PathVariable Long batchId) {
        return csvResponse(FAILED_FILE_NAME_PREFIX + batchId + ".csv",
            deviceImportService.buildFailedCsv(batchId));
    }

    /**
     * 构造 CSV 下载响应（统一 BOM/编码/文件名编码）。
     *
     * <p>文件名走 {@code filename*=UTF-8''...}：中文文件名直接放 {@code filename=} 时
     * 不同浏览器会按各自编码解读而乱码；{@code filename*}（RFC 5987）是唯一可靠的形式。
     * 这是一个**文件名**，永远不参与页面渲染，因此不需要 HTML 转义——
     * 但它会被 URL 编码，避免头注入与非法字符。</p>
     *
     * @param fileName 文件名
     * @param content  CSV 文本
     * @return 响应
     */
    private ResponseEntity<byte[]> csvResponse(String fileName, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
            .replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(CSV_CONTENT_TYPE));
        headers.setContentLength(body.length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        // 下载内容按租户/批次动态生成，任何中间层缓存都会让用户拿到别人/过期的文件
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
