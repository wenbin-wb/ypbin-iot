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

import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型文件的内容校验（**按内容校验，不是按扩展名**）。
 *
 * <p><b>为什么必须做魔数校验</b>：只校验扩展名等于没有校验——把任意文件改名为
 * {@code .glb} 就能塞进存储，并通过带鉴权的下载端点取回，等于给平台开了一个
 * 「可上传任意内容」的通道（可用于存放 HTML 做钓鱼、存放体积炸弹）。这里按 glTF 规范的
 * 真实头部判定：</p>
 * <ul>
 *   <li>{@code .glb}：头部 12 字节 = 魔数 {@code 0x46546C67}（ASCII {@code glTF}）+
 *       version(4) + 总长(4)，version 必须为 {@value TwinLimits#GLTF_VERSION}；</li>
 *   <li>{@code .gltf}：必须是 UTF-8 JSON，且顶层含 {@code asset} 对象、其 {@code version}
 *       以 {@code "2."} 开头。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Component
public class TwinModelFileGuard {

    private static final Logger log = LoggerFactory.getLogger(TwinModelFileGuard.class);

    /** glTF 2.x 的版本前缀（规范写法：{@code "2.0"}）。 */
    private static final String GLTF_VERSION_PREFIX = "2.";

    /**
     * {@code .gltf} 探测允许扫描的 JSON 令牌上限。
     *
     * <p>防「结构合法但超长」的文件拖住线程：token 流式读取是 O(文件大小)，
     * 一个 64MB、含百万级数组元素的文件仍会占住线程较久。100 万令牌对真实模型足够宽裕
     * （合法 glTF 的 {@code asset} 总在文档前部附近）。</p>
     */
    private static final int MAX_JSON_TOKENS = 1_000_000;

    private final ObjectMapper objectMapper;

    public TwinModelFileGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从原始文件名推断并校验格式。
     *
     * @param originalName 原始文件名
     * @return 归一化后的扩展名（{@code glb} / {@code gltf}）
     */
    public String requireFormat(String originalName) {
        String ext = extension(originalName);
        if (TwinLimits.EXT_GLB.equals(ext) || TwinLimits.EXT_GLTF.equals(ext)) {
            return ext;
        }
        throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
            "只支持 .glb 与 .gltf 模型文件（当前文件：" + originalName
                + "）。其它格式请先用建模工具或 glTF 转换工具导出为 glTF 2.0");
    }

    /**
     * 校验内容头部（**流会被读取前若干字节，调用方需传入可重复读的流或按需重新打开**）。
     *
     * @param format  归一化扩展名（{@code glb} / {@code gltf}）
     * @param content 模型内容流（方法内不关闭；调用方负责）
     * @return 模型内容摘要信息
     * @throws IOException 读取流失败
     */
    public ProbeResult probe(String format, InputStream content) throws IOException {
        if (TwinLimits.EXT_GLB.equals(format)) {
            return probeGlb(content);
        }
        return probeGltf(content);
    }

    private ProbeResult probeGlb(InputStream content) throws IOException {
        byte[] header = content.readNBytes(TwinLimits.GLB_HEADER_BYTES);
        if (header.length < TwinLimits.GLB_HEADER_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 GLB：内容不足 " + TwinLimits.GLB_HEADER_BYTES
                    + " 字节，无法读取文件头");
        }
        int magic = readIntLe(header, 0);
        if (magic != TwinLimits.GLB_MAGIC) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 GLB：文件头魔数不匹配（期望 ASCII \"glTF\"）。"
                    + "若这是 ASCII glTF 文本文件，请把扩展名改为 .gltf 后重新上传");
        }
        int version = readIntLe(header, 4);
        if (version != TwinLimits.GLTF_VERSION) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "不支持的 glTF 版本：" + version + "（只支持 glTF "
                    + TwinLimits.GLTF_VERSION + "）。请用支持 glTF 2.0 的工具重新导出");
        }
        long declaredLength = readIntLe(header, 8) & 0xFFFFFFFFL;
        if (declaredLength <= 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "GLB 文件头声明的总长度为 0，文件已损坏");
        }
        return new ProbeResult(declaredLength);
    }

    /**
     * 探测 {@code .gltf}（JSON 文本）——**真正的流式 token 读取**。
     *
     * <h3>为什么不能只读前 N 字节（历史缺陷 1）</h3>
     * 旧实现只读前 64KB 就当完整 JSON 解析 ⇒ 一个合法但较大的 {@code .gltf}
     * （{@code asset} 出现在 64KB 之后）会被判为「不是有效的 glTF JSON」而**无法上传**。
     * 且截断会把二进制内容解成一堆 U+FFFD，错误信息同样误导。
     *
     * <h3>为什么也不能用 {@code readTree(InputStream)}（历史缺陷 2，实测取证）</h3>
     * 上一版改成 {@code objectMapper.readTree(content)} 并注释「流式消费、不把文件读进内存」，
     * 这个说法是<b>错的</b>：{@code readTree} 返回**完整 JsonNode 树**，"流式"的只是 IO，
     * 不是内存占用。实测 18.8MB 的合法 glTF 消耗约 <b>242MB 堆</b>（单节点开销远大于原始
     * 文本，约 10~15 倍），按 64MB 上限推算需 800MB~1GB ⇒ **一次上传就可能把 iot 服务
     * 打爆**（不只是该请求失败）。
     *
     * <h3>现在的做法</h3>
     * 用 {@link JsonParser} 逐 token 扫描：只在顶层 {@code asset} 对象里取
     * {@code version}，读到即返回，**内存占用与文件大小无关**。
     * 同时用 {@link #MAX_JSON_TOKENS} 限制扫描的 token 总数，防止「超长数组」拖住 CPU。
     *
     * <p>两种历史缺陷的能力要求都保留：能读到文档<b>任意位置</b>的 {@code asset.version}
     * （不退回只读前 N 字节），且不整体驻留内存。</p>
     *
     * @param content 文件内容流
     * @return 探测结果（{@code .gltf} 无法从头部预知总长度，声明长度返回 -1）
     * @throws IOException 读取失败
     */
    private ProbeResult probeGltf(InputStream content) throws IOException {
        // 当前嵌套深度：顶层对象内部为 1
        int depth = 0;
        // 是否已见到顶层 asset 对象
        boolean seenAsset = false;
        // 进入 asset 时的深度（离开该深度即说明 asset 已结束）
        int assetDepth = -1;
        int tokenCount = 0;
        String fieldName = null;
        boolean sawAnyToken = false;
        try (JsonParser parser = objectMapper.createParser(content)) {
            for (JsonToken token = parser.nextToken(); token != null;
                 token = parser.nextToken()) {
                sawAnyToken = true;
                tokenCount++;
                if (tokenCount > MAX_JSON_TOKENS) {
                    throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                        "文件结构过于复杂（JSON 令牌数超过 " + MAX_JSON_TOKENS
                            + "），已拒绝。请先用 gltf-pipeline 等工具优化模型后再上传");
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                    continue;
                }
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                    if (assetDepth >= 0 && depth < assetDepth) {
                        // asset 对象已结束：若期间没读到 version ⇒ 缺失（规范必须）
                        assetDepth = -1;
                    }
                    continue;
                }
                if (token == JsonToken.PROPERTY_NAME) {
                    fieldName = parser.currentName();
                    if (depth == 1 && "asset".equals(fieldName)) {
                        // asset 必须是对象；否则结构不合法
                        if (parser.nextToken() != JsonToken.START_OBJECT) {
                            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                                "文件不是有效的 glTF：asset 不是对象（glTF 2.0 规范要求）");
                        }
                        depth++;
                        seenAsset = true;
                        assetDepth = depth;
                    }
                    continue;
                }
                // 值 token：只在「顶层 asset 的直接子字段 version」上取值
                if (assetDepth == depth && "version".equals(fieldName)) {
                    String version = token == JsonToken.VALUE_STRING ? parser.getText() : null;
                    if (version == null || !version.startsWith(GLTF_VERSION_PREFIX)) {
                        throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                            "不支持的 glTF 版本：" + (version == null ? "未声明" : version)
                                + "（只支持 glTF " + TwinLimits.GLTF_VERSION + "）");
                    }
                    // 拿到 version 即可下结论，**不必继续扫完整个文件**
                    return new ProbeResult(-1);
                }
            }
        } catch (JacksonException ex) {
            log.warn("[iot] .gltf 文件不是合法 JSON，已拒绝", ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 glTF JSON：" + ex.getOriginalMessage()
                    + "。若这是二进制 GLB 文件，请把扩展名改为 .glb 后重新上传");
        }
        if (!sawAnyToken) {
            // 空文件是**独立**的失败原因，不该被归为「缺少 asset」（两者修复动作不同）
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "文件内容为空");
        }
        if (!seenAsset) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "文件不是有效的 glTF：顶层缺少 asset 对象（glTF 2.0 规范要求）");
        }
        throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
            "不支持的 glTF 版本：未声明（只支持 glTF " + TwinLimits.GLTF_VERSION + "）");
    }

    /**
     * 提取扩展名（小写，不含点）。
     *
     * @param fileName 文件名
     * @return 扩展名（无扩展名时为空串）
     */
    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 小端读取 4 字节整数（GLB 规范固定小端）。
     *
     * @param buf    字节数组
     * @param offset 起始偏移
     * @return 整数值
     */
    private static int readIntLe(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
            | ((buf[offset + 1] & 0xFF) << 8)
            | ((buf[offset + 2] & 0xFF) << 16)
            | ((buf[offset + 3] & 0xFF) << 24);
    }

    /**
     * 校验并归一节点名快照（长度、条目数、去重）。
     *
     * <p>它是辅助数据，超过上限时**截断后继续**（记 WARN），不阻塞上传。</p>
     *
     * @param nodeNames 页面上报的节点名列表（可空）
     * @return 归一后的列表（保证不超过上限）
     */
    public List<String> normalizeNodeNames(List<String> nodeNames) {
        if (nodeNames == null || nodeNames.isEmpty()) {
            return List.of();
        }
        List<String> result = nodeNames.stream()
            .filter(name -> name != null && !name.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (result.size() > TwinLimits.MAX_NODE_NAME_SNAPSHOT) {
            log.warn("[iot] 模型节点名快照超过上限，已截断：原 {} 条，上限 {} 条",
                result.size(), TwinLimits.MAX_NODE_NAME_SNAPSHOT);
            return result.subList(0, TwinLimits.MAX_NODE_NAME_SNAPSHOT);
        }
        return result;
    }

    /**
     * 探测结果。
     *
     * @param declaredLength GLB 头部声明的文件总长（{@code .gltf} 时为 -1，表示未知）
     */
    public record ProbeResult(long declaredLength) {
    }
}
