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

import cn.ypbin.admin.iot.model.req.IotTwinModelExternalReq;
import cn.ypbin.admin.iot.model.resp.IotTwinModelAssetResp;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 数字孪生模型资产服务。
 *
 * @author wenbin
 * @since 2026-10-21
 */
public interface IotTwinModelAssetService {

    /**
     * 查询全部模型资产（含被场景引用数）。
     *
     * @return 资产列表（按创建时间倒序；无数据为空列表）
     */
    List<IotTwinModelAssetResp> listAssets();

    /**
     * 上传模型文件。
     *
     * <p>校验顺序固定（先便宜后昂贵）：扩展名 → 字节数 → 魔数/glTF 版本 → 落存储
     * → 落库。任一环节失败都不留下半截文件（存储写入成功但落库失败时补偿删除）。</p>
     *
     * @param file      模型文件（multipart 字段 {@code file}）
     * @param assetName 模型名称（可空，空则用文件名去扩展名）
     * @param nodeNames 页面解析出的节点名快照（可空）
     * @return 新资产
     * @throws IOException 读取上传流失败
     */
    IotTwinModelAssetResp upload(MultipartFile file, String assetName, List<String> nodeNames)
        throws IOException;

    /**
     * 登记外链模型。
     *
     * @param req 外链信息
     * @return 新资产
     */
    IotTwinModelAssetResp createExternal(IotTwinModelExternalReq req);

    /**
     * 打开模型内容流（仅 {@code asset_type=upload}）。
     *
     * @param id 资产主键
     * @return 文件元数据 + 内容流（调用方负责关闭）
     */
    ModelContent openContent(Long id);

    /**
     * 删除资产（被场景引用时拒绝）。
     *
     * @param id 资产主键
     */
    void remove(Long id);

    /**
     * 模型内容（元数据 + 流）。
     *
     * @param fileName    下载时的文件名
     * @param contentType 内容类型
     * @param size        字节数（未知时为 -1）
     * @param contentHash SHA-256 摘要（用于 ETag）
     * @param stream      内容流
     */
    record ModelContent(String fileName, String contentType, long size, String contentHash,
                        InputStream stream) {
    }
}
