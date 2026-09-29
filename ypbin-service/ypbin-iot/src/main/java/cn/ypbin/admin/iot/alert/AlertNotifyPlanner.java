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

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyStatus;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.data.core.EntityStatus;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通知**投递意图**的生成（设计 §2.4 投递模型第 1 条 / 第 4 条）。
 *
 * <p>它只把「该发一条什么通知」写成 {@code iot_alert_notification} 的 PENDING 行，
 * **真正的发送在 {@code AlertNotifyDispatcher}**（段 C2）。分开的理由是设计 §2.1 表 D 的原话：
 * 把判定结果与投递结果分开存，才能在通知全挂时仍然看得到告警。</p>
 *
 * <p><b>幂等键</b>：{@code instance_id + event + channel + target + 轮次}。轮次取「本条实例已经发过几次」
 * （{@code notify_count}），因此同一状态转换重复入队不会产生第二行（{@code INSERT IGNORE} + 唯一键兜住），
 * 而重复提醒的每一轮都有各自的轮次 ⇒ 都不会被误判成重复。</p>
 *
 * <p><b>没有收件人时不许静默</b>：规则没配收件人、又取不到创建者与兜底收件人时，仍然落一条
 * {@code GIVEN_UP} 行并写明原因——页面上能回答「为什么我一条都没收到」，而不是什么都没有。</p>
 *
 * <p><b>而且必须在服务端日志里看得见</b>（2026-09-30 事故整改）：此前这条路径**只写 DB**
 * （{@code last_error} + {@code GIVEN_UP}），平台侧没人主动看 ⇒ 遗留规则 {@code x}
 * （{@code notify_targets=NULL}）配平台兜底 {@code notify-fallback-emails=[]} 时，**每 10 分钟
 * 静默产生一条放弃行**，运维无从知晓。现在同一路径上补一条 WARN（含租户/规则/设备/渠道/原因码与
 * 可执行指引），使「没人收到告警」这件事在 {@code docker logs ypbin-iot} 里直接可见。</p>
 *
 * @author wenbin
 * @since 2026-10-03
 */
@Component
public class AlertNotifyPlanner {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifyPlanner.class);

    /**
     * 不可投递原因码：规则未配收件人、回落链（规则创建者 → 平台兜底收件人）也全不可用。
     *
     * <p>抽成常量而非内联字面量，是为了让日志与 {@code last_error} 共用同一份文案、避免两处漂移。</p>
     */
    static final String REASON_NO_RECIPIENT = "NO_RECIPIENT";

    private final AlertProperties properties;

    public AlertNotifyPlanner(AlertProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成一条实例在指定事件下的全部投递记录（每个渠道 × 每个收件人一行）。
     *
     * @param instance 实例（**必须已带 id 与 tenantId**）
     * @param rule     规则（用于解析渠道与收件人；断档映射时为覆盖规则，可为 {@code null}）
     * @param channels 生效渠道码集合（逗号分隔形态）
     * @param targets  生效收件人原文（逗号分隔；可空）
     * @param event    通知事件
     * @param now      当前时刻
     * @return 待投递记录（可能为空：渠道集合为空时）
     */
    public List<IotAlertNotification> plan(IotAlertInstance instance, IotAlertRule rule,
                                           String channels, String targets,
                                           AlertNotifyEvent event, LocalDateTime now) {
        Set<AlertChannel> channelSet;
        try {
            channelSet = AlertChannel.parse(channels);
        } catch (IllegalArgumentException ex) {
            // 渠道码非法（脏数据/旧版本残留）：不静默丢弃，落一条 GIVEN_UP 说明原因
            return List.of(unsendable(instance, AlertChannel.INBOX, "规则渠道码非法：" + ex.getMessage(),
                event, now));
        }
        if (channelSet.isEmpty()) {
            return List.of();
        }
        Set<String> rawTargets = splitTargets(targets);
        List<IotAlertNotification> rows = new ArrayList<>();
        for (AlertChannel channel : channelSet) {
            Set<String> resolved = resolveTargets(channel, rawTargets, rule);
            if (resolved.isEmpty()) {
                String reason = "没有可用的收件人：规则未配收件人、且规则创建者与平台兜底收件人都不可用"
                    + "（请在规则里填「通知对象」，或联系平台管理员配置 "
                    + AlertProperties.PREFIX + ".notify-fallback-*-ids）";
                logUnsendable(instance, rule, channel, REASON_NO_RECIPIENT);
                rows.add(unsendable(instance, channel, reason, event, now));
                continue;
            }
            for (String target : resolved) {
                rows.add(pending(instance, channel, target, event, now));
            }
        }
        return rows;
    }

    /**
     * 把「不可投递」这件事打到服务端日志（WARN）——**只写 DB 不够**。
     *
     * <p>为什么必须有：{@code GIVEN_UP} 行只落在 {@code iot_alert_notification} 表里，运维不会主动查表；
     * 真实事故中遗留规则未配收件人 + 平台兜底为空 ⇒ 每 10 分钟静默产生一条放弃行，**无人知晓**。
     * 本方法让它在 {@code docker logs ypbin-iot} 中直接可见，并给出可执行的处理指引。</p>
     *
     * <p><b>不打印敏感信息</b>：只记 id/名称/渠道/原因码，**不记收件人明文**（此路径本就没有收件人）；
     * 规则名与设备 id 经 {@code LogSanitizer} 脱敏，防止脏数据里的换行/控制字符伪造日志行。</p>
     *
     * @param instance  实例
     * @param rule      规则（可为 {@code null}）
     * @param channel   渠道
     * @param reasonCode 原因码（便于日志检索与告警规则匹配）
     */
    private void logUnsendable(IotAlertInstance instance, IotAlertRule rule, AlertChannel channel,
                               String reasonCode) {
        // TODO(board #14): 规则未配通知对象时应在前端给出可见提示（或按需配置兜底收件人），
        //  避免只落 GIVEN_UP 无人知晓。用户 2026-09-30 决定：本批只加日志 + 此 TODO，
        //  不实现兜底收件人配置、也不做前端提示。
        log.warn("[iot] 告警通知不可投递，已放弃（GIVEN_UP）：{} tenantId={} ruleId={} ruleName={} "
                + "deviceId={} channel={} instanceId={} 指引=请在规则的「通知对象」里填写收件人；"
                + "或联系平台管理员配置 {}.notify-fallback-emails / {}.notify-fallback-inbox-user-ids",
            reasonCode, instance.getTenantId(), rule == null ? null : rule.getId(),
            LogSanitizer.sanitize(rule == null ? null : rule.getRuleName()),
            LogSanitizer.sanitize(instance.getDeviceId()), channel.getCode(), instance.getId(),
            AlertProperties.PREFIX, AlertProperties.PREFIX);
    }

    /** 幂等键列宽（{@code idempotent_key VARCHAR(191)}）。 */
    private static final int MAX_IDEMPOTENT_KEY_LENGTH = 191;

    /** 正常待投递行。 */
    private IotAlertNotification pending(IotAlertInstance instance, AlertChannel channel, String target,
                                         AlertNotifyEvent event, LocalDateTime now) {
        IotAlertNotification row = base(instance, channel, target, event, now);
        row.setNotifyStatus(AlertNotifyStatus.PENDING.getCode());
        row.setAttempt(0);
        row.setNextRetryTs(now);
        return row;
    }

    /** 无法投递行（**可见的失败**，不静默）。 */
    private IotAlertNotification unsendable(IotAlertInstance instance, AlertChannel channel,
                                            String reason, AlertNotifyEvent event, LocalDateTime now) {
        IotAlertNotification row = base(instance, channel, "", event, now);
        row.setNotifyStatus(AlertNotifyStatus.GIVEN_UP.getCode());
        row.setAttempt(properties.getNotifyMaxAttempt());
        row.setNextRetryTs(null);
        row.setLastError(truncate(reason));
        return row;
    }

    private IotAlertNotification base(IotAlertInstance instance, AlertChannel channel, String target,
                                      AlertNotifyEvent event, LocalDateTime now) {
        IotAlertNotification row = new IotAlertNotification();
        row.setId(IdWorker.getId());
        row.setTenantId(instance.getTenantId());
        row.setInstanceId(instance.getId());
        row.setChannel(channel.getCode());
        row.setTarget(target);
        row.setEvent(event.getCode());
        row.setIdempotentKey(idempotentKey(instance, event, channel, target));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setStatus(EntityStatus.ENABLED.getCode());
        row.setIsDeleted(0);
        return row;
    }

    /**
     * 幂等键：{@code instanceId:event:channel:target:轮次}。
     *
     * @param instance 实例
     * @param event    通知事件
     * @param channel  渠道
     * @param target   收件人
     * @return 幂等键（长度受列宽 191 限制，故对超长键做稳定截断）
     */
    public static String idempotentKey(IotAlertInstance instance, AlertNotifyEvent event,
                                       AlertChannel channel, String target) {
        int round = instance.getNotifyCount() == null ? 0 : instance.getNotifyCount();
        String key = instance.getId() + ":" + event.getCode() + ":" + channel.getCode() + ":" + target
            + ":" + round;
        if (key.length() <= MAX_IDEMPOTENT_KEY_LENGTH) {
            return key;
        }
        // 列宽 191：**不能简单截断**——末尾恰好是「轮次」，截掉会让不同 REPEAT 轮次撞成同一个键
        // （重复提醒会被幂等键挡掉，表现为「活动告警再也不提醒」）。故改为「前缀 + 全键哈希」：
        // 前缀保留可读性（能看出是哪个实例/事件/渠道），哈希保证唯一性。
        String hash = sha256Hex(key);
        int prefixLength = MAX_IDEMPOTENT_KEY_LENGTH - hash.length() - 1;
        return key.substring(0, prefixLength) + ":" + hash;
    }

    /** 拆收件人原文（逗号分隔、去空白、去重、保序）。 */
    private static Set<String> splitTargets(String raw) {
        Set<String> targets = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return targets;
        }
        for (String part : raw.split(AlertChannel.SEPARATOR)) {
            String item = part.trim();
            if (!item.isEmpty()) {
                targets.add(item);
            }
        }
        return targets;
    }

    /**
     * 把一个渠道的收件人解析出来。
     *
     * <p>规则：配置里的 token 按**形态**分流——含 {@code @} 的进邮件、纯数字的进站内信（用户 ID）。
     * 站内信没有可用 token 时回落「规则创建者 → 平台兜底收件人」；邮件同理回落平台兜底邮箱。
     * 回落链每一段都写清楚了，避免「为什么发到了这个人」无从解释。</p>
     */
    private Set<String> resolveTargets(AlertChannel channel, Set<String> rawTargets, IotAlertRule rule) {
        Set<String> resolved = new LinkedHashSet<>();
        for (String token : rawTargets) {
            if (channel == AlertChannel.EMAIL && token.contains("@")) {
                resolved.add(token);
            } else if (channel == AlertChannel.INBOX && !token.contains("@") && isPositiveNumber(token)) {
                resolved.add(token);
            }
            // 形态不匹配当前渠道的 token 直接忽略：它是给**另一个渠道**用的（一条规则的收件人列表同时
            // 服务两个渠道，按形态分流是唯一不需要额外字段表达「谁是邮箱、谁是用户」的做法）
        }
        if (!resolved.isEmpty()) {
            return resolved;
        }
        if (channel == AlertChannel.INBOX) {
            if (rule != null && rule.getCreateUser() != null) {
                resolved.add(String.valueOf(rule.getCreateUser()));
            }
            for (Long fallback : properties.getNotifyFallbackInboxUserIds()) {
                if (fallback != null) {
                    resolved.add(String.valueOf(fallback));
                }
            }
        } else {
            for (String fallback : properties.getNotifyFallbackEmails()) {
                if (fallback != null && !fallback.isBlank()) {
                    resolved.add(fallback.trim());
                }
            }
        }
        return resolved;
    }

    private static boolean isPositiveNumber(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            if (!Character.isDigit(token.charAt(index))) {
                return false;
            }
        }
        return !"0".equals(token);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int max = properties.getNotifyMaxErrorLength();
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 全键的 SHA-256 十六进制（前 32 位足够区分同实例同轮的不同收件人；碰撞概率可忽略）。 */
    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                out.append(String.format("%02x", bytes[index]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法；取不到属环境异常，必须暴露（不能静默退化成截断）
            throw new IllegalStateException("SHA-256 不可用，无法生成告警通知幂等键", ex);
        }
    }

    /**
     * 把待投递记录按「实例 ID → 记录列表」分组（便于调用方按实例更新通知计数）。
     *
     * @param rows 待投递记录
     * @return 分组结果
     */
    public static Map<Long, List<IotAlertNotification>> groupByInstance(List<IotAlertNotification> rows) {
        Map<Long, List<IotAlertNotification>> grouped = new LinkedHashMap<>();
        for (IotAlertNotification row : rows) {
            grouped.computeIfAbsent(row.getInstanceId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
