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

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.admin.system.model.dto.SysSpaceDto;
import cn.ypbin.admin.system.model.dto.SysSpaceRelationDto;
import cn.ypbin.admin.system.model.req.SpaceSyncReq;
import cn.ypbin.starter.core.model.R;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * IoT 侧的空间适配层：把 system 的空间契约收敛到一处。
 *
 * <p><b>为什么需要这一层</b>：设备详情/列表/筛选/统计四处都要调空间接口，
 * 若各写一遍「调 Feign + 判 R.success + 处理降级」，失败语义就会漂移
 * （一处抛异常、一处返回空列表 ⇒ 同一份数据在不同页面上表现不同）。</p>
 *
 * <p><b>失败语义（按用途分两类，这是刻意的，不是不一致）</b>：</p>
 * <ul>
 *   <li><b>读侧（本类全部方法）</b>：system 不可用时返回<b>空集合</b>并记完整堆栈。
 *       理由：设备台账本身有效，空间只是附加信息；若因空间服务抖动就让设备列表整页报错，
 *       是把「附属信息不可用」放大成「主数据不可用」。调用方据返回值即可判断（空 = 无空间/不可用）。</li>
 *   <li><b>写侧</b>（{@code bindDeviceSpaces} 等）：失败必须<b>抛异常</b>——
 *       用户明确点了「绑定到 A栋」，静默失败会让他在列表里看不到刚绑的设备，
 *       而系统毫无提示。</li>
 * </ul>
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入</b>：与 {@code PlatformAlertNotifier}
 * 同一处理——{@code ISystemClient} 来自可选依赖 {@code ypbin-system-api}，
 * 某些宿主（如纯采集节点）可能不带它；直接注入会让上下文启动失败。</p>
 *
 * @author wenbin
 * @since 2026-10-25
 */
@Component
public class DeviceSpaceAdapter {

    private static final Logger log = LoggerFactory.getLogger(DeviceSpaceAdapter.class);

    /** 空间关联的对象类型（与 system 字典 {@code space_target_type} 的项值一致）。 */
    public static final String TARGET_TYPE_DEVICE = "device";

    private final ObjectProvider<ISystemClient> systemClientProvider;

    public DeviceSpaceAdapter(ObjectProvider<ISystemClient> systemClientProvider) {
        this.systemClientProvider = systemClientProvider;
    }

    /**
     * 批量取设备的空间关联（列表页「所在空间」列用）。
     *
     * @param deviceIds 设备 ID 列表
     * @return 设备 ID → 空间关系列表（无关联的设备不出现在结果里；system 不可用时返回空 Map）
     */
    public Map<Long, List<SysSpaceRelationDto>> relationsOf(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            // 空集合直接返回：空 IN () 对端也是语法错，且无意义
            return Map.of();
        }
        ISystemClient client = systemClientProvider.getIfAvailable();
        if (client == null) {
            // 宿主没带 system-api（如纯采集节点）：这是**部署形态的合理缺失**，不是故障
            log.debug("未装配 ISystemClient，设备空间信息不可用（宿主未依赖 system-api）");
            return Map.of();
        }
        // **一次批量反查**，不是逐设备调：列表一页几十条设备，逐条调就是 N 次跨服务 HTTP。
        // system 侧的 /space-relations-batch 内部也是「1 次关联 IN 查询 + 1 次空间批量」。
        R<Map<Long, List<SysSpaceRelationDto>>> resp = safeCall(
            () -> client.listSpaceRelationsByTargets(TARGET_TYPE_DEVICE, deviceIds));
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            return Map.of();
        }
        return resp.getData();
    }

    /**
     * 按空间（含后代）筛选设备 ID。
     *
     * @param spaceId 根空间 ID
     * @return 设备 ID 列表（去重；system 不可用时返回空集合）
     */
    public List<Long> deviceIdsInSpaceTree(Long spaceId) {
        ISystemClient client = systemClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("未装配 ISystemClient，按空间筛选不可用");
            return List.of();
        }
        R<List<Long>> resp = safeCall(() -> client.listSpaceTargetIdsBySpace(spaceId,
            TARGET_TYPE_DEVICE));
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            return List.of();
        }
        // 去重：同一设备可能同时挂在房间与楼层
        return List.copyOf(new LinkedHashSet<>(resp.getData()));
    }

    /**
     * 取空间摘要（设备详情展示「A栋/1F/101室」用）。
     *
     * <p><b>用一次批量 {@code /space-list} 而不是逐个 {@code /space-by-id}</b>：
     * 一页设备可能涉及几十个不同空间，逐个调是 N 次跨服务 RPC（架构门禁
     * {@code loopsMustNotCallDbOrRpc} 会判红）。空间是典型小集合，system 侧
     * {@code listSpaces()} 本就返回全量，这里一次取回后按 ID 过滤。</p>
     *
     * @param spaceIds 空间 ID 集合
     * @return 空间 ID → 空间摘要（取不到的 ID 不出现在结果里）
     */
    public Map<Long, SysSpaceDto> spacesOf(Set<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Map.of();
        }
        ISystemClient client = systemClientProvider.getIfAvailable();
        if (client == null) {
            return Map.of();
        }
        R<List<SysSpaceDto>> resp = safeCall(client::listSpaces);
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            return Map.of();
        }
        // 在内存里按 ID 过滤（一次 RPC，无循环调用）
        Map<Long, SysSpaceDto> result = new HashMap<>();
        for (SysSpaceDto space : resp.getData()) {
            if (space != null && spaceIds.contains(space.getId())) {
                result.put(space.getId(), space);
            }
        }
        return result;
    }

    /**
     * 绑定设备到空间（写侧：失败必须抛异常，不能静默）。
     *
     * @param spaceId     空间 ID
     * @param deviceIds   设备 ID 列表
     * @param isPrimary   是否主空间
     * @param clearOthers 是否先清空这些设备在其他空间的同类关联（设备挪位置 = true）
     * @return 实际生效的关联条数
     * @throws IllegalStateException system 不可用或绑定失败
     */
    public int bind(Long spaceId, List<Long> deviceIds, boolean isPrimary, boolean clearOthers) {
        ISystemClient client = requireClient();
        R<Integer> resp = client.bindSpaceRelations(spaceId, TARGET_TYPE_DEVICE, deviceIds,
            isPrimary ? 1 : 0, clearOthers, null);
        requireSuccess(resp, "设备绑定空间失败");
        return resp.getData() == null ? 0 : resp.getData();
    }

    /**
     * 解绑设备与空间（写侧：失败必须抛异常）。
     *
     * @param spaceId   空间 ID
     * @param deviceIds 设备 ID 列表
     * @return 实际删除的关联条数
     * @throws IllegalStateException system 不可用或解绑失败
     */
    public int unbind(Long spaceId, List<Long> deviceIds) {
        ISystemClient client = requireClient();
        R<Integer> resp = client.unbindSpaceRelations(spaceId, TARGET_TYPE_DEVICE, deviceIds);
        requireSuccess(resp, "设备解绑空间失败");
        return resp.getData() == null ? 0 : resp.getData();
    }

    /**
     * 整体设置设备的空间集合（单事务语义：算出差集后批量增删）。
     *
     * @param deviceId 设备 ID
     * @param spaceIds 目标空间集合（空集合 = 清空该设备的所有空间关联）
     * @throws IllegalStateException system 不可用或同步失败
     */
    public void syncDeviceSpaces(Long deviceId, List<Long> spaceIds) {
        ISystemClient client = requireClient();
        List<Long> desired = spaceIds == null ? List.of()
            : List.copyOf(new LinkedHashSet<>(spaceIds));
        SpaceSyncReq req = new SpaceSyncReq();
        req.setTargetType(TARGET_TYPE_DEVICE);
        req.setTargetId(deviceId);
        req.setSpaceIds(desired);
        R<Integer> resp = client.syncSpaceRelations(req);
        requireSuccess(resp, "设备空间关系同步失败");
    }

    /**
     * 取 system 客户端；缺失时抛明确异常（写侧专用）。
     *
     * @return 客户端
     * @throws IllegalStateException 宿主未依赖 system-api
     */
    private ISystemClient requireClient() {
        ISystemClient client = systemClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                "当前部署未依赖 system-api，无法绑定空间——请确认 ypbin-iot 引入了 ypbin-system-api");
        }
        return client;
    }

    /**
     * 断言 R 成功；失败时抛异常并带上对端原文（写侧专用）。
     *
     * @param resp    响应
     * @param message 业务动作描述
     * @throws IllegalStateException 响应失败
     */
    private void requireSuccess(R<?> resp, String message) {
        if (resp == null) {
            throw new IllegalStateException(message + "：system 无响应");
        }
        if (!resp.isSuccess()) {
            // 带对端原文：否则用户只看到「绑定失败」，运维也不知道是空间不存在还是权限不足
            throw new IllegalStateException(
                message + "：" + (resp.getMessage() == null ? "未知原因" : resp.getMessage()));
        }
    }

    /**
     * 读侧调用包装：异常与失败 R 都降级为 {@code null} 并记完整堆栈。
     *
     * @param call 调用
     * @param <T>  响应数据类型
     * @return 成功时的响应；失败时 {@code null}
     */
    private <T> R<T> safeCall(Supplier<R<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException ex) {
            // 读侧降级：设备台账仍是有效的，空间只是附加信息。
            // 但必须记完整堆栈——否则「空间列一直空」会被当成前端 bug，查不到根因。
            log.error("调用 system 空间接口失败（设备空间信息降级为空）", ex);
            return null;
        }
    }
}
