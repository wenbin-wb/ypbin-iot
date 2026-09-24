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

/**
 * 历史时序的一个点（§5.2.1 查询路径的返回元素）。
 *
 * <p>{@code value} 统一为字符串（与上报契约一致）：数值列与文本列在前端不再区分，
 * 由物模型属性类型决定如何解释。</p>
 *
 * @param ts      读数时刻（epoch 毫秒）
 * @param value   读数原值（字符串化）
 * @param quality 质量码
 * @author wenbin
 * @since 2026-09-24
 */
public record TimeSeriesPointResp(Long ts, String value, String quality) {
}
