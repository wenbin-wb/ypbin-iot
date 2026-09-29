/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 设备批量导入**批次状态**（有界集合，落库/接口/日志都用 {@code code}）。
 *
 * <p>状态是**三态而非两态**：批量导入的常态恰恰是「一部分成功、一部分失败」，
 * 把它压成「成功/失败」二选一会让页面无法回答「我到底进去了几台」——
 * 而这正是用户上传完最想知道的一件事（对标阿里云批次管理的「部分失败」态）。</p>
 *
 * <p>不引入「排队中/处理中」这类中间态：本能力的执行是**同步**的（一次请求内解析+逐行写库），
 * 请求返回时批次必然已经完结。多一个永远不会出现的状态只会让页面的分支永不执行。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum DeviceImportStatus {

    /** 进行中（批次行已写、明细尚未落齐；当前实现下只在事务内短暂存在）。 */
    RUNNING("running", "进行中"),

    /** 全部成功。 */
    SUCCESS("success", "全部成功"),

    /** 部分失败（有成功行也有失败行）。 */
    PARTIAL_FAILED("partial-failed", "部分失败"),

    /** 全部失败（一行都没进去）。 */
    FAILED("failed", "全部失败");

    private final String code;

    private final String desc;

    DeviceImportStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 状态码（数据库/接口/日志用；稳定标识，改文案不改它）。
     *
     * @return 状态码
     */
    public String getCode() {
        return code;
    }

    /**
     * 状态描述（面向用户的中文说明）。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按成功/失败计数判定终态。
     *
     * <p>判据集中在枚举里而**不散落在服务层**：三处（写库、接口返回、前端展示）各自 if-else
     * 迟早会漂移出「库里是 partial-failed、接口说 failed」这种自相矛盾的状态。</p>
     *
     * @param totalRows   总行数
     * @param successRows 成功行数
     * @return 终态（总数与成功数都非正时按失败处理，不返回 RUNNING —— 调用方用它收口）
     */
    public static DeviceImportStatus ofCounts(int totalRows, int successRows) {
        if (totalRows <= 0) {
            return FAILED;
        }
        if (successRows <= 0) {
            return FAILED;
        }
        if (successRows >= totalRows) {
            return SUCCESS;
        }
        return PARTIAL_FAILED;
    }
}
