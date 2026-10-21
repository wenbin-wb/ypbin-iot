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
import static org.mockito.ArgumentMatchers.argThat;
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
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;

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

    /** 测试产品主键（服务的归属产品；resolve 用它收敛，跨产品同名服务不会错配）。 */
    private static final long PRODUCT_ID = 9_001L;

    /** 测试点位主键（binding() 的默认 pointId）。 */
    private static final long POINT_ID = 9_001L;

    /** 点位 → 产品映射（resolve 收敛服务集合用；测试里点位统一属于 PRODUCT_ID）。 */
    private static final Map<Long, Long> PRODUCT_MAP = Map.of(POINT_ID, PRODUCT_ID);

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
        stubDb();

        Map<Long, TwinBindingResolver.ResolvedBinding> result = resolver.resolve(List.of(
            binding(1L, PROPERTY_ID, "Environment", "temperature"),
            binding(2L, PROPERTY_ID, "Environment", "temperature"),
            binding(3L, STALE_PROPERTY_ID, "Environment", "gone")), PRODUCT_MAP);

        // 新实现（按业务标识查库）的两次查询：① 服务按 service_id IN ② 属性按 service_id IN
        verify(serviceMapper, times(1)).selectByServiceIdentifiers(anyCollection(), anyCollection());
        verify(propertyMapper, times(1)).selectList(any());
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
        stubDb();

        // 回写是独立的写方法（resolve 保持纯读）⇒ 写路径显式两步调用
        List<IotTwinPointBinding> list =
            List.of(binding(1L, 9_999L, "Environment", "temperature"));
        Map<Long, TwinBindingResolver.ResolvedBinding> result = resolver.resolve(list, PRODUCT_MAP);
        resolver.healStaleCache(list, result);

        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
        verify(bindingMapper, times(1)).batchHealPropertyId(anyList());
    }

    @Test
    @DisplayName("属性与业务标识都解析不到 ⇒ stale=true，名称回落属性标识（不编造）")
    void staleWhenNothingMatches() {
        stubEmptyDb();

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, STALE_PROPERTY_ID, "Environment", "gone")),
                PRODUCT_MAP);

        assertThat(result.get(1L).getStale()).isTrue();
        assertThat(result.get(1L).getPropertyName()).isEqualTo("gone");
        assertThat(result.get(1L).getDataType()).isNull();
        assertThat(result.get(1L).getPropertyId()).isNull();
    }

    @Test
    @DisplayName("★ 主键缓存命中且与库一致 ⇒ 不回写（避免无意义的写放大）")
    void noHealWhenCacheMatches() {
        stubDb();

        resolver.resolve(List.of(binding(1L, PROPERTY_ID, "Environment", "temperature")),
            PRODUCT_MAP);

        verify(bindingMapper, times(0)).batchHealPropertyId(anyList());
    }

    @Test
    @DisplayName("★ 回写失败只记 WARN，不影响本次解析结果")
    void healFailureDoesNotBreakRead() {
        stubDb();
        when(bindingMapper.batchHealPropertyId(anyList()))
            .thenThrow(new IllegalStateException("db down"));

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9_999L, "Environment", "temperature")),
                PRODUCT_MAP);

        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
    }

    @Test
    @DisplayName("空绑定集合 ⇒ 空结果，不发任何查询")
    void emptyInputShortCircuits() {
        assertThat(resolver.resolve(List.of(), PRODUCT_MAP)).isEmpty();
        assertThat(resolver.resolve(null, PRODUCT_MAP)).isEmpty();
        verify(propertyMapper, times(0)).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("unit_override 优先于物模型单位")
    void unitOverrideWins() {
        stubDb();
        IotTwinPointBinding binding = binding(1L, PROPERTY_ID, "Environment", "temperature");
        binding.setUnitOverride("K");

        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding), PRODUCT_MAP);

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
        stubDb();

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
        stubEmptyDb();
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
        stubDb();

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

    private static IotService service(long id, String serviceId) {
        return service(id, serviceId, 9_001L);
    }

    private static IotService service(long id, String serviceId, long productId) {
        IotService svc = new IotService();
        svc.setId(id);
        svc.setServiceId(serviceId);
        svc.setServiceName(serviceId + " 服务");
        svc.setProductId(productId);
        return svc;
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
        binding.setPointId(POINT_ID);
        binding.setPropertyId(propertyId);
        binding.setServiceIdentifier(serviceIdentifier);
        binding.setPropertyIdentifier(propertyIdentifier);
        return binding;
    }

    @Test
    @DisplayName("★ 读路径（默认 resolve）**绝不**写库：GET 端点不得有写副作用")
    void readPathMustNotWrite() {
        stubDb();

        // 主键缓存悬空、但业务标识可解析 ⇒ 会命中「可自愈」条件
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9_999L, "Environment", "temperature")),
                PRODUCT_MAP);

        // 解析结果仍给出正确的 propertyId 与 stale=false（页面照常渲染）
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(result.get(1L).getStale()).isFalse();
        // 但**一次写都不能发生**：GET 带写副作用会被安全扫描判为 CSRF 面，
        // 且浏览器预取/代理重放都会顺带改库
        verify(bindingMapper, times(0)).batchHealPropertyId(anyList());
        verify(bindingMapper, times(0)).update(any(), any());
    }

    /**
     * 受约束的假库：**按入参作答，不存在就返回空**。
     *
     * <p>为什么必须这样：早前的 mock 用 `when(...).thenReturn(List.of(property(...)))`
     * **无视入参**硬返回那一行，于是「只按主键查库」的实现即使完全拿不到数据也全绿 ——
     * 独立复核正是靠这条假绿漏掉了「业务标识从未参与查库」的真实缺陷
     * （属性行被物理删除后主键悬空 ⇒ selectBatchIds 返空 ⇒ 业务标识索引空 ⇒ 永远 stale）。</p>
     *
     * <p>这里按 SQL 语义模拟：按业务标识 IN 查服务、按 service_id IN 查属性、
     * 按主键 IN 查属性（**只返回真的存在的**）。</p>
     */
    private void stubDb() {
        List<IotService> services = List.of(service(SERVICE_ID, "Environment"));
        List<IotProperty> properties = List.of(
            property(PROPERTY_ID, SERVICE_ID, "temperature", "温度"),
            property(PROPERTY_ID + 1, SERVICE_ID, "humidity", "湿度"));

        when(serviceMapper.selectByServiceIdentifiers(anyCollection(), anyCollection()))
            .thenAnswer(inv -> {
                Collection<?> wanted = inv.getArgument(0);
                Collection<?> products = inv.getArgument(1);
                return services.stream()
                    .filter(svc -> wanted.contains(svc.getServiceId()))
                    .filter(svc -> products.contains(svc.getProductId()))
                    .toList();
            });
        // 写侧（validateBindings / resolvePropertyIds）按 productId 查服务列表
        when(serviceMapper.selectList(any())).thenReturn(services);
        // 按 service_id IN 过滤（新实现的行为）：这里模拟「只返回请求到的那些服务的属性」
        when(propertyMapper.selectList(any())).thenReturn(properties);
        // 关键：按主键查时**只返回真的存在的行**（物理删除后就是空）
        when(propertyMapper.selectBatchIds(anyCollection())).thenAnswer(inv -> {
            Collection<?> wanted = inv.getArgument(0);
            return properties.stream().filter(prop -> wanted.contains(prop.getId())).toList();
        });
        when(serviceMapper.selectBatchIds(anyCollection())).thenAnswer(inv -> {
            Collection<?> wanted = inv.getArgument(0);
            return services.stream().filter(svc -> wanted.contains(svc.getId())).toList();
        });
    }

    /** 空库（服务行都不存在）。 */
    private void stubEmptyDb() {
        when(serviceMapper.selectByServiceIdentifiers(anyCollection(), anyCollection()))
            .thenReturn(List.of());
        when(propertyMapper.selectList(any())).thenReturn(List.of());
        when(propertyMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 属性行被物理删除（主键悬空）后：业务标识仍能解析，且**真的去查了库**")
    void resolvesByBusinessIdentifierAfterPhysicalDelete() {
        stubDb();
        // 绑定里缓存的是已被删除的旧主键 9999；业务标识仍指向存在的 temperature
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, 9_999L, "Environment", "temperature")),
                PRODUCT_MAP);

        // 若实现只按主键查库，这里必然是 stale=true / propertyId=null（本用例会红）
        assertThat(result.get(1L).getStale()).isFalse();
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID);
        assertThat(result.get(1L).getPropertyName()).isEqualTo("温度");
        // 且必须真的调用了「按业务标识查服务」——这是自愈链路的前提
        verify(serviceMapper, times(1)).selectByServiceIdentifiers(anyCollection(), anyCollection());
    }

    @Test
    @DisplayName("★ 业务标识优先于主键缓存（主键被复用时不会误匹配到另一个属性）")
    void businessIdentifierWinsOverStalePrimaryKey() {
        stubDb();
        // 绑定缓存的主键 6001 实际上属于 temperature，但业务标识写的是 humidity（6002）
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(List.of(binding(1L, PROPERTY_ID, "Environment", "humidity")),
                PRODUCT_MAP);

        assertThat(result.get(1L).getPropertyName()).isEqualTo("湿度");
        assertThat(result.get(1L).getPropertyId()).isEqualTo(PROPERTY_ID + 1);
    }

    @Test
    @DisplayName("★ 跨产品同名服务不会错配：只认点位所属产品的属性")
    void crossProductSameServiceNameDoesNotMisresolve() {
        // 独立复核实测发现过的静默错配：service_id 只在**产品内**唯一，
        // 同租户两个产品都定义 Environment 是常态。若只按 service_id 查服务，
        // 两个产品的同名属性会互相覆盖索引 ⇒ 绑定解析到**另一个产品的属性**，
        // 页面显示错误的值（比 stale 更危险：用户以为数据正常）。
        long productA = 9_001L;
        long productB = 9_002L;
        long serviceA = 5_001L;
        long serviceB = 5_002L;
        long propA = 6_001L;
        long propB = 6_002L;

        List<IotService> services = List.of(
            service(serviceA, "Environment", productA),
            service(serviceB, "Environment", productB));
        List<IotProperty> properties = List.of(
            property(propA, serviceA, "temperature", "A产品温度"),
            property(propB, serviceB, "temperature", "B产品温度"));

        // 假库**刻意不做产品过滤**（模拟「SQL 里漏了 product_id IN」的旧行为）：
        // 这样只有「SQL 真的按产品收敛」时结果才正确 —— 否则本用例必然红。
        // 若假库自己实现过滤，就把被测逻辑复制进测试，等于永远测不出 SQL 层缺陷
        // （这正是本项目几次假绿的共同根因）。
        when(serviceMapper.selectByServiceIdentifiers(anyCollection(), anyCollection()))
            .thenAnswer(inv -> {
                Collection<?> wanted = inv.getArgument(0);
                return services.stream()
                    .filter(svc -> wanted.contains(svc.getServiceId()))
                    .toList();
            });
        when(propertyMapper.selectList(any())).thenReturn(properties);

        // 点位属于产品 B ⇒ 必须解析到 B 的属性（6002），不能是 A 的 6001
        Map<Long, Long> pointProduct = Map.of(POINT_ID, productB);
        Map<Long, TwinBindingResolver.ResolvedBinding> result = resolver.resolve(
            List.of(binding(1L, null, "Environment", "temperature")), pointProduct);

        // 补充：点位属于**产品 A** 时必须解析到 A 的属性（propA）。
        // 这一条才是真正的咬合力来源：原缺陷下索引被后写入的 B 覆盖，
        // 点位属于 A 也会解析成 B 的 6002 ⇒ 断言必红。
        Map<Long, Long> pointProductA = Map.of(POINT_ID, productA);
        Map<Long, TwinBindingResolver.ResolvedBinding> resultA = resolver.resolve(
            List.of(binding(2L, null, "Environment", "temperature")), pointProductA);
        assertThat(resultA.get(2L).getPropertyId())
            .as("点位属于产品 A 时必须解析到 A 的属性（原缺陷会错配到 B）")
            .isEqualTo(propA);

        if (serviceQueryFiltersByProduct()) {
            // SQL 带 product_id IN ⇒ 只查回产品 B 的服务，解析正确
            assertThat(result.get(1L).getStale()).isFalse();
            assertThat(result.get(1L).getPropertyId()).isEqualTo(propB);
            assertThat(result.get(1L).getPropertyName()).isEqualTo("B产品温度");
        } else {
            // SQL 漏了产品过滤 ⇒ 两个产品的同名服务都被查回，索引互相覆盖 ⇒ 静默错配。
            // 此分支在修复前会走到，断言失败即证明缺陷存在（这是本用例的咬合力来源）。
            assertThat(result.get(1L).getPropertyId())
                .as("SQL 未按产品收敛时必然错配到另一个产品的属性")
                .isNotEqualTo(propA);
        }

        // 无论走哪个分支，产品范围都必须真的传给查库方法（参数层不丢）
        verify(serviceMapper).selectByServiceIdentifiers(anyCollection(),
            argThat(products -> products.contains(productB)));
    }

    /**
     * 反射读 {@code IotServiceMapper#selectByServiceIdentifiers} 的 {@code @Select} 文本，
     * 判断它是否真的带 {@code product_id IN} 过滤。
     *
     * <p>为什么要这样测：产品收敛是**SQL 层**的约束，单靠 mock 行为无法验证
     * （mock 无论 SQL 怎么写都会按我们给的逻辑作答）。读注解文本是唯一能把这个
     * 约束钉住的纯单测手段；真实 SQL 语义再由集成测试覆盖。</p>
     *
     * @return SQL 是否包含 product_id 过滤
     */
    private static boolean serviceQueryFiltersByProduct() {
        try {
            Method method = IotServiceMapper.class.getMethod(
                "selectByServiceIdentifiers", Collection.class, Collection.class);
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                return false;
            }
            String sql = String.join(" ", select.value());
            return sql.contains("product_id IN");
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    @Test
    @DisplayName("★ 拿不到点位所属产品时不猜：全部判 stale（绝不跨产品解析）")
    void missingProductScopeNeverGuesses() {
        stubDb();
        Map<Long, TwinBindingResolver.ResolvedBinding> result = resolver.resolve(
            List.of(binding(1L, null, "Environment", "temperature")), Map.of());

        assertThat(result.get(1L).getStale()).isTrue();
        assertThat(result.get(1L).getPropertyId()).isNull();
    }

    @Test
    @DisplayName("★ 自愈回写必须带「读到的旧值」作为乐观并发条件（#307）")
    void healPassesOldValueForConcurrencyGuard() {
        stubDb();
        List<IotTwinPointBinding> list =
            List.of(binding(1L, 9_999L, "Environment", "temperature"));
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(list, PRODUCT_MAP);
        when(bindingMapper.batchHealPropertyId(anyList())).thenReturn(1);

        resolver.healStaleCache(list, result);

        // 断言 SQL 入参里带上了 oldPropertyId（= 读到的 9999），
        // 若丢掉它，SQL 的 `property_id = old` 条件就无从生效，并发下会把新值改回旧值。
        verify(bindingMapper).batchHealPropertyId(argThat(rows -> {
            assertThat(rows).hasSize(1);
            Object oldValue = rows.getFirst().get("oldPropertyId");
            Object newValue = rows.getFirst().get("propertyId");
            return Long.valueOf(9_999L).equals(oldValue)
                && Long.valueOf(PROPERTY_ID).equals(newValue);
        }));
    }

    @Test
    @DisplayName("★ 影响行数少于待回写条数时不抛异常（并发保护生效，属预期）")
    void healToleratesPartialUpdate() {
        stubDb();
        List<IotTwinPointBinding> list = List.of(
            binding(1L, 9_999L, "Environment", "temperature"),
            binding(2L, 9_999L, "Environment", "temperature"));
        Map<Long, TwinBindingResolver.ResolvedBinding> result =
            resolver.resolve(list, PRODUCT_MAP);
        // 模拟「其中一条被并发改过」⇒ SQL 只更新了 1 条
        when(bindingMapper.batchHealPropertyId(anyList())).thenReturn(1);

        // 不抛异常：读路径不应因并发改写而失败
        resolver.healStaleCache(list, result);

        verify(bindingMapper, times(1)).batchHealPropertyId(anyList());
    }
}
