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

import cn.ypbin.admin.iot.model.req.DashboardBoardSaveReq;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 组态看板组件的**纯校验/序列化规则**（零 Spring 依赖，便于单测）。
 *
 * <p><b>为什么需要这一层</b>：看板组件是前端定义的展示配置，后端只做「按租户存取」。但
 * 「只存取」不等于「不校验」——不认识结构就写库，等于把前端 bug、抓包重放、脚本调用产生的脏数据
 * 固化到租户共享配置里，之后每个用户打开看板都会看到坏卡片（且原始脏数据已无从追溯）。因此写入前
 * 逐条校验结构、必填与上限；读路径做**纯解析**，保证「写进去什么、读出来什么」。</p>
 *
 * <p><b>为什么 {@code type} 不做枚举白名单</b>：组件类型的主语是前端
 * （{@code board-utils.ts} 的 {@code BoardWidgetType}），前端加一种组件（如开关、多序列折线、
 * 表格）不应被迫等后端发版；后端一旦白名单化，就成了两仓同步升级的硬耦合。这里只要求
 * {@code type} 是非空字符串，字段语义由前端裁决。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
final class DashboardBoardWidgets {

    /** 单个租户的组件上限（与请求 DTO 的 {@code @Size} 同源，防御式复核）。 */
    private static final int MAX_WIDGETS = DashboardBoardSaveReq.MAX_WIDGETS;

    /** 配置 JSON 上限（64 KB）：超过它说明前端把大对象塞进了组件，单请求写入会拖垮库与网关。 */
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    /**
     * 组件必须提供的字段：与前端 {@code BoardWidget} 的核心字段逐一同名。
     *
     * <p>前四个是「卡片画什么」的最小集合，缺任何一个前端都渲染不出卡片；这些字段在写入时
     * 就要求非空，避免把「用户看不到、也删不掉的坏卡片」写进共享配置。</p>
     */
    private static final List<String> REQUIRED_TEXT_FIELDS =
        List.of("id", "type", "title", "deviceId", "propertyId");

    private DashboardBoardWidgets() {
    }

    /**
     * 校验并归一化组件列表。
     *
     * <p>归一化只做一件事：把设备/点位等标识字段统一成**去空白的字符串**（前端 {@code asText}
     * 同口径，数字 ID 也接受）。其余字段（{@code unit}/{@code decimals}/{@code max}/{@code min}/
     * {@code windowMinutes} 以及前端未来新增的字段）原样保留，不做改名、不做裁剪。</p>
     *
     * <p><b>id 去重的范围（措辞澄清）</b>：这里的 {@code seenIds} 是**方法局部变量**，去重只覆盖
     * **单次请求内**的组件（防同一次保存里出现两张同 id 卡片）。组件 id 在库中**没有**唯一约束；
     * 跨请求/跨用户的唯一性由「每租户只有一行配置」这一事实承担——唯一键是配置行的
     * {@code uk_iot_dashboard_board_config(tenant_id)}，不是组件 id。</p>
     *
     * @param raw 原始组件列表（{@code null} 视为漏传，显式报错）
     * @return 校验通过的组件列表（保留输入顺序）
     */
    static List<Map<String, Object>> normalize(List<Object> raw) {
        if (raw == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "组件列表不能为空（清空看板请传空数组 []）");
        }
        if (raw.size() > MAX_WIDGETS) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "看板组件不能超过 " + MAX_WIDGETS + " 个，当前 " + raw.size() + " 个");
        }
        List<Map<String, Object>> widgets = new ArrayList<>(raw.size());
        Set<String> seenIds = new LinkedHashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            Object item = raw.get(index);
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "第 " + (index + 1) + " 个组件格式非法：必须是 JSON 对象");
            }
            Map<String, Object> widget = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null) {
                    widget.put(String.valueOf(key), value);
                }
            });
            for (String field : REQUIRED_TEXT_FIELDS) {
                widget.put(field, requiredText(widget.get(field), field, index));
            }
            String id = (String) widget.get("id");
            if (!seenIds.add(id)) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "第 " + (index + 1) + " 个组件的 id 与前面的组件重复：" + id);
            }
            widgets.add(widget);
        }
        return widgets;
    }

    /**
     * 序列化组件列表并校验配置体积。
     *
     * <p>序列化失败**不在这里转成业务异常**：本类没有日志能力，吞掉 Jackson 异常会丢掉堆栈。
     * 让调用方（服务实现，持有 logger）捕获并 {@code log.error(..., ex)} 之后再转人话报错。</p>
     *
     * @param objectMapper 运行期 ObjectMapper（与接口出参同一套 Jackson 口径）
     * @param widgets      已归一化的组件列表
     * @return 入库用的 JSON 文本
     * @throws JacksonException 组件含无法序列化的对象
     */
    static String serialize(ObjectMapper objectMapper, List<Map<String, Object>> widgets)
        throws JacksonException {
        String json = objectMapper.writeValueAsString(widgets);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONFIG_BYTES) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "看板配置过大（" + bytes + " 字节，上限 " + MAX_CONFIG_BYTES + " 字节 / 64 KB），"
                    + "请减少组件数量或精简组件字段");
        }
        return json;
    }

    /**
     * 解析入库的配置文本。
     *
     * <p>只解析、不重新校验条目：写入路径已保证结构合法，读路径若再「顺手丢弃坏条目」就是
     * 静默降级（用户看不到数据被丢了）。真的解析不出来（手工改库、截断写入）一律显式报错，
     * 让问题立刻可见而不是悄悄返回空看板（Jackson 异常由调用方带堆栈记录后转人话报错）。</p>
     *
     * @param objectMapper 运行期 ObjectMapper
     * @param json         入库的配置文本；空白/JSON {@code null} 视为「尚无配置」
     * @return 组件列表（永不为 {@code null}）
     * @throws JacksonException 库中的 config_json 不是合法 JSON 数组
     */
    static List<Map<String, Object>> parse(ObjectMapper objectMapper, String json)
        throws JacksonException {
        // 空白与**合法 JSON null** 都按「无组件」处理，返回空数组而非 fail-loud —— 这是**有意为之**：
        //   ① 语义上等价：「没有组件」就是空看板，JSON null 并未提供任何其它信息，不存在静默丢数据的可能；
        //   ② 自愈性优先：该分支只可能由手工改库触发（正常写路径 serialize 永远写 "[]"，见 saveBoard），
        //      若在此 fail-loud，一次误改就会让整个租户的看板读取持续报错，而错误信息也帮不上恢复；
        //   ③ 真正的**损坏**（语法错误、JSON 非数组、元素非对象）仍然 fail-loud，本分支不吞它们。
        // 该行为由 IotDashboardBoardServiceImplTest#getBoardShouldTreatJsonNullAndBlankAsNoConfig 钉住。
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Object> raw = objectMapper.readValue(json, new TypeReference<List<Object>>() { });
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> widgets = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Object item = raw.get(index);
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "看板配置数据损坏：第 " + (index + 1) + " 个组件不是 JSON 对象");
            }
            Map<String, Object> widget = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null) {
                    widget.put(String.valueOf(key), value);
                }
            });
            widgets.add(widget);
        }
        return widgets;
    }

    /**
     * 取必填文本字段：接受非空字符串与有限数字（数字 ID 转字符串，与前端 {@code asText} 同口径）。
     *
     * @param value 字段原值
     * @param field 字段名（报错定位用）
     * @param index 组件下标（报错定位用）
     * @return 去空白后的文本
     */
    private static String requiredText(Object value, String field, int index) {
        String position = "第 " + (index + 1) + " 个组件";
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    position + "的 " + field + " 不能为空");
            }
            return trimmed;
        }
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            return decimal.stripTrailingZeros().toPlainString();
        }
        throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
            position + "缺少必填字段 " + field + "（应为非空字符串或数字）");
    }
}
