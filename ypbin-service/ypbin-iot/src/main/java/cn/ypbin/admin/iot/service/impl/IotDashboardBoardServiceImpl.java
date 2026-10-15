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

import cn.ypbin.admin.iot.entity.IotDashboardBoardConfig;
import cn.ypbin.admin.iot.mapper.IotDashboardBoardConfigMapper;
import cn.ypbin.admin.iot.model.req.DashboardBoardSaveReq;
import cn.ypbin.admin.iot.model.resp.DashboardBoardResp;
import cn.ypbin.admin.iot.service.IotDashboardBoardService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * IoT 组态看板配置服务实现（每租户一份）。
 *
 * <p><b>租户隔离</b>：不手写 {@code tenant_id} 过滤——查询与写入全部由 MyBatis-Plus 租户插件
 * 按当前请求租户追加条件；无租户上下文时插件 fail-closed 直接拒绝（不会写出跨租户脏数据）。</p>
 *
 * <p><b>并发</b>：唯一键 {@code uk_iot_dashboard_board_config(tenant_id)} 保证每租户一行。
 * 「先查后写」在并发首写时会撞唯一键，此时捕获 {@link DuplicateKeyException} 回退为更新；
 * 若冲突后仍查不到行则原样抛出，不静默吞异常（与 {@code IotDashboardSnapshotServiceImpl} 同款做法）。</p>
 *
 * <p><b>覆盖式替换</b>：本接口是「整体替换」语义，不做字段级合并——两次保存之间被删掉的组件
 * 必须真正消失（局部合并会让用户「删不掉的卡片」永远留在共享配置里）。</p>
 *
 * <p>直接注入 Mapper 而非继承 {@code BaseServiceImpl}：本服务只用单行读写，没有列表/分页语义，
 * 继承会把一整套 CRUD 一并暴露给调用方（看板配置不该被任意删除），也与同目录
 * {@code IotDashboardSnapshotServiceImpl} 的写法一致。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Service
@RequiredArgsConstructor
public class IotDashboardBoardServiceImpl implements IotDashboardBoardService {

    private static final Logger log = LoggerFactory.getLogger(IotDashboardBoardServiceImpl.class);

    private final IotDashboardBoardConfigMapper iotDashboardBoardConfigMapper;

    private final ObjectMapper objectMapper;

    @Override
    public DashboardBoardResp getBoard() {
        DashboardBoardResp resp = new DashboardBoardResp();
        IotDashboardBoardConfig config = findConfig();
        if (config == null) {
            // 从未配置：空数组（不是 null）——前端把 widgets:null 判为结构不兼容并重置看板
            resp.setWidgets(List.of());
            return resp;
        }
        try {
            List<Map<String, Object>> widgets =
                DashboardBoardWidgets.parse(objectMapper, config.getConfigJson());
            resp.setWidgets(widgets);
        } catch (JacksonException ex) {
            // 库里的数据坏了（手工改库/截断写入）：带堆栈记录后给人话报错，绝不静默返回空看板
            log.error("看板配置解析失败（config_json 非法）：configId={}", config.getId(), ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "看板配置数据损坏：平台未能解析已保存的看板配置，请联系管理员（配置 ID "
                    + config.getId() + "）");
        }
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBoard(DashboardBoardSaveReq req) {
        List<Map<String, Object>> widgets =
            DashboardBoardWidgets.normalize(req == null ? null : req.getWidgets());
        String configJson;
        try {
            configJson = DashboardBoardWidgets.serialize(objectMapper, widgets);
        } catch (JacksonException ex) {
            log.error("看板配置序列化失败：widgetCount={}", widgets.size(), ex);
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "看板配置序列化失败，请检查组件字段是否含非法值");
        }

        IotDashboardBoardConfig existing = findConfig();
        if (existing != null) {
            existing.setConfigJson(configJson);
            iotDashboardBoardConfigMapper.updateById(existing);
            return;
        }

        IotDashboardBoardConfig config = new IotDashboardBoardConfig();
        config.setConfigJson(configJson);
        try {
            iotDashboardBoardConfigMapper.insert(config);
        } catch (DuplicateKeyException ex) {
            // 唯一键冲突 ⇒ 并发请求已插入本租户首行；锁读最新版本后改写（避免两行/丢更新）
            IotDashboardBoardConfig concurrent = findConfigForUpdate();
            if (concurrent == null) {
                throw ex;
            }
            log.info("看板配置并发首写冲突，改为更新：configId={}", concurrent.getId());
            concurrent.setConfigJson(configJson);
            iotDashboardBoardConfigMapper.updateById(concurrent);
        }
    }

    /**
     * 查当前租户的看板配置行（租户条件由插件追加；唯一键保证至多一行）。
     *
     * @return 配置行；不存在返回 {@code null}
     */
    private IotDashboardBoardConfig findConfig() {
        return iotDashboardBoardConfigMapper.selectOne(
            new QueryWrapper<IotDashboardBoardConfig>());
    }

    /**
     * 锁定读当前租户的看板配置行（仅用于唯一键冲突后的回退路径）。
     *
     * @return 配置行；不存在返回 {@code null}
     */
    private IotDashboardBoardConfig findConfigForUpdate() {
        return iotDashboardBoardConfigMapper.selectOne(
            new QueryWrapper<IotDashboardBoardConfig>().last("FOR UPDATE"));
    }
}
