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

/**
 * 数字孪生域的全部硬上限（**唯一来源**）。
 *
 * <p>集中在一个类里而不是散落在 DTO 注解与 service 判断中：同一个上限会同时出现在
 * 请求 DTO 的 {@code @Size}、服务侧复核、前端常量三处，散落必然漂移，而漂移的后果是
 * 「前端放过、后端拒绝」或反之，用户拿到的是无法理解的失败。</p>
 *
 * <p>前端在 {@code api/iot/twin.ts} 的 {@code TWIN_LIMITS} 里镜像同一组数字，并注明
 * 「与后端 TwinLimits 同值」；修改时必须两处同改（既有
 * {@code DEVICE_IMPORT_FRONTEND_LIMITS} ←→ {@code DeviceImportLimits} 就是这个模式）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public final class TwinLimits {

    /** 场景树最大深度（根为第 1 层）。 */
    public static final int MAX_SCENE_DEPTH = 4;

    /** 场景编码长度上限。 */
    public static final int MAX_SCENE_CODE_LENGTH = 64;

    /** 场景编码允许的字符集（正则）。 */
    public static final String SCENE_CODE_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    /** 场景名称长度上限。 */
    public static final int MAX_SCENE_NAME_LENGTH = 128;

    /** 场景备注长度上限。 */
    public static final int MAX_SCENE_REMARK_LENGTH = 500;

    /** 场景视角配置 JSON 字节上限（16 KB）。 */
    public static final int MAX_VIEW_JSON_BYTES = 16 * 1024;

    /** 单场景点位上限。 */
    public static final int MAX_POINTS_PER_SCENE = 500;

    /** 单点位属性绑定上限。 */
    public static final int MAX_BINDINGS_PER_POINT = 12;

    /** 模型节点名长度上限。 */
    public static final int MAX_NODE_NAME_LENGTH = 255;

    /** 点位图标标识长度上限。 */
    public static final int MAX_ICON_LENGTH = 64;

    /** 点位备注长度上限。 */
    public static final int MAX_POINT_REMARK_LENGTH = 500;

    /** 单位覆盖长度上限。 */
    public static final int MAX_UNIT_LENGTH = 32;

    /** 小数位上限。 */
    public static final int MAX_DECIMALS = 6;

    /** 模型名称长度上限。 */
    public static final int MAX_ASSET_NAME_LENGTH = 128;

    /** 外链地址长度上限。 */
    public static final int MAX_EXTERNAL_URL_LENGTH = 1024;

    /** 模型文件字节上限（64 MB），与 {@code ypbin.storage.max-file-size} 同值。 */
    public static final long MAX_MODEL_BYTES = 64L * 1024 * 1024;

    /** 模型节点名快照条目上限（超出截断并记 WARN，不影响任何功能）。 */
    public static final int MAX_NODE_NAME_SNAPSHOT = 5_000;

    /** 模型节点名快照 JSON 字节上限（1 MB）。 */
    public static final int MAX_NODE_NAMES_JSON_BYTES = 1024 * 1024;

    /** {@code .glb} 扩展名（不含点）。 */
    public static final String EXT_GLB = "glb";

    /** {@code .gltf} 扩展名（不含点）。 */
    public static final String EXT_GLTF = "gltf";

    /** GLB 文件头魔数（ASCII {@code glTF} 的小端读取值）。 */
    public static final int GLB_MAGIC = 0x46546C67;

    /** 支持的 glTF 主版本。 */
    public static final int GLTF_VERSION = 2;

    /** GLB 头部长度（魔数 4 + 版本 4 + 总长 4）。 */
    public static final int GLB_HEADER_BYTES = 12;

    /** 锚点缩放系数下界（必须严格大于 0）。 */
    public static final double MIN_ANCHOR_SCALE = 0.000001D;

    private TwinLimits() {
    }
}
