/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

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
import cn.ypbin.admin.iot.service.IotThingModelService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 物模型接口（§3.3–§3.7：服务/属性/命令/事件 + TSL 导入导出）。
 *
 * <p>权限码沿用产品域：编辑物模型归 {@code iot:product:update}，TSL 导入/导出分别归
 * {@code iot:product:tsl-import} / {@code iot:product:tsl-export}。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/products/{productId}")
@RequiredArgsConstructor
public class IotThingModelController {

    private final IotThingModelService iotThingModelService;

    // ---------- 服务 ----------

    /**
     * 查询产品下的服务列表。
     *
     * @param productId 产品主键
     * @return 服务列表
     */
    @GetMapping("/services")
    @SaCheckPermission("iot:product:list")
    public R<List<IotServiceResp>> listServices(@PathVariable Long productId) {
        return R.ok(iotThingModelService.listServices(productId));
    }

    /**
     * 新增服务。
     *
     * @param productId 产品主键
     * @param req       服务信息
     * @return 新服务主键
     */
    @PostMapping("/services")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("新增 IoT 物模型服务")
    public R<Long> createService(@PathVariable Long productId, @Valid @RequestBody IotServiceReq req) {
        req.setProductId(productId);
        return R.ok(iotThingModelService.createService(req));
    }

    /**
     * 编辑服务。
     *
     * @param productId 产品主键
     * @param id        服务主键
     * @param req       服务信息
     * @return 空响应
     */
    @PutMapping("/services/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("编辑 IoT 物模型服务")
    public R<Void> updateService(@PathVariable Long productId, @PathVariable Long id,
                                 @Valid @RequestBody IotServiceReq req) {
        iotThingModelService.updateService(id, req);
        return R.ok();
    }

    /**
     * 删除服务。
     *
     * @param productId 产品主键
     * @param id        服务主键
     * @return 空响应
     */
    @DeleteMapping("/services/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("删除 IoT 物模型服务")
    public R<Void> removeService(@PathVariable Long productId, @PathVariable Long id) {
        iotThingModelService.removeService(id);
        return R.ok();
    }

    // ---------- 属性（挂在服务下，用 /services/{serviceId}/properties） ----------

    /**
     * 查询服务下的属性列表。
     *
     * @param serviceId 服务主键
     * @return 属性列表
     */
    @GetMapping("/services/{serviceId}/properties")
    @SaCheckPermission("iot:product:list")
    public R<List<IotPropertyResp>> listProperties(@PathVariable Long serviceId) {
        return R.ok(iotThingModelService.listProperties(serviceId));
    }

    /**
     * 新增属性。
     *
     * @param serviceId 服务主键
     * @param req       属性信息
     * @return 新属性主键
     */
    @PostMapping("/services/{serviceId}/properties")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("新增 IoT 物模型属性")
    public R<Long> createProperty(@PathVariable Long serviceId, @Valid @RequestBody IotPropertyReq req) {
        req.setServiceId(serviceId);
        return R.ok(iotThingModelService.createProperty(req));
    }

    /**
     * 编辑属性。
     *
     * @param serviceId 服务主键
     * @param id        属性主键
     * @param req       属性信息
     * @return 空响应
     */
    @PutMapping("/services/{serviceId}/properties/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("编辑 IoT 物模型属性")
    public R<Void> updateProperty(@PathVariable Long serviceId, @PathVariable Long id,
                                  @Valid @RequestBody IotPropertyReq req) {
        iotThingModelService.updateProperty(id, req);
        return R.ok();
    }

    /**
     * 删除属性。
     *
     * @param serviceId 服务主键
     * @param id        属性主键
     * @return 空响应
     */
    @DeleteMapping("/services/{serviceId}/properties/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("删除 IoT 物模型属性")
    public R<Void> removeProperty(@PathVariable Long serviceId, @PathVariable Long id) {
        iotThingModelService.removeProperty(id);
        return R.ok();
    }

    // ---------- 命令 ----------

    /**
     * 查询服务下的命令列表。
     *
     * @param serviceId 服务主键
     * @return 命令列表
     */
    @GetMapping("/services/{serviceId}/commands")
    @SaCheckPermission("iot:product:list")
    public R<List<IotCommandResp>> listCommands(@PathVariable Long serviceId) {
        return R.ok(iotThingModelService.listCommands(serviceId));
    }

    /**
     * 新增命令。
     *
     * @param serviceId 服务主键
     * @param req       命令信息
     * @return 新命令主键
     */
    @PostMapping("/services/{serviceId}/commands")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("新增 IoT 物模型命令")
    public R<Long> createCommand(@PathVariable Long serviceId, @Valid @RequestBody IotCommandReq req) {
        req.setServiceId(serviceId);
        return R.ok(iotThingModelService.createCommand(req));
    }

    /**
     * 编辑命令。
     *
     * @param serviceId 服务主键
     * @param id        命令主键
     * @param req       命令信息
     * @return 空响应
     */
    @PutMapping("/services/{serviceId}/commands/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("编辑 IoT 物模型命令")
    public R<Void> updateCommand(@PathVariable Long serviceId, @PathVariable Long id,
                                 @Valid @RequestBody IotCommandReq req) {
        iotThingModelService.updateCommand(id, req);
        return R.ok();
    }

    /**
     * 删除命令。
     *
     * @param serviceId 服务主键
     * @param id        命令主键
     * @return 空响应
     */
    @DeleteMapping("/services/{serviceId}/commands/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("删除 IoT 物模型命令")
    public R<Void> removeCommand(@PathVariable Long serviceId, @PathVariable Long id) {
        iotThingModelService.removeCommand(id);
        return R.ok();
    }

    // ---------- 事件 ----------

    /**
     * 查询服务下的事件列表。
     *
     * @param serviceId 服务主键
     * @return 事件列表
     */
    @GetMapping("/services/{serviceId}/events")
    @SaCheckPermission("iot:product:list")
    public R<List<IotEventResp>> listEvents(@PathVariable Long serviceId) {
        return R.ok(iotThingModelService.listEvents(serviceId));
    }

    /**
     * 新增事件。
     *
     * @param serviceId 服务主键
     * @param req       事件信息
     * @return 新事件主键
     */
    @PostMapping("/services/{serviceId}/events")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("新增 IoT 物模型事件")
    public R<Long> createEvent(@PathVariable Long serviceId, @Valid @RequestBody IotEventReq req) {
        req.setServiceId(serviceId);
        return R.ok(iotThingModelService.createEvent(req));
    }

    /**
     * 编辑事件。
     *
     * @param serviceId 服务主键
     * @param id        事件主键
     * @param req       事件信息
     * @return 空响应
     */
    @PutMapping("/services/{serviceId}/events/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("编辑 IoT 物模型事件")
    public R<Void> updateEvent(@PathVariable Long serviceId, @PathVariable Long id,
                               @Valid @RequestBody IotEventReq req) {
        iotThingModelService.updateEvent(id, req);
        return R.ok();
    }

    /**
     * 删除事件。
     *
     * @param serviceId 服务主键
     * @param id        事件主键
     * @return 空响应
     */
    @DeleteMapping("/services/{serviceId}/events/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("删除 IoT 物模型事件")
    public R<Void> removeEvent(@PathVariable Long serviceId, @PathVariable Long id) {
        iotThingModelService.removeEvent(id);
        return R.ok();
    }

    // ---------- TSL 导入导出 ----------

    /**
     * 导出 TSL（平台表 → TSL JSON）。
     *
     * @param productId 产品主键
     * @return TSL 文档
     */
    @GetMapping("/tsl")
    @SaCheckPermission("iot:product:tsl-export")
    public R<TslDocument> exportTsl(@PathVariable Long productId) {
        return R.ok(iotThingModelService.exportTsl(productId));
    }

    /**
     * 导入 TSL（TSL JSON → 平台表，全量替换当前草稿结构；校验失败整体不落库）。
     *
     * @param productId 产品主键
     * @param doc       TSL 文档
     * @return 导入结果
     */
    @PostMapping("/tsl")
    @SaCheckPermission("iot:product:tsl-import")
    @Idempotent
    @Log("导入 IoT 产品 TSL")
    public R<TslImportResult> importTsl(@PathVariable Long productId,
                                        @Valid @RequestBody TslDocument doc) {
        return R.ok(iotThingModelService.importTsl(productId, doc));
    }
}
