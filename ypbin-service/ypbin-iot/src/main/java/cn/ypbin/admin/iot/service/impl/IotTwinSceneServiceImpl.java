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
import cn.ypbin.admin.iot.entity.IotTwinPoint;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.enums.TwinSceneType;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.admin.iot.model.resp.IotTwinSceneResp;
import cn.ypbin.admin.iot.service.IotTwinSceneService;
import cn.ypbin.admin.iot.twin.TwinLimits;
import cn.ypbin.admin.iot.twin.TwinSceneTransform;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 数字孪生场景服务实现。
 *
 * <p><b>父链是唯一的不变量来源</b>：没有数据库约束能拦住「把场景挂到自己的后代下」，
 * 也没有约束能限制树深。全部靠本类的 {@code validateParent} 守；少写一行不会报错、
 * 不会红任何用例，只会让前端组树时无限递归（与 {@code IotDeviceGroupServiceImpl}
 * 的成环校验同理）。</p>
 *
 * <p><b>删除不级联</b>：级联删掉用户几个月的配置是不可逆事故。存在子场景或点位时一律拒绝，
 * 由用户逐层显式确认。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Service
public class IotTwinSceneServiceImpl extends BaseServiceImpl<IotTwinSceneMapper, IotTwinScene>
    implements IotTwinSceneService {

    private static final Logger log = LoggerFactory.getLogger(IotTwinSceneServiceImpl.class);

    /** 根场景的 parentId 哨兵值（与表默认值一致）。 */
    private static final long ROOT_PARENT_ID = 0L;

    private final IotTwinPointMapper twinPointMapper;

    private final IotTwinModelAssetMapper twinModelAssetMapper;

    private final ObjectMapper objectMapper;

    public IotTwinSceneServiceImpl(IotTwinPointMapper twinPointMapper,
                                   IotTwinModelAssetMapper twinModelAssetMapper,
                                   ObjectMapper objectMapper) {
        this.twinPointMapper = twinPointMapper;
        this.twinModelAssetMapper = twinModelAssetMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IotTwinSceneResp> listScenes() {
        List<IotTwinScene> scenes = baseMapper.selectList(new LambdaQueryWrapper<IotTwinScene>()
            .orderByAsc(IotTwinScene::getParentId)
            .orderByAsc(IotTwinScene::getSort)
            .orderByAsc(IotTwinScene::getId));
        return toRespList(scenes);
    }

    @Override
    public IotTwinSceneResp getScene(Long id) {
        return toRespList(List.of(requireScene(id))).getFirst();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IotTwinSceneReq req) {
        TwinSceneType sceneType = requireSceneType(req.getSceneType());
        checkSceneCodeUnique(req.getSceneCode(), null);
        long parentId = normParentId(req.getParentId());
        validateParent(null, parentId);
        requireModelAssetIfPresent(req.getModelAssetId());
        String viewJson = normalizeViewJson(req.getViewJson());

        IotTwinScene scene = new IotTwinScene();
        apply(scene, req, sceneType, parentId, viewJson);
        save(scene);
        return scene.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotTwinSceneReq req) {
        IotTwinScene scene = requireScene(id);
        TwinSceneType sceneType = requireSceneType(req.getSceneType());
        checkSceneCodeUnique(req.getSceneCode(), id);
        long parentId = normParentId(req.getParentId());
        validateParent(id, parentId);
        requireModelAssetIfPresent(req.getModelAssetId());
        String viewJson = normalizeViewJson(req.getViewJson());
        apply(scene, req, sceneType, parentId, viewJson);
        updateById(scene);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        IotTwinScene scene = requireScene(id);
        Long childCount = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinScene>()
            .eq(IotTwinScene::getParentId, scene.getId()));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该场景下还有 " + childCount + " 个子场景，无法删除：请先删除（或移走）子场景。"
                    + "平台刻意不做级联删除，避免误删掉整棵配置树");
        }
        Long pointCount = twinPointMapper.selectCount(new LambdaQueryWrapper<IotTwinPoint>()
            .eq(IotTwinPoint::getSceneId, scene.getId()));
        if (pointCount != null && pointCount > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "该场景下还有 " + pointCount + " 个设备点位，无法删除：请先删除点位"
                    + "（点位保存了设备与坐标，删场景不会替你决定它们去哪）");
        }
        removeById(id);
    }

    // ---------- 校验 ----------

    /**
     * 校验父场景与树深（同时防成环）。
     *
     * <p>上溯上限用 {@link TwinLimits#MAX_SCENE_DEPTH}：正常树远达不到 4 层以上，
     * 一旦遇到历史脏数据留下的环，到上限即拒绝并明确报「父子关系成环」，
     * 保证查询次数有界、不会无界循环。</p>
     *
     * @param selfId   当前场景主键（新建时为 null）
     * @param parentId 归一后的父场景 ID（0=根）
     */
    private void validateParent(Long selfId, long parentId) {
        if (parentId == ROOT_PARENT_ID) {
            return;
        }
        if (selfId != null && selfId == parentId) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不能作为自己的父场景");
        }
        // **一条递归 CTE 取回整条父链**（不在 Java 里逐层查库：那是循环内 DB 调用，
        // 既有门禁 loopsMustNotCallDbOrRpc 会判红，且查询次数会随树深增长）。
        List<Map<String, Object>> chain = baseMapper.selectParentChain(parentId);
        if (chain.isEmpty()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "父场景不存在：" + parentId);
        }
        // CTE 按 depth 升序返回：chain[0] 是候选父，链尾是根（parentId=0）或断点。
        int chainLength = chain.size();
        for (Map<String, Object> row : chain) {
            long nodeId = asLong(row.get("id"));
            if (selfId != null && selfId == nodeId) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "场景不能挂到自己的后代下（父链上出现了当前场景本身）");
            }
        }
        // 链尾必须是根（parentId=0）；否则说明父链断裂（父场景已不存在）
        Map<String, Object> tail = chain.getLast();
        long tailParent = asLong(tail.get("parentId"));
        if (tailParent != ROOT_PARENT_ID) {
            // CTE 到深度上限仍未到根：要么是环，要么链更深
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "场景层级数据异常（父子关系成环或层级过深）：请检查场景树的父场景配置");
        }
        // 新深度 = 父链长度 + 自身一层
        int newDepth = chainLength + 1;
        if (newDepth > TwinLimits.MAX_SCENE_DEPTH) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "场景层级不能超过 " + TwinLimits.MAX_SCENE_DEPTH + " 层（当前将达第 "
                    + newDepth + " 层）。房间级展示请在楼层场景内做区域标注，不要再加一层");
        }
    }

    private TwinSceneType requireSceneType(String code) {
        TwinSceneType type = TwinSceneType.of(code);
        if (type == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "场景类型不合法：" + code + "（允许值：park / building / floor / room）");
        }
        return type;
    }

    private void checkSceneCodeUnique(String sceneCode, Long excludeId) {
        Long exists = baseMapper.selectCount(new LambdaQueryWrapper<IotTwinScene>()
            .eq(IotTwinScene::getSceneCode, sceneCode)
            .ne(excludeId != null, IotTwinScene::getId, excludeId));
        if (exists != null && exists > 0) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "场景编码已存在：" + sceneCode);
        }
    }

    /**
     * 模型资产必须存在且属于本租户。
     *
     * <p>查询走租户插件，跨租户资产查不到 ⇒ 报「模型资产不存在」而不是「无权限」：
     * 返回 403 等于告知调用方「该 id 确实存在、只是你无权访问」，属存在性泄露。
     * 与设计 D0.5 同口径。</p>
     */
    private void requireModelAssetIfPresent(Long modelAssetId) {
        if (modelAssetId == null) {
            return;
        }
        IotTwinModelAsset asset = twinModelAssetMapper.selectById(modelAssetId);
        if (asset == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "模型资产不存在：" + modelAssetId);
        }
    }

    /**
     * 校验并归一 {@code viewJson}。
     *
     * <p>它是前端拥有的展示偏好，但**必须校验合法性**：写进去一段坏 JSON 会让页面每次进入
     * 该场景都解析失败，而用户已无从知道是哪一步写坏的。大小上限 16KB 防
     * 「把整份配置塞进视角字段」。</p>
     *
     * @param viewJson 原始 JSON（可空）
     * @return 归一后的 JSON（空串归一为 null）
     */
    private String normalizeViewJson(String viewJson) {
        if (!StringUtils.hasText(viewJson)) {
            return null;
        }
        int bytes = viewJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > TwinLimits.MAX_VIEW_JSON_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "视角配置过大（" + bytes + " 字节，上限 " + TwinLimits.MAX_VIEW_JSON_BYTES
                    + " 字节）");
        }
        try {
            objectMapper.readTree(viewJson);
        } catch (JacksonException ex) {
            log.warn("[iot] 场景视角配置不是合法 JSON，已拒绝保存", ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "视角配置不是合法的 JSON：" + ex.getOriginalMessage());
        }
        return viewJson;
    }

    private long normParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    private void apply(IotTwinScene scene, IotTwinSceneReq req, TwinSceneType sceneType,
                       long parentId, String viewJson) {
        scene.setSceneCode(req.getSceneCode().trim());
        scene.setSceneName(req.getSceneName().trim());
        scene.setParentId(parentId);
        scene.setSceneType(sceneType.getCode());
        scene.setModelAssetId(req.getModelAssetId());
        scene.setAnchorX(TwinSceneTransform.zeroIfNull(req.getAnchorX()));
        scene.setAnchorY(TwinSceneTransform.zeroIfNull(req.getAnchorY()));
        scene.setAnchorZ(TwinSceneTransform.zeroIfNull(req.getAnchorZ()));
        scene.setAnchorRx(TwinSceneTransform.zeroIfNull(req.getAnchorRx()));
        scene.setAnchorRy(TwinSceneTransform.zeroIfNull(req.getAnchorRy()));
        scene.setAnchorRz(TwinSceneTransform.zeroIfNull(req.getAnchorRz()));
        scene.setAnchorScale(TwinSceneTransform.requireScale(req.getAnchorScale()));
        scene.setViewJson(viewJson);
        scene.setSort(req.getSort() == null ? 0 : req.getSort());
        scene.setRemark(req.getRemark());
    }

    private IotTwinScene requireScene(Long id) {
        IotTwinScene scene = getById(id);
        if (scene == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "场景不存在：" + id);
        }
        return scene;
    }

    // ---------- 响应转换 ----------

    /**
     * 批量转换为响应（**一次分组查询**填点数与子场景数，不逐行查库）。
     *
     * @param scenes 场景实体列表
     * @return 响应列表
     */
    private List<IotTwinSceneResp> toRespList(List<IotTwinScene> scenes) {
        if (scenes.isEmpty()) {
            return List.of();
        }
        List<Long> sceneIds = scenes.stream().map(IotTwinScene::getId).toList();
        Map<Long, Map<String, Object>> statsById = new HashMap<>();
        for (Map<String, Object> row : baseMapper.countPointsAndChildren(sceneIds)) {
            statsById.put(asLong(row.get("sceneId")), row);
        }
        List<IotTwinSceneResp> result = new ArrayList<>(scenes.size());
        for (IotTwinScene scene : scenes) {
            result.add(toResp(scene, statsById.get(scene.getId())));
        }
        return result;
    }

    private IotTwinSceneResp toResp(IotTwinScene scene, Map<String, Object> stats) {
        IotTwinSceneResp resp = new IotTwinSceneResp();
        resp.setId(scene.getId());
        resp.setSceneCode(scene.getSceneCode());
        resp.setSceneName(scene.getSceneName());
        resp.setParentId(scene.getParentId());
        resp.setSceneType(scene.getSceneType());
        resp.setModelAssetId(scene.getModelAssetId());
        resp.setAnchorX(scene.getAnchorX());
        resp.setAnchorY(scene.getAnchorY());
        resp.setAnchorZ(scene.getAnchorZ());
        resp.setAnchorRx(scene.getAnchorRx());
        resp.setAnchorRy(scene.getAnchorRy());
        resp.setAnchorRz(scene.getAnchorRz());
        resp.setAnchorScale(scene.getAnchorScale());
        resp.setViewJson(scene.getViewJson());
        resp.setSort(scene.getSort());
        resp.setRemark(scene.getRemark());
        resp.setCreateTime(scene.getCreateTime());
        resp.setPointCount(stats == null ? 0 : (int) asLong(stats.get("pointCount")));
        resp.setChildCount(stats == null ? 0 : (int) asLong(stats.get("childCount")));
        return resp;
    }

    /**
     * 安全转 long（{@code COUNT(*)} 在不同驱动下可能返回 Long/Integer/BigInteger）。
     *
     * @param value 原始值
     * @return 数字；null 或不可解析时为 0
     */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            log.warn("[iot] 场景统计值不是数字，按 0 处理：value={}", value);
            return 0L;
        }
    }
}
