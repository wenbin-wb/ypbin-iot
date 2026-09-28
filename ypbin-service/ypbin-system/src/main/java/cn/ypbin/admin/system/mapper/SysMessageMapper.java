/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.mapper;

import cn.ypbin.admin.system.entity.SysMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

/**
 * 用户消息 Mapper。
 *
 * @author wenbin
 * @since 2026-08-02
 */
public interface SysMessageMapper extends BaseMapper<SysMessage> {

    @Insert("""
        INSERT INTO sys_message
            (id, tenant_id, notice_id, publish_version, receiver_user_id,
             title, content, message_type, read_status,
             create_time, update_time, status, is_deleted)
        VALUES
            (#{id}, #{tenantId}, #{noticeId}, #{publishVersion}, #{receiverUserId},
             #{title}, #{content}, #{messageType}, #{readStatus},
             #{createTime}, #{updateTime}, #{status}, #{isDeleted})
        """)
    int insertNoticeMessage(SysMessage message);

    /**
     * 写一条**普通站内信**（无公告来源：{@code notice_id}/{@code publish_version} 为空）。
     *
     * <p>列清单与 {@link #insertNoticeMessage} 一致，但语义不同：那条来自公告投递（带公告 ID 与发布版本），
     * 这条来自业务域的通知投递（如 IoT 告警）。刻意不合并成同一个方法名——两者的事后排查路径完全不同
     * （「这条消息是哪个公告发的」vs「这条消息是哪条告警发的」），把名字写清楚比省一个方法重要。</p>
     *
     * <p>审计字段由调用方显式给出：投递发生在调度线程里，没有登录态可填。</p>
     *
     * @param message 消息（tenantId/receiverUserId/title/content/messageType/readStatus 必须已填）
     * @return 影响行数
     */
    @Insert("""
        INSERT INTO sys_message
            (id, tenant_id, notice_id, publish_version, receiver_user_id,
             title, content, message_type, read_status,
             create_time, update_time, status, is_deleted)
        VALUES
            (#{id}, #{tenantId}, #{noticeId}, #{publishVersion}, #{receiverUserId},
             #{title}, #{content}, #{messageType}, #{readStatus},
             #{createTime}, #{updateTime}, #{status}, #{isDeleted})
        """)
    int insertPlainMessage(SysMessage message);
}
