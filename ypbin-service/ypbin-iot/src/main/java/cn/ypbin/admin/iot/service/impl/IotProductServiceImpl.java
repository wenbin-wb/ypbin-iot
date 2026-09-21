/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.enums.ModelStatus;
import cn.ypbin.admin.iot.entity.IotProductVersion;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotProductVersionMapper;
import cn.ypbin.admin.iot.model.query.IotProductQuery;
import cn.ypbin.admin.iot.model.req.IotProductReq;
import cn.ypbin.admin.iot.model.resp.IotProductResp;
import cn.ypbin.admin.iot.model.resp.IotProductVersionResp;
import cn.ypbin.admin.iot.service.IotProductService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IoT 产品服务实现（§3.2/§3.8）。
 *
 * <p>物模型结构（服务/属性/命令/事件）以「当前草稿结构」形式存在各表中；
 * 发布时把版本号写入 {@code iot_product_version} 并将产品置为 published（§3.8 状态机）。
 * 已发布产品同版本不可变：编辑物模型前须先 {@code newDraft}。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotProductServiceImpl extends BaseServiceImpl<IotProductMapper, IotProduct>
    implements IotProductService {

    /** 从未产生过版本记录时的哨兵值（不是真实版本号，仅用于内部比较）。 */
    private static final String NO_VERSION = "v0.0";

    /** 首个正式版本号。 */
    private static final String FIRST_VERSION = "v1.0";

    private final IotProductVersionMapper iotProductVersionMapper;

    public IotProductServiceImpl(IotProductVersionMapper iotProductVersionMapper) {
        this.iotProductVersionMapper = iotProductVersionMapper;
    }

    @Override
    public PageResult<IotProductResp> pageProducts(IotProductQuery query) {
        PageResult<IotProduct> source = page(query, buildWrapper(query));
        List<IotProductResp> items = source.getItems().stream().map(this::toResp).toList();
        return PageResult.of(items, source.getTotal(), source.getPage(), source.getPageSize());
    }

    @Override
    public IotProductResp detailProduct(Long id) {
        return toResp(requireProduct(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProduct(IotProductReq req) {
        IotProduct product = new IotProduct();
        applyProduct(product, req);
        product.setModelStatus(ModelStatus.DRAFT.getCode());
        save(product);
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProduct(Long id, IotProductReq req) {
        IotProduct product = requireProduct(id);
        applyProduct(product, req);
        updateById(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeProduct(Long id) {
        IotProduct product = requireProduct(id);
        if (ModelStatus.PUBLISHED.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "已发布产品不可直接删除，请先新建草稿再删除");
        }
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String newDraft(Long id) {
        IotProduct product = requireProduct(id);
        IotProductVersion draft = findLatestDraftVersion(id);
        if (ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {
            // 已是草稿：复用当前草稿版本；产品刚建、尚无版本记录时补一条草稿
            if (draft != null) {
                return draft.getVersionNo();
            }
            String versionNo = nextDraftVersionNo(id);
            saveVersion(id, versionNo, ModelStatus.DRAFT.getCode(), null);
            return versionNo;
        }
        // 已发布 → 推进一个 minor 作为新草稿（已发布版本记录保持不可变，§3.8）
        String versionNo = nextDraftVersionNo(id);
        saveVersion(id, versionNo, ModelStatus.DRAFT.getCode(), null);
        product.setModelStatus(ModelStatus.DRAFT.getCode());
        updateById(product);
        return versionNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String publish(Long id) {
        IotProduct product = requireProduct(id);
        if (!ModelStatus.DRAFT.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "仅草稿状态可发布，请先新建草稿");
        }
        IotProductVersion draft = findLatestDraftVersion(id);
        String versionNo;
        if (draft != null) {
            // 发布的是「当前草稿那一版」：把该草稿记录置为已发布，不另分配新号，
            // 否则每次编辑周期会跳两级版本并留下永不发布的孤儿草稿记录
            versionNo = draft.getVersionNo();
            draft.setModelStatus(ModelStatus.PUBLISHED.getCode());
            draft.setPublishedAt(LocalDateTime.now());
            iotProductVersionMapper.updateById(draft);
        } else {
            versionNo = nextDraftVersionNo(id);
            saveVersion(id, versionNo, ModelStatus.PUBLISHED.getCode(), null);
        }
        product.setModelStatus(ModelStatus.PUBLISHED.getCode());
        updateById(product);
        return versionNo;
    }

    @Override
    public List<IotProductVersionResp> listVersions(Long productId) {
        return listVersionEntities(productId).stream()
            .map(this::toVersionResp)
            .toList();
    }

    /**
     * 产品基础字段赋值（编辑与新增共用）。
     *
     * @param product 实体
     * @param req     请求
     */
    private void applyProduct(IotProduct product, IotProductReq req) {
        product.setProductCode(req.getProductCode());
        product.setProductName(req.getProductName());
        product.setProtocol(req.getProtocol());
        product.setDataFormat(StringUtils.hasText(req.getDataFormat()) ? req.getDataFormat() : "json");
        product.setDeviceType(req.getDeviceType());
        product.setManufacturerId(req.getManufacturerId());
        product.setManufacturerName(req.getManufacturerName());
        product.setRemark(req.getRemark());
    }

    /**
     * 按主键取产品，不存在抛业务异常。
     *
     * @param id 产品主键
     * @return 产品实体
     */
    IotProduct requireProduct(Long id) {
        IotProduct product = getById(id);
        if (product == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "产品不存在：" + id);
        }
        return product;
    }

    /**
     * 记录一个版本（draft/published）。
     *
     * @param productId   产品主键
     * @param versionNo   版本号
     * @param modelStatus 版本状态
     * @param remark      备注（可空）
     */
    private void saveVersion(Long productId, String versionNo, String modelStatus, String remark) {
        IotProductVersion version = new IotProductVersion();
        version.setProductId(productId);
        version.setVersionNo(versionNo);
        version.setModelStatus(modelStatus);
        version.setRemark(remark);
        version.setPublishedAt(ModelStatus.PUBLISHED.getCode().equals(modelStatus) ? LocalDateTime.now() : null);
        iotProductVersionMapper.insert(version);
    }

    /**
     * 取该产品已存在的最大版本号（未发布过则 {@link #NO_VERSION}）。
     *
     * <p>按主键倒序取首条而非按版本号字符串倒序：版本记录按单调递增的顺序写入，
     * 主键序即版本序；字符串序会把 {@code v1.10} 排到 {@code v1.9} 之前。</p>
     *
     * @param productId 产品主键
     * @return 版本号
     */
    private String latestVersionNo(Long productId) {
        List<IotProductVersion> versions = listVersionEntities(productId);
        return versions.isEmpty() ? NO_VERSION : versions.getFirst().getVersionNo();
    }

    /**
     * 取该产品最新的草稿版本记录（无则 {@code null}）。
     *
     * @param productId 产品主键
     * @return 草稿版本记录；不存在时 {@code null}
     */
    private IotProductVersion findLatestDraftVersion(Long productId) {
        return listVersionEntities(productId).stream()
            .filter(version -> ModelStatus.DRAFT.getCode().equals(version.getModelStatus()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 查询版本记录（按主键倒序，即版本由新到旧）。
     *
     * @param productId 产品主键
     * @return 版本记录列表（空集合表示无版本）
     */
    private List<IotProductVersion> listVersionEntities(Long productId) {
        LambdaQueryWrapper<IotProductVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotProductVersion::getProductId, productId)
            .orderByDesc(IotProductVersion::getId);
        return iotProductVersionMapper.selectList(wrapper);
    }

    /**
     * 计算下一个草稿版本号：未发布过 v1.0；否则在最新版本的 minor 上 +1（major 保持不变）。
     *
     * @param productId 产品主键
     * @return 版本号
     */
    String nextDraftVersionNo(Long productId) {
        String latest = latestVersionNo(productId);
        if (NO_VERSION.equals(latest)) {
            return FIRST_VERSION;
        }
        int dot = latest.lastIndexOf('.');
        int major = Integer.parseInt(latest.substring(1, dot));
        int minor = Integer.parseInt(latest.substring(dot + 1));
        return "v" + major + "." + (minor + 1);
    }

    /**
     * 构造查询条件（包内可见以便单测）。
     *
     * @param query 查询条件
     * @return 条件构造器
     */
    LambdaQueryWrapper<IotProduct> buildWrapper(IotProductQuery query) {
        LambdaQueryWrapper<IotProduct> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(inner -> inner.like(IotProduct::getProductName, keyword)
                .or()
                .like(IotProduct::getProductCode, keyword));
        }
        if (StringUtils.hasText(query.getProtocol())) {
            wrapper.eq(IotProduct::getProtocol, query.getProtocol());
        }
        if (StringUtils.hasText(query.getModelStatus())) {
            wrapper.eq(IotProduct::getModelStatus, query.getModelStatus());
        }
        return wrapper;
    }

    /**
     * 实体转响应（包内可见以便单测）。
     *
     * @param entity 实体
     * @return 响应
     */
    IotProductResp toResp(IotProduct entity) {
        IotProductResp resp = new IotProductResp();
        resp.setId(entity.getId());
        resp.setProductCode(entity.getProductCode());
        resp.setProductName(entity.getProductName());
        resp.setProtocol(entity.getProtocol());
        resp.setDataFormat(entity.getDataFormat());
        resp.setDeviceType(entity.getDeviceType());
        resp.setManufacturerId(entity.getManufacturerId());
        resp.setManufacturerName(entity.getManufacturerName());
        resp.setModelStatus(entity.getModelStatus());
        resp.setRemark(entity.getRemark());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }

    /**
     * 版本实体转响应（包内可见以便单测）。
     *
     * @param entity 版本实体
     * @return 响应
     */
    IotProductVersionResp toVersionResp(IotProductVersion entity) {
        IotProductVersionResp resp = new IotProductVersionResp();
        resp.setId(entity.getId());
        resp.setProductId(entity.getProductId());
        resp.setVersionNo(entity.getVersionNo());
        resp.setModelStatus(entity.getModelStatus());
        resp.setPublishedAt(entity.getPublishedAt());
        resp.setRemark(entity.getRemark());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
