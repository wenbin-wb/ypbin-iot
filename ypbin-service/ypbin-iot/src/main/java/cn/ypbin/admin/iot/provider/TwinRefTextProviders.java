/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.provider;

import cn.ypbin.admin.iot.entity.IotTwinModelAsset;
import cn.ypbin.admin.iot.entity.IotTwinScene;
import cn.ypbin.admin.iot.mapper.IotTwinModelAssetMapper;
import cn.ypbin.admin.iot.mapper.IotTwinSceneMapper;
import cn.ypbin.starter.json.ref.RefTextProvider;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字孪生引用字段翻译数据源（数据库实现）。
 *
 * <p>提供两类：{@code twinScene}（场景 ID → 场景名）与 {@code twinModelAsset}
 * （资产 ID → 资产名），分别对应响应模型上 {@code parentId} / {@code modelAssetId} 的
 * {@code @RefText}。</p>
 *
 * <p>批量翻译由框架按类型聚合后一次调用，本类一律走批量主键查询，不在循环里查库。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public final class TwinRefTextProviders {

    private TwinRefTextProviders() {
    }

    /**
     * 场景引用翻译：类型 {@code twinScene}，ID → 场景名。
     *
     * <p>根场景的 {@code parentId=0} 查不到任何行 ⇒ 不输出派生字段（<b>不编造「根场景」这样的
     * 名字</b>：页面据此显示「（根）」是展示决策，不该由后端伪造数据）。</p>
     *
     * @author wenbin
     * @since 2026-10-21
     */
    @Component
    @RequiredArgsConstructor
    public static class TwinSceneName implements RefTextProvider {

        private static final Logger log = LoggerFactory.getLogger(TwinSceneName.class);

        private final IotTwinSceneMapper twinSceneMapper;

        @Override
        public String type() {
            return "twinScene";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = IotRefTextProviders.parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotTwinScene scene : twinSceneMapper.selectByIds(idList)) {
                if (!StringUtils.hasText(scene.getSceneName())) {
                    log.warn("数字孪生引用文本翻译跳过名称为空的场景：sceneId={}", scene.getId());
                    continue;
                }
                names.put(scene.getId(), scene.getSceneName());
            }
            return names;
        }
    }

    /**
     * 模型资产引用翻译：类型 {@code twinModelAsset}，ID → 资产名。
     *
     * @author wenbin
     * @since 2026-10-21
     */
    @Component
    @RequiredArgsConstructor
    public static class TwinModelAssetName implements RefTextProvider {

        private static final Logger log = LoggerFactory.getLogger(TwinModelAssetName.class);

        private final IotTwinModelAssetMapper twinModelAssetMapper;

        @Override
        public String type() {
            return "twinModelAsset";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = IotRefTextProviders.parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotTwinModelAsset asset : twinModelAssetMapper.selectByIds(idList)) {
                if (!StringUtils.hasText(asset.getAssetName())) {
                    log.warn("数字孪生引用文本翻译跳过名称为空的模型资产：assetId={}", asset.getId());
                    continue;
                }
                names.put(asset.getId(), asset.getAssetName());
            }
            return names;
        }
    }
}
