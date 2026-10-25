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
package cn.ypbin.admin.iot.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.dto.SysSpaceDto;
import cn.ypbin.admin.system.model.dto.SysSpaceRelationDto;
import cn.ypbin.admin.system.model.req.SpaceSyncReq;
import cn.ypbin.starter.core.model.R;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link DeviceSpaceAdapter} 的行为测试。
 *
 * <p>本类承载 IoT 与 system 空间契约之间的全部「失败语义」判断，是本功能的信任边界：
 * 读侧降级（返回空集合）与写侧抛异常的分野必须被逐条锁定——两者搞反都会造成
 * 「设备列表整页报错」或「用户点了绑定、实际没绑上却无提示」。</p>
 *
 * @author wenbin
 * @since 2026-10-25
 */
class DeviceSpaceAdapterTest {

    private ISystemClient systemClient;

    private DeviceSpaceAdapter adapter;

    @BeforeEach
    void setUp() {
        systemClient = mock(ISystemClient.class);
        adapter = new DeviceSpaceAdapter(providerOf(systemClient));
    }

    // ---------- 读侧：降级为空集合 ----------

    @Test
    @DisplayName("★ 读侧：system 抛异常时降级为空 Map（设备列表不因空间服务抖动而整页报错）")
    void shouldDegradeToEmptyWhenBatchLookupThrows() {
        when(systemClient.listSpaceRelationsByTargets(any(), anyList()))
            .thenThrow(new RuntimeException("system 不可达"));

        assertThat(adapter.relationsOf(List.of(1L, 2L))).isEmpty();
    }

    @Test
    @DisplayName("读侧：system 返回失败 R 时降级为空 Map（不把失败当成功）")
    void shouldDegradeToEmptyWhenBatchLookupFails() {
        when(systemClient.listSpaceRelationsByTargets(any(), anyList()))
            .thenReturn(R.fail("空间服务不可用"));

        assertThat(adapter.relationsOf(List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("读侧：空设备列表直接返回空 Map，不调 system（空 IN () 对端也是语法错）")
    void shouldNotCallSystemWhenDeviceIdsEmpty() {
        assertThat(adapter.relationsOf(List.of())).isEmpty();
        verify(systemClient, never()).listSpaceRelationsByTargets(any(), anyList());
    }

    @Test
    @DisplayName("★ 读侧：一次批量反查，不逐设备调用（N 条设备只调 1 次）")
    void shouldCallSystemOnceForBatchLookup() {
        Map<Long, List<SysSpaceRelationDto>> grouped = new LinkedHashMap<>();
        grouped.put(1L, List.of(relation(101L)));
        when(systemClient.listSpaceRelationsByTargets(eq("device"), anyList()))
            .thenReturn(R.ok(grouped));

        Map<Long, List<SysSpaceRelationDto>> result =
            adapter.relationsOf(List.of(1L, 2L, 3L, 4L, 5L));

        assertThat(result).containsKey(1L);
        // 关键：5 台设备只触发 1 次跨服务调用
        verify(systemClient).listSpaceRelationsByTargets(eq("device"), anyList());
    }

    @Test
    @DisplayName("读侧：宿主未装配 ISystemClient（纯采集节点）时返回空集合，不报错")
    void shouldReturnEmptyWhenClientAbsent() {
        DeviceSpaceAdapter noClient = new DeviceSpaceAdapter(providerOf(null));

        assertThat(noClient.relationsOf(List.of(1L))).isEmpty();
        assertThat(noClient.deviceIdsInSpaceTree(1L)).isEmpty();
        assertThat(noClient.spacesOf(Set.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("按空间筛选：结果去重（同一设备挂房间与楼层时只返回 1 次）")
    void shouldDedupDeviceIdsInSpaceTree() {
        when(systemClient.listSpaceTargetIdsBySpace(eq(2L), eq("device")))
            .thenReturn(R.ok(List.of(100L, 100L, 200L)));

        assertThat(adapter.deviceIdsInSpaceTree(2L)).containsExactly(100L, 200L);
    }

    @Test
    @DisplayName("按空间筛选：system 失败时返回空集合（降级，不抛）")
    void shouldDegradeWhenSpaceTreeLookupFails() {
        when(systemClient.listSpaceTargetIdsBySpace(anyLong(), any()))
            .thenReturn(R.fail("不可用"));

        assertThat(adapter.deviceIdsInSpaceTree(2L)).isEmpty();
    }

    // ---------- 写侧：必须抛异常，禁止静默 ----------

    @Test
    @DisplayName("★ 写侧：绑定失败时必须抛异常（用户点了绑定、静默失败会让人以为绑上了）")
    void shouldThrowWhenBindFails() {
        when(systemClient.bindSpaceRelations(anyLong(), any(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(R.fail("空间不存在"));

        assertThatThrownBy(() -> adapter.bind(1L, List.of(10L), false, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("空间不存在");
    }

    @Test
    @DisplayName("写侧：宿主未依赖 system-api 时抛明确异常（而不是静默成功）")
    void shouldThrowWhenClientAbsentOnWrite() {
        DeviceSpaceAdapter noClient = new DeviceSpaceAdapter(providerOf(null));

        assertThatThrownBy(() -> noClient.bind(1L, List.of(10L), false, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("system-api");
    }

    @Test
    @DisplayName("写侧：解绑失败时抛异常并带对端原因")
    void shouldThrowWhenUnbindFails() {
        when(systemClient.unbindSpaceRelations(anyLong(), any(), anyList()))
            .thenReturn(R.fail("关联不存在"));

        assertThatThrownBy(() -> adapter.unbind(1L, List.of(10L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("关联不存在");
    }

    @Test
    @DisplayName("写侧：同步空间集合时把 null 视为空集合，且请求体字段完整")
    void shouldSendCompleteSyncRequest() {
        when(systemClient.syncSpaceRelations(any())).thenReturn(R.ok(2));

        adapter.syncDeviceSpaces(5L, List.of(1L, 1L, 2L));

        verify(systemClient).syncSpaceRelations(any());
    }

    @Test
    @DisplayName("写侧：同步失败时抛异常")
    void shouldThrowWhenSyncFails() {
        when(systemClient.syncSpaceRelations(any())).thenReturn(R.fail("目标空间不存在"));

        assertThatThrownBy(() -> adapter.syncDeviceSpaces(5L, List.of(1L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("目标空间不存在");
    }

    // ---------- 辅助 ----------

    private static SysSpaceRelationDto relation(Long spaceId) {
        SysSpaceRelationDto dto = new SysSpaceRelationDto();
        dto.setSpaceId(spaceId);
        dto.setTargetId(1L);
        dto.setTargetType("device");
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
