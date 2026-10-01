/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.openapi;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.ypbin.admin.iot.model.query.IotProductQuery;
import cn.ypbin.admin.iot.model.resp.IotCommandResp;
import cn.ypbin.admin.iot.model.resp.IotEventResp;
import cn.ypbin.admin.iot.model.resp.IotProductResp;
import cn.ypbin.admin.iot.model.resp.IotProductVersionResp;
import cn.ypbin.admin.iot.model.resp.IotPropertyResp;
import cn.ypbin.admin.iot.model.resp.IotServiceResp;
import cn.ypbin.admin.iot.model.tsl.TslDocument;
import cn.ypbin.admin.iot.service.IotProductService;
import cn.ypbin.admin.iot.service.IotThingModelService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API 门面：产品/物模型只读（看板 #11 门面，O-5）。
 *
 * <p>薄适配，委托 {@link IotProductService}/{@link IotThingModelService}
 * （与管理面同源）。物模型路径形状与管理面逐字一致（含 {@code productId} 段，
 * 尽管部分方法只用 {@code serviceId}——第三方认知一致优先）。</p>
 *
 * <p>权限注意：{@code /tsl} 用独立码 {@code iot:product:tsl-export}（不是 list），
 * 前置核验 PREFLIGHT §1.2 修法 (i) 已定。刻意不映射：物模型/产品/映射/标签的全部写端点。</p>
 */
@RestController
@RequestMapping("/open-api/v1/products")
@RequiredArgsConstructor
public class OpenApiProductController {

    private final IotProductService iotProductService;
    private final IotThingModelService iotThingModelService;

    /** O-5 产品分页查询。 */
    @GetMapping
    @SaCheckPermission("iot:product:list")
    public R<PageResult<IotProductResp>> page(IotProductQuery query) {
        return R.ok(iotProductService.pageProducts(query));
    }

    /** O-5 产品详情。 */
    @GetMapping("/{id}")
    @SaCheckPermission("iot:product:list")
    public R<IotProductResp> detail(@PathVariable("id") Long id) {
        return R.ok(iotProductService.detailProduct(id));
    }

    /** O-5 产品版本列表。 */
    @GetMapping("/{id}/versions")
    @SaCheckPermission("iot:product:list")
    public R<List<IotProductVersionResp>> versions(@PathVariable("id") Long id) {
        return R.ok(iotProductService.listVersions(id));
    }

    /** O-5 物模型服务列表。 */
    @GetMapping("/{productId}/services")
    @SaCheckPermission("iot:product:list")
    public R<List<IotServiceResp>> services(@PathVariable("productId") Long productId) {
        return R.ok(iotThingModelService.listServices(productId));
    }

    /** O-5 物模型属性列表。 */
    @GetMapping("/{productId}/services/{serviceId}/properties")
    @SaCheckPermission("iot:product:list")
    public R<List<IotPropertyResp>> properties(@PathVariable("productId") Long productId,
                                               @PathVariable("serviceId") Long serviceId) {
        return R.ok(iotThingModelService.listProperties(serviceId));
    }

    /** O-5 物模型命令列表（只读定义，不含下发）。 */
    @GetMapping("/{productId}/services/{serviceId}/commands")
    @SaCheckPermission("iot:product:list")
    public R<List<IotCommandResp>> commands(@PathVariable("productId") Long productId,
                                            @PathVariable("serviceId") Long serviceId) {
        return R.ok(iotThingModelService.listCommands(serviceId));
    }

    /** O-5 物模型事件列表。 */
    @GetMapping("/{productId}/services/{serviceId}/events")
    @SaCheckPermission("iot:product:list")
    public R<List<IotEventResp>> events(@PathVariable("productId") Long productId,
                                        @PathVariable("serviceId") Long serviceId) {
        return R.ok(iotThingModelService.listEvents(serviceId));
    }

    /** O-5 TSL 文档导出（独立权限码，不与 list 共用）。 */
    @GetMapping("/{productId}/tsl")
    @SaCheckPermission("iot:product:tsl-export")
    public R<TslDocument> tsl(@PathVariable("productId") Long productId) {
        return R.ok(iotThingModelService.exportTsl(productId));
    }
}
