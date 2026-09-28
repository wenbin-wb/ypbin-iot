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

/**
 * 断档 → 告警映射（用户已批准口径 4；设计 §2.6 里被列为「不做」的设备离线类告警，
 * 本实现按用户口径**并入**，但严格只做映射、不新造判定）。
 *
 * @author wenbin
 * @since 2026-10-03
 */
public interface AlertOutageMappingService {

    /**
     * 一轮映射结果。
     *
     * @param enabled          是否执行（总开关）
     * @param skipped          是否因「断档映射开关」关闭而跳过
     * @param ruleLoadFailed   规则加载是否失败（失败时本轮整体放弃）
     * @param tenants          处理的租户数
     * @param devices          被离线规则覆盖的设备数
     * @param open             进行中的断档数
     * @param created          新建的告警实例数
     * @param resolved         同步恢复的实例数
     * @param queued           生成的待投递通知条数
     * @param orphan           找不到对应事件的孤立实例数（需要人工关注）
     */
    record OutageOutcome(boolean enabled, boolean skipped, boolean ruleLoadFailed, int tenants, int devices,
                         int open, int created, int resolved, int queued, int orphan) {

        /** 未执行。 */
        public static OutageOutcome skipped(boolean enabled) {
            return new OutageOutcome(enabled, true, false, 0, 0, 0, 0, 0, 0, 0);
        }

        /** 规则加载失败。 */
        public static OutageOutcome ruleLoadFailure() {
            return new OutageOutcome(true, false, true, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 执行一轮映射。
     *
     * @return 汇总结果（永不为 {@code null}）
     */
    OutageOutcome mapOnce();
}
