/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.system.entity.SysNotice;
import cn.ypbin.admin.system.mapper.SysNoticeMapper;
import cn.ypbin.admin.system.model.req.NoticeSaveReq;
import cn.ypbin.admin.system.service.NoticePublishService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 公告投放客户端集合的保存归一化测试。
 *
 * <p>客户端 ID 以逗号集合落库，空值代表「全部客户端可见」。保存时必须 trim、去掉空段与重复项，
 * 并把 {@code null}/空白统一成空串——空串才能让「编辑时清空客户端」在 {@code update-strategy: not_null}
 * 下真正写入，同时保证列表的 {@code FIND_IN_SET} 精确匹配不受空白干扰。</p>
 *
 * @author wenbin
 * @since 2026-11-15
 */
class SysNoticeServiceImplClientIdsTest {

    private SysNoticeMapper noticeMapper;

    private SysNoticeServiceImpl noticeService;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "notice-client-ids"),
            SysNotice.class);
    }

    @BeforeEach
    void setUp() {
        noticeMapper = mock(SysNoticeMapper.class);
        noticeService = spy(new SysNoticeServiceImpl(mock(NoticePublishService.class)));
        // ServiceImpl 的 baseMapper 由容器注入，单测里手工装配（不启动 Spring）
        ReflectionTestUtils.setField(noticeService, "baseMapper", noticeMapper);
        when(noticeMapper.insert(any(SysNotice.class))).thenReturn(1);
    }

    @Test
    @DisplayName("保存草稿：客户端集合 trim、去空段、去重后以逗号连接")
    void shouldNormalizeClientIdsOnCreate() {
        noticeService.createNotice(draftReq(" web, app ,web, ,miniapp "));

        SysNotice saved = capturedInsert();
        assertThat(saved.getClientIds()).isEqualTo("web,app,miniapp");
    }

    @Test
    @DisplayName("保存草稿：null/空白客户端集合统一为空串（空=全部客户端）")
    void shouldNormalizeBlankClientIdsToEmptyString() {
        noticeService.createNotice(draftReq("   "));

        SysNotice saved = capturedInsert();
        assertThat(saved.getClientIds()).isEmpty();
    }

    private SysNotice capturedInsert() {
        ArgumentCaptor<SysNotice> captor = ArgumentCaptor.forClass(SysNotice.class);
        verify(noticeMapper).insert(captor.capture());
        return captor.getValue();
    }

    private NoticeSaveReq draftReq(String clientIds) {
        NoticeSaveReq req = new NoticeSaveReq();
        req.setTitle("测试公告");
        req.setContent("<p>内容</p>");
        req.setClientIds(clientIds);
        req.setNoticeType(1);
        req.setNoticeScope(1);
        req.setNotifyMethods("site");
        req.setIsTop(0);
        req.setPublishType(1);
        req.setPublishStatus(0);
        return req;
    }
}
