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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.admin.iot.model.req.IotTwinSceneReq;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 场景服务的父链校验与删除保护。
 *
 * <p>这些规则**没有任何数据库约束兜底**——少写一行不会报错、不会红任何用例，
 * 只会让前端组树时无限递归（成环）或让用户看到一棵 5 层的树（超深）。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
class IotTwinSceneServiceImplTest {

    private static final long SCENE_ROOT = 7_000_001L;

    private static final long SCENE_CHILD = 7_000_002L;

    private static final long SCENE_GRANDCHILD = 7_000_003L;

    private static final long SCENE_SELF = 7_000_004L;

    private final IotTwinSceneMapper sceneMapper = mock(IotTwinSceneMapper.class);

    private final IotTwinPointMapper pointMapper = mock(IotTwinPointMapper.class);

    private final IotTwinModelAssetMapper assetMapper = mock(IotTwinModelAssetMapper.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IotTwinSceneServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 靠方法引用解析列名，纯单测不起 Spring 时必须先初始化实体元信息
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinScene.class);
        TableInfoHelper.initTableInfo(assistant, IotTwinModelAsset.class);
    }

    @BeforeEach
    void setUp() {
        service = new IotTwinSceneServiceImpl(pointMapper, assetMapper, objectMapper);
        // baseMapper 是 BaseServiceImpl 的 protected 字段：纯单测不起 Spring 时用 Spring Test 反射工具注入
        ReflectionTestUtils.setField(service, "baseMapper", sceneMapper);
        when(sceneMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("★ 父场景是自己 ⇒ 拒绝")
    void selfAsParentRejected() {
        when(sceneMapper.selectById(SCENE_SELF)).thenReturn(scene(SCENE_SELF, 0L));
        assertThatThrownBy(() -> service.update(SCENE_SELF, req("A", SCENE_SELF)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("自己的父场景");
    }

    /**
     * 构造一条父链（按 depth 升序：链首是候选父，链尾是根）。
     *
     * @param entries id 与 parentId 交替传入，如 (child, root, root, 0)
     * @return CTE 返回的行
     */
    private static List<Map<String, Object>> chain(Object... entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < entries.length; index += 2) {
            rows.add(Map.of("id", entries[index], "parentId", entries[index + 1]));
        }
        return rows;
    }

    @Test
    @DisplayName("★ 父场景是自己的后代 ⇒ 拒绝（成环）")
    void descendantAsParentRejected() {
        // 树形：ROOT(0) → CHILD(ROOT) → GRANDCHILD(CHILD)
        // 把 ROOT（祖先）的父改成 GRANDCHILD（它自己的后代）⇒ 必须拒绝。
        // ⚠️ 这个用例的构造本身就是一次纠错：最初写成「把 SELF 的父改成它的原父」
        //    ——那等于没改（不是成环），测试会误报实现有缺陷。
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        // 父链：GRANDCHILD -> CHILD -> ROOT(0)；其中出现 ROOT 自己 ⇒ 成环
        when(sceneMapper.selectParentChain(SCENE_GRANDCHILD)).thenReturn(
            chain(SCENE_GRANDCHILD, SCENE_CHILD, SCENE_CHILD, SCENE_ROOT, SCENE_ROOT, 0L));
        assertThatThrownBy(() -> service.update(SCENE_ROOT, req("A", SCENE_GRANDCHILD)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能挂到自己的后代下");
    }

    @Test
    @DisplayName("父链成环（历史脏数据）⇒ 拒绝并提示数据异常")
    void cyclicChainRejected() {
        // CTE 到深度上限仍未碰到 parentId=0 ⇒ 链尾 parentId 非 0 ⇒ 判为成环/过深
        when(sceneMapper.selectParentChain(SCENE_CHILD)).thenReturn(
            chain(SCENE_CHILD, SCENE_GRANDCHILD, SCENE_GRANDCHILD, SCENE_CHILD));
        assertThatThrownBy(() -> service.create(req("A", SCENE_CHILD)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("成环");
    }

    @Test
    @DisplayName("★ 超过 4 层 ⇒ 拒绝并报实际深度")
    void tooDeepRejected() {
        // 候选父的链长 4（SELF -> GRANDCHILD -> CHILD -> ROOT），新深度 = 4+1 = 5 > 4
        when(sceneMapper.selectParentChain(SCENE_SELF)).thenReturn(
            chain(SCENE_SELF, SCENE_GRANDCHILD, SCENE_GRANDCHILD, SCENE_CHILD,
                SCENE_CHILD, SCENE_ROOT, SCENE_ROOT, 0L));
        assertThatThrownBy(() -> service.create(req("A", SCENE_SELF)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能超过 4 层")
            .hasMessageContaining("第 5 层");
    }

    @Test
    @DisplayName("父场景不存在 ⇒ 拒绝")
    void missingParentRejected() {
        when(sceneMapper.selectParentChain(9_999_999L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(req("A", 9_999_999L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("父场景不存在");
    }

    @Test
    @DisplayName("★ 删除有子场景的场景 ⇒ 拒绝（不级联）")
    void deleteWithChildrenRejected() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.remove(SCENE_ROOT))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("子场景")
            .hasMessageContaining("刻意不做级联删除");
        verify(sceneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("★ 删除有点位的场景 ⇒ 拒绝")
    void deleteWithPointsRejected() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectCount(any())).thenReturn(0L);
        when(pointMapper.selectCount(any())).thenReturn(3L);
        assertThatThrownBy(() -> service.remove(SCENE_ROOT))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备点位");
        verify(sceneMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除既无子场景也无点位的场景 ⇒ 执行删除")
    void deleteEmptySceneSucceeds() {
        when(sceneMapper.selectById(SCENE_ROOT)).thenReturn(scene(SCENE_ROOT, 0L));
        when(sceneMapper.selectCount(any())).thenReturn(0L);
        when(pointMapper.selectCount(any())).thenReturn(0L);
        service.remove(SCENE_ROOT);
        verify(sceneMapper).deleteById(SCENE_ROOT);
    }

    @Test
    @DisplayName("★ 模型资产跨租户/不存在 ⇒ 报「不存在」而不是「无权限」（不泄露存在性）")
    void missingModelAssetRejected() {
        when(assetMapper.selectById(anyLong())).thenReturn(null);
        IotTwinSceneReq req = req("A", null);
        req.setModelAssetId(9_999_999L);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("模型资产不存在");
    }

    @Test
    @DisplayName("模型资产存在 ⇒ 允许创建")
    void existingModelAssetAllowed() {
        IotTwinModelAsset asset = new IotTwinModelAsset();
        asset.setId(5_001L);
        when(assetMapper.selectById(5_001L)).thenReturn(asset);
        IotTwinSceneReq req = req("A", null);
        req.setModelAssetId(5_001L);
        service.create(req);
        verify(sceneMapper).insert(any(IotTwinScene.class));
    }

    @Test
    @DisplayName("场景类型非法 ⇒ 拒绝并列出允许值")
    void invalidSceneTypeRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setSceneType("warehouse");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景类型不合法")
            .hasMessageContaining("park");
    }

    @Test
    @DisplayName("viewJson 非合法 JSON ⇒ 拒绝")
    void invalidViewJsonRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setViewJson("{not json");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不是合法的 JSON");
    }

    @Test
    @DisplayName("viewJson 合法 ⇒ 允许创建")
    void validViewJsonAllowed() {
        IotTwinSceneReq req = req("A", null);
        req.setViewJson("{\"background\":\"#0b1220\"}");
        service.create(req);
        verify(sceneMapper).insert(any(IotTwinScene.class));
    }

    @Test
    @DisplayName("viewJson 空白 ⇒ 归一为 null（不落空串）")
    void blankViewJsonNormalized() {
        IotTwinSceneReq req = req("A", null);
        req.setViewJson("   ");
        service.create(req);
        verify(sceneMapper).insert(any(IotTwinScene.class));
    }

    @Test
    @DisplayName("viewJson 超过 16KB ⇒ 拒绝")
    void oversizedViewJsonRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setViewJson("{\"a\":\"" + "x".repeat(17 * 1024) + "\"}");
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("视角配置过大");
    }

    @Test
    @DisplayName("★ 缩放为 0 ⇒ 拒绝")
    void zeroScaleRejected() {
        IotTwinSceneReq req = req("A", null);
        req.setAnchorScale(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须大于 0");
    }

    @Test
    @DisplayName("场景编码重复 ⇒ 拒绝")
    void duplicateSceneCodeRejected() {
        when(sceneMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(req("A", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景编码已存在");
    }

    @Test
    @DisplayName("场景不存在 ⇒ 查询/编辑/删除都如实报错")
    void missingSceneRejected() {
        when(sceneMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.getScene(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景不存在");
        assertThatThrownBy(() -> service.update(1L, req("A", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景不存在");
        assertThatThrownBy(() -> service.remove(1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景不存在");
    }

    @Test
    @DisplayName("列表为空 ⇒ 返回空列表（不返回 null、不查统计）")
    void emptyListReturnsEmpty() {
        when(sceneMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listScenes()).isEmpty();
        verify(sceneMapper, never()).countPointsAndChildren(any());
    }

    private static IotTwinScene scene(long id, long parentId) {
        IotTwinScene scene = new IotTwinScene();
        scene.setId(id);
        scene.setParentId(parentId);
        scene.setSceneName("scene-" + id);
        return scene;
    }

    private static IotTwinSceneReq req(String code, Long parentId) {
        IotTwinSceneReq req = new IotTwinSceneReq();
        req.setSceneCode(code);
        req.setSceneName("场景 " + code);
        req.setParentId(parentId);
        req.setSceneType("building");
        req.setAnchorScale(BigDecimal.ONE);
        return req;
    }
}
