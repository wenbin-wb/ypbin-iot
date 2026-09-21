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

import cn.ypbin.admin.iot.model.req.IotCommandReq;
import cn.ypbin.admin.iot.model.req.IotEventReq;
import cn.ypbin.admin.iot.model.req.IotPropertyReq;
import cn.ypbin.admin.iot.model.req.IotServiceReq;
import cn.ypbin.admin.iot.model.resp.IotCommandResp;
import cn.ypbin.admin.iot.model.resp.IotEventResp;
import cn.ypbin.admin.iot.model.resp.IotPropertyResp;
import cn.ypbin.admin.iot.model.resp.IotServiceResp;
import cn.ypbin.admin.iot.model.resp.TslImportResult;
import cn.ypbin.admin.iot.model.tsl.TslDocument;
import java.util.List;

/**
 * IoT 物模型服务（§3.3–§3.7）。
 *
 * <p>服务/属性/命令/事件的细粒度 CRUD（挂在产品/服务下），以及整棵 TSL 树的导入导出。
 * 已发布产品同版本不可变：本服务的写操作要求产品处于 draft（草稿）状态。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotThingModelService {

    /**
     * 查询产品下的服务列表。
     *
     * @param productId 产品主键
     * @return 服务列表
     */
    List<IotServiceResp> listServices(Long productId);

    /**
     * 新增服务。
     *
     * @param req 服务信息
     * @return 新服务主键
     */
    Long createService(IotServiceReq req);

    /**
     * 编辑服务。
     *
     * @param id  服务主键
     * @param req 服务信息
     */
    void updateService(Long id, IotServiceReq req);

    /**
     * 删除服务（连同其下属性/命令/事件）。
     *
     * @param id 服务主键
     */
    void removeService(Long id);

    /**
     * 查询服务下的属性列表。
     *
     * @param serviceId 服务主键
     * @return 属性列表
     */
    List<IotPropertyResp> listProperties(Long serviceId);

    /**
     * 新增属性。
     *
     * @param req 属性信息
     * @return 新属性主键
     */
    Long createProperty(IotPropertyReq req);

    /**
     * 编辑属性。
     *
     * @param id  属性主键
     * @param req 属性信息
     */
    void updateProperty(Long id, IotPropertyReq req);

    /**
     * 删除属性。
     *
     * @param id 属性主键
     */
    void removeProperty(Long id);

    /**
     * 查询服务下的命令列表。
     *
     * @param serviceId 服务主键
     * @return 命令列表
     */
    List<IotCommandResp> listCommands(Long serviceId);

    /**
     * 新增命令。
     *
     * @param req 命令信息
     * @return 新命令主键
     */
    Long createCommand(IotCommandReq req);

    /**
     * 编辑命令。
     *
     * @param id  命令主键
     * @param req 命令信息
     */
    void updateCommand(Long id, IotCommandReq req);

    /**
     * 删除命令。
     *
     * @param id 命令主键
     */
    void removeCommand(Long id);

    /**
     * 查询服务下的事件列表。
     *
     * @param serviceId 服务主键
     * @return 事件列表
     */
    List<IotEventResp> listEvents(Long serviceId);

    /**
     * 新增事件。
     *
     * @param req 事件信息
     * @return 新事件主键
     */
    Long createEvent(IotEventReq req);

    /**
     * 编辑事件。
     *
     * @param id  事件主键
     * @param req 事件信息
     */
    void updateEvent(Long id, IotEventReq req);

    /**
     * 删除事件。
     *
     * @param id 事件主键
     */
    void removeEvent(Long id);

    /**
     * 导出 TSL（平台表 → TSL JSON，对齐 IoTDA 双文件结构）。
     *
     * @param productId 产品主键
     * @return TSL 文档
     */
    TslDocument exportTsl(Long productId);

    /**
     * 导入 TSL（TSL JSON → 平台表，全量替换当前草稿结构）。
     *
     * <p>全字段校验（命名规范/类型枚举/引用完整性），失败逐项报错且<b>整体不落库</b>；
     * 产品须为草稿状态。</p>
     *
     * @param productId 产品主键
     * @param doc       TSL 文档
     * @return 导入结果（errors 为空表示成功）
     */
    TslImportResult importTsl(Long productId, TslDocument doc);
}
