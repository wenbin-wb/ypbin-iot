/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.alert;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 告警与阈值能力的可调参数（口径的**可调部分**；口径本身见 {@link AlertRules} 与设计文档 §2）。
 *
 * <p><b>默认值都是「开箱可用」的那一档</b>（用户口径：处处合理默认、开箱可用）：检查周期 15s、
 * 连续 3 次、静默（重复通知）10 分钟、级别「警告」、通知「站内信 + 邮件」。</p>
 *
 * <p><b>两个开关的语义必须分清</b>：</p>
 * <ul>
 *   <li>{@link #enabled}：**总开关**。关闭时评估器不启动、接口返回明确失败（**不是空列表**——
 *       空列表会被读成「没有告警」，那是最危险的一种假阴性，设计 §3.6.2）；</li>
 *   <li>{@link #notifyEnabled}：只关通知不动判定（判定结果照常落库、页面照常可见）。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AlertProperties.PREFIX)
public class AlertProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.alert";

    /** 是否启用告警能力（关闭时接口返回明确失败而不是空列表）。 */
    private boolean enabled = true;

    /** 评估周期（毫秒；默认 15s，与既有 {@code OutageScanner} 同量级）。 */
    private long evaluateIntervalMs = 15_000L;

    /** 评估器首次执行延迟（毫秒；避免与应用启动争抢数据库/Redis）。 */
    private long evaluateInitialDelayMs = 10_000L;

    /**
     * 单轮最多评估的设备数（超出则**跨轮滚动**并计数，不静默丢弃）。
     *
     * <p>设计 §2.2.2 的原话：「单轮设备数上限可配（超出则跨轮滚动并计数，不静默丢弃）」。</p>
     */
    private int evaluateBatchSize = 500;

    /** 陈旧判定倍数：阈值 = 倍数 × 采集周期（取不到周期时用 {@link #stalenessFallbackTtlMs}）。 */
    private long stalenessIntervalMultiplier = 3L;

    /** 取不到采集周期时的陈旧兜底 TTL（毫秒；默认 90s）。 */
    private long stalenessFallbackTtlMs = 90_000L;

    /** 陈旧 TTL 上限（毫秒）：防「采集周期配成 1 小时」把陈旧判定变成永不生效。 */
    private long stalenessMaxTtlMs = 3_600_000L;

    /** 默认连续次数 N（预设模板与缺省值用）。 */
    private int defaultTriggerThreshold = 3;

    /** 默认 pending 最大挂起时长（秒）。 */
    private int defaultPendingTtlSec = 300;

    /** 默认重复通知间隔（秒；默认 10 分钟）。 */
    private int defaultRepeatIntervalSec = 1800;

    /** 默认级别码。 */
    private String defaultSeverity = "WARNING";

    /** 默认通知渠道（逗号分隔）。 */
    private String defaultNotifyChannels = "INBOX,EMAIL";

    /** 单条规则最多允许的点位条件行数（防一次保存塞进上千行把库与规则加载拖住）。 */
    private int maxPointsPerRule = 50;

    /** 单页最大条数（列表接口）。 */
    private int maxPageSize = 200;

    /**
     * 断档 → 告警映射总开关（**平台级**；关掉即完全不做断档类告警）。
     *
     * <p><b>opt-in 语义（刻意如此）</b>：断档类告警**只在设备被某条「启用且没有点位条件」的规则覆盖时**
     * 才产生（作用域四级照旧：POINT/DEVICE &gt; PRODUCT &gt; TENANT）。级别、通知渠道、重复间隔、静默窗口
     * 全部取自那条规则。理由：默认对全量设备产生离线告警会在一次部署后立刻改变线上通知量
     * （设计 §3.6 要求「回滚不改动既有能力」的同一条思路），而一键模板让开通只需要点一下。</p>
     */
    private boolean outageEnabled = true;

    /** 断档映射扫描周期（毫秒；与 {@code OutageScanner} 同量级即可）。 */
    private long outageScanIntervalMs = 15_000L;

    /** 断档映射首次执行延迟（毫秒）。 */
    private long outageInitialDelayMs = 20_000L;

    /** 断档映射单轮最多处理的断档事件数（超出跨轮滚动）。 */
    private int outageBatchSize = 200;

    /** 已恢复告警的保留天数（**活动告警永久保留**；默认 180 天）。 */
    private int retentionResolvedDays = 180;

    /** 保留清理周期（毫秒；默认 24 小时）。 */
    private long retentionCleanupIntervalMs = 86_400_000L;

    /** 保留清理首次执行延迟（毫秒）。 */
    private long retentionInitialDelayMs = 300_000L;

    /** 保留清理单批删除行数（批量删除，不在循环里单条查）。 */
    private int retentionBatchSize = 1000;

    /** 是否投递通知（关闭时判定照常、只不投递）。 */
    private boolean notifyEnabled = true;

    /**
     * 单条通知最多尝试次数（**含首次**）。
     *
     * <p>默认 4 = 1 次首发 + 3 次重试，因此 {@link #notifyBackoffSeconds} 的三级退避
     * （30s → 2min → 10min）**全部生效**。设计 §2.4 写的是「最多 3 次，退避 30s→2min→10min」——
     * 那两个数字自相矛盾（3 次尝试只会用到前两级退避，第三级永远走不到），本实现取「3 次**重试**」
     * 的读法并在此登记。</p>
     */
    private int notifyMaxAttempt = 4;

    /** 重试退避（秒；第 N 次尝试失败后用第 N 个值；步数应等于 {@link #notifyMaxAttempt} - 1）。 */
    private List<Long> notifyBackoffSeconds = List.of(30L, 120L, 600L);

    /** 每渠道每分钟的通知上限（超出的通知置为 PENDING **延后**，不丢弃）。 */
    private int notifyThrottlePerMinute = 60;

    /** 一轮投递最多处理的通知条数。 */
    private int notifyDispatchBatchSize = 200;

    /** 投递扫描周期（毫秒）。 */
    private long notifyDispatchIntervalMs = 10_000L;

    /** 投递扫描首次执行延迟（毫秒）。 */
    private long notifyInitialDelayMs = 15_000L;

    /** 最近一次错误的最大长度（列宽 512，留余量）。 */
    private int notifyMaxErrorLength = 500;

    /**
     * 没有可解析收件人时的兜底站内信收件人（用户 id，逗号分隔；可空）。
     *
     * <p>为什么需要它：规则没配收件人时按设计「回落规则创建者」，但**评估器线程没有登录态**，
     * 某些由系统创建的规则（例如平台侧模板）可能连创建人都没有 ⇒ 没有兜底就会静默「通知了一条没人收的
     * 站内信」。宁可发到一个配置好的值班收件人，也不要静默丢弃。</p>
     */
    private List<Long> notifyFallbackInboxUserIds = List.of();

    /** 没有可解析收件人时的兜底邮件收件人（可空）。 */
    private List<String> notifyFallbackEmails = List.of();

    /** 人话预览/通知正文里的设备与点位展示上限（防超长文本）。 */
    private int notifyMaxTextLength = 2000;
}
