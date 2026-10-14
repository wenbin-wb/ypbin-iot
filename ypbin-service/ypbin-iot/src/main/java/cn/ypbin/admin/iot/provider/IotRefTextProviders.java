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

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceGroup;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.mapper.IotDeviceGroupMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.starter.json.ref.RefTextProvider;
import java.util.ArrayList;
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
 * IoT 引用字段翻译数据源（数据库实现）。
 *
 * <p>starter 的 {@code @RefText} 机制是"有对应 type 的 provider 才输出派生名称"：本类提供
 * {@code device} / {@code product} / {@code group} 三类数据源，分别产出
 * {@code deviceIdName} / {@code productIdName} / {@code parentIdName} / {@code groupIdName}
 * 等派生展示字段（原字段名与值不变）。</p>
 *
 * <p>批量翻译由框架按类型聚合后一次调用，本类一律走 Mapper 的批量主键查询，
 * 不在循环里查库；入参解析容错与空集合短路见 {@link #parseIds(Collection)}。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
public final class IotRefTextProviders {

    private static final Logger log = LoggerFactory.getLogger(IotRefTextProviders.class);

    private IotRefTextProviders() {
    }

    /**
     * 把引用值解析成主键列表：<b>不可解析的值跳过并记 WARN</b>，而不是让整段引用文本翻译直接抛异常。
     *
     * <p>为什么必须容错：引用字段里的值是历史数据（可能是字符串、可能被写过非数字内容）。
     * 一旦有一个脏值就让批量「ID → 名称」整体抛 {@code NumberFormatException}，表现为整批引用翻译报错；
     * 这里跳过脏值并留日志——不静默（本仓禁静默降级），也不因个别脏数据拖垮整批翻译。</p>
     *
     * @param ids 引用值集合
     * @return 可解析的主键列表（可能为空，调用方必须据此短路，不得让空集合进 SQL）
     */
    static List<Long> parseIds(Collection<Object> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> idList = new ArrayList<>(ids.size());
        for (Object id : ids) {
            if (id == null) {
                log.warn("IoT 引用文本翻译跳过空主键");
                continue;
            }
            try {
                idList.add(Long.valueOf(id.toString().trim()));
            } catch (NumberFormatException e) {
                log.warn("IoT 引用文本翻译跳过非数字主键：value={}", id, e);
            }
        }
        return idList;
    }

    /**
     * 设备引用翻译：类型 {@code device}，ID → 设备名称（无名称时回退设备编码）。
     */
    @Component
    @RequiredArgsConstructor
    public static class DeviceName implements RefTextProvider {

        private final IotDeviceMapper deviceMapper;

        @Override
        public String type() {
            return "device";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotDevice device : deviceMapper.selectByIds(idList)) {
                String name = StringUtils.hasText(device.getDeviceName())
                    ? device.getDeviceName() : device.getDeviceCode();
                if (!StringUtils.hasText(name)) {
                    log.warn("IoT 引用文本翻译跳过名称与编码均为空的设备：deviceId={}", device.getId());
                    continue;
                }
                names.put(device.getId(), name);
            }
            return names;
        }
    }

    /**
     * 产品引用翻译：类型 {@code product}，ID → 产品名称（无名称时回退产品编码）。
     */
    @Component
    @RequiredArgsConstructor
    public static class ProductName implements RefTextProvider {

        private final IotProductMapper productMapper;

        @Override
        public String type() {
            return "product";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotProduct product : productMapper.selectByIds(idList)) {
                String name = StringUtils.hasText(product.getProductName())
                    ? product.getProductName() : product.getProductCode();
                if (!StringUtils.hasText(name)) {
                    log.warn("IoT 引用文本翻译跳过名称与编码均为空的产品：productId={}", product.getId());
                    continue;
                }
                names.put(product.getId(), name);
            }
            return names;
        }
    }

    /**
     * 分组引用翻译：类型 {@code group}，ID → 分组名称（无名称的分组跳过并记 WARN）。
     */
    @Component
    @RequiredArgsConstructor
    public static class GroupName implements RefTextProvider {

        private final IotDeviceGroupMapper deviceGroupMapper;

        @Override
        public String type() {
            return "group";
        }

        @Override
        public Map<Object, String> getNames(Collection<Object> ids) {
            List<Long> idList = parseIds(ids);
            if (idList.isEmpty()) {
                return Map.of();
            }
            Map<Object, String> names = new HashMap<>();
            for (IotDeviceGroup group : deviceGroupMapper.selectByIds(idList)) {
                if (!StringUtils.hasText(group.getGroupName())) {
                    log.warn("IoT 引用文本翻译跳过分组名称为空的分组：groupId={}", group.getId());
                    continue;
                }
                names.put(group.getId(), group.getGroupName());
            }
            return names;
        }
    }
}
