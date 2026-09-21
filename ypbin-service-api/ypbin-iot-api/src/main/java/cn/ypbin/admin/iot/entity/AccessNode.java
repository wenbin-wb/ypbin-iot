/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.entity;

import cn.ypbin.starter.data.core.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 接入节点注册表（M0b-1：容量落库）。
 *
 * <p>为什么必须落库：容量判定原先在 iot 服务的**进程内计数**里做，多副本部署时会各自以为还有余额，
 * 造成超额分配（且只能靠日志发现）。落库后可在事务内对**节点行**加锁（{@code SELECT ... FOR UPDATE}），
 * 使「查余量 + 分配」成为数据库级原子操作。</p>
 *
 * <p>本表是**平台表**（不按租户隔离）⇒ 必须登记在 {@code ypbin.tenant.ignore-tables}。</p>
 *
 * @author wenbin
 * @since 2026-09-21
 */
@Getter
@Setter
@TableName("access_node")
public class AccessNode extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 节点标识（租约归属的键，全局唯一）。 */
    private String accessNode;

    /** 最多可持有租户数；{@code null} = 不限。 */
    private Integer maxTenants;

    /** 最近一次注册/心跳时间。 */
    private LocalDateTime lastHeartbeatAt;
}
