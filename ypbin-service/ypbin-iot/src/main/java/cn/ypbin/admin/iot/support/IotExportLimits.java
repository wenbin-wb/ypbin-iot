/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.support;

/**
 * IoT 导出功能的**业务配额**（CSV 编码本身由 {@code ypbin-starter-tools} 的 {@code CsvUtils} 承担）。
 *
 * <p><b>为什么配额留在业务侧</b>：starter 的 {@code CsvUtils} 有意不上收「单次导出行数上限」——
 * 那是**业务决策**（导哪些、给谁、多少条算合理），框架无法替各宿主决定。见其类注释与
 * {@code ROADMAP} 相关条目。本类因此只承载配额这一个常量，实现全部走官方工具。</p>
 *
 * <p><b>为什么不静默截断</b>：超限由 service 显式拒绝并给出可操作提示；截断成「看起来完整的
 * 10000 条」会让用户以为拿到了全量。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public final class IotExportLimits {

    /** 单次导出上限（设备/告警共用）；超限由 service 显式拒绝，不静默截断。 */
    public static final int MAX_EXPORT_ROWS = 10_000;

    private IotExportLimits() {
    }
}
