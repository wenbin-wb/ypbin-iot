/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * IoT 设备影子响应模型（§3.10，reported + desired 合并视图）。
 *
 * <p>读取语义：reported 优先，无则回退 desired（§3.10「读最近一次写入值」）；字段本身永不为 null，
 * 未同步时为空 Map。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class IotShadowResp {

    /** 设备 ID（Long 由全局序列化转字符串输出）。 */
    private Long deviceId;

    /** 设备上报值（键=属性标识）。 */
    private Map<String, Object> reported;

    /** 平台期望值（键=属性标识）。 */
    private Map<String, Object> desired;

    /** 合并视图（reported 优先，无则 desired）。 */
    private Map<String, Object> merged;

    /** 最近上报时刻。 */
    private LocalDateTime reportTs;

    /** 最近期望时刻。 */
    private LocalDateTime desiredTs;
}
