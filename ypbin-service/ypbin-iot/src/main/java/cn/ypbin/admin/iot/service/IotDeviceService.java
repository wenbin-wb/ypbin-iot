/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service;

import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.starter.crud.model.PageResult;
import java.util.List;

/**
 * IoT 设备台账服务。
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface IotDeviceService {

    /**
     * 分页查询本租户的设备台账。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    PageResult<IotDeviceResp> pageDevices(IotDeviceQuery query);

    /**
     * 新增设备（租户由上下文注入，不接受请求参数指定）。
     *
     * @param req 设备信息
     * @return 新设备主键
     */
    Long createDevice(IotDeviceReq req);

    /**
     * 逻辑删除设备。
     *
     * @param id 设备主键
     */
    void removeDevice(Long id);

    /**
     * 编辑设备（含绑定产品与物模型版本，§4.1；版本须为已发布版本）。
     *
     * @param id  设备主键
     * @param req 设备信息
     */
    void updateDevice(Long id, IotDeviceReq req);

    /**
     * 启用/停用设备（设备台账里的启停开关）。
     *
     * <p>与 {@link #updateDevice} 分开的理由：编辑走的是「整表单提交」，而启停是**单点动作**——
     * 列表页的开关只改启停位，不该要求调用方回填设备编码/名称/协议/端点（那些字段带
     * {@code @NotBlank} 校验，回填等于「读到什么就写回什么」，一旦读到的是旧值就静默覆盖别人的修改）。
     * 独立端点同时让权限与日志语义准确（{@code iot:device:update} + 「切换设备状态」）。</p>
     *
     * <p><b>停用即停止采集</b>：本方法推进台账 {@code config_epoch}，接入侧据此重新对账，
     * 而 {@code DeviceSpecServiceImpl} 只下发 {@code status=1} 的设备 ⇒ 停用设备被解绑、
     * 其点位订阅随之撤销。</p>
     *
     * @param id     设备主键
     * @param status 启停位：1 启用 / 0 停用（{@code EntityStatus} 的 code）
     */
    void updateStatus(Long id, Integer status);

    /**
     * 批量启用/停用设备（列表页多选后的单点动作）。
     *
     * <p>校验后只发<b>一条批量 UPDATE</b>，不逐台调用 {@link #updateStatus}：单条 SQL 是原子操作，
     * 且不会把 500 台设备放大成 500 次数据库往返。写入成功后台账 {@code config_epoch} 只推进一次，
     * 接入侧据一次对账即可看到整批变更。</p>
     *
     * @param ids    设备主键（非空，最多 500 个）
     * @param status 启停位：1 启用 / 0 停用（{@code EntityStatus} 的 code）
     * @return 实际受影响行数
     */
    int batchUpdateStatus(List<Long> ids, Integer status);

    /**
     * 批量逻辑删除设备（列表页多选删除）。
     *
     * <p>校验后只发<b>一条批量 DELETE</b>（逻辑删除由 MyBatis-Plus 注入为 {@code UPDATE is_deleted=1}），
     * 不逐台调用 {@link #removeDevice}；写入成功后台账 {@code config_epoch} 只推进一次。</p>
     *
     * @param ids 设备主键（非空，最多 500 个）
     * @return 实际受影响行数
     */
    int batchRemove(List<Long> ids);

    /**
     * 导出本租户设备台账 CSV（复用列表筛选条件 {@code keyword/protocol/productId}；分页参数忽略）。
     *
     * <p>单租户最多导出 <b>10000</b> 条：超过抛 {@link cn.ypbin.starter.core.exception.BusinessException}
     * 提示先筛选，<b>不静默截断</b>——否则用户会以为拿到了全量数据。CSV 带 UTF-8 BOM，
     * 单元格做 RFC 4180 转义与公式注入中和（见 {@code IotCsvExportUtils}）。</p>
     *
     * @param query 查询条件（复用列表查询的 keyword/protocol/productId）
     * @return CSV 文本
     */
    String exportCsv(IotDeviceQuery query);
}
