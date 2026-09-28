/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单测里初始化 MyBatis-Plus 实体元数据的测试支撑。
 *
 * <p><b>为什么需要它</b>：告警相关服务用 {@code LambdaQueryWrapper} 构造查询条件，而 Lambda 的列名解析依赖
 * MyBatis-Plus 的 {@code TableInfo} 缓存。纯单测没有 MyBatis 容器 ⇒ 缓存为空时会抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。本类为被测实体登记元数据，
 * 让**生产实现**（而不是它的替身）能被单测驱动。</p>
 *
 * <p><b>覆盖边界（如实声明，勿夸大）</b>：登记元数据只让 wrapper **能被构造出来**；wrapper 生成的具体
 * 条件（IN 了哪些 id、是否带 {@code is_deleted}）在 Mapper 被 mock 的情况下**不会被执行**，
 * 因此不由单测覆盖——那部分由真库 IT（CI 的 {@code -Pit}）与生产演示承担。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
public final class AlertMybatisTestSupport {

    /** 已登记过的实体（避免重复初始化）。 */
    private static final Set<Class<?>> REGISTERED = new LinkedHashSet<>();

    private AlertMybatisTestSupport() {
    }

    /**
     * 为给定实体登记 TableInfo（幂等，可重复调用）。
     *
     * @param entities 实体类（如 {@code IotDevice.class}）
     */
    public static synchronized void initMetadata(Class<?>... entities) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity : entities) {
            if (REGISTERED.contains(entity)) {
                continue;
            }
            TableInfo info = TableInfoHelper.initTableInfo(assistant, entity);
            if (info != null) {
                REGISTERED.add(entity);
            }
        }
    }
}
