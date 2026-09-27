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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.admin.iot.entity.IotAlertInstance;
import cn.ypbin.admin.iot.entity.IotAlertNotification;
import cn.ypbin.admin.iot.entity.IotAlertRule;
import cn.ypbin.admin.iot.enums.AlertChannel;
import cn.ypbin.admin.iot.enums.AlertNotifyEvent;
import cn.ypbin.admin.iot.enums.AlertNotifyStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通知投递意图生成的用例（设计 §2.4 投递模型第 1/4 条 + §3.4 的 S6/S7 相关面）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
class AlertNotifyPlannerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

    private final AlertProperties properties = new AlertProperties();

    private final AlertNotifyPlanner planner = new AlertNotifyPlanner(properties);

    private static IotAlertInstance instance() {
        IotAlertInstance instance = new IotAlertInstance();
        instance.setId(555L);
        instance.setTenantId(1L);
        instance.setNotifyCount(0);
        return instance;
    }

    private static IotAlertRule rule(Long createUser, String targets) {
        IotAlertRule rule = new IotAlertRule();
        rule.setId(7L);
        rule.setCreateUser(createUser);
        rule.setNotifyTargets(targets);
        return rule;
    }

    @Test
    @DisplayName("收件人按形态分流：纯数字进站内信、含 @ 进邮件（一条规则同时服务两个渠道）")
    void targetsAreSplitByShape() {
        List<IotAlertNotification> rows = planner.plan(instance(), rule(1L, "1001,ops@example.com,1002"),
            "INBOX,EMAIL", "1001,ops@example.com,1002", AlertNotifyEvent.FIRING, NOW);
        assertThat(rows).hasSize(3);
        assertThat(rows).filteredOn(row -> AlertChannel.INBOX.getCode().equals(row.getChannel()))
            .extracting(IotAlertNotification::getTarget).containsExactlyInAnyOrder("1001", "1002");
        assertThat(rows).filteredOn(row -> AlertChannel.EMAIL.getCode().equals(row.getChannel()))
            .extracting(IotAlertNotification::getTarget).containsExactly("ops@example.com");
        assertThat(rows).allSatisfy(row -> assertThat(row.getNotifyStatus())
            .isEqualTo(AlertNotifyStatus.PENDING.getCode()));
    }

    @Test
    @DisplayName("没配收件人 ⇒ 站内信回落规则创建者；邮件回落平台兜底邮箱")
    void fallsBackToCreatorAndPlatformDefaults() {
        properties.setNotifyFallbackEmails(List.of("oncall@example.com"));
        List<IotAlertNotification> rows = planner.plan(instance(), rule(42L, null), "INBOX,EMAIL", null,
            AlertNotifyEvent.FIRING, NOW);
        assertThat(rows).filteredOn(row -> AlertChannel.INBOX.getCode().equals(row.getChannel()))
            .extracting(IotAlertNotification::getTarget).containsExactly("42");
        assertThat(rows).filteredOn(row -> AlertChannel.EMAIL.getCode().equals(row.getChannel()))
            .extracting(IotAlertNotification::getTarget).containsExactly("oncall@example.com");
    }

    @Test
    @DisplayName("★ 收件人一个都解析不出来时**不静默**：落一条 GIVEN_UP 并写明原因（页面能回答「为什么没收到」）")
    void unresolvableTargetProducesVisibleGivenUpRow() {
        List<IotAlertNotification> rows = planner.plan(instance(), rule(null, null), "INBOX,EMAIL", null,
            AlertNotifyEvent.FIRING, NOW);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getNotifyStatus()).isEqualTo(AlertNotifyStatus.GIVEN_UP.getCode());
            assertThat(row.getLastError()).contains("没有可用的收件人");
            assertThat(row.getNextRetryTs()).isNull();
        });
    }

    @Test
    @DisplayName("渠道码非法（脏数据）⇒ GIVEN_UP + 原因，不静默丢弃")
    void unknownChannelIsVisible() {
        List<IotAlertNotification> rows = planner.plan(instance(), rule(1L, "1001"), "INBOX,WEBHOOK",
            "1001", AlertNotifyEvent.FIRING, NOW);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getNotifyStatus()).isEqualTo(AlertNotifyStatus.GIVEN_UP.getCode());
        assertThat(rows.get(0).getLastError()).contains("渠道码非法");
    }

    @Test
    @DisplayName("渠道集合为空 ⇒ 不产生任何投递记录（规则没开通知渠道时不该凭空造行）")
    void emptyChannelsProduceNoRows() {
        assertThat(planner.plan(instance(), rule(1L, "1001"), "", "1001", AlertNotifyEvent.FIRING, NOW))
            .isEmpty();
        assertThat(planner.plan(instance(), rule(1L, "1001"), null, "1001", AlertNotifyEvent.FIRING, NOW))
            .isEmpty();
    }

    @Test
    @DisplayName("幂等键含轮次：同一实例同一事件同一轮的重复入队会被唯一键挡住，而重复提醒的每一轮各不相同")
    void idempotentKeyIncludesRound() {
        IotAlertInstance first = instance();
        IotAlertInstance second = instance();
        second.setNotifyCount(3);
        String key1 = AlertNotifyPlanner.idempotentKey(first, AlertNotifyEvent.REPEAT, AlertChannel.INBOX,
            "1001");
        String key2 = AlertNotifyPlanner.idempotentKey(second, AlertNotifyEvent.REPEAT, AlertChannel.INBOX,
            "1001");
        assertThat(key1).isEqualTo("555:REPEAT:INBOX:1001:0");
        assertThat(key2).isEqualTo("555:REPEAT:INBOX:1001:3");
        assertThat(key1).isNotEqualTo(key2);
        // 同一实例同一轮重复生成 ⇒ 键完全相同（这就是幂等生效的依据）
        assertThat(AlertNotifyPlanner.idempotentKey(instance(), AlertNotifyEvent.FIRING,
            AlertChannel.EMAIL, "a@b.com"))
            .isEqualTo(AlertNotifyPlanner.idempotentKey(instance(), AlertNotifyEvent.FIRING,
                AlertChannel.EMAIL, "a@b.com"));
        // 超长键按前缀截断（列宽 191），不抛异常
        String longKey = AlertNotifyPlanner.idempotentKey(instance(), AlertNotifyEvent.REPEAT,
            AlertChannel.EMAIL, "x".repeat(300) + "@example.com");
        assertThat(longKey).hasSize(191);
    }

    @Test
    @DisplayName("分组：按实例 ID 归拢待投递记录（调用方据此更新通知计数）")
    void groupsByInstance() {
        IotAlertInstance one = instance();
        IotAlertInstance two = instance();
        two.setId(556L);
        List<IotAlertNotification> rows = new java.util.ArrayList<>();
        rows.addAll(planner.plan(one, rule(1L, "1001"), "INBOX", "1001", AlertNotifyEvent.FIRING, NOW));
        rows.addAll(planner.plan(two, rule(1L, "1002,ops@example.com"), "INBOX,EMAIL", "1002,ops@example.com",
            AlertNotifyEvent.FIRING, NOW));
        assertThat(AlertNotifyPlanner.groupByInstance(rows)).containsOnlyKeys(555L, 556L);
        assertThat(AlertNotifyPlanner.groupByInstance(rows).get(556L)).hasSize(2);
    }
}
