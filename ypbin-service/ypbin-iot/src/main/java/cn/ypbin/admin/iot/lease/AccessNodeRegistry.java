/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.lease;

import cn.ypbin.admin.iot.entity.AccessNode;
import cn.ypbin.admin.iot.mapper.AccessNodeMapper;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * access 节点注册表（M0b-1：**落库**，容量成为数据库级原子）。
 *
 * <p><b>为什么必须落库</b>：容量判定原先在进程内计数里做。单副本可用，但多副本部署时每个副本都以为
 * 「我还有余额」，于是各自分配 ⇒ **超额分配**，且只能靠日志发现（服务端只打 WARN，从数据上看不出来）。
 * 落库后，用节点行的 {@code SELECT ... FOR UPDATE}（见 {@link #lockCapacity(String)}）把
 * 「计数 + 限量分配」跨副本串行化；节点存活也随之跨重启（此前重启期间其它节点续约会收到
 * {@code nodeFenced=true}，靠 access 重新注册恢复）。</p>
 *
 * <p><b>进程内锁仍保留</b>：{@link #lockFor(String)} 只用于减少同一 JVM 内的无效竞争；
 * 跨副本的正确性**只由数据库行锁保证**。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class AccessNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(AccessNodeRegistry.class);

    /** 「不限容量」的哨兵值（表里用 {@code NULL} 表示，内存表示用这个）。 */
    public static final int UNLIMITED_CAPACITY = Integer.MAX_VALUE;

    private final AccessNodeMapper mapper;

    /** 进程内锁（仅减少同 JVM 竞争；跨副本互斥靠数据库行锁）。 */
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    public AccessNodeRegistry(AccessNodeMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 注册（或覆盖注册）节点：落库 upsert，并更新心跳时间。
     *
     * @param accessNode 节点标识
     * @param maxTenants 最多可持有租户数；{@code null} = 不限
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(String accessNode, Integer maxTenants) {
        if (maxTenants != null && maxTenants < 0) {
            throw new IllegalArgumentException("节点容量不能为负数：node=" + accessNode
                + " maxTenants=" + maxTenants);
        }
        AccessNode existing = selectByNode(accessNode);
        if (existing == null) {
            AccessNode node = new AccessNode();
            node.setAccessNode(accessNode);
            node.setMaxTenants(maxTenants);
            node.setLastHeartbeatAt(LocalDateTime.now());
            try {
                mapper.insert(node);
                log.info("[iot] access 节点注册（首次落库）：node={} capacity={}",
                    LogSanitizer.sanitize(accessNode), capacityText(maxTenants));
                return;
            } catch (DuplicateKeyException ex) {
                // 并发首次注册：另一副本已插入 ⇒ 退化为更新路径（有意降级到 upsert 语义，异常仍入日志）
                log.debug("[iot] 节点并发首次注册，转为更新：node={}",
                    LogSanitizer.sanitize(accessNode), ex);
                existing = selectByNode(accessNode);
            }
        }
        if (existing == null) {
            // 极端竞态：既没插进去也查不到（例如被并发删除）。暴露而不是静默当作注册成功。
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "节点注册失败（并发冲突且无法读取）：" + accessNode);
        }
        Integer previous = existing.getMaxTenants();
        mapper.update(null, Wrappers.<AccessNode>lambdaUpdate()
            .eq(AccessNode::getAccessNode, accessNode)
            .set(AccessNode::getMaxTenants, maxTenants)
            .set(AccessNode::getLastHeartbeatAt, LocalDateTime.now()));
        // 覆盖注册是允许的（重启/配置调整），但「两个副本用同一个 nodeId」也走这条路，
        // 而那种误配置会带来超额分配与双份续约且**无法从数据上察觉** ⇒ 至少留下痕迹。
        log.warn("节点重复注册（覆盖原容量）：node={} 原容量={} 新容量={}；"
            + "若这是两个副本共用一个 nodeId，属误配置，请为每个副本分配唯一 node-id",
            LogSanitizer.sanitize(accessNode), capacityText(previous), capacityText(maxTenants));
    }

    /**
     * 在**事务内**锁定节点行并返回其容量（跨副本容量判定的原子前提）。
     *
     * @param accessNode 节点标识
     * @return 容量；不限容量返回 {@link #UNLIMITED_CAPACITY}
     */
    public int lockCapacity(String accessNode) {
        AccessNode row = mapper.selectForUpdate(accessNode);
        if (row == null) {
            // 不隐式注册：未注册节点拿到归属会让「谁在线」不可审计
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "节点未注册，请先调用 register：" + accessNode);
        }
        return row.getMaxTenants() == null ? UNLIMITED_CAPACITY : row.getMaxTenants();
    }

    /**
     * 非锁定读容量（仅观测用途；**不要**用它做分配判定）。
     *
     * @param accessNode 节点标识
     * @return 容量；未注册或不限容量返回 {@link #UNLIMITED_CAPACITY}
     */
    public int capacityOf(String accessNode) {
        AccessNode row = selectByNode(accessNode);
        if (row == null || row.getMaxTenants() == null) {
            return UNLIMITED_CAPACITY;
        }
        return row.getMaxTenants();
    }

    /**
     * 节点是否已注册。
     *
     * @param accessNode 节点标识
     * @return 已注册返回 {@code true}
     */
    public boolean isRegistered(String accessNode) {
        return selectByNode(accessNode) != null;
    }

    /**
     * 取该节点的进程内锁。
     *
     * @param accessNode 节点标识
     * @return 进程内锁
     */
    public Lock lockFor(String accessNode) {
        return locks.computeIfAbsent(accessNode, key -> new ReentrantLock());
    }

    private AccessNode selectByNode(String accessNode) {
        return mapper.selectByNode(accessNode);
    }

    private String capacityText(Integer maxTenants) {
        return maxTenants == null ? "不限" : String.valueOf(maxTenants);
    }
}
