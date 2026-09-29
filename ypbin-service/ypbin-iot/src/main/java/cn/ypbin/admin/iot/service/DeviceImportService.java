/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.query.DeviceImportRowQuery;
import cn.ypbin.admin.iot.model.resp.DeviceImportBatchDetailResp;
import cn.ypbin.admin.iot.model.resp.DeviceImportBatchResp;
import cn.ypbin.starter.crud.model.PageResult;

/**
 * 设备批量导入（CSV）+ 批次管理服务。
 *
 * <p>对标阿里云 IoT「批量创建设备」的四个动作，全部在这一个服务上：</p>
 * <ol>
 *   <li><b>下载模板</b>（{@link #buildTemplateCsv()}）：表头 + 说明行 + 示例行，用户不读文档就能填；</li>
 *   <li><b>上传</b>（{@link #importCsv(String, byte[])}）：解析 + 逐行独立校验 + 落库，返回批次；</li>
 *   <li><b>查批次</b>（{@link #getBatchDetail(Long, DeviceImportRowQuery)}）：批次头 + 分页明细（可按结果筛）；</li>
 *   <li><b>下载失败行</b>（{@link #buildFailedCsv(Long)}）：含错误码/错误信息列，改完直接重传。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public interface DeviceImportService {

    /**
     * 生成 CSV 模板（表头固定 + 说明行 + 示例行；含 UTF-8 BOM 便于 Excel 直接打开）。
     *
     * @return 模板文本
     */
    String buildTemplateCsv();

    /**
     * 解析并执行一次批量导入。
     *
     * <p><b>单行失败不影响其它行</b>：每一行独立校验、独立写入，失败行只让<b>自己</b>失败。
     * 这是本能力存在的核心价值（整批回滚会让用户必须先找出所有错误才能进去一台）。</p>
     *
     * @param fileName 原始文件名（原样留痕）
     * @param content  文件字节
     * @return 批次头信息（含成功/失败计数与状态）
     */
    DeviceImportBatchResp importCsv(String fileName, byte[] content);

    /**
     * 查询批次详情（批次头 + 分页明细）。
     *
     * <p>批次 id 不存在或**不属于本租户**时抛业务异常（不区分「不存在」与「不是你的」：
     * 区分等于把别的租户的批次 id 是否存在这个信息泄露出去）。</p>
     *
     * @param batchId 批次 id
     * @param query   明细筛选/分页条件
     * @return 批次详情
     */
    DeviceImportBatchDetailResp getBatchDetail(Long batchId, DeviceImportRowQuery query);

    /**
     * 生成失败行 CSV（表头与模板一致 + errorCode/errorMessage 两列）。
     *
     * <p>失败行为空时仍返回一份只有表头与说明行的文件（不报错）：用户的动作是「下载失败行」，
     * 而「这批没有失败行」是一个正常结论——报错反而让用户以为下载坏了。</p>
     *
     * @param batchId 批次 id
     * @return 失败行 CSV 文本
     */
    String buildFailedCsv(Long batchId);

    /**
     * 分页查询本租户的导入批次（最近在前）。
     *
     * @param page     页码（从 1 开始；≤0 时按 1 处理）
     * @param pageSize 每页条数
     * @return 批次分页结果
     */
    PageResult<DeviceImportBatchResp> pageBatches(long page, long pageSize);
}
