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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * access 节点注册表（进程内）。
 *
 * <p><b>取舍（写清楚，别当成完整实现）</b>：节点信息目前只活在进程内——本服务重启后节点需要重新注册，
 * 期间它们的续约会收到 {@code nodeFenced=true}。这不是漏洞而是设计好的恢复路径：access 收到节点级失效后
 * 会整体停采 → 重新注册 → 重新领取（P4b 已端到端验证过这条路径）。
 * 把节点也落库（容量与存活跨重启）属于 M0b 的后续项，不影响本增量的正确性。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class AccessNodeRegistry {

    /** 「不限容量」的哨兵值（{@code ConcurrentHashMap} 不接受 null 值，用它代替）。 */
    public static final int UNLIMITED_CAPACITY = Integer.MAX_VALUE;

    private final Map<String, Integer> nodes = new ConcurrentHashMap<>();

    /**
     * 每节点的领取锁：容量是「先读后写」（数当前持有 → 算剩余 → 限量分配），
     * 同一进程内并发领取同一节点必须串行，否则两边都算出「剩余 = 全量」而超额分配。
     *
     * <p><b>作用域是「同一 JVM」</b>：多个副本用同一个 nodeId 属误配置（nodeId 是租约归属的键，必须唯一）；
     * 把它做成数据库级原子（节点行 + {@code SELECT ... FOR UPDATE}）是 M0b 的事，已在 docs/LEASE.md 记录。</p>
     */
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    /**
     * 注册/覆盖节点。
     *
     * <p>⚠️ {@code maxTenants == null} 表示<b>不限</b>（单节点全量）——这是最常见的配置，
     * 必须映射成哨兵值再入 Map：直接把 null 放进 {@code ConcurrentHashMap} 会抛 NPE，
     * 表现为 register 端点 500、access 启动失败（本类曾经就是这样，被单测抓出来）。</p>
     *
     * @param accessNode 节点标识
     * @param maxTenants 最多可持有租户数；{@code null} = 不限
     * @throws IllegalArgumentException 容量为负数（配置错误，不静默接受）
     */
    public void register(String accessNode, Integer maxTenants) {
        if (maxTenants != null && maxTenants < 0) {
            throw new IllegalArgumentException("节点容量不能为负数：node=" + accessNode + " maxTenants=" + maxTenants);
        }
        nodes.put(accessNode, maxTenants == null ? UNLIMITED_CAPACITY : maxTenants);
    }

    /**
     * 查询节点的容量。
     *
     * @param accessNode 节点标识
     * @return 容量；未注册节点返回 {@link #UNLIMITED_CAPACITY}
     *     （判定「是否注册」请用 {@link #isRegistered}，不要靠本方法的返回值）
     */
    public int capacityOf(String accessNode) {
        return nodes.getOrDefault(accessNode, UNLIMITED_CAPACITY);
    }

    /**
     * 节点是否已注册。
     *
     * @param accessNode 节点标识
     * @return 已注册返回 {@code true}
     */
    public boolean isRegistered(String accessNode) {
        return nodes.containsKey(accessNode);
    }

    /**
     * 取该节点的领取锁（容量计数的临界区）。
     *
     * @param accessNode 节点标识
     * @return 该节点的可重入锁（同节点返回同一把）
     */
    public Lock lockFor(String accessNode) {
        return locks.computeIfAbsent(accessNode, ignored -> new ReentrantLock());
    }

    /** 已注册节点数（供观测/测试）。 */
    public int size() {
        return nodes.size();
    }
}
