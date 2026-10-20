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
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行态场景快照（一次请求拉齐本场景全部点位）。
 *
 * <p><b>不宣称「原子快照」</b>：{@code serverTime} 是服务端开始组装该响应的时刻；
 * 各设备的读数来自同一次 Redis pipeline，但 Redis 里的值本身来自不同时刻的上报。
 * 需要严格同时刻切片时走时序库的历史查询，不是本端点。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
@Getter
@Setter
public class IotTwinRuntimeResp {

    /** 场景主键。 */
    private Long sceneId;

    /** 服务端组装时刻。 */
    private LocalDateTime serverTime;

    /** 本场景点位数。 */
    private Integer pointCount;

    /** 是否被截断（点位超过上限时为 true；理论上不可达，防御式字段）。 */
    private Boolean truncated;

    /** 点位快照列表（按 point.sort、point.id 升序；无点位时为空数组，永不为 null）。 */
    private List<TwinRuntimePointResp> points;
}
