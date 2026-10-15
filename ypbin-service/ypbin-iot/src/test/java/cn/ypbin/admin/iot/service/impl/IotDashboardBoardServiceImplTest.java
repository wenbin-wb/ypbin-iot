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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDashboardBoardConfig;
import cn.ypbin.admin.iot.mapper.IotDashboardBoardConfigMapper;
import cn.ypbin.admin.iot.model.req.DashboardBoardSaveReq;
import cn.ypbin.admin.iot.model.resp.DashboardBoardResp;
import cn.ypbin.starter.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 组态看板配置服务的纯逻辑单测（Mockito mock Mapper，不起 Spring、不连库）。
 *
 * <p>钉住五件事：① 无配置返回**空数组**（不是 null，否则前端判为结构不兼容并重置看板）；
 * ② 保存后回读一致（写入的 JSON 与读出的组件逐字段相同，含 {@code unit/decimals/max/min/
 * windowMinutes} 与未知字段的原样透传）；③ JSON 非法/超限/结构非法给人话报错，而**恰好命中边界**
 * （50 个组件、64KB 配置）必须放行；④ 覆盖式替换——第二次保存后旧组件不残留（不是字段级合并）；
 * ⑤ 读路径对「尚无配置」与「数据损坏」区分对待（前者空数组、后者 fail-loud）。</p>
 *
 * <p><b>为什么边界用例按字节而不是字符</b>：上限是对序列化后的 UTF-8 字节数设的，中文标题
 * 一个字符占 3 字节；按字符断言会在「用中文标题刚好越界」时漏判，故用真实序列化字节数双向钉住
 * （恰好 64KB 接受、+1 字节拒绝）。</p>
 *
 * <p>租户条件由 MyBatis-Plus 租户插件在真实 SQL 上追加，本测试不重复验证（那由
 * {@code IotTenantIsolationGateTest}、{@code IotDashboardBoardTenantIsolationTest} 与真库 IT 覆盖）。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
class IotDashboardBoardServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IotDashboardBoardConfigMapper mapper = mock(IotDashboardBoardConfigMapper.class);

    private final IotDashboardBoardServiceImpl service =
        new IotDashboardBoardServiceImpl(mapper, MAPPER);

    @Test
    @DisplayName("读取：从未配置时返回空数组（不得返回 null，否则前端会重置看板）")
    void getBoardShouldReturnEmptyWidgetsWhenAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);

        DashboardBoardResp resp = service.getBoard();

        assertThat(resp).isNotNull();
        assertThat(resp.getWidgets())
            .as("无配置必须返回空数组：前端把 widgets:null 判为结构不兼容并重置看板")
            .isNotNull()
            .isEmpty();
    }

    @Test
    @DisplayName("读取：已有配置时按写入原样读回（字段不裁剪、不改名）")
    void getBoardShouldReturnStoredWidgets() {
        IotDashboardBoardConfig row = new IotDashboardBoardConfig();
        row.setId(9001L);
        row.setConfigJson("[{\"id\":\"board-1\",\"type\":\"gauge\",\"title\":\"温度\","
            + "\"deviceId\":\"11\",\"propertyId\":\"temp\",\"unit\":\"℃\","
            + "\"decimals\":1,\"max\":100,\"min\":0,\"windowMinutes\":30}]");
        when(mapper.selectOne(any())).thenReturn(row);

        List<Map<String, Object>> widgets = service.getBoard().getWidgets();

        assertThat(widgets).hasSize(1);
        Map<String, Object> widget = widgets.get(0);
        assertThat(widget).containsEntry("id", "board-1")
            .containsEntry("type", "gauge")
            .containsEntry("title", "温度")
            .containsEntry("deviceId", "11")
            .containsEntry("propertyId", "temp")
            .containsEntry("unit", "℃")
            .containsEntry("windowMinutes", 30);
    }

    @Test
    @DisplayName("读取：库中 config_json 损坏时逐形态显式报错（不静默返回空看板，否则用户看板凭空消失）")
    void getBoardShouldFailLoudlyOnCorruptedJson() {
        // 四种真实损坏形态：语法错误、JSON 非数组、元素非对象、标量。
        // 逐一断言（只测一种会让「其它形态静默返回空看板」的缺陷漏网）
        assertCorruptedGetFails("not-a-json");
        assertCorruptedGetFails("{}");
        assertCorruptedGetFails("3");
        assertCorruptedGetFails("[1,2,3]");
    }

    @Test
    @DisplayName("读取：config_json 为 JSON null / 空白时按「无组件」返回空数组（非损坏路径，有意为之）")
    void getBoardShouldTreatJsonNullAndBlankAsNoConfig() {
        // 与「损坏」刻意区分：空白/JSON null 语义上就是「没有组件」，不存在丢数据的可能；
        // 正常写路径永远写 "[]"（见 saveBoardShouldAcceptEmptyWidgets），该形态只可能由手工改库触发。
        // 若这里 fail-loud，一次误改会让整个租户的看板读取持续报错，且错误信息帮不上恢复。
        assertThat(getWidgets("null")).isEmpty();
        assertThat(getWidgets("   ")).isEmpty();
    }

    @Test
    @DisplayName("保存：首次保存插入一行，config_json 与请求组件一致")
    void saveBoardShouldInsertWhenAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);
        DashboardBoardSaveReq req = reqOfWidget(widget("board-1", "value", "进水压力", "11", "pressure"));

        service.saveBoard(req);

        ArgumentCaptor<IotDashboardBoardConfig> captor =
            ArgumentCaptor.forClass(IotDashboardBoardConfig.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getConfigJson())
            .contains("\"id\":\"board-1\"")
            .contains("\"propertyId\":\"pressure\"");
        verify(mapper, never()).updateById(any(IotDashboardBoardConfig.class));
    }

    @Test
    @DisplayName("保存：已有配置整体替换（旧组件不残留，不是字段级合并）")
    void saveBoardShouldReplaceAllWidgets() {
        IotDashboardBoardConfig existing = new IotDashboardBoardConfig();
        existing.setId(9003L);
        existing.setConfigJson("[{\"id\":\"stale\",\"type\":\"line\",\"title\":\"旧\","
            + "\"deviceId\":\"1\",\"propertyId\":\"old\"}]");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.saveBoard(reqOfWidget(widget("fresh-1", "status", "在线状态", "11", "online")));

        ArgumentCaptor<IotDashboardBoardConfig> captor =
            ArgumentCaptor.forClass(IotDashboardBoardConfig.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9003L);
        assertThat(captor.getValue().getConfigJson())
            .as("覆盖式替换：旧组件 stale 必须消失")
            .doesNotContain("stale")
            .contains("fresh-1");
        // 组件数也必须恰好是请求里的数量：只断言「包含新 id」会让「旧的被追加保留」漏网
        assertThat(readWidgetJson(captor.getValue().getConfigJson()))
            .as("覆盖式替换后组件数必须等于本次请求，不得残留旧组件")
            .hasSize(1);
    }

    @Test
    @DisplayName("保存：清空看板（空数组）合法，写入 []")
    void saveBoardShouldAcceptEmptyWidgets() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.saveBoard(reqOfList(List.of()));

        ArgumentCaptor<IotDashboardBoardConfig> captor =
            ArgumentCaptor.forClass(IotDashboardBoardConfig.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getConfigJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("保存：widgets 为 null（漏传）显式报错，不得当成清空看板")
    void saveBoardShouldRejectNullWidgets() {
        assertThatThrownBy(() -> service.saveBoard(reqOfList(null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("组件列表不能为空");
        verify(mapper, never()).insert(any(IotDashboardBoardConfig.class));
    }

    @Test
    @DisplayName("保存：组件缺必填字段时人话报错并定位到第几个组件")
    void saveBoardShouldRejectMissingRequiredField() {
        Map<String, Object> broken = widget("board-1", "value", "标题", "11", "p");
        broken.remove("deviceId");

        assertThatThrownBy(() -> service.saveBoard(reqOfWidget(broken)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("第 1 个组件")
            .hasMessageContaining("deviceId");
        verify(mapper, never()).insert(any(IotDashboardBoardConfig.class));
    }

    @Test
    @DisplayName("保存：组件 id 重复（含跨类型同值）时人话报错并定位到第几个组件")
    void saveBoardShouldRejectDuplicateIds() {
        List<Object> widgets = new ArrayList<>();
        widgets.add(widget("dup", "value", "A", "11", "p1"));
        widgets.add(widget("dup", "value", "B", "12", "p2"));

        assertThatThrownBy(() -> service.saveBoard(reqOfList(widgets)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("第 2 个组件")
            .hasMessageContaining("重复");

        // 数字 id 归一为字符串后与同值字符串 id 撞车：11L 与 "11" 必须判为重复
        // （否则同一张卡片会被写成两条，前端按 id 去重时会丢一条）
        List<Object> numericDup = new ArrayList<>();
        numericDup.add(widget(11L, "value", "A", "11", "p1"));
        numericDup.add(widget("11", "value", "B", "12", "p2"));
        assertThatThrownBy(() -> service.saveBoard(reqOfList(numericDup)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("保存：组件数边界——恰好 50 接受、51 拒绝（服务侧复核，不依赖 @Size）")
    void saveBoardShouldEnforceWidgetCountBoundary() {
        // 恰好上限必须放行：只有「拒绝 51」会让「上限被写成 49」这类收窄悄悄通过
        String jsonAtLimit = saveAndCaptureJson(widgets(DashboardBoardSaveReq.MAX_WIDGETS));
        assertThat(readWidgetJson(jsonAtLimit)).hasSize(DashboardBoardSaveReq.MAX_WIDGETS);

        assertThatThrownBy(() -> saveBoard(widgets(DashboardBoardSaveReq.MAX_WIDGETS + 1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能超过 " + DashboardBoardSaveReq.MAX_WIDGETS);
    }

    @Test
    @DisplayName("保存：非对象元素（把数组元素写成字符串）人话报错，不抛框架反序列化堆栈")
    void saveBoardShouldRejectNonObjectElement() {
        List<Object> widgets = new ArrayList<>();
        widgets.add("not-an-object");

        assertThatThrownBy(() -> service.saveBoard(reqOfList(widgets)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("必须是 JSON 对象");
    }

    @Test
    @DisplayName("保存：配置体积边界——恰好 65536 字节接受、65537 字节拒绝（真实序列化字节数）")
    void saveBoardShouldEnforceConfigSizeBoundary() {
        // 二分出「仍被接受」的最大 title 填充长度：恰好 64KB 必须放行，+1 字节必须拒绝。
        // 只测「超限被拒」会漏掉「上限被改成更小值」的收窄；且必须按**字节**判定（中文标题 1 字符 3 字节）。
        int lo = 0;
        int hi = 200_000;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (acceptedAsSave(titleFill(mid))) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        final int maxAccepted = lo;
        String atLimit = saveAndCaptureJson(titleFill(maxAccepted));
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8).length)
            .as("恰好 64KB 的配置必须被接受")
            .isEqualTo(64 * 1024);

        // 拒绝侧只看异常：二分过程中已发生过成功 insert，用 never() 断言「没有写入」会得到假红。
        // 真正要钉的是「超限被拦在写库之前」——由 normalize/serialize 在 mapper 调用之前抛错保证，
        // 「超限不写库」的 never 语义由 saveBoardShouldRejectOversizedConfig 单独覆盖。
        assertThatThrownBy(() -> saveBoard(titleFill(maxAccepted + 1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("看板配置过大")
            .hasMessageContaining("64 KB");
    }

    @Test
    @DisplayName("保存：配置超限必须在写库之前被拦下（不得半写）")
    void saveBoardShouldRejectOversizedConfigBeforePersisting() {
        Map<String, Object> huge = widget("big", "value", "x".repeat(70 * 1024), "11", "p");

        assertThatThrownBy(() -> service.saveBoard(reqOfWidget(huge)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("看板配置过大")
            .hasMessageContaining("64 KB");
        verify(mapper, never()).insert(any(IotDashboardBoardConfig.class));
        verify(mapper, never()).updateById(any(IotDashboardBoardConfig.class));
    }

    @Test
    @DisplayName("保存：并发首写撞唯一键时回退为更新（两次 selectOne，最后一次是锁读）")
    void saveBoardShouldFallBackToUpdateOnDuplicateKey() {
        IotDashboardBoardConfig concurrent = new IotDashboardBoardConfig();
        concurrent.setId(9004L);
        concurrent.setConfigJson("[]");
        when(mapper.selectOne(any())).thenReturn(null, concurrent);
        when(mapper.insert(any(IotDashboardBoardConfig.class)))
            .thenThrow(new DuplicateKeyException("uk_iot_dashboard_board_config"));

        service.saveBoard(reqOfWidget(widget("board-1", "value", "压力", "11", "pressure")));

        verify(mapper, times(2)).selectOne(any());
        verify(mapper).insert(any(IotDashboardBoardConfig.class));
        ArgumentCaptor<IotDashboardBoardConfig> captor =
            ArgumentCaptor.forClass(IotDashboardBoardConfig.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9004L);
        assertThat(captor.getValue().getConfigJson()).contains("board-1");
    }

    @Test
    @DisplayName("保存：unit/decimals/max/min/windowMinutes 与未知字段原样往返，未被裁剪")
    void saveBoardShouldPreserveExtraAndUnknownFields() {
        Map<String, Object> widget = widget("w1", "gauge", "温度", "11", "temp");
        widget.put("unit", "℃");
        widget.put("decimals", 1);
        widget.put("max", 100);
        widget.put("min", 0);
        widget.put("windowMinutes", 30);
        // 前端未来新增的字段（含嵌套结构）：后端不解释、也不许裁剪，否则前端升级会静默丢配置
        widget.put("futureField", Map.of("nested", List.of(1, 2)));

        String json = saveAndCaptureJson(List.of(widget));
        List<Map<String, Object>> parsed = DashboardBoardWidgets.parse(MAPPER, json);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0))
            .containsEntry("unit", "℃")
            .containsEntry("decimals", 1)
            .containsEntry("max", 100)
            .containsEntry("min", 0)
            .containsEntry("windowMinutes", 30)
            .containsKey("futureField");
        assertThat(String.valueOf(parsed.get(0).get("futureField"))).contains("nested");
    }

    @Test
    @DisplayName("保存：撞唯一键后仍查不到行时原样抛出，不静默吞异常")
    void saveBoardShouldRethrowWhenDuplicateButNoRowFound() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(IotDashboardBoardConfig.class)))
            .thenThrow(new DuplicateKeyException("uk_iot_dashboard_board_config"));

        assertThatThrownBy(() -> service.saveBoard(reqOfWidget(widget("board-1", "value", "T", "1", "p"))))
            .isInstanceOf(DuplicateKeyException.class);
        verify(mapper, never()).updateById(any(IotDashboardBoardConfig.class));
    }

    /**
     * 断言指定 config_json 的读路径必须 fail-loud。
     *
     * @param configJson 库中的配置文本
     */
    private void assertCorruptedGetFails(String configJson) {
        IotDashboardBoardConfig row = new IotDashboardBoardConfig();
        row.setId(9002L);
        row.setConfigJson(configJson);
        when(mapper.selectOne(any())).thenReturn(row);

        assertThatThrownBy(service::getBoard)
            .as("config_json=%s 必须显式报错而不是静默返回空看板", configJson)
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("看板配置数据损坏");
    }

    /**
     * 读取指定 config_json 的组件列表。
     *
     * @param configJson 库中的配置文本
     * @return 组件列表
     */
    private List<Map<String, Object>> getWidgets(String configJson) {
        IotDashboardBoardConfig row = new IotDashboardBoardConfig();
        row.setId(9005L);
        row.setConfigJson(configJson);
        when(mapper.selectOne(any())).thenReturn(row);
        return service.getBoard().getWidgets();
    }

    /**
     * 保存并返回写入库中的 config_json（mock 无既有行 ⇒ 走 insert）。
     *
     * @param widgets 组件列表
     * @return 入库的 JSON 文本
     */
    private String saveAndCaptureJson(List<Object> widgets) {
        when(mapper.selectOne(any())).thenReturn(null);
        service.saveBoard(reqOfList(widgets));
        ArgumentCaptor<IotDashboardBoardConfig> captor =
            ArgumentCaptor.forClass(IotDashboardBoardConfig.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue().getConfigJson();
    }

    /**
     * 保存（断言必须走 insert 一次），用于边界用例的「接受」侧。
     *
     * @param widgets 组件列表
     */
    private void saveBoard(List<Object> widgets) {
        saveAndCaptureJson(widgets);
    }

    /**
     * 执行一次保存，返回是否被接受（仅用于二分探测上限，不产生断言）。
     *
     * @param widgets 组件列表
     * @return 被接受返回 {@code true}
     */
    private boolean acceptedAsSave(List<Object> widgets) {
        // 用**独立** mock 探测：共用字段 mock 会让二分过程中反复调用同一 mock，
        // 破坏其它用例里 verify(...) 的调用次数语义（本仓踩过同类坑）
        IotDashboardBoardConfigMapper probeMapper = mock(IotDashboardBoardConfigMapper.class);
        when(probeMapper.selectOne(any())).thenReturn(null);
        try {
            new IotDashboardBoardServiceImpl(probeMapper, MAPPER).saveBoard(reqOfList(widgets));
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    /**
     * 构造一个标题被填充到指定长度的单组件列表（用于体积边界二分）。
     *
     * @param length 标题填充长度
     * @return 组件列表
     */
    private static List<Object> titleFill(int length) {
        return List.of(widget("big", "value", "x".repeat(length), "11", "p"));
    }

    /**
     * 构造指定数量的合法组件。
     *
     * @param count 数量
     * @return 组件列表
     */
    private static List<Object> widgets(int count) {
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(widget("w-" + i, "value", "T" + i, "11", "p"));
        }
        return list;
    }

    /**
     * 解析 config_json 为组件列表（断言用）。
     *
     * @param json 配置文本
     * @return 组件列表
     */
    private List<Map<String, Object>> readWidgetJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception ex) {
            throw new AssertionError("config_json 不是合法组件数组：" + json, ex);
        }
    }

    /**
     * 构造保存请求。
     *
     * @param widgets 组件列表
     * @return 请求对象
     */
    private static DashboardBoardSaveReq reqOfList(List<Object> widgets) {
        DashboardBoardSaveReq req = new DashboardBoardSaveReq();
        req.setWidgets(widgets);
        return req;
    }

    /**
     * 构造单组件请求。
     *
     * @param widget 组件
     * @return 请求对象
     */
    private static DashboardBoardSaveReq reqOfWidget(Map<String, Object> widget) {
        return reqOfList(List.of(widget));
    }

    /**
     * 构造一个合法组件（字段名与前端 IotBoardWidget 同名）。
     *
     * @param id         组件 id
     * @param type       组件类型
     * @param title      标题
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     * @return 组件
     */
    private static Map<String, Object> widget(String id, String type, String title, String deviceId,
                                              String propertyId) {
        return widget((Object) id, type, title, deviceId, propertyId);
    }

    /**
     * 构造一个合法组件（id 允许非字符串，用于验证数字 id 归一化后的去重语义）。
     *
     * @param id         组件 id（任意类型）
     * @param type       组件类型
     * @param title      标题
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     * @return 组件
     */
    private static Map<String, Object> widget(Object id, String type, String title, String deviceId,
                                              String propertyId) {
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("id", id);
        widget.put("type", type);
        widget.put("title", title);
        widget.put("deviceId", deviceId);
        widget.put("propertyId", propertyId);
        return widget;
    }
}
