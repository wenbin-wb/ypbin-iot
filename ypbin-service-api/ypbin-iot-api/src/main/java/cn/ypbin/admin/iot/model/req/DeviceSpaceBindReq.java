/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备批量绑定/解绑空间的请求。
 *
 * <p>绑定与解绑共用一个 DTO：两者的入参只有「哪些设备」是必需的，
 * 另外两个字段只在绑定时有意义（解绑忽略）。拆成两个类会让调用方多学一个类型，
 * 而语义差异已由端点区分清楚。</p>
 *
 * @author wenbin
 * @since 2026-10-25
 */
@Getter
@Setter
public class DeviceSpaceBindReq {

    /** 设备 ID 列表（至少 1 个） */
    @NotEmpty(message = "设备 ID 列表不能为空")
    @Size(max = 500, message = "单次最多操作 500 台设备")
    private List<Long> deviceIds;

    /**
     * 是否把这批设备的该空间标为「主空间」。
     *
     * <p>主空间用于列表/详情展示「这台设备主要在哪」；一个设备最多一个主空间（后端唯一键保证）。</p>
     */
    private Boolean isPrimary;

    /**
     * 是否先清空这批设备在其他空间上的关联（设备挪位置 = true）。
     *
     * <p><b>默认 false</b>：不清空意味着「追加一个空间点」（如设备同时属于机房与机房所在楼层）。
     * 若用户意图是「搬走」，必须显式传 true——默认 true 会静默删掉用户没打算删的关联。</p>
     */
    private Boolean clearOthers;
}
