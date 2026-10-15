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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 批量命令下发的运维参数（前缀 {@code ypbin.emqx.command-batch}）。
 *
 * <p><b>为什么不塞进 {@code EmqxProperties}</b>：{@code EmqxProperties} 管的是与 broker 交互的协议参数
 * （QoS/超时/API 凭据）与扫描周期，而本类是**批量端点自身的容量与放行阈值**（一批多少台、
 * 何时停止继续下发）——它是 HTTP 端点的治理参数，不是 broker 参数。两者都放在 {@code ypbin.emqx}
 * 命名空间下（避免出现第三个"命令相关"前缀的命名漂移），但各自成类、职责单一。</p>
 *
 * <p>键名：{@code ypbin.emqx.command-batch.max-devices} / {@code ypbin.emqx.command-batch.total-budget-ms}。</p>
 *
 * @author wenbin
 * @since 2026-10-15
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = CommandBatchProperties.PREFIX)
public class CommandBatchProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.emqx.command-batch";

    /** 默认单批设备数上限（与设备台账批量运维的 500 不同：下发是逐台 MQTT 往返，量级必须更保守）。 */
    public static final int DEFAULT_MAX_DEVICES = 50;

    /** 默认「继续下发」判定阈值（毫秒）。 */
    public static final int DEFAULT_TOTAL_BUDGET_MS = 30_000;

    /**
     * 单批最多下发的设备数（去重后计；nacos 可调）。
     *
     * <p>上限存在的原因不是"怕数据库"，而是下行是**逐台 MQTT 往返**（每台最坏等一次 EMQX 写超时），
     * 且批次占住一个同步 HTTP 请求线程。取 50 是「一次运维多选一屏设备」的量级。</p>
     */
    @Min(1)
    @Max(1000)
    private int maxDevices = DEFAULT_MAX_DEVICES;

    /**
     * 用于在剩余时间内**继续下发**的判定阈值（毫秒；nacos 可调）。
     *
     * <p><b>它是「循环入口检查点」，不是硬中断</b>：每一台设备开始处理**之前**判一次
     * 「已耗时是否已超过本阈值」，超了就把该台及其后各台标为 {@code BATCH_BUDGET_EXCEEDED}。
     * 因此**实际总耗时并不是被本值卡住的上界**，而是</p>
     * <pre>实际总耗时 ≈ 本阈值 + 最后一台被放行设备的耗时</pre>
     * <p>即最坏可超出「本阈值 + 单台下发耗时」（单台耗时上界 ≈ EMQX 写超时）。运维按这个口径估算：
     * 想压住请求总时长，要么调小本值，要么同时调小 {@code ypbin.emqx.read-timeout-ms}
     * 与单条命令的 {@code timeoutMs}。</p>
     *
     * <p>阈值用尽后**剩下的设备逐条给出 {@code BATCH_BUDGET_EXCEEDED}**，而不是让请求一直挂着：
     * 网关与调用方都有自己的超时，静默拖到被外部掐断会让"哪些设备已下发"变成未知数（更危险）。
     * 已发出的命令不受影响（不计入回滚）。</p>
     */
    @Min(1000)
    private int totalBudgetMs = DEFAULT_TOTAL_BUDGET_MS;
}
