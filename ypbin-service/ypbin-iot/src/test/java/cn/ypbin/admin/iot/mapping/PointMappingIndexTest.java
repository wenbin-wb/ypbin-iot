/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapping.PointMappingIndex.DeviceCoordinates;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 设备 → 点位坐标索引：批量取数、判空短路、**规范标识与历史主键形态并存**、**孤儿映射收紧**。
 *
 * <p>本类证明的是「取映射并算出坐标索引」这一半（SQL 形态与索引内容）；「用索引做判定与归一」那一半在
 * {@code AvailabilityServiceImplTest}（单测）与 {@code IngestPointMappingIT}（真库）里。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
class PointMappingIndexTest {

    private static final long DEVICE_A = 9300001L;

    private static final long DEVICE_B = 9300002L;

    private static final long PROP_TEMPERATURE = 9130001L;

    private static final long PROP_HUMIDITY = 9130002L;

    private final IotPointMappingMapper pointMappingMapper = mock(IotPointMappingMapper.class);

    private final IotPropertyMapper propertyMapper = mock(IotPropertyMapper.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final PointMappingIndex index =
        new PointMappingIndex(pointMappingMapper, propertyMapper, registry);

    /**
     * 初始化 MyBatis-Plus 实体元信息。
     *
     * <p>为什么需要：{@code LambdaQueryWrapper} 靠方法引用解析列名，列名来自实体 TableInfo；纯单测不起 Spring、
     * 不扫 Mapper，没有这一步会抛 {@code can not find lambda cache for this entity}（与
     * {@code AvailabilityServiceImplTest} 同款处理）。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotPointMapping.class);
        TableInfoHelper.initTableInfo(assistant, IotProperty.class);
    }

    private static IotPointMapping mapping(Long deviceId, Long propertyId) {
        IotPointMapping row = new IotPointMapping();
        row.setDeviceId(deviceId);
        row.setPropertyId(propertyId);
        return row;
    }

    private static IotProperty property(Long id, String identifier) {
        return property(id, identifier, null);
    }

    private static IotProperty property(Long id, String identifier, String dataType) {
        IotProperty row = new IotProperty();
        row.setId(id);
        row.setIdentifier(identifier);
        row.setDataType(dataType);
        return row;
    }

    @Test
    @DisplayName("★ 空设备集合 ⇒ 两张表都不查（先判空短路，不做 IN ()）")
    void emptyDeviceIdsMustNotTouchDatabase() {
        assertThat(index.loadCoordinates(Set.of())).isEmpty();

        verify(pointMappingMapper, never()).selectList(any());
        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("★ 批量：两张表各一次查询，与设备数/点位数无关；索引同时含规范标识与历史主键形态")
    void mustLoadInTwoRoundedTripsAndExposeBothCoordinateForms() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE),
            mapping(DEVICE_A, PROP_HUMIDITY),
            mapping(DEVICE_B, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "temperature"),
            property(PROP_HUMIDITY, "humidity")));

        Map<Long, DeviceCoordinates> mapped = index.loadCoordinates(Set.of(DEVICE_A, DEVICE_B));

        assertThat(mapped).containsOnlyKeys(DEVICE_A, DEVICE_B);
        DeviceCoordinates a = mapped.get(DEVICE_A);
        assertThat(a.canonicalize("temperature")).as("规范标识形如自身").isEqualTo("temperature");
        assertThat(a.canonicalize(String.valueOf(PROP_TEMPERATURE)))
            .as("历史主键字符串形态在过渡期仍被接受，并归一到标识").isEqualTo("temperature");
        assertThat(a.canonicalize("humidity")).isEqualTo("humidity");
        assertThat(a.legacyFormsByCanonical())
            .containsEntry("temperature", Set.of(String.valueOf(PROP_TEMPERATURE)))
            .containsEntry("humidity", Set.of(String.valueOf(PROP_HUMIDITY)));
        assertThat(a.orphanForms()).as("属性行都在 ⇒ 没有孤儿").isEmpty();
        assertThat(a.ambiguousForms()).isEmpty();
        assertThat(a.duplicateIdentifiers()).as("两个点位标识不同 ⇒ 没有坐标级撞名").isEmpty();
        assertThat(mapped.get(DEVICE_B).canonicalize(String.valueOf(PROP_TEMPERATURE)))
            .isEqualTo("temperature");
        verify(pointMappingMapper, times(1)).selectList(any());
        verify(propertyMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("★ 映射查询必须显式 `ORDER BY id`：`aliasesOf` 的「有序」靠它保障（删掉 orderByAsc ⇒ 本用例转红）")
    void mappingQueryMustOrderByIdSoAliasOrderIsDeterministic() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(mapping(DEVICE_A, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(property(PROP_TEMPERATURE, "temperature")));

        index.loadCoordinates(Set.of(DEVICE_A));

        // 说明为什么必须断言 SQL（而不是断言结果集顺序）：无 ORDER BY 时 MySQL 的行序不保证，
        // 而「行序」正是 legacyFormsByCanonical 的插入顺序 ⇒ 会传到生成的 SQL 谓词里。
        // mock mapper 看不到真实行序，所以这里钉住的是**查询本身带了排序**这个可验证事实。
        ArgumentCaptor<Wrapper<IotPointMapping>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pointMappingMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .as("映射查询的 SQL 片段必须含 ORDER BY（否则 aliasesOf 的顺序会跟随不保证的 DB 行序）")
            .containsIgnoringCase("order by");
    }

    @Test
    @DisplayName("★ M-3：坐标索引只取属性点位（查询条件必须带 ref_type='property'，命令点位不得进读数坐标）")
    void commandMappingsMustBeExcludedFromCoordinateIndex() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of());

        index.loadCoordinates(Set.of(DEVICE_A));

        ArgumentCaptor<LambdaQueryWrapper<IotPointMapping>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(pointMappingMapper).selectList(captor.capture());
        // 先渲染 SQL 段：MyBatis-Plus 在渲染时才把参数登记进 paramNameValuePairs（与 DeviceSpecServiceImplTest 同款）
        assertThat(captor.getValue().getSqlSegment())
            .as("WHERE 必须引用 ref_type 列（否则命令点位会混进读数坐标，污染时序/最新值）")
            .contains("ref_type");
        assertThat(captor.getValue().getParamNameValuePairs().toString())
            .as("过滤值必须是 property")
            .contains("property")
            .doesNotContain("command");
    }

    @Test
    @DisplayName("★ aliasesOf：读侧要同时查「标识」与「历史主键字符串」两种存储形态")
    void aliasesOfMustContainBothStorageForms() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(mapping(DEVICE_A, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(property(PROP_TEMPERATURE, "temperature")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.aliasesOf("temperature"))
            .as("有序：规范标识在首位，其后是历史形态 ⇒ 拼出的 SQL 谓词不随 JVM/哈希顺序变化")
            .containsExactly("temperature", String.valueOf(PROP_TEMPERATURE));
        assertThat(a.aliasesOf("unknownPoint"))
            .as("不是本设备的点位 ⇒ 只返回请求形态自身（调用方据此仍按该形态查一次）")
            .containsExactly("unknownPoint");
    }

    @Test
    @DisplayName("★ 映射为空 ⇒ 直接返回空 Map，**不再查属性表**（且不抛）")
    void emptyMappingsMustShortCircuitBeforePropertyQuery() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of());

        assertThat(index.loadCoordinates(Set.of(DEVICE_A))).isEmpty();

        verify(propertyMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("★ 孤儿映射收紧：属性行缺失/标识为空 ⇒ **不算已映射**，只进 orphanForms（统一前它会被放行）")
    void missingOrBlankIdentifierMustBecomeOrphanNotMapped() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "  ")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.canonicalize(String.valueOf(PROP_HUMIDITY)))
            .as("属性行缺失 ⇒ 该映射不再是合法坐标（这正是「孤儿映射仍算已映射」缺口的修复点）").isNull();
        assertThat(a.orphanForms()).contains(String.valueOf(PROP_HUMIDITY));
        assertThat(a.canonicalize(String.valueOf(PROP_TEMPERATURE)))
            .as("标识为空白同样按孤儿处理（拿不到可用坐标）").isNull();
        assertThat(a.orphanForms()).containsExactlyInAnyOrder(String.valueOf(PROP_TEMPERATURE),
            String.valueOf(PROP_HUMIDITY));
        assertThat(a.canonicalize("temperature")).isNull();
    }

    @Test
    @DisplayName("★ 形态撞名（A 的历史主键字符串 == B 的属性标识）⇒ 判给标识且记入 ambiguousForms，结果确定")
    void ambiguousFormMustPreferIdentifierDeterministically() {
        // PROP_TEMPERATURE 的 identifier 恰好就是另一个点位的历史主键字符串 "9130002"
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "9130002"), property(PROP_HUMIDITY, "humidity")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.canonicalize("9130002"))
            .as("撞名时判给**属性标识**（确定性规则，两次调用必须一致）").isEqualTo("9130002");
        assertThat(a.ambiguousForms()).contains("9130002");
        assertThat(a.canonicalize("9130002")).isEqualTo("9130002");
    }

    @Test
    @DisplayName("★ 坐标级撞名：同设备两条映射的**属性标识相同**（跨 service 重名）⇒ 记入 duplicateIdentifiers + 计数，且历史形态全保留")
    void duplicateIdentifiersMustBeReportedAndKept() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        // 同一产品的两个 service 各有一个 temperature（uk 只保证 service 内唯一）
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "temperature"), property(PROP_HUMIDITY, "temperature")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.duplicateIdentifiers())
            .as("必须留痕：两个点位真实共享一个规范坐标，不静默并点").containsExactly("temperature");
        assertThat(a.aliasesOf("temperature"))
            .as("读侧要把两个点位的历史形态都查出来，否则其中一个的存量数据凭空消失")
            .hasSize(3)
            .contains("temperature", String.valueOf(PROP_TEMPERATURE),
                String.valueOf(PROP_HUMIDITY));
        assertThat(registry.get(PointMappingIndex.METRIC_COORDINATE_COLLISION).counter().count())
            .as("撞名次数必须可观测（否则只能靠翻日志）").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("★ 形态级撞名（A 的历史形态 == B 的标识）单独成立时不误报坐标级撞名")
    void formAndCoordinateCollisionsMustBothBeReported() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        // PROP_TEMPERATURE 的 identifier 恰好是 PROP_HUMIDITY 的历史主键字符串（形态撞名），
        // 同时两者又……不是同一标识 ⇒ 这里只验形态级；坐标级由上一用例覆盖
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, String.valueOf(PROP_HUMIDITY)), property(PROP_HUMIDITY, "humidity")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.ambiguousForms()).contains(String.valueOf(PROP_HUMIDITY));
        assertThat(a.duplicateIdentifiers()).isEmpty();
    }

    @Test
    @DisplayName("★ task-23：同一次属性查询顺带取 data_type，产出 identifier → 类型（列 SSOT 的输入）")
    void dataTypeMustBeLoadedInSamePropertyQuery() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "temperature", "decimal"),
            property(PROP_HUMIDITY, "humidity", "string")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.dataTypeOf("temperature")).isEqualTo("decimal");
        assertThat(a.dataTypeOf("humidity")).isEqualTo("string");
        assertThat(a.dataTypeOf("unknown")).isNull();
        // 属性查询只允许一次（与标识同一批取回）；若将来漏 select data_type，上面的 dataTypeOf 断言会先转红。
        verify(propertyMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("★ task-23：同一标识的类型必须唯一且非空；缺失或冲突 ⇒ 类型不可用（不猜、不回落）")
    void missingOrConflictingDataTypeMustBeUnavailable() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY),
            mapping(DEVICE_A, 9130099L)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            // 同一标识 temperature：一条 decimal、一条 string ⇒ 冲突
            property(PROP_TEMPERATURE, "temperature", "decimal"),
            property(PROP_HUMIDITY, "temperature", "string"),
            // 单条但 data_type 缺失 ⇒ 不可用
            property(9130099L, "pressure", null)));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.dataTypeOf("temperature")).as("类型冲突 ⇒ 整体不可用").isNull();
        assertThat(a.dataTypeOf("pressure")).as("类型缺失 ⇒ 不可用").isNull();
        assertThat(a.canonicalize("pressure")).as("类型不影响坐标归一").isEqualTo("pressure");
    }

    @Test
    @DisplayName("★ task-23：重复标识但类型一致 ⇒ 类型可用（不是所有撞名都要丢弃）")
    void duplicateIdentifierWithSameTypeMustKeepType() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(DEVICE_A, PROP_TEMPERATURE), mapping(DEVICE_A, PROP_HUMIDITY)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(
            property(PROP_TEMPERATURE, "temperature", "decimal"),
            property(PROP_HUMIDITY, "temperature", "decimal")));

        DeviceCoordinates a = index.loadCoordinates(Set.of(DEVICE_A)).get(DEVICE_A);

        assertThat(a.duplicateIdentifiers()).containsExactly("temperature");
        assertThat(a.dataTypeOf("temperature")).as("类型一致 ⇒ 可继续写入（坐标撞名仍告警）")
            .isEqualTo("decimal");
    }

    @Test
    @DisplayName("★ 脏映射行（设备或属性为空）被跳过而不是让整批失败")
    void dirtyMappingsMustBeSkipped() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(
            mapping(null, PROP_TEMPERATURE), mapping(DEVICE_A, null), mapping(DEVICE_A, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(property(PROP_TEMPERATURE, "temperature")));

        Map<Long, DeviceCoordinates> mapped = index.loadCoordinates(Set.of(DEVICE_A));

        assertThat(mapped).containsOnlyKeys(DEVICE_A);
        assertThat(mapped.get(DEVICE_A).canonicalByForm()).containsOnlyKeys("temperature",
            String.valueOf(PROP_TEMPERATURE));
    }

    @Test
    @DisplayName("★ 没有映射的设备**不出现**在结果里（调用方据此把它的读数全判为未映射）")
    void deviceWithoutMappingMustBeAbsentFromResult() {
        when(pointMappingMapper.selectList(any())).thenReturn(List.of(mapping(DEVICE_A, PROP_TEMPERATURE)));
        when(propertyMapper.selectList(any())).thenReturn(List.of(property(PROP_TEMPERATURE, "temperature")));

        Map<Long, DeviceCoordinates> mapped = index.loadCoordinates(Set.of(DEVICE_A, DEVICE_B));

        assertThat(mapped).containsOnlyKeys(DEVICE_A);
        assertThat(mapped.getOrDefault(DEVICE_B, DeviceCoordinates.empty()).noMapping())
            .as("未映射设备 → 空索引 → 全部丢弃").isTrue();
    }
}
