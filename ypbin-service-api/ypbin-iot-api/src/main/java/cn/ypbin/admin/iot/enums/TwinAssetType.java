/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 数字孪生模型来源类型。
 *
 * <p>{@link #UPLOAD} 的模型落在平台存储、经 API 流式下载（鉴权与租户隔离由 API 层承担）；
 * {@link #EXTERNAL} 只登记外链地址，由浏览器直连（服务端不主动拉取，避免 SSRF）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinAssetType {

    /** 平台内上传（存储平台 + 桶 + 路径） */
    UPLOAD("upload", "平台内上传"),

    /** 外链登记（仅校验 scheme 与长度，不服务端拉取） */
    EXTERNAL("external", "外链登记");

    private final String code;
    private final String desc;

    TwinAssetType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 来源类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinAssetType of(String code) {
        for (TwinAssetType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
