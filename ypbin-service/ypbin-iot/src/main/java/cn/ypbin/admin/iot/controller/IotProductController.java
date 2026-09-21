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

import cn.ypbin.admin.iot.model.query.IotProductQuery;
import cn.ypbin.admin.iot.model.req.IotProductReq;
import cn.ypbin.admin.iot.model.resp.IotProductResp;
import cn.ypbin.admin.iot.model.resp.IotProductVersionResp;
import cn.ypbin.admin.iot.service.IotProductService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
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
 * IoT 产品接口（§3.2/§3.8）。
 *
 * <p>权限码形如 {@code iot:product:list}；路径是纯资源路径，网关按
 * {@code Path=/iot/**} + {@code StripPrefix=1} 转发，服务内看到 {@code /products}。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class IotProductController {

    private final IotProductService iotProductService;

    /**
     * 分页查询产品。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:product:list")
    public R<PageResult<IotProductResp>> page(IotProductQuery query) {
        return R.ok(iotProductService.pageProducts(query));
    }

    /**
     * 产品详情。
     *
     * @param id 产品主键
     * @return 产品信息
     */
    @GetMapping("/{id}")
    @SaCheckPermission("iot:product:list")
    public R<IotProductResp> detail(@PathVariable Long id) {
        return R.ok(iotProductService.detailProduct(id));
    }

    /**
     * 新增产品。
     *
     * @param req 产品信息
     * @return 新产品主键
     */
    @PostMapping
    @SaCheckPermission("iot:product:create")
    @Idempotent
    @Log("新增 IoT 产品")
    public R<Long> create(@Valid @RequestBody IotProductReq req) {
        return R.ok(iotProductService.createProduct(req));
    }

    /**
     * 编辑产品基础信息。
     *
     * @param id  产品主键
     * @param req 产品信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("编辑 IoT 产品")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IotProductReq req) {
        iotProductService.updateProduct(id, req);
        return R.ok();
    }

    /**
     * 删除产品（已发布产品需先建草稿再删）。
     *
     * @param id 产品主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:product:delete")
    @Idempotent
    @Log("删除 IoT 产品")
    public R<Void> remove(@PathVariable Long id) {
        iotProductService.removeProduct(id);
        return R.ok();
    }

    /**
     * 新建草稿（已发布产品置回可编辑态，推进下一个版本号）。
     *
     * @param id 产品主键
     * @return 新草稿版本号
     */
    @PostMapping("/{id}/draft")
    @SaCheckPermission("iot:product:update")
    @Idempotent
    @Log("新建 IoT 产品物模型草稿")
    public R<String> newDraft(@PathVariable Long id) {
        return R.ok(iotProductService.newDraft(id));
    }

    /**
     * 发布当前草稿。
     *
     * @param id 产品主键
     * @return 本次发布的版本号
     */
    @PostMapping("/{id}/publish")
    @SaCheckPermission("iot:product:publish")
    @Idempotent
    @Log("发布 IoT 产品物模型")
    public R<String> publish(@PathVariable Long id) {
        return R.ok(iotProductService.publish(id));
    }

    /**
     * 版本列表。
     *
     * @param id 产品主键
     * @return 版本列表
     */
    @GetMapping("/{id}/versions")
    @SaCheckPermission("iot:product:list")
    public R<List<IotProductVersionResp>> versions(@PathVariable Long id) {
        return R.ok(iotProductService.listVersions(id));
    }
}
