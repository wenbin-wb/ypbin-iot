/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.timeseries;

import java.util.List;

/**
 * 未接入时序库时的**空实现**：`available()` 返回 false ⇒ 查询端点会明确报错（不返回空列表假装没数据）。
 *
 * <p>IoTDB JDBC 实现（读写）落地后由装配替换本实现；替换时必须加条件/`@Primary`，
 * 否则两个 {@code TimeSeriesStore}/{@code TimeSeriesWriter} bean 会 `NoUniqueBeanDefinitionException`。</p>
 *
 * @author wenbin
 * @since 2026-09-24
 */
public class UnavailableTimeSeriesStore implements TimeSeriesStore {

    @Override
    public List<TimeSeriesPointResp> query(Long tenantId, Long deviceId, List<String> propertyIds, Long from,
                                           Long to, int limit) {
        // 不可用即报错语义由 service 承担；这里返回空列表仅为满足契约（调用方在 available()=false 时不会走到这）
        return List.of();
    }

    @Override
    public boolean available() {
        return false;
    }
}
