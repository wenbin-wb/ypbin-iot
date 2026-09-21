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

import cn.ypbin.admin.iot.model.query.IotProductQuery;
import cn.ypbin.admin.iot.model.req.IotProductReq;
import cn.ypbin.admin.iot.model.resp.IotProductResp;
import cn.ypbin.admin.iot.model.resp.IotProductVersionResp;
import cn.ypbin.starter.crud.model.PageResult;
import java.util.List;

/**
 * IoT 产品服务（§3.2/§3.8）。
 *
 * <p>涵盖产品 CRUD 与物模型版本化（draft → published）；TSL 导入导出由
 * {@link cn.ypbin.admin.iot.service.IotThingModelService} 提供。
 * 已发布产品同版本不可变：编辑物模型前须先 {@link #newDraft(Long)} 建新草稿。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
public interface IotProductService {

    /**
     * 分页查询本租户的产品。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    PageResult<IotProductResp> pageProducts(IotProductQuery query);

    /**
     * 产品详情。
     *
     * @param id 产品主键
     * @return 产品信息
     */
    IotProductResp detailProduct(Long id);

    /**
     * 新增产品（初始为 draft，无物模型）。
     *
     * @param req 产品信息
     * @return 新产品主键
     */
    Long createProduct(IotProductReq req);

    /**
     * 编辑产品基础信息。
     *
     * @param id  产品主键
     * @param req 产品信息
     */
    void updateProduct(Long id, IotProductReq req);

    /**
     * 逻辑删除产品（已发布产品不可直接删除）。
     *
     * @param id 产品主键
     */
    void removeProduct(Long id);

    /**
     * 新建草稿：把已发布产品置回可编辑草稿状态，并推进下一个未发布版本号。
     *
     * <p>仅当产品当前为 published 时有效；已是 draft 则幂等返回当前版本号。</p>
     *
     * @param id 产品主键
     * @return 新草稿版本号
     */
    String newDraft(Long id);

    /**
     * 发布当前草稿：校验结构 → 递增版本号 → 记录已发布版本。
     *
     * <p>产品须为 draft；发布后同版本不可变，后续变更须 {@link #newDraft(Long)}。</p>
     *
     * @param id 产品主键
     * @return 本次发布的版本号
     */
    String publish(Long id);

    /**
     * 版本列表（按版本号倒序）。
     *
     * @param productId 产品主键
     * @return 版本列表
     */
    List<IotProductVersionResp> listVersions(Long productId);
}
