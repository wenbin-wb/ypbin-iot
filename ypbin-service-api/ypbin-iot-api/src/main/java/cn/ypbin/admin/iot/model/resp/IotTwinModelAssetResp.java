/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 模型资产响应模型。
 *
 * <p>{@code contentUrl} 是**页面实际使用的地址**，由服务端按来源类型给出：
 * {@code upload} ⇒ {@code /iot/twin/model-assets/{id}/content}（同源、带鉴权、走浏览器缓存）；
 * {@code external} ⇒ 原始外链。页面不需要自己判断来源类型去拼路径。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinModelAssetResp {

    /** 主键。 */
    private Long id;

    /** 模型名称。 */
    private String assetName;

    /** 来源类型：upload | external。 */
    private String assetType;

    /** 模型格式：glb | gltf。 */
    private String modelFormat;

    /** 外链地址（仅 external）。 */
    private String externalUrl;

    /** 页面使用的模型地址（upload ⇒ 平台下载端点；external ⇒ 外链原文）。 */
    private String contentUrl;

    /** 上传时的原始文件名（仅 upload）。 */
    private String originalName;

    /** 文件字节数（仅 upload）。 */
    private Long fileSize;

    /** SHA-256 摘要（仅 upload）。 */
    private String contentHash;

    /** 节点名快照（解析失败或未上报时为空数组）。 */
    private List<String> nodeNames;

    /** 被多少个场景引用。 */
    private Integer sceneCount;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
