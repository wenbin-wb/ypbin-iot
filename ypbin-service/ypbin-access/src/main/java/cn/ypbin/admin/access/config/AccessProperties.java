/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.access.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * access 节点参数（前缀 {@code ypbin.access}）。
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AccessProperties.PREFIX)
public class AccessProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.access";

        /**
     * 时钟偏移**跳变**阈值（默认 10 分钟）。
     *
     * <p>语义是「相对**已校准值**的跳变」，不是「绝对量级」：首次校准一律采纳（不校准的判据比大偏移更危险），
     * 跳变则要求连续两次读数一致才采纳——避免 NTP 阶跃/DB 故障切换的过渡态把全部租户误判过期。</p>
     */
    private Duration clockSkewJumpThreshold = Duration.ofMinutes(10);

/** 节点标识：租约归属的键，**必须全局唯一**（为空则拒绝启动）。 */
    private String nodeId = "";

    /** 最多可持有租户数；{@code null} = 不限（单节点全量）。 */
    private Integer capacity;

    /** 续约周期（毫秒）：必须 ≥ 一次调用最坏耗时的 2 倍（启动自检拒绝太小）。 */
    private long renewIntervalMs = 10_000L;

    /** 周期重领间隔（毫秒）：接管的执行入口（只在启动时领取会让待接管租户永远无人接手）。 */
    private long acquireIntervalMs = 15_000L;

    /** 是否在启动期执行握手（注册 + 领取）；关闭时节点零采集，仅供「验装配」的测试使用。 */
    private boolean startupHandshakeEnabled = true;

    /**
     * 配置对账的**周期安全网**间隔（毫秒）：超过该间隔未尝试过对账的持有租户会被强制对账一次
     * （每轮最多一个）。
     *
     * <p>为什么需要：{@code config_epoch} 信号依赖业务侧写入口与租户上下文，单租户部署
     * （未开租户插件）或台账没有该租户时**没有信号**，纯信号驱动会退化成「改了永远发现不了」。
     * 5 分钟是权衡：太小则退化成「周期全量拉」（失去信号驱动的意义），太大则信号缺失场景收敛太慢。
     * 设为 {@code <= 0} 可关闭（仅在确定信号链路可靠时这么做）。</p>
     */
    private long configRefreshIntervalMs = 300_000L;
}
