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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.entity.IotTwinPointBinding;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.mapper.IotTwinPointBindingMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 绑定解析器：**批量（不 N+1）**、stale 判定、按业务标识自愈回写、写侧校验。
 *
 * @author wenbin
 * @since 2026-10-21
 */
class TwinBindingResolverTest {

    private static final long SERVICE_ID = 5_001L;

    private static final long PROPERTY_ID = 6_001L;

    private static final long STALE_PROPERTY_ID = 6_002L;

    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);

    private final IotServiceMapper serviceMapper = mock(IotServiceMapper.class);

    private final IotTwinPointBindingMapper bindingMapper = mock(IotTwinPointBindingMapper.class);

    private final TwinBindingResolver resolver = new TwinBindingResolver(propertyMapper,
        serviceMapper, bindingMapper);

    /**
     * 初始化 MyBatis-Plus 实体元信息。
     *
     * <p>为什么需要：{@code LambdaUpdateWrapper} 靠方法引用解析列名，列名来自实体 TableInfo；
     * 纯单测不起 Spring、不扫 Mapper 时没有这一步会抛
     * {@code can not find lambda cache for this entity}——而 healStaleCache 会把它 catch 成
     * WARN（读路径不该因写失败而失败），于是测试表现为「update 未被调用」而非报错。
     * 这与 {@code AvailabilityServiceImplTest} / {@code PointMappingIndexTest} 同款处理。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotTwinPointBinding.class);
        TableInfoHelper.initTableInfo(assistant, IotProperty.class);
        TableInfoHelper.initTableInfo(assistant, IotService.class);
    }

    @Test
    @DisplayName("★ 批量解析只发 2 次查询（与绑定数无关）")
    void resolveIsBatched() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));

        Map<Long, TwinBindingResolver.ResolvedBinding> result = resolver.resolve(List.of(
            binding(1L, PROPERTY_ID, "Environment", "temperature"),
            binding(2L, PROPERTY_ID, "Environment", "temperature"),
            binding(3L, STALE_PROPERTY_ID, "Environment", "gone")));

        verify(propertyMapper, times(1)).selectBatchIds(any(Collection.class));
        verify(serviceMapper, times(1)).selectBatchIds(any(Collection.class));
        assertThat(result).hasSize(3);
        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyName()).isEqualTo("温度");
        assertThat(result.get(1L).getDataType()).isEqualTo("decimal");
        assertThat(result.get(1L).getUnit()).isEqualTo("℃");
        assertThat(result.get(3L).getStale()).isTrue();
    }

    @Test
    @DisplayName("★ 属性行被物理删除（主键悬空）但业务标识可解析 ⇒ 不 stale，并回写主键缓存")
    void healsStalePropertyId() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));

        // 回写是写副作用 ⇒ 只有显式传 healCache=true 才执行（读路径必须 false）
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9_999L, "Environment", "temperature")), true);

        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
        verify(bindingMapper, times(1)).batchHealPropertyId(anyList());
    }

    @Test
    @DisplayName("属性与业务标识都解析不到 ⇒ stale=true，名称回落属性标识（不编造）")
    void staleWhenNothingMatches() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, STALE_PROPERTY_ID, "Environment", "gone")));

        assertThat(result.get(1L).getStale()).isTrue();
        assertThat(result.get(1L).getPropertyName()).isEqualTo("gone");
        assertThat(result.get(1L).getDataType()).isNull();
        assertThat(result.get(1L).getPropertyId()).isNull();
    }

    @Test
    @DisplayName("★ 主键缓存命中且与库一致 ⇒ 不回写（避免无意义的写放大）")
    void noHealWhenCacheMatches() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));

        resolver.resolve(List.of(binding(1L, PROPERTY_ID, "Environment", "temperature")));

        verify(bindingMapper, times(0)).batchHealPropertyId(anyList());
    }

    @Test
    @DisplayName("★ 回写失败只记 WARN，不影响本次解析结果")
    void healFailureDoesNotBreakRead() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));
        when(bindingMapper.batchHealPropertyId(anyList()))
            .thenThrow(new IllegalStateException("db down"));

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9_999L, "Environment", "temperature")), true);

        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
    }

    @Test
    @DisplayName("空绑定集合 ⇒ 空结果，不发任何查询")
    void emptyInputShortCircuits() {
        assertThat(resolver.resolve(List.of())).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        verify(propertyMapper, times(0)).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("unit_override 优先于物模型单位")
    void unitOverrideWins() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));
        IotTwinPointBinding binding = binding(1L, PROPERTY_ID, "Environment", "temperature");
        binding.setUnitOverride("K");

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding));

        assertThat(result.get(1L).getUnit()).isEqualTo("K");
    }

    @Test
    @DisplayName("★ 写侧：设备未绑产品 + 有绑定 ⇒ 拒绝并提示先绑产品")
    void validateWithoutProductRejected() {
        assertThatThrownBy(() -> resolver.validateBindings(null,
            List.of(new TwinBindingResolver.BindingKey("Environment", "temperature"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("未绑定产品");
    }

    @Test
    @DisplayName("★ 写侧：一次列全所有不存在的属性组合")
    void validateListsAllMissing() {
        when(serviceMapper.selectList(any())).thenReturn(List.of(serviceEntity(SERVICE_ID)));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));
        when(propertyMapper.selectList(any())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));

        assertThatThrownBy(() -> resolver.validateBindings(9_001L, List.of(
            new TwinBindingResolver.BindingKey("Environment", "temperature"),
            new TwinBindingResolver.BindingKey("Environment", "notExist1"),
            new TwinBindingResolver.BindingKey("Device", "notExist2"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("共 2 个")
            .hasMessageContaining("Environment.notExist1")
            .hasMessageContaining("Device.notExist2");
    }

    @Test
    @DisplayName("写侧：产品无任何物模型服务 ⇒ 拒绝")
    void validateWithoutServicesRejected() {
        when(serviceMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> resolver.validateBindings(9_001L,
            List.of(new TwinBindingResolver.BindingKey("Environment", "temperature"))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("没有任何物模型服务");
    }

    @Test
    @DisplayName("写侧：空绑定集合 ⇒ 不校验（直接返回）")
    void validateEmptyKeysNoop() {
        resolver.validateBindings(null, List.of());
        resolver.validateBindings(9_001L, List.of());
        verify(serviceMapper, times(0)).selectList(any());
    }

    @Test
    @DisplayName("写侧：全部存在 ⇒ 通过，并解析出属性主键")
    void validateAndResolvePropertyIds() {
        when(serviceMapper.selectList(any())).thenReturn(List.of(serviceEntity(SERVICE_ID)));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));
        when(propertyMapper.selectList(any())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));

        List<TwinBindingResolver.BindingKey> keys = List.of(
            new TwinBindingResolver.BindingKey("Environment", "temperature"));
        resolver.validateBindings(9_001L, keys);
        Map<String, Long> propertyIds = resolver.resolvePropertyIds(9_001L, keys);

        assertThat(propertyIds)
            .containsEntry(TwinBindingResolver.key("Environment", "temperature"), PROPERTY_ID);
    }

    @Test
    @DisplayName("业务键拼接：分隔符不会与普通标识冲突")
    void keyConcatenationIsUnambiguous() {
        assertThat(TwinBindingResolver.key("a", "b"))
            .isNotEqualTo(TwinBindingResolver.key("ab", ""));
        assertThat(TwinBindingResolver.key("a", "b"))
            .isNotEqualTo(TwinBindingResolver.key("a\u0000b", ""));
    }

    private static IotService serviceEntity(long id) {
        IotService service = new IotService();
        service.setId(id);
        service.setProductId(9_001L);
        service.setServiceId("Environment");
        return service;
    }

    private static IotService service(long id, String serviceId) {
        IotService service = new IotService();
        service.setId(id);
        service.setServiceId(serviceId);
        service.setServiceName(serviceId + " 服务");
        return service;
    }

    private static IotProperty property(long id, long serviceId, String identifier, String name) {
        IotProperty property = new IotProperty();
        property.setId(id);
        property.setServiceId(serviceId);
        property.setIdentifier(identifier);
        property.setPropertyName(name);
        property.setDataType("decimal");
        property.setUnit("℃");
        return property;
    }

    private static IotTwinPointBinding binding(long id, Long propertyId, String serviceIdentifier,
                                               String propertyIdentifier) {
        IotTwinPointBinding binding = new IotTwinPointBinding();
        binding.setId(id);
        binding.setPointId(1L);
        binding.setPropertyId(propertyId);
        binding.setServiceIdentifier(serviceIdentifier);
        binding.setPropertyIdentifier(propertyIdentifier);
        return binding;
    }

    @Test
    @DisplayName("★ 读路径（默认 resolve）**绝不**写库：GET 端点不得有写副作用")
    void readPathMustNotWrite() {
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(property(PROPERTY_ID, SERVICE_ID, "temperature", "温度")));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(
            List.of(service(SERVICE_ID, "Environment")));

        // 主键缓存悬空、但业务标识可解析 ⇒ 会命中「可自愈」条件
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9_999L, "Environment", "temperature")));

        // 解析结果仍给出正确的 propertyId 与 stale=false（页面照常渲染）
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(result.get(1L).getStale()).isFalse();
        // 但**一次写都不能发生**：GET 带写副作用会被安全扫描判为 CSRF 面，
        // 且浏览器预取/代理重放都会顺带改库
        verify(bindingMapper, times(0)).batchHealPropertyId(anyList());
        verify(bindingMapper, times(0)).update(any(), any());
    }
}
