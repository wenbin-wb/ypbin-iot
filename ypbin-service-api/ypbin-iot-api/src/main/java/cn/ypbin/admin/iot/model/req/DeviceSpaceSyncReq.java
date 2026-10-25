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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 整体设置某设备空间集合的请求。
 *
 * <p><b>{@code spaceIds} 是「全量目标集合」</b>：不在列表里的既有空间关联会被删除。
 * 允许空数组（= 清空该设备全部空间关联），但**不允许 null**——
 * null 等同于「调用方忘了传」，会静默清空，属危险默认值。</p>
 *
 * @author wenbin
 * @since 2026-10-25
 */
@Getter
@Setter
public class DeviceSpaceSyncReq {

    /** 目标空间集合（全量语义；空数组 = 清空） */
    @NotNull(message = "目标空间集合不能为空（清空请传空数组）")
    @Size(max = 500, message = "单次最多关联 500 个空间")
    private List<Long> spaceIds;
}
