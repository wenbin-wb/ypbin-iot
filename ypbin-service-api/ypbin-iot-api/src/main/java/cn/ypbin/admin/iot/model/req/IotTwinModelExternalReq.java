/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import cn.ypbin.admin.iot.twin.TwinLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 外链模型登记请求。
 *
 * <p>服务端**不主动拉取**该地址（避免 SSRF：内网地址、云元数据端点都在射程内），
 * 只校验 scheme 与长度，连通性由页面「试加载」按钮暴露。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinModelExternalReq {

    /** 模型名称（租户内唯一）。 */
    @NotBlank(message = "模型名称不能为空")
    @Size(max = TwinLimits.MAX_ASSET_NAME_LENGTH, message = "模型名称长度不能超过 128")
    private String assetName;

    /** 模型格式：glb | gltf（缺省按扩展名推断，推断不出则要求显式传）。 */
    private String modelFormat;

    /** 外链地址（必须是 http/https 绝对地址）。 */
    @NotBlank(message = "外链地址不能为空")
    @Size(max = TwinLimits.MAX_EXTERNAL_URL_LENGTH, message = "外链地址长度不能超过 1024")
    private String externalUrl;
}
