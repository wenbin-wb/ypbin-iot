/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量下发命令请求（**一个命令模板 + 多台设备**）。
 *
 * <p><b>为什么是「一个模板」而不是「逐设备参数」</b>：批量运维的场景定义就是「同一个动作发给一组设备」
 * （典型：给一批传感器同时设一个上报周期）。逐设备参数会让幂等键派生、上限校验、结果聚合与前端交互
 * 全部复杂化，而这类场景本身不需要——真要逐台不同参数，逐台调用单台端点即可（语义更清楚，
 * 也不会把「一台配错」藏在批量结果里）。</p>
 *
 * <p><b>上限为什么不在本 DTO 上写死</b>：单批设备数上限是**运维可调**的
 * （{@code ypbin.emqx.command-batch.max-devices}，nacos 可改）。若在这里写死 {@code @Size(max=…)}，
 * 调大配置后仍会被 DTO 校验先拒，配置就形同虚设。因此本 DTO 只做「非空 + 非空元素 + 层级校验」，
 * 数量上限与去重由服务层按配置判定。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
public class CommandBatchSendReq {

    /**
     * 设备主键清单（非空；元素不得为 {@code null}；重复项由服务层去重后按配置上限判定）。
     */
    @NotEmpty(message = "设备 ID 列表不能为空")
    private List<Long> deviceIds;

    /**
     * 命令模板（必填；该模板会**原样**应用到每一台设备）。
     *
     * <p>模板自身的字段（{@code kind}/{@code identifier}/{@code params}/{@code timeoutMs}/
     * {@code writeDesired}）在进入逐台循环之前**整体校验一次**：模板不合法是调用方写错了请求，
     * 整批拒绝比返回 N 条相同错误更有用。</p>
     */
    @NotNull(message = "命令模板不能为空")
    @Valid
    private CommandSendReq command;

    /**
     * 请求级幂等键（可选；语义与单台下发的 {@link CommandSendReq#getClientRequestId()} 一致）。
     *
     * <p><b>批量下逐设备派生</b>：命令实例的幂等唯一口径是「同租户 + 同设备」
     * （{@code uk_command_client_key(tenant_id, device_id, client_request_id)}），所以批量下必须
     * **逐设备独立去重**——实际写入每个实例的键是 {@code <本字段>:<deviceId>}。这样
     * 「A 设备命中已有实例」不会被放大成「B 设备也不下发」。派生规则与字符集约束见服务层注释。</p>
     */
    private String clientRequestId;
}
